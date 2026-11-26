package com.agyassistant.config

enum class DirectServicePreset(
    val key: String,
    val translationKey: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String?,
) {
    GEMINI(
        key = "gemini",
        translationKey = "screen.agyassistant.settings.service.gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.0-flash",
        keyUrl = "https://aistudio.google.com/apikey",
    ),
    OPENROUTER(
        key = "openrouter",
        translationKey = "screen.agyassistant.settings.service.openrouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openrouter/free",
        keyUrl = "https://openrouter.ai/settings/keys",
    ),
    GROQ(
        key = "groq",
        translationKey = "screen.agyassistant.settings.service.groq",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "qwen/qwen3-32b",
        keyUrl = "https://console.groq.com/keys",
    ),
    OLLAMA(
        key = "ollama",
        translationKey = "screen.agyassistant.settings.service.ollama",
        baseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen3:8b",
        keyUrl = null,
    );

    companion object {
        val DEFAULT = OPENROUTER

        fun byKey(key: String): DirectServicePreset =
            entries.find { it.key == key } ?: DEFAULT

        fun next(current: DirectServicePreset): DirectServicePreset = when (current) {
            GEMINI -> OPENROUTER
            OPENROUTER -> GROQ
            GROQ -> OLLAMA
            OLLAMA -> GEMINI
        }
    }
}
