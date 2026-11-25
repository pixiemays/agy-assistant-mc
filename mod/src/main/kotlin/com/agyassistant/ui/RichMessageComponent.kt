package com.agyassistant.ui

import io.wispforest.owo.ui.base.BaseComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIDrawContext
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import kotlin.math.ceil

private sealed interface InlineNode {
    data class Text(val text: String, val style: Style, val url: String? = null) : InlineNode
    data class Pill(val ref: GameReference) : InlineNode
}

private sealed interface LaidOutItem {
    val x: Int
    val y: Int
    val w: Int
    val h: Int

    data class TextRun(
        override val x: Int,
        override val y: Int,
        override val w: Int,
        override val h: Int,
        val text: String,
        val style: Style,
        val url: String?,
    ) : LaidOutItem

    data class RefPill(
        override val x: Int,
        override val y: Int,
        override val w: Int,
        override val h: Int,
        val ref: GameReference,
    ) : LaidOutItem
}

class RichMessageComponent(
    private var markdown: String,
    private val scale: Float = 0.85f,
    private var defaultColor: Int = 0xF8FAFC,
) : BaseComponent() {

    private val bodyStyle: Style = Style.EMPTY.withColor(0xF8FAFC)
    private val emphasisStyle: Style = Style.EMPTY.withColor(0x93C5FD)
    private val italicStyle: Style = Style.EMPTY.withColor(0xE2E8F0).withItalic(true)
    private val codeStyle: Style = Style.EMPTY.withColor(0xFDE68A)
    private val codeBlockStyle: Style = Style.EMPTY.withColor(0xA5F3FC)
    private val headingStyle: Style = Style.EMPTY.withColor(0x38BDF8)
    private val bulletStyle: Style = Style.EMPTY.withColor(0x93C5FD)
    private val urlStyle: Style = Style.EMPTY.withColor(0x60A5FA).withUnderlined(true)

    private val bareIdRegex = Regex("^([a-z0-9_.-]+:[a-z0-9_/.-]+)")
    private val mdLinkRegex = Regex("^\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    private val bareUrlRegex = Regex("^(https?://[^\\s)\\]>]+)")

    private var laidOut: List<LaidOutItem> = emptyList()
    private var contentHeightPx: Int = 12
    private var lastLayoutWidth: Int = -1
    private var lastHoverKey: String? = null
    private var hoveredPill: LaidOutItem.RefPill? = null

    var onGameRefClick: ((GameReference, Int, Boolean) -> Boolean)? = null
    var onUrlClick: ((String) -> Unit)? = null

    fun markdown(newMarkdown: String): RichMessageComponent {
        this.markdown = newMarkdown
        this.lastLayoutWidth = -1
        notifyParentIfMounted()
        return this
    }

    fun color(rgb: Int): RichMessageComponent {
        this.defaultColor = rgb
        this.lastLayoutWidth = -1
        return this
    }

    private fun effectiveScale(): Float {
        if (scale >= 0.99f) return 1.0f
        val guiScale = Minecraft.getInstance().window.guiScale.toInt()
        if (guiScale <= 2) return 1.0f
        val targetPhysicalPx = (guiScale * scale).toInt().coerceIn(1, guiScale - 1)
        return targetPhysicalPx.toFloat() / guiScale.toFloat()
    }

    private fun ensureLayout(pixelWidth: Int) {
        val targetW = pixelWidth.coerceAtLeast(40)
        if (targetW == lastLayoutWidth && laidOut.isNotEmpty()) return
        lastLayoutWidth = targetW

        val font = Minecraft.getInstance().font
        val s = effectiveScale()
        val textLineH = ceil(font.lineHeight * s).toInt().coerceAtLeast(8)
        val pillH = (textLineH + 4).coerceAtLeast(13)
        val iconSize = 10
        val lineGap = 3

        val items = mutableListOf<LaidOutItem>()
        val rawLines = markdown.split("\n")
        var inCodeBlock = false
        var cursorY = 0

        for (rawLine in rawLines) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            if (line.isBlank()) {
                cursorY += (textLineH / 2).coerceAtLeast(4)
                continue
            }

            val nodes = mutableListOf<InlineNode>()
            if (inCodeBlock) {
                nodes.add(InlineNode.Text("  $line", codeBlockStyle))
            } else {
                val headingMatch = Regex("^(#{1,4})\\s+(.*)$").find(trimmed)
                val bulletMatch = Regex("^(\\s*)[-*]\\s+(.*)$").find(line)
                when {
                    headingMatch != null -> tokenizeInline(headingMatch.groupValues[2], headingStyle, nodes)
                    bulletMatch != null -> {
                        nodes.add(InlineNode.Text("${bulletMatch.groupValues[1]}• ", bulletStyle))
                        tokenizeInline(bulletMatch.groupValues[2], Style.EMPTY.withColor(defaultColor), nodes)
                    }
                    else -> tokenizeInline(line, Style.EMPTY.withColor(defaultColor), nodes)
                }
            }

            // Word-wrap inline nodes into visual lines
            var cursorX = 0
            var rowHasPill = false
            val rowStartIdx = items.size
            var currentRowStartIdx = rowStartIdx

            fun finishRow() {
                val rowH = if (rowHasPill) pillH else textLineH
                // Vertically center text runs inside pill rows
                if (rowHasPill) {
                    val textOffsetY = (rowH - textLineH) / 2
                    for (k in currentRowStartIdx until items.size) {
                        val it = items[k]
                        if (it is LaidOutItem.TextRun) {
                            items[k] = it.copy(y = cursorY + textOffsetY)
                        }
                    }
                }
                cursorY += rowH + lineGap
                cursorX = 0
                rowHasPill = false
                currentRowStartIdx = items.size
            }

            for (node in nodes) {
                when (node) {
                    is InlineNode.Pill -> {
                        val nameW = ceil(font.width(node.ref.displayName) * s).toInt()
                        val pw = 3 + iconSize + 3 + nameW + 4
                        if (cursorX > 0 && cursorX + pw > targetW) {
                            finishRow()
                        }
                        rowHasPill = true
                        items.add(LaidOutItem.RefPill(cursorX, cursorY, pw, pillH, node.ref))
                        cursorX += pw + 2
                    }
                    is InlineNode.Text -> {
                        // Split into words keeping spaces so wrapping is natural
                        val pieces = splitWords(node.text)
                        for (piece in pieces) {
                            if (piece.isEmpty()) continue
                            val isSpace = piece.isBlank()
                            val pw = ceil(font.width(piece) * s).toInt()
                            if (cursorX == 0 && isSpace) continue
                            if (cursorX > 0 && cursorX + pw > targetW && !isSpace) {
                                finishRow()
                            }
                            items.add(LaidOutItem.TextRun(cursorX, cursorY, pw, textLineH, piece, node.style, node.url))
                            cursorX += pw
                        }
                    }
                }
            }
            finishRow()
        }

        laidOut = items
        contentHeightPx = (cursorY - lineGap).coerceAtLeast(textLineH)
    }

    private fun splitWords(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            val ws = s[i].isWhitespace()
            while (i < s.length && s[i].isWhitespace() == ws) i++
            out.add(s.substring(start, i))
        }
        return out
    }

    private fun tokenizeInline(text: String, baseStyle: Style, out: MutableList<InlineNode>) {
        var i = 0
        val n = text.length
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(InlineNode.Text(buf.toString(), baseStyle))
                buf.clear()
            }
        }

        while (i < n) {
            if (text[i] == '[') {
                val m = mdLinkRegex.find(text.substring(i))
                if (m != null) {
                    val label = m.groupValues[1]
                    val url = m.groupValues[2].trimEnd('.', ',', ';')
                    flush()
                    out.add(InlineNode.Text("$label ↗", urlStyle, url = url))
                    i += m.value.length
                    continue
                }
            }

            if (text[i] == 'h' && (text.startsWith("https://", i) || text.startsWith("http://", i))) {
                val m = bareUrlRegex.find(text.substring(i))
                if (m != null) {
                    val url = m.groupValues[1].trimEnd('.', ',', ';')
                    val shortHost = url.removePrefix("https://").removePrefix("http://").substringBefore('/').take(28)
                    flush()
                    out.add(InlineNode.Text("$shortHost ↗", urlStyle, url = url))
                    i += url.length
                    continue
                }
            }

            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    val inner = text.substring(i + 1, end)
                    val ref = ReferenceExtractor.resolve(inner)
                    flush()
                    if (ref != null) {
                        out.add(InlineNode.Pill(ref))
                    } else {
                        out.add(InlineNode.Text(inner, codeStyle))
                    }
                    i = end + 1
                    continue
                }
            }

            if ((i == 0 || !text[i - 1].isLetterOrDigit()) && text[i] in 'a'..'z') {
                val m = bareIdRegex.find(text.substring(i))
                if (m != null) {
                    val candidate = m.groupValues[1].trimEnd('.', ',', ';')
                    val ref = ReferenceExtractor.resolve(candidate)
                    if (ref != null) {
                        flush()
                        out.add(InlineNode.Pill(ref))
                        i += candidate.length
                        continue
                    }
                }
            }

            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    out.add(InlineNode.Text(text.substring(i + 2, end), emphasisStyle))
                    i = end + 2
                    continue
                }
            }

            if (i + 1 < n && text[i] == '_' && text[i + 1] == '_') {
                val end = text.indexOf("__", i + 2)
                if (end > i + 2) {
                    flush()
                    out.add(InlineNode.Text(text.substring(i + 2, end), emphasisStyle))
                    i = end + 2
                    continue
                }
            }

            if (text[i] == '*' && (i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') {
                    flush()
                    out.add(InlineNode.Text(text.substring(i + 1, end), italicStyle))
                    i = end + 1
                    continue
                }
            }

            buf.append(text[i])
            i++
        }
        flush()
    }

    private fun hitTestLocal(localX: Double, localY: Double): LaidOutItem? {
        ensureLayout(width)
        val lx = localX.toInt()
        val ly = localY.toInt()
        for (item in laidOut) {
            if (lx >= item.x && lx <= item.x + item.w && ly >= item.y && ly <= item.y + item.h) {
                if (item is LaidOutItem.RefPill || (item is LaidOutItem.TextRun && item.url != null)) {
                    return item
                }
            }
        }
        return null
    }

    override fun onMouseDown(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val hit = hitTestLocal(mouseX, mouseY)
        if (hit is LaidOutItem.RefPill && onGameRefClick != null) {
            if (onGameRefClick!!.invoke(hit.ref, button, Screen.hasShiftDown())) {
                return true
            }
        } else if (hit is LaidOutItem.TextRun && hit.url != null && button == 0 && onUrlClick != null) {
            onUrlClick!!.invoke(hit.url)
            return true
        }
        return super.onMouseDown(mouseX, mouseY, button)
    }

    override fun determineHorizontalContentSize(sizing: Sizing): Int =
        parent?.width()?.minus(16)?.coerceAtLeast(60) ?: 260

    override fun determineVerticalContentSize(sizing: Sizing): Int {
        val availWidth = if (width > 0) width else (parent?.width()?.minus(16) ?: 260)
        ensureLayout(availWidth)
        return contentHeightPx
    }

    override fun inflate(space: io.wispforest.owo.ui.core.Size) {
        super.inflate(space)
        val prevH = contentHeightPx
        ensureLayout(width)
        if (contentHeightPx != prevH && verticalSizing.get().isContent) {
            this.height = contentHeightPx
        }
    }

    override fun draw(context: OwoUIDrawContext, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        ensureLayout(width)
        val font = Minecraft.getInstance().font
        val s = effectiveScale()

        val hit = if (isInBoundingBox(mouseX.toDouble(), mouseY.toDouble())) {
            hitTestLocal((mouseX - x).toDouble(), (mouseY - y).toDouble())
        } else null

        hoveredPill = hit as? LaidOutItem.RefPill
        val hoverKey = when (hit) {
            is LaidOutItem.RefPill -> "ref:${hit.ref.id}"
            is LaidOutItem.TextRun -> hit.url?.let { "url:$it" }
            null -> null
        }
        if (hoverKey != lastHoverKey) {
            lastHoverKey = hoverKey
            when (hit) {
                is LaidOutItem.RefPill -> {
                    cursorStyle(CursorStyle.HAND)
                    tooltip(ReferenceExtractor.buildTooltip(hit.ref))
                }
                is LaidOutItem.TextRun -> {
                    val u = hit.url
                    if (u != null) {
                        cursorStyle(CursorStyle.HAND)
                        tooltip(
                            listOf(
                                Component.translatable("screen.agyassistant.link.open_browser"),
                                Component.literal(u).withStyle(Style.EMPTY.withColor(0x94A3B8)),
                            )
                        )
                    } else {
                        cursorStyle(CursorStyle.POINTER)
                        tooltip(emptyList<Component>())
                    }
                }
                null -> {
                    cursorStyle(CursorStyle.POINTER)
                    tooltip(emptyList<Component>())
                }
            }
        }

        val pose = context.pose()
        for (item in laidOut) {
            when (item) {
                is LaidOutItem.TextRun -> {
                    pose.pushPose()
                    pose.translate((x + item.x).toFloat(), (y + item.y).toFloat(), 0f)
                    if (s != 1.0f) pose.scale(s, s, 1f)
                    context.drawString(font, Component.literal(item.text).withStyle(item.style), 0, 0, defaultColor, false)
                    pose.popPose()
                }
                is LaidOutItem.RefPill -> {
                    val px = x + item.x
                    val py = y + item.y
                    val isHovered = (hoveredPill === item)
                    val (bgColor, borderColor) = when (item.ref.kind) {
                        RefKind.ITEM -> 0xCC3B235A.toInt() to 0xFFA855F7.toInt()
                        RefKind.BLOCK -> 0xCC1E3A5F.toInt() to 0xFF3B82F6.toInt()
                        RefKind.ENTITY -> 0xCC134E4A.toInt() to 0xFF2DD4BF.toInt()
                    }
                    val finalBorder = if (isHovered) 0xFFF8FAFC.toInt() else borderColor

                    context.fill(px, py, px + item.w, py + item.h, bgColor)
                    context.drawRectOutline(px, py, item.w, item.h, finalBorder)

                    // Render 10x10 item sprite inside pill
                    val iconY = py + (item.h - 10) / 2
                    pose.pushPose()
                    pose.translate((px + 3).toFloat(), iconY.toFloat(), 0f)
                    pose.scale(10f / 16f, 10f / 16f, 1f)
                    context.renderFakeItem(item.ref.stack, 0, 0)
                    pose.popPose()

                    // Render localized item name
                    val textH = ceil(font.lineHeight * s).toInt()
                    val textY = py + (item.h - textH) / 2 + 1
                    pose.pushPose()
                    pose.translate((px + 15).toFloat(), textY.toFloat(), 0f)
                    if (s != 1.0f) pose.scale(s, s, 1f)
                    context.drawString(font, item.ref.displayName, 0, 0, 0xF8FAFC, false)
                    pose.popPose()
                }
            }
        }
    }
}
