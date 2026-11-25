package com.agyassistant.ui

import com.agyassistant.context.ScreenshotInfo
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class ScreenshotPreviewScreen(
    private val parent: Screen,
    private val screenshot: ScreenshotInfo,
) : Screen(Component.translatable("screen.agyassistant.cat.screenshot")) {

    companion object {
        private val previewCounter = AtomicInteger(0)
    }

    private var textureLoc: ResourceLocation? = null
    private var imgW: Int = screenshot.width.coerceAtLeast(1)
    private var imgH: Int = screenshot.height.coerceAtLeast(1)

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        super.init()
        if (textureLoc == null) {
            loadTexture()
        }
    }

    private fun loadTexture() {
        runCatching {
            val bytes = Base64.getDecoder().decode(screenshot.base64)
            val buffered = ImageIO.read(ByteArrayInputStream(bytes)) ?: return
            val w = buffered.width
            val h = buffered.height
            val nativeImg = NativeImage(w, h, false)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val argb = buffered.getRGB(x, y)
                    val a = (argb ushr 24) and 0xFF
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                    nativeImg.setPixelRGBA(x, y, abgr)
                }
            }
            val loc = ResourceLocation.fromNamespaceAndPath(
                "agyassistant",
                "dynamic/shot_preview_${previewCounter.incrementAndGet()}",
            )
            val dyn = DynamicTexture(nativeImg)
            Minecraft.getInstance().textureManager.register(loc, dyn)
            textureLoc = loc
            imgW = w
            imgH = h
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, width, height, 0xCC000000.toInt())

        val tex = textureLoc
        if (tex != null) {
            val maxW = (width * 0.86f).toInt().coerceAtLeast(64)
            val maxH = (height * 0.80f).toInt().coerceAtLeast(64)
            val scale = minOf(maxW.toFloat() / imgW.toFloat(), maxH.toFloat() / imgH.toFloat(), 1.0f)
            val drawW = (imgW * scale).toInt().coerceAtLeast(1)
            val drawH = (imgH * scale).toInt().coerceAtLeast(1)
            val x = (width - drawW) / 2
            val y = (height - drawH) / 2 - 6

            guiGraphics.fill(x - 2, y - 2, x + drawW + 2, y + drawH + 2, 0xFF334155.toInt())
            guiGraphics.blit(tex, x, y, drawW, drawH, 0f, 0f, imgW, imgH, imgW, imgH)

            val caption = Component.translatable(
                "screen.agyassistant.shot.preview_hint",
                imgW,
                imgH,
            )
            guiGraphics.drawCenteredString(font, caption, width / 2, y + drawH + 6, 0xCBD5E1)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onClose()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    override fun removed() {
        super.removed()
        textureLoc?.let { loc ->
            runCatching { Minecraft.getInstance().textureManager.release(loc) }
        }
        textureLoc = null
    }
}
