package com.agyassistant.ui

import io.wispforest.owo.ui.base.BaseComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIDrawContext
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import kotlin.math.ceil

class ScaledLabelComponent(
    private var text: Component,
    private val scale: Float = 0.85f,
    private var defaultColor: Int = 0xF8FAFC,
    private val shadow: Boolean = false,
) : BaseComponent() {

    private var wrappedLines: List<FormattedCharSequence> = emptyList()
    private var lastWrapWidth: Int = -1
    private var lastHoverKey: String? = null

    var onGameRefClick: ((GameReference, Int, Boolean) -> Boolean)? = null
    var onUrlClick: ((String) -> Unit)? = null

    fun text(newText: Component): ScaledLabelComponent {
        this.text = newText
        this.lastWrapWidth = -1
        notifyParentIfMounted()
        return this
    }

    fun color(rgb: Int): ScaledLabelComponent {
        this.defaultColor = rgb
        return this
    }

    private fun effectiveScale(): Float {
        if (scale >= 0.99f) return 1.0f
        val guiScale = Minecraft.getInstance().window.guiScale.toInt()
        if (guiScale <= 2) return 1.0f
        val targetPhysicalPx = (guiScale * scale).toInt().coerceIn(1, guiScale - 1)
        return targetPhysicalPx.toFloat() / guiScale.toFloat()
    }

    private fun ensureWrapped(pixelWidth: Int) {
        val target = pixelWidth.coerceAtLeast(20)
        if (target == lastWrapWidth && wrappedLines.isNotEmpty()) return
        lastWrapWidth = target
        val font = Minecraft.getInstance().font
        val s = effectiveScale()
        val scaledMaxWidth = (target / s).toInt().coerceAtLeast(20)
        wrappedLines = font.split(text, scaledMaxWidth)
    }

    private fun clickValueAtLocal(localX: Double, localY: Double): String? {
        if (onGameRefClick == null && onUrlClick == null) return null
        ensureWrapped(width)
        val font = Minecraft.getInstance().font
        val s = effectiveScale()
        val lineStep = font.lineHeight + 2
        val relX = (localX / s).toInt()
        val relY = (localY / s).toInt()
        if (relX < 0 || relY < 0) return null

        val lineIdx = relY / lineStep
        if (lineIdx !in wrappedLines.indices) return null

        val style = font.splitter.componentStyleAtWidth(wrappedLines[lineIdx], relX) ?: return null
        return style.clickEvent?.value
    }

    override fun onMouseDown(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val clickVal = clickValueAtLocal(mouseX, mouseY)
        if (clickVal != null) {
            if (clickVal.startsWith(ReferenceExtractor.CLICK_PREFIX) && onGameRefClick != null) {
                val ref = ReferenceExtractor.resolve(clickVal.removePrefix(ReferenceExtractor.CLICK_PREFIX))
                if (ref != null && onGameRefClick!!.invoke(ref, button, Screen.hasShiftDown())) {
                    return true
                }
            } else if ((clickVal.startsWith("https://") || clickVal.startsWith("http://")) && button == 0 && onUrlClick != null) {
                onUrlClick!!.invoke(clickVal)
                return true
            }
        }
        return super.onMouseDown(mouseX, mouseY, button)
    }

    override fun determineHorizontalContentSize(sizing: Sizing): Int {
        val font = Minecraft.getInstance().font
        return ceil(font.width(text) * effectiveScale()).toInt().coerceAtLeast(1)
    }

    override fun determineVerticalContentSize(sizing: Sizing): Int {
        val font = Minecraft.getInstance().font
        val availWidth = if (width > 0) width else (parent?.width()?.minus(16) ?: 260)
        ensureWrapped(availWidth)
        val lineCount = wrappedLines.size.coerceAtLeast(1)
        val lineStep = font.lineHeight + 2
        return ceil(lineCount * lineStep * effectiveScale()).toInt().coerceAtLeast(1)
    }

    override fun inflate(space: io.wispforest.owo.ui.core.Size) {
        super.inflate(space)
        val prevCount = wrappedLines.size
        ensureWrapped(width)
        if (wrappedLines.size != prevCount) {
            val font = Minecraft.getInstance().font
            val lineStep = font.lineHeight + 2
            val neededH = ceil(wrappedLines.size.coerceAtLeast(1) * lineStep * effectiveScale()).toInt()
            if (verticalSizing.get().isContent) {
                this.height = neededH
            }
        }
    }

    override fun draw(context: OwoUIDrawContext, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        ensureWrapped(width)
        val font = Minecraft.getInstance().font
        val lineStep = font.lineHeight + 2
        val s = effectiveScale()

        if (onGameRefClick != null || onUrlClick != null) {
            val clickVal = if (isInBoundingBox(mouseX.toDouble(), mouseY.toDouble())) {
                clickValueAtLocal((mouseX - x).toDouble(), (mouseY - y).toDouble())
            } else {
                null
            }
            if (clickVal != lastHoverKey) {
                lastHoverKey = clickVal
                if (clickVal != null && clickVal.startsWith(ReferenceExtractor.CLICK_PREFIX)) {
                    val ref = ReferenceExtractor.resolve(clickVal.removePrefix(ReferenceExtractor.CLICK_PREFIX))
                    if (ref != null) {
                        cursorStyle(CursorStyle.HAND)
                        tooltip(ReferenceExtractor.buildTooltip(ref))
                    } else {
                        cursorStyle(CursorStyle.POINTER)
                        tooltip(emptyList<Component>())
                    }
                } else if (clickVal != null && (clickVal.startsWith("https://") || clickVal.startsWith("http://"))) {
                    cursorStyle(CursorStyle.HAND)
                    tooltip(
                        listOf(
                            Component.translatable("screen.agyassistant.link.open_browser"),
                            Component.literal(clickVal).withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0x94A3B8)),
                        )
                    )
                } else {
                    cursorStyle(CursorStyle.POINTER)
                    tooltip(emptyList<Component>())
                }
            }
        }

        val pose = context.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        if (s != 1.0f) {
            pose.scale(s, s, 1f)
        }

        var dy = 0
        for (line in wrappedLines) {
            context.drawString(font, line, 0, dy, defaultColor, shadow)
            dy += lineStep
        }

        pose.popPose()
    }
}
