package com.agyassistant.ui

import com.agyassistant.config.DirectServicePreset
import com.agyassistant.config.ModConfig
import com.agyassistant.config.ModConfigData
import net.minecraft.network.chat.Component

sealed class ErrorAction {
    abstract val label: Component

    class OpenUrl(
        override val label: Component,
        val url: String,
    ) : ErrorAction()

    class OpenSettings(
        override val label: Component = Component.translatable("screen.agyassistant.err.action.open_settings"),
        val tab: SettingsScreen.Tab = SettingsScreen.Tab.CONNECTION,
    ) : ErrorAction()

    class Retry(
        override val label: Component = Component.translatable("screen.agyassistant.err.action.retry"),
    ) : ErrorAction()

    class ResetChat(
        override val label: Component = Component.translatable("screen.agyassistant.err.action.reset_chat"),
    ) : ErrorAction()
}

data class ParsedError(
    val message: String,
    val rawError: String?,
    val actions: List<ErrorAction> = emptyList(),
)

/**
 * Translates common raw API / CLI / bridge / network errors into plain language
 * with context-aware quick-action buttons (key links, install links, settings, retry).
 */
object ErrorParser {
    const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"
    const val AGY_CLI_INSTALL_URL = "https://antigravity.google/download"
    const val OPENCODE_INSTALL_URL = "https://opencode.ai"
    const val OPENROUTER_PRIVACY_URL = "https://openrouter.ai/settings/privacy"
    const val OLLAMA_DOWNLOAD_URL = "https://ollama.com/download"
    const val BRIDGE_RELEASES_URL = "https://github.com/pixiemays/agy-assistant-mc/releases/latest"
    const val PROXY_GUIDE_URL =
        "https://github.com/pixiemays/agy-assistant-mc#%D0%B3%D0%B0%D0%B9%D0%B4-vpn-%D1%82%D0%BE%D0%BB%D1%8C%D0%BA%D0%BE-%D0%B4%D0%BB%D1%8F-%D0%BD%D0%B5%D0%B9%D1%80%D0%BE%D0%BD%D0%BA%D0%B8-%D0%BF%D1%80%D0%BE%D0%BA%D1%81%D0%B8-%D0%B4%D0%BB%D1%8F-api"

    fun parse(rawError: String, cfg: ModConfigData = ModConfig.data): ParsedError {
        val trimmed = rawError.trim()
        val lower = trimmed.lowercase()

        // 1. Missing both GEMINI_API_KEY and agy CLI
        if (lower.contains("no gemini_api_key") ||
            lower.contains("agy cli not found") ||
            (lower.contains("agy") && (lower.contains("not found") || lower.contains("install agy")))
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.agy_or_gemini_missing"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.get_gemini_key"), GEMINI_KEY_URL),
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.install_agy"), AGY_CLI_INSTALL_URL),
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION),
                ),
            )
        }

        // 2. OpenCode CLI missing
        if (lower.contains("opencode") &&
            (lower.contains("not found") || lower.contains("no such file") || lower.contains("cannot find"))
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.opencode_missing"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.install_opencode"), OPENCODE_INSTALL_URL),
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.ADVANCED),
                ),
            )
        }

        // 3. Direct API not configured (missing key / URL / model)
        if (lower.contains("direct_unconfigured") ||
            lower.contains("direct api не настроен") ||
            lower.contains("direct api is not configured") ||
            lower.contains("base url and model must be configured")
        ) {
            val actions = mutableListOf<ErrorAction>()
            activeKeyUrlAction(cfg)?.let { actions.add(it) }
            actions.add(ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION))
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.direct_unconfigured"),
                rawError = trimmed,
                actions = actions,
            )
        }

        // 4. Local agy-bridge offline / unreachable
        if (lower.contains("bridge_offline") ||
            lower.contains("agy-bridge не отвечает") ||
            lower.contains("agy-bridge is not responding") ||
            (cfg.provider != "openai_compat" && isConnectionFailure(lower))
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.bridge_offline", cfg.bridgePort),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.download_bridge"), BRIDGE_RELEASES_URL),
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.ADVANCED),
                    ErrorAction.Retry(),
                ),
            )
        }

        // 5. Screenshots blocked by privacy setting
        if (lower.contains("shots_blocked") ||
            lower.contains("отправка скриншотов отключена") ||
            lower.contains("screenshot sending is disabled")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.msg.shots_blocked"),
                rawError = null,
                actions = listOf(
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.BEHAVIOR),
                ),
            )
        }

        // 6. OpenRouter :free prompt logging / privacy policy (404 No endpoints found)
        if (lower.contains("no endpoints found") ||
            lower.contains("data policy") ||
            lower.contains("логирование промптов") ||
            lower.contains("allow prompt logging")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.openrouter_privacy"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.openrouter_privacy"), OPENROUTER_PRIVACY_URL),
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION),
                ),
            )
        }

        // 7. Invalid API key / Unauthorized (401)
        if (lower.contains("401") ||
            lower.contains("api_key_invalid") ||
            lower.contains("api key not valid") ||
            lower.contains("invalid api key") ||
            lower.contains("invalid_api_key") ||
            lower.contains("incorrect api key") ||
            lower.contains("неверный api-ключ") ||
            lower.contains("unauthorized") ||
            lower.contains("unauthenticated") ||
            lower.contains("not logged in") ||
            lower.contains("login required")
        ) {
            val actions = mutableListOf<ErrorAction>()
            activeKeyUrlAction(cfg)?.let { actions.add(it) }
            if (cfg.provider == "bridge") {
                actions.add(ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.install_agy"), AGY_CLI_INSTALL_URL))
            }
            actions.add(ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION))
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.invalid_key"),
                rawError = trimmed,
                actions = actions,
            )
        }

        // 8. Region blocked / geo restriction (403)
        if (lower.contains("403") ||
            lower.contains("user location is not supported") ||
            lower.contains("unsupported_country") ||
            lower.contains("заблокирован по региону") ||
            lower.contains("blocked by region") ||
            lower.contains("forbidden")
        ) {
            val tab = if (cfg.provider == "openai_compat") SettingsScreen.Tab.ADVANCED else SettingsScreen.Tab.CONNECTION
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.region_blocked"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenSettings(tab = tab),
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.proxy_guide"), PROXY_GUIDE_URL),
                ),
            )
        }

        // 9. Payment required / insufficient credits (402)
        if (lower.contains("402") ||
            lower.contains("insufficient credits") ||
            lower.contains("insufficient_quota") ||
            lower.contains("payment required") ||
            lower.contains("credit balance") ||
            lower.contains("billing")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.no_credits"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION),
                ),
            )
        }

        // 10. Rate limit / quota exceeded (429)
        if (lower.contains("429") ||
            lower.contains("rate limit") ||
            lower.contains("rate_limit") ||
            lower.contains("too many requests") ||
            lower.contains("resource_exhausted") ||
            lower.contains("лимит запросов") ||
            lower.contains("quota")
        ) {
            val tab = if (cfg.provider == "openai_compat") SettingsScreen.Tab.ADVANCED else SettingsScreen.Tab.CONNECTION
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.rate_limited"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.Retry(),
                    ErrorAction.OpenSettings(tab = tab),
                ),
            )
        }

        // 11. Model does not support vision / screenshots
        if (lower.contains("does not support image") ||
            lower.contains("not support images") ||
            lower.contains("images are not supported") ||
            lower.contains("image_url") ||
            (lower.contains("vision") && (lower.contains("not support") || lower.contains("не поддерж")))
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.vision_unsupported"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION),
                ),
            )
        }

        // 12. Context length / payload too large
        if (lower.contains("context_length") ||
            lower.contains("maximum context") ||
            lower.contains("too many tokens") ||
            lower.contains("token limit") ||
            lower.contains("payload too large") ||
            lower.contains("413")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.context_too_long"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.ResetChat(),
                ),
            )
        }

        // 13. Model not found / unavailable
        if (lower.contains("model_not_found") ||
            lower.contains("model not found") ||
            lower.contains("model unavailable") ||
            lower.contains("модель не найдена") ||
            lower.contains("модель недоступна") ||
            lower.contains("does not exist") ||
            (lower.contains("404") && lower.contains("model"))
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.model_not_found"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION),
                ),
            )
        }

        // 14. Local Ollama not running
        val isOllama = cfg.provider == "openai_compat" && (
            cfg.directServicePreset == DirectServicePreset.OLLAMA.key ||
                cfg.openaiBaseUrl.contains("11434")
            )
        if (isOllama && isConnectionFailure(lower)) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.ollama_offline"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenUrl(Component.translatable("screen.agyassistant.err.action.download_ollama"), OLLAMA_DOWNLOAD_URL),
                    ErrorAction.Retry(),
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.CONNECTION),
                ),
            )
        }

        // 15. Server-side 5xx errors
        if (lower.contains("http 50") ||
            lower.contains("500") ||
            lower.contains("502") ||
            lower.contains("503") ||
            lower.contains("504") ||
            lower.contains("overloaded") ||
            lower.contains("service unavailable") ||
            lower.contains("bad gateway") ||
            lower.contains("internal server error") ||
            lower.contains("ошибка на стороне сервиса")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.server_error"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.Retry(),
                ),
            )
        }

        // 16. Network / timeout / SSL / proxy failure
        if (isConnectionFailure(lower) ||
            lower.contains("timed out") ||
            lower.contains("timeout") ||
            lower.contains("не ответил вовремя")
        ) {
            val tab = if (cfg.provider == "openai_compat") SettingsScreen.Tab.ADVANCED else SettingsScreen.Tab.CONNECTION
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.network_error"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.Retry(),
                    ErrorAction.OpenSettings(tab = tab),
                ),
            )
        }

        // 17. Empty response
        if (lower.contains("empty response") ||
            lower.contains("returned no text") ||
            lower.contains("no tokens")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.empty_response"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.Retry(),
                    ErrorAction.ResetChat(),
                ),
            )
        }

        // 18. Endpoint / Base URL not found (404)
        if (lower.contains("endpoint not found") ||
            lower.contains("адрес не найден") ||
            lower.contains("base url")
        ) {
            return ParsedError(
                message = tr("screen.agyassistant.err.parsed.bad_endpoint"),
                rawError = trimmed,
                actions = listOf(
                    ErrorAction.OpenSettings(tab = SettingsScreen.Tab.ADVANCED),
                ),
            )
        }

        // Fallback: keep raw message, still offer Retry
        return ParsedError(
            message = tr("screen.agyassistant.err.request_failed", trimmed),
            rawError = null,
            actions = listOf(ErrorAction.Retry()),
        )
    }

    private fun isConnectionFailure(lower: String): Boolean =
        lower.contains("connection refused") ||
            lower.contains("connection reset") ||
            lower.contains("connection failed") ||
            lower.contains("не удалось подключиться") ||
            lower.contains("handshake") ||
            lower.contains("ssl") ||
            lower.contains("unknownhost") ||
            lower.contains("unknown host") ||
            lower.contains("failed to connect")

    private fun activeKeyUrlAction(cfg: ModConfigData): ErrorAction.OpenUrl? {
        if (cfg.provider == "bridge") {
            return ErrorAction.OpenUrl(
                Component.translatable("screen.agyassistant.err.action.get_gemini_key"),
                GEMINI_KEY_URL,
            )
        }
        if (cfg.provider == "openai_compat") {
            val preset = DirectServicePreset.byKey(cfg.directServicePreset)
            val url = preset.keyUrl ?: return null
            val labelKey = when (preset) {
                DirectServicePreset.GEMINI -> "screen.agyassistant.err.action.get_gemini_key"
                DirectServicePreset.OPENROUTER -> "screen.agyassistant.err.action.get_openrouter_key"
                DirectServicePreset.GROQ -> "screen.agyassistant.err.action.get_groq_key"
                DirectServicePreset.OLLAMA -> return null
            }
            return ErrorAction.OpenUrl(Component.translatable(labelKey), url)
        }
        return null
    }

    private fun tr(key: String, vararg args: Any): String =
        Component.translatable(key, *args).string
}
