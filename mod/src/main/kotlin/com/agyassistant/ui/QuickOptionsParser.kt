package com.agyassistant.ui

data class ParsedReply(
    val displayText: String,
    val options: List<String>,
)

object QuickOptionsParser {
    private val FULL_OPTIONS_REGEX = Regex("\\[options:\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE)
    private val PARTIAL_OPTIONS_REGEX = Regex("\\[options:[^\\]]*$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedReply {
        val options = mutableListOf<String>()
        val withoutFull = FULL_OPTIONS_REGEX.replace(raw) { match ->
            match.groupValues[1]
                .split("|")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { opt ->
                    if (options.size < 4 && !options.contains(opt)) {
                        options.add(opt.take(40))
                    }
                }
            ""
        }
        val clean = PARTIAL_OPTIONS_REGEX.replace(withoutFull, "").trimEnd()
        return ParsedReply(displayText = clean, options = options)
    }
}
