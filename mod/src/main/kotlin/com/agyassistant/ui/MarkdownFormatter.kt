package com.agyassistant.ui

import net.minecraft.network.chat.ClickEvent
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

    private val BARE_ID_REGEX = Regex("^([a-z0-9_.-]+:[a-z0-9_/.-]+)")
    private val MD_LINK_REGEX = Regex("^\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    private val BARE_URL_REGEX = Regex("^(https?://[^\\s)\\]>]+)")

    private fun urlStyle(url: String): Style = Style.EMPTY
        .withColor(0x60A5FA)
        .withUnderlined(true)
        .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, url))

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

    private fun refStyle(ref: GameReference): Style {
        val color = when (ref.kind) {
            RefKind.ITEM -> 0xFDE68A
            RefKind.BLOCK -> 0x38BDF8
            RefKind.ENTITY -> 0x5EEAD4
        }
        return Style.EMPTY
            .withColor(color)
            .withUnderlined(true)
            .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, "${ReferenceExtractor.CLICK_PREFIX}${ref.id}"))
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
            // Markdown link [label](https://...)
            if (text[i] == '[') {
                val m = MD_LINK_REGEX.find(text.substring(i))
                if (m != null) {
                    val label = m.groupValues[1]
                    val url = m.groupValues[2].trimEnd('.', ',', ';')
                    flush()
                    target.append(Component.literal("$label ↗").withStyle(urlStyle(url)))
                    i += m.value.length
                    continue
                }
            }

            // Bare https://... URL
            if (text[i] == 'h' && (text.startsWith("https://", i) || text.startsWith("http://", i))) {
                val m = BARE_URL_REGEX.find(text.substring(i))
                if (m != null) {
                    val url = m.groupValues[1].trimEnd('.', ',', ';')
                    val shortHost = url.removePrefix("https://").removePrefix("http://").substringBefore('/').take(28)
                    flush()
                    target.append(Component.literal("$shortHost ↗").withStyle(urlStyle(url)))
                    i += url.length
                    continue
                }
            }

            // Inline code `...` -> if valid game ID, render as clickable localized link!
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    val inner = text.substring(i + 1, end)
                    val ref = ReferenceExtractor.resolve(inner)
                    flush()
                    if (ref != null) {
                        target.append(Component.literal("[${ref.displayName}]").withStyle(refStyle(ref)))
                    } else {
                        target.append(Component.literal(inner).withStyle(CODE_STYLE))
                    }
                    i = end + 1
                    continue
                }
            }

            // Bare namespace:path in prose (not inside a word)
            if ((i == 0 || !text[i - 1].isLetterOrDigit()) && text[i] in 'a'..'z') {
                val m = BARE_ID_REGEX.find(text.substring(i))
                if (m != null) {
                    val candidate = m.groupValues[1].trimEnd('.', ',', ';')
                    val ref = ReferenceExtractor.resolve(candidate)
                    if (ref != null) {
                        flush()
                        target.append(Component.literal("[${ref.displayName}]").withStyle(refStyle(ref)))
                        i += candidate.length
                        continue
                    }
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

            // Italic *...*
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
