package com.agyassistant.ui

import com.agyassistant.input.KeyBindings
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.RenderGuiEvent

/**
 * HUD notification shown in the bottom-right corner while the chat screen is closed.
 * Styled after the mod's own UI: dark panel with outline and a colored accent bar.
 */
object AssistantNotifications {
    private const val SLIDE_IN_MS = 180L
    private const val HOLD_MS = 3500L
    private const val FADE_OUT_MS = 450L

    private const val PAD_X = 7
    private const val PAD_Y = 5
    private const val MARGIN_RIGHT = 6
    private const val MARGIN_BOTTOM = 8
    private const val ACCENT_BAR_W = 2
    private const val LINE_GAP = 2

    private const val BG_RGB = 0x0B1120
    private const val OUTLINE_RGB = 0x334155
    private const val BODY_RGB = 0x94A3B8

    /** Vanilla HUD layers render with growing z offsets (hotbar ~400). Draw above them all. */
    private const val NOTIFICATION_Z = 3000f

    enum class Kind { ANSWER, ERROR }

    private class Notification(val kind: Kind, val title: String, val body: String) {
        val startMs = System.currentTimeMillis()
        private val totalMs = SLIDE_IN_MS + HOLD_MS + FADE_OUT_MS

        fun isDone(nowMs: Long): Boolean = nowMs - startMs >= totalMs

        fun alpha(nowMs: Long): Float {
            val elapsed = nowMs - startMs
            return when {
                elapsed < SLIDE_IN_MS -> elapsed.toFloat() / SLIDE_IN_MS
                elapsed < SLIDE_IN_MS + HOLD_MS -> 1f
                else -> 1f - (elapsed - SLIDE_IN_MS - HOLD_MS).toFloat() / FADE_OUT_MS
            }.coerceIn(0f, 1f)
        }

        fun slideOffset(nowMs: Long): Int {
            val elapsed = nowMs - startMs
            if (elapsed >= SLIDE_IN_MS) return 0
            val t = 1f - elapsed.toFloat() / SLIDE_IN_MS
            // ease-out cubic
            return (t * t * t * 16f).toInt().coerceAtMost(16)
        }
    }

    @Volatile
    private var current: Notification? = null

    fun show(kind: Kind) {
        val titleKey = when (kind) {
            Kind.ANSWER -> "screen.agyassistant.toast.answer_title"
            Kind.ERROR -> "screen.agyassistant.toast.error_title"
        }
        current = Notification(
            kind = kind,
            title = Component.translatable(titleKey).string,
            body = Component.translatable(
                "screen.agyassistant.toast.answer_body",
                KeyBindings.OPEN_CHAT.translatedKeyMessage,
            ).string,
        )
    }

    fun onRenderGui(event: RenderGuiEvent.Post) {
        render(event.guiGraphics)
    }

    private fun render(gui: GuiGraphics) {
        val note = current ?: return
        val nowMs = System.currentTimeMillis()
        if (note.isDone(nowMs)) {
            current = null
            return
        }

        val font = Minecraft.getInstance().font
        val alpha = note.alpha(nowMs)
        val slide = note.slideOffset(nowMs)

        val accentRgb = when (note.kind) {
            Kind.ANSWER -> 0x4ADE80
            Kind.ERROR -> 0xEF4444
        }
        val titleRgb = when (note.kind) {
            Kind.ANSWER -> 0x86EFAC
            Kind.ERROR -> 0xFCA5A5
        }

        val dotW = font.width("●")
        val titleW = font.width(note.title)
        val bodyW = font.width(note.body)
        val textW = maxOf(dotW + 3 + titleW, bodyW)
        val panelW = ACCENT_BAR_W + PAD_X + textW + PAD_X
        val panelH = PAD_Y + 9 + LINE_GAP + 9 + PAD_Y

        val x = gui.guiWidth() - panelW - MARGIN_RIGHT
        val y = gui.guiHeight() - panelH - MARGIN_BOTTOM + slide

        fun argb(rgb: Int): Int = (((0xFF * alpha).toInt()) shl 24) or (rgb and 0xFFFFFF)

        val pose = gui.pose()
        pose.pushPose()
        pose.translate(0.0f, 0.0f, NOTIFICATION_Z)

        // Panel background
        gui.fill(x, y, x + panelW, y + panelH, argb(BG_RGB))

        // 1px outline
        val outline = argb(OUTLINE_RGB)
        gui.fill(x, y, x + panelW, y + 1, outline)
        gui.fill(x, y + panelH - 1, x + panelW, y + panelH, outline)
        gui.fill(x, y, x + 1, y + panelH, outline)
        gui.fill(x + panelW - 1, y, x + panelW, y + panelH, outline)

        // Accent bar on the left edge
        gui.fill(x, y + 1, x + ACCENT_BAR_W, y + panelH - 1, argb(accentRgb))

        // Title line: colored dot + title
        val textX = x + ACCENT_BAR_W + PAD_X
        val titleY = y + PAD_Y
        gui.drawString(font, "●", textX, titleY, argb(accentRgb), false)
        gui.drawString(font, note.title, textX + dotW + 3, titleY, argb(titleRgb), false)

        // Body line
        gui.drawString(font, note.body, textX, titleY + 9 + LINE_GAP, argb(BODY_RGB), false)

        pose.popPose()
    }
}
