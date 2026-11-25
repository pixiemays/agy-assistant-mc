package com.agyassistant.context

import com.agyassistant.config.ModConfig
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

object ScreenCapture {
    /**
     * Must be called on the Minecraft render thread to capture the framebuffer,
     * then resizes & JPEG-encodes off the render thread to avoid frame drops.
     */
    fun captureAsync(): CompletableFuture<ScreenshotInfo?> {
        val mc = Minecraft.getInstance()
        val nativeImage: NativeImage = try {
            Screenshot.takeScreenshot(mc.mainRenderTarget)
        } catch (_: Exception) {
            return CompletableFuture.completedFuture(null)
        }

        val maxWidth = ModConfig.data.maxScreenshotWidth.coerceIn(320, 2560)
        val quality = ModConfig.data.screenshotJpegQuality.coerceIn(0.3f, 0.95f)

        return CompletableFuture.supplyAsync {
            try {
                encodeNativeImage(nativeImage, maxWidth, quality)
            } finally {
                nativeImage.close()
            }
        }
    }

    private fun encodeNativeImage(
        image: NativeImage,
        maxWidth: Int,
        quality: Float,
    ): ScreenshotInfo {
        val srcW = image.width
        val srcH = image.height
        val buffered = BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_RGB)

        // NativeImage getPixelRGBA returns ABGR in 1.21.1
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val abgr = image.getPixelRGBA(x, y)
                val r = abgr and 0xFF
                val g = (abgr ushr 8) and 0xFF
                val b = (abgr ushr 16) and 0xFF
                buffered.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }

        val (dstW, dstH, finalImg) = if (srcW > maxWidth) {
            val scale = maxWidth.toDouble() / srcW.toDouble()
            val h = (srcH * scale).roundToInt().coerceAtLeast(1)
            val scaled = BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB)
            val g2 = scaled.createGraphics()
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(buffered, 0, 0, maxWidth, h, null)
            g2.dispose()
            Triple(maxWidth, h, scaled)
        } else {
            Triple(srcW, srcH, buffered)
        }

        val baos = ByteArrayOutputStream()
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (writers.hasNext()) {
            val writer = writers.next()
            try {
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                ImageIO.createImageOutputStream(baos).use { ios ->
                    writer.output = ios
                    writer.write(null, IIOImage(finalImg, null, null), param)
                }
            } finally {
                writer.dispose()
            }
            val b64 = Base64.getEncoder().encodeToString(baos.toByteArray())
            return ScreenshotInfo(base64 = b64, mimeType = "image/jpeg", width = dstW, height = dstH)
        }

        // Fallback to PNG if no JPEG writer is available
        ImageIO.write(finalImg, "png", baos)
        val b64 = Base64.getEncoder().encodeToString(baos.toByteArray())
        return ScreenshotInfo(base64 = b64, mimeType = "image/png", width = dstW, height = dstH)
    }
}
