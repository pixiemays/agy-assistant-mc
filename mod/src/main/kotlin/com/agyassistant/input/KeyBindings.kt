package com.agyassistant.input

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.context.GuiContextCollector
import com.agyassistant.context.ItemStackSerializer
import com.agyassistant.context.ScreenCapture
import com.agyassistant.context.WorldContextCollector
import com.agyassistant.ui.AssistantScreen
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.settings.KeyModifier
import org.lwjgl.glfw.GLFW

object KeyBindings {
    private const val CATEGORY = "key.categories.agyassistant"

    val OPEN_CHAT = KeyMapping(
        "key.agyassistant.open_chat",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        CATEGORY,
    )

    val CAPTURE_AND_OPEN = KeyMapping(
        "key.agyassistant.capture_and_open",
        KeyConflictContext.UNIVERSAL,
        KeyModifier.ALT,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        CATEGORY,
    )

    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(OPEN_CHAT)
        event.register(CAPTURE_AND_OPEN)
    }

    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.screen != null) return

        if (CAPTURE_AND_OPEN.consumeClick()) {
            openWithContext(fromGui = false)
            return
        }
        if (OPEN_CHAT.consumeClick()) {
            mc.setScreen(AssistantScreen(AttachedContext(), null))
        }
    }

    fun onScreenKeyPressed(event: ScreenEvent.KeyPressed.Pre) {
        val mc = Minecraft.getInstance()
        if (mc.player == null) return
        if (event.screen is AssistantScreen) return

        val key = InputConstants.getKey(event.keyCode, event.scanCode)
        if (CAPTURE_AND_OPEN.isActiveAndMatches(key)) {
            event.isCanceled = true
            openWithContext(fromGui = true)
        }
    }

    private fun openWithContext(fromGui: Boolean) {
        val mc = Minecraft.getInstance()
        val context = AttachedContext()

        if (fromGui) {
            context.item = GuiContextCollector.collectHoveredItem()
        } else {
            context.target = WorldContextCollector.collectTarget()
            if (context.target == null) {
                val held = mc.player?.mainHandItem
                if (held != null && !held.isEmpty) {
                    context.item = ItemStackSerializer.toItemInfo(held, "hand")
                }
            }
        }

        val screenshotFuture = if (ModConfig.data.attachScreenshotByDefault) {
            ScreenCapture.captureAsync()
        } else {
            null
        }

        mc.setScreen(AssistantScreen(context, screenshotFuture))
    }
}
