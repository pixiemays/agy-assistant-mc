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

    data class BoxBg(
        override val x: Int,
        override val y: Int,
        override val w: Int,
        override val h: Int,
        val bgColor: Int,
        val borderColor: Int = 0,
        val leftBarColor: Int = 0,
    ) : LaidOutItem

    data class Divider(
        override val x: Int,
        override val y: Int,
        override val w: Int,
        override val h: Int = 1,
        val color: Int = 0xFF334155.toInt(),
    ) : LaidOutItem

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

    private val emphasisStyle: Style = Style.EMPTY.withColor(0x93C5FD)
    private val italicStyle: Style = Style.EMPTY.withColor(0xE2E8F0).withItalic(true)
    private val strikeStyle: Style = Style.EMPTY.withColor(0x94A3B8).withStrikethrough(true)
    private val codeStyle: Style = Style.EMPTY.withColor(0xFDE68A)
    private val codeBlockStyle: Style = Style.EMPTY.withColor(0xA5F3FC)
    private val headingStyle: Style = Style.EMPTY.withColor(0x38BDF8)
    private val bulletStyle: Style = Style.EMPTY.withColor(0x93C5FD)
    private val urlStyle: Style = Style.EMPTY.withColor(0x60A5FA).withUnderlined(true)

    private val bareIdRegex = Regex("^([a-z0-9_.-]+:[a-z0-9_/.-]+)")
    private val mdLinkRegex = Regex("^\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    private val bareUrlRegex = Regex("^(https?://[^\\s)\\]>]+)")
    private val hrRegex = Regex("^([-*_])\\s*\\1\\s*\\1[\\s\\1]*$")
    private val tableSepRegex = Regex("^\\|?[\\s:]*-{2,}[\\s:]*(\\|[\\s:]*-{2,}[\\s:]*)*\\|?$")
    private val orderedListRegex = Regex("^(\\s*)(\\d+\\.)\\s+(.*)$")
    private val bulletListRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val headingRegex = Regex("^(#{1,4})\\s+(.*)$")

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

    private fun isTableRow(line: String): Boolean {
        val t = line.trim()
        return t.contains('|') && (t.startsWith('|') || t.count { it == '|' } >= 2)
    }

    private fun parseTableCells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith('|')) t = t.substring(1)
        if (t.endsWith('|')) t = t.dropLast(1)
        return t.split('|').map { it.trim() }
    }

    private fun ensureLayout(pixelWidth: Int) {
        val targetW = pixelWidth.coerceAtLeast(60)
        if (targetW == lastLayoutWidth && laidOut.isNotEmpty()) return
        lastLayoutWidth = targetW

        val font = Minecraft.getInstance().font
        val s = effectiveScale()
        val textLineH = ceil(font.lineHeight * s).toInt().coerceAtLeast(8)
        val pillH = (textLineH + 4).coerceAtLeast(13)
        val iconSize = 10
        val lineGap = 3

        val items = mutableListOf<LaidOutItem>()
        val rawLines = markdown.split("\n").map { it.trimEnd('\r') }
        var cursorY = 0
        var lineIdx = 0

        fun layoutInlineNodes(
            nodes: List<InlineNode>,
            startX: Int,
            maxWidth: Int,
        ) {
            var cursorX = 0
            var rowHasPill = false
            var currentRowStartIdx = items.size

            fun finishRow() {
                if (items.size == currentRowStartIdx) return
                val rowH = if (rowHasPill) pillH else textLineH
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
                        if (cursorX > 0 && cursorX + pw > maxWidth) {
                            finishRow()
                        }
                        rowHasPill = true
                        items.add(LaidOutItem.RefPill(startX + cursorX, cursorY, pw, pillH, node.ref))
                        cursorX += pw + 2
                    }
                    is InlineNode.Text -> {
                        val pieces = splitWords(node.text)
                        for (piece in pieces) {
                            if (piece.isEmpty()) continue
                            val isSpace = piece.isBlank()
                            val pw = ceil(font.width(piece) * s).toInt()
                            if (cursorX == 0 && isSpace) continue
                            if (cursorX > 0 && cursorX + pw > maxWidth && !isSpace) {
                                finishRow()
                            }
                            items.add(LaidOutItem.TextRun(startX + cursorX, cursorY, pw, textLineH, piece, node.style, node.url))
                            cursorX += pw
                        }
                    }
                }
            }
            finishRow()
        }

        while (lineIdx < rawLines.size) {
            val line = rawLines[lineIdx]
            val trimmed = line.trim()

            // 1. Blank line
            if (trimmed.isEmpty()) {
                cursorY += (textLineH / 2).coerceAtLeast(4)
                lineIdx++
                continue
            }

            // 2. Fenced code block ```...```
            if (trimmed.startsWith("```")) {
                lineIdx++
                val codeLines = mutableListOf<String>()
                while (lineIdx < rawLines.size && !rawLines[lineIdx].trim().startsWith("```")) {
                    codeLines.add(rawLines[lineIdx])
                    lineIdx++
                }
                if (lineIdx < rawLines.size) lineIdx++ // skip closing ```

                val boxTop = cursorY
                val bgIdx = items.size
                items.add(LaidOutItem.BoxBg(0, boxTop, targetW, 10, 0xCC070B14.toInt(), 0xFF1E293B.toInt()))
                cursorY += 4
                for (cl in codeLines) {
                    layoutInlineNodes(listOf(InlineNode.Text(cl.ifEmpty { " " }, codeBlockStyle)), 5, targetW - 10)
                }
                cursorY += 2
                items[bgIdx] = LaidOutItem.BoxBg(0, boxTop, targetW, cursorY - boxTop, 0xCC070B14.toInt(), 0xFF1E293B.toInt())
                cursorY += lineGap
                continue
            }

            // 3. Horizontal rule (---, ***, ___)
            if (hrRegex.matches(trimmed)) {
                cursorY += 3
                items.add(LaidOutItem.Divider(0, cursorY, targetW, 1, 0xFF334155.toInt()))
                cursorY += 6
                lineIdx++
                continue
            }

            // 4. Markdown Table
            if (isTableRow(line) && lineIdx + 1 < rawLines.size && tableSepRegex.matches(rawLines[lineIdx + 1].trim())) {
                val headerCells = parseTableCells(line)
                lineIdx += 2 // skip header + separator
                val bodyRows = mutableListOf<List<String>>()
                while (lineIdx < rawLines.size && isTableRow(rawLines[lineIdx])) {
                    if (!tableSepRegex.matches(rawLines[lineIdx].trim())) {
                        bodyRows.add(parseTableCells(rawLines[lineIdx]))
                    }
                    lineIdx++
                }

                val colCount = maxOf(headerCells.size, bodyRows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                val colW = (targetW / colCount).coerceAtLeast(28)
                val tableW = (colW * colCount).coerceAtMost(targetW)

                fun renderTableRow(cells: List<String>, isHeader: Boolean, rowIndex: Int) {
                    val rowTop = cursorY
                    val bgColor = when {
                        isHeader -> 0xCC1E293B.toInt()
                        rowIndex % 2 == 1 -> 0x551E293B.toInt()
                        else -> 0x330F172A.toInt()
                    }
                    val bgIdx = items.size
                    items.add(LaidOutItem.BoxBg(0, rowTop, tableW, textLineH + 6, bgColor, 0xFF334155.toInt()))

                    var maxCellBottom = rowTop + textLineH + 4
                    for (c in 0 until colCount) {
                        val cellText = cells.getOrElse(c) { "" }
                        val cellNodes = mutableListOf<InlineNode>()
                        val cellStyle = if (isHeader) headingStyle else Style.EMPTY.withColor(defaultColor)
                        tokenizeInline(cellText, cellStyle, cellNodes)

                        val savedY = cursorY
                        cursorY = rowTop + 3
                        layoutInlineNodes(cellNodes, c * colW + 4, (colW - 8).coerceAtLeast(18))
                        if (cursorY > maxCellBottom) maxCellBottom = cursorY
                        cursorY = savedY
                    }
                    val rowH = (maxCellBottom - rowTop).coerceAtLeast(textLineH + 6)
                    items[bgIdx] = LaidOutItem.BoxBg(0, rowTop, tableW, rowH, bgColor, 0xFF334155.toInt())
                    cursorY = rowTop + rowH
                }

                renderTableRow(headerCells, isHeader = true, rowIndex = 0)
                for ((rIdx, rCells) in bodyRows.withIndex()) {
                    renderTableRow(rCells, isHeader = false, rowIndex = rIdx)
                }
                cursorY += lineGap
                continue
            }

            // 5. Blockquote (> text)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (lineIdx < rawLines.size && rawLines[lineIdx].trimStart().startsWith(">")) {
                    quoteLines.add(rawLines[lineIdx].trimStart().removePrefix(">").trimStart())
                    lineIdx++
                }
                val boxTop = cursorY
                val bgIdx = items.size
                items.add(LaidOutItem.BoxBg(0, boxTop, targetW, 10, 0x661E293B.toInt(), 0, 0xFF3B82F6.toInt()))
                cursorY += 3
                for (ql in quoteLines) {
                    val nodes = mutableListOf<InlineNode>()
                    tokenizeInline(ql, italicStyle, nodes)
                    layoutInlineNodes(nodes, 7, targetW - 10)
                }
                cursorY += 1
                items[bgIdx] = LaidOutItem.BoxBg(0, boxTop, targetW, cursorY - boxTop, 0x661E293B.toInt(), 0, 0xFF3B82F6.toInt())
                cursorY += lineGap
                continue
            }

            // 6. Normal line: Heading, Bullet list, Ordered list, or Paragraph
            val nodes = mutableListOf<InlineNode>()
            val headingMatch = headingRegex.find(trimmed)
            val bulletMatch = bulletListRegex.find(line)
            val orderedMatch = orderedListRegex.find(line)
            when {
                headingMatch != null -> {
                    if (cursorY > 0) cursorY += 2
                    tokenizeInline(headingMatch.groupValues[2], headingStyle, nodes)
                }
                bulletMatch != null -> {
                    nodes.add(InlineNode.Text("${bulletMatch.groupValues[1]}• ", bulletStyle))
                    tokenizeInline(bulletMatch.groupValues[2], Style.EMPTY.withColor(defaultColor), nodes)
                }
                orderedMatch != null -> {
                    nodes.add(InlineNode.Text("${orderedMatch.groupValues[1]}${orderedMatch.groupValues[2]} ", bulletStyle))
                    tokenizeInline(orderedMatch.groupValues[3], Style.EMPTY.withColor(defaultColor), nodes)
                }
                else -> tokenizeInline(line, Style.EMPTY.withColor(defaultColor), nodes)
            }
            layoutInlineNodes(nodes, 0, targetW)
            lineIdx++
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
                    val ref = ReferenceExtractor.resolve(inner, allowNameLookup = true)
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

            // Strikethrough ~~...~~
            if (i + 1 < n && text[i] == '~' && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    tokenizeInline(text.substring(i + 2, end), strikeStyle, out)
                    i = end + 2
                    continue
                }
            }

            // Bold **...** -> recursive so nested `code` / pills parse cleanly!
            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    tokenizeInline(text.substring(i + 2, end), emphasisStyle, out)
                    i = end + 2
                    continue
                }
            }

            // Bold __...__ -> recursive
            if (i + 1 < n && text[i] == '_' && text[i + 1] == '_') {
                val end = text.indexOf("__", i + 2)
                if (end > i + 2) {
                    flush()
                    tokenizeInline(text.substring(i + 2, end), emphasisStyle, out)
                    i = end + 2
                    continue
                }
            }

            // Italic *...* -> recursive so nested `code` / pills parse cleanly!
            if (text[i] == '*' && (i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') {
                    flush()
                    tokenizeInline(text.substring(i + 1, end), italicStyle, out)
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
            else -> null
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
                else -> {
                    cursorStyle(CursorStyle.POINTER)
                    tooltip(emptyList<Component>())
                }
            }
        }

        val pose = context.pose()
        for (item in laidOut) {
            when (item) {
                is LaidOutItem.BoxBg -> {
                    val bx = x + item.x
                    val by = y + item.y
                    if (item.bgColor != 0) {
                        context.fill(bx, by, bx + item.w, by + item.h, item.bgColor)
                    }
                    if (item.borderColor != 0) {
                        context.drawRectOutline(bx, by, item.w, item.h, item.borderColor)
                    }
                    if (item.leftBarColor != 0) {
                        context.fill(bx, by, bx + 2, by + item.h, item.leftBarColor)
                    }
                }
                is LaidOutItem.Divider -> {
                    val dx = x + item.x
                    val dy = y + item.y
                    context.fill(dx, dy, dx + item.w, dy + item.h, item.color)
                }
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

                    val iconY = py + (item.h - 10) / 2
                    pose.pushPose()
                    pose.translate((px + 3).toFloat(), iconY.toFloat(), 0f)
                    pose.scale(10f / 16f, 10f / 16f, 1f)
                    context.renderFakeItem(item.ref.stack, 0, 0)
                    pose.popPose()

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
