package com.agyassistant.ui

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

object MarkdownFormatter {
    private val BODY_STYLE: Style = Style.EMPTY.withColor(0xF8FAFC)
    private val EMPHASIS_STYLE: Style = Style.EMPTY.withColor(0x93C5FD)
    private val ITALIC_STYLE: Style = Style.EMPTY.withColor(0xE2E8F0).withItalic(true)
    private val CODE_STYLE: Style = Style.EMPTY.withColor(0xFDE68A)
    private val CODE_BLOCK_STYLE: Style = Style.EMPTY.withColor(0xA5F3FC)
    private val HEADING_STYLE: Style = Style.EMPTY.withColor(0x38BDF8)
    private val BULLET_STYLE: Style = Style.EMPTY.withColor(0x93C5FD)

    fun format(markdown: String): Component {
        val root: MutableComponent = Component.empty()
        val lines = markdown.split("\n")
        var inCodeBlock = false

        for (idx in lines.indices) {
            if (idx > 0) {
                root.append(Component.literal("\n"))
            }
            val rawLine = lines[idx].trimEnd('\r')
            val trimmed = rawLine.trimStart()

            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            if (inCodeBlock) {
                root.append(Component.literal("  $rawLine").withStyle(CODE_BLOCK_STYLE))
                continue
            }

            // Headings: #, ##, ###
            val headingMatch = Regex("^(#{1,4})\\s+(.*)$").find(trimmed)
            if (headingMatch != null) {
                val headingText = headingMatch.groupValues[2]
                appendInline(root, headingText, HEADING_STYLE)
                continue
            }

            // Bullet list items: - item or * item
            val bulletMatch = Regex("^(\\s*)[-*]\\s+(.*)$").find(rawLine)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1]
                val rest = bulletMatch.groupValues[2]
                root.append(Component.literal("$indent• ").withStyle(BULLET_STYLE))
                appendInline(root, rest, BODY_STYLE)
                continue
            }

            appendInline(root, rawLine, BODY_STYLE)
        }

        return root
    }

    private fun appendInline(target: MutableComponent, text: String, baseStyle: Style) {
        var i = 0
        val n = text.length
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                target.append(Component.literal(buf.toString()).withStyle(baseStyle))
                buf.clear()
            }
        }

        while (i < n) {
            // Inline code `...`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    target.append(Component.literal(text.substring(i + 1, end)).withStyle(CODE_STYLE))
                    i = end + 1
                    continue
                }
            }

            // Bold **...** -> clean color highlight (no Minecraft bold)
            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    val inner = text.substring(i + 2, end)
                    target.append(Component.literal(inner).withStyle(EMPHASIS_STYLE))
                    i = end + 2
                    continue
                }
            }

            // Bold __...__ -> clean color highlight (no Minecraft bold)
            if (i + 1 < n && text[i] == '_' && text[i + 1] == '_') {
                val end = text.indexOf("__", i + 2)
                if (end > i + 2) {
                    flush()
                    val inner = text.substring(i + 2, end)
                    target.append(Component.literal(inner).withStyle(EMPHASIS_STYLE))
                    i = end + 2
                    continue
                }
            }

            // Italic *...* (single asterisk, not preceded/followed by space at edges)
            if (text[i] == '*' && (i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') {
                    flush()
                    val inner = text.substring(i + 1, end)
                    target.append(Component.literal(inner).withStyle(ITALIC_STYLE))
                    i = end + 1
                    continue
                }
            }

            buf.append(text[i])
            i++
        }
        flush()
    }
}
