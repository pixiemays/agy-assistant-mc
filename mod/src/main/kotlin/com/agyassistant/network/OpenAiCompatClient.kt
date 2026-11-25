package com.agyassistant.network

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.context.ItemInfo
import com.agyassistant.context.TargetInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

object OpenAiCompatClient {
    private val logger = LoggerFactory.getLogger("agyassistant.openai")
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var proxyClient: HttpClient? = null

    @Volatile
    private var proxyClientUrl: String = ""

    /**
     * Client used for AI API requests: goes through the player's local VPN proxy when
     * configured, direct otherwise (game traffic itself never touches the proxy).
     */
    private fun apiClient(): HttpClient {
        val proxyUrl = ModConfig.data.openaiProxyUrl.trim()
        if (proxyUrl.isEmpty()) return client
        if (!proxyUrl.startsWith("http://") && !proxyUrl.startsWith("https://")) {
            logger.warn("Proxy URL '{}' must start with http:// — using direct connection", proxyUrl)
            return client
        }
        if (proxyClient != null && proxyClientUrl == proxyUrl) return proxyClient!!

        val proxied = runCatching {
            val uri = URI.create(proxyUrl)
            val host = uri.host ?: "127.0.0.1"
            val port = if (uri.port > 0) uri.port else 8080
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.of(InetSocketAddress(host, port)))
                .build()
        }.getOrElse {
            logger.warn("Invalid proxy URL '{}', falling back to direct connection", proxyUrl)
            client
        }
        proxyClient = proxied
        proxyClientUrl = proxyUrl
        return proxied
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val history = mutableListOf<JsonObject>()

    private const val DEFAULT_SYSTEM_PROMPT =
        "You are an expert in-game assistant strictly for Minecraft (vanilla, mods, modpacks, redstone, commands, " +
            "KubeJS, CC:Tweaked/Lua, configs, logs, shaders, and Minecraft FPS/JVM tuning). " +
            "If the user asks about unrelated real-world topics (news, politics, everyday non-Minecraft tasks), " +
            "politely decline in one short sentence and remind them you only help with Minecraft. " +
            "Answer clearly and concisely. Match the user's language. " +
            "Do NOT use Markdown bold (**...** or __...__), because Minecraft's pixel font renders bold poorly. " +
            "When mentioning Minecraft items, blocks, or entities, write their exact `namespace:id` in backticks directly in the sentence (do not repeat the name in parentheses); the client renders it as a localized clickable link. " +
            "Only use IDs you are confident exist in Minecraft 1.21.1 modpacks; if unsure about a modded item's ID, refer to it by its display name instead of inventing an ID. " +
            "For complex builds, farms, or mod mechanics, you may include 1-2 clickable Markdown links [Label](https://...) to official wikis or YouTube search results (never invent unverified deep URLs). " +
            "Use plain text, short bullet lists (- ), and inline backticks for IDs/commands."

    private const val CLARIFY_INSTRUCTIONS =
        "\n- If the user's question is already specific or has clear in-game context, answer directly without asking. " +
            "If the question is broad or ambiguous (e.g. 'how to make power', 'help with bees', 'what is wrong'), " +
            "ask 1 short clarifying question first and end your message with a single line in this exact format: " +
            "[options: Short option 1 | Short option 2 | Short option 3] (2-4 short options, max 32 chars each, in the user's language)."

    data class ConnectionResult(
        val ok: Boolean,
        val message: String,
    )

    sealed class ModelListResult {
        data class Success(val entries: List<JsonObject>) : ModelListResult() {
            val models: List<String>
                get() = entries.mapNotNull { e -> e.get("id")?.takeIf { !it.isJsonNull }?.asString }
        }
        data class Error(val message: String) : ModelListResult()
    }

    private enum class ChatTestKind { OK, INVALID_KEY, REGION, MODEL, RATE_LIMIT, SERVER, OTHER }

    private data class ChatTestResult(
        val kind: ChatTestKind,
        val message: String,
        val reply: String? = null,
        val latencyMs: Long? = null,
    )

    private fun isConnectionError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("handshake") ||
            lower.contains("ssl") ||
            lower.contains("connect") ||
            lower.contains("connection")
    }

    private fun connectionFailedMessage(cause: String): String {
        val lower = cause.lowercase()
        return when {
            lower.contains("timed out") ->
                Component.translatable("screen.agyassistant.connection.timeout").string
            isConnectionError(cause) ->
                "${Component.translatable("screen.agyassistant.connection.failed", cause.take(100)).string} " +
                    Component.translatable("screen.agyassistant.connection.vpn_hint").string
            else ->
                Component.translatable("screen.agyassistant.connection.failed", cause.take(100)).string
        }
    }

    private fun classifyHttpError(statusCode: Int, bodyPreview: String, model: String): String {
        val lowerBody = bodyPreview.lowercase()
        return when (statusCode) {
            401 -> Component.translatable("screen.agyassistant.connection.invalid_key").string
            403 -> "${Component.translatable("screen.agyassistant.connection.region_blocked").string} " +
                Component.translatable("screen.agyassistant.connection.vpn_hint").string
            404 -> when {
                lowerBody.contains("no endpoints found") && model.contains(":free") ->
                    Component.translatable("screen.agyassistant.connection.free_no_endpoints").string
                lowerBody.contains("model") && model.isNotBlank() ->
                    Component.translatable("screen.agyassistant.connection.model_unavailable", model).string
                else ->
                    Component.translatable("screen.agyassistant.connection.endpoint_not_found").string
            }
            400 -> if (lowerBody.contains("model") && model.isNotBlank()) {
                Component.translatable("screen.agyassistant.connection.model_unavailable", model).string
            } else {
                "HTTP $statusCode${if (bodyPreview.isNotBlank()) ": ${bodyPreview.take(120)}" else ""}"
            }
            429 -> Component.translatable("screen.agyassistant.connection.rate_limited").string
            in 500..599 -> Component.translatable("screen.agyassistant.connection.server_error", statusCode).string
            else -> "HTTP $statusCode${if (bodyPreview.isNotBlank()) ": ${bodyPreview.take(120)}" else ""}"
        }
    }

    fun checkConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
    ): CompletableFuture<ConnectionResult> {
        val url = baseUrl.trim().trimEnd('/')
        val trimmedModel = model.trim()
        if (url.isEmpty() || trimmedModel.isBlank()) {
            return CompletableFuture.completedFuture(
                ConnectionResult(false, Component.translatable("screen.agyassistant.connection.endpoint_not_found").string),
            )
        }

        // Step 1: /models — verifies endpoint, key, region and whether the model is listed
        val modelList = trimmedModel.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val primaryModel = modelList.firstOrNull().orEmpty()
        return fetchModelsRaw(url, apiKey).thenCompose { modelsResult ->
            when (modelsResult) {
                is ModelListResult.Error ->
                    CompletableFuture.completedFuture(ConnectionResult(false, modelsResult.message))
                is ModelListResult.Success -> {
                    val entry = modelsResult.entries.firstOrNull {
                        it.get("id")?.takeIf { e -> !e.isJsonNull }?.asString == primaryModel
                    }
                    val modelFound = modelsResult.entries.isNotEmpty() && entry != null
                    val missingFallbacks = if (modelsResult.entries.isEmpty()) {
                        emptyList()
                    } else {
                        modelList.filter { m ->
                            m != primaryModel && modelsResult.entries.none {
                                it.get("id")?.takeIf { e -> !e.isJsonNull }?.asString == m
                            }
                        }
                    }
                    val visionNote = visionNote(entry)
                    // Step 2: real mini chat request for EVERY model in the chain,
                    // sequentially to avoid tripping free-tier rate limits
                    var chain: CompletableFuture<List<Pair<String, ChatTestResult>>> =
                        CompletableFuture.completedFuture(emptyList())
                    for (m in modelList) {
                        chain = chain.thenCompose { acc ->
                            testChat(url, apiKey, m).thenApply { acc + (m to it) }
                        }
                    }
                    chain.thenApply { results ->
                        composeVerdict(modelFound, visionNote, results, primaryModel, missingFallbacks)
                    }
                }
            }
        }
    }

    private fun composeVerdict(
        modelFound: Boolean,
        visionNote: String?,
        results: List<Pair<String, ChatTestResult>>,
        model: String,
        missingFallbacks: List<String> = emptyList(),
    ): ConnectionResult {
        if (results.isEmpty()) {
            return ConnectionResult(
                false,
                Component.translatable("screen.agyassistant.connection.endpoint_not_found").string,
            )
        }
        val primaryChat = results.first().second
        val fallbacks = results.drop(1)
        val failedFallbacks = fallbacks.filter { it.second.kind != ChatTestKind.OK }
        val okFallbacks = fallbacks.size - failedFallbacks.size

        // Primary answers — the chain works end-to-end
        if (primaryChat.kind == ChatTestKind.OK) {
            val sb = StringBuilder(
                Component.translatable("screen.agyassistant.connection.test_ok", primaryChat.latencyMs ?: 0).string,
            )
            if (!modelFound) {
                sb.append(" ").append(Component.translatable("screen.agyassistant.connection.model_unlisted").string)
            }
            if (!primaryChat.reply.isNullOrBlank()) {
                sb.append(" ")
                    .append(Component.translatable("screen.agyassistant.connection.test_reply", primaryChat.reply.take(40)).string)
            }
            if (visionNote != null) {
                sb.append(" ").append(visionNote)
            }
            if (fallbacks.isNotEmpty()) {
                if (failedFallbacks.isEmpty()) {
                    sb.append(" ").append(
                        Component.translatable("screen.agyassistant.connection.fallbacks_ok", okFallbacks, fallbacks.size).string,
                    )
                } else {
                    sb.append(" ").append(
                        Component.translatable("screen.agyassistant.connection.fallbacks_partial", okFallbacks, fallbacks.size).string,
                    )
                    for ((m, r) in failedFallbacks) {
                        sb.append(" ").append(
                            Component.translatable("screen.agyassistant.connection.fallback_failed_entry", m, r.message.take(90)).string,
                        )
                    }
                }
            }
            if (missingFallbacks.isNotEmpty()) {
                sb.append(" ").append(
                    Component.translatable(
                        "screen.agyassistant.connection.fallback_missing",
                        missingFallbacks.joinToString(", "),
                    ).string,
                )
            }
            return ConnectionResult(true, sb.toString().trim())
        }

        // Primary failed — but a working fallback still guarantees an answer
        val workingFallback = fallbacks.firstOrNull { it.second.kind == ChatTestKind.OK }
        if (workingFallback != null) {
            val sb = StringBuilder(
                Component.translatable("screen.agyassistant.connection.primary_down_fallback_ok", workingFallback.first).string,
            )
            sb.append(" ").append(
                Component.translatable("screen.agyassistant.connection.primary_fail_reason", primaryChat.message.take(90)).string,
            )
            val otherFallbacks = fallbacks.filter { it != workingFallback }
            if (otherFallbacks.isNotEmpty() && otherFallbacks.any { it.second.kind != ChatTestKind.OK }) {
                sb.append(" ").append(
                    Component.translatable(
                        "screen.agyassistant.connection.fallbacks_partial",
                        otherFallbacks.count { it.second.kind == ChatTestKind.OK },
                        otherFallbacks.size,
                    ).string,
                )
            }
            if (missingFallbacks.isNotEmpty()) {
                sb.append(" ").append(
                    Component.translatable(
                        "screen.agyassistant.connection.fallback_missing",
                        missingFallbacks.joinToString(", "),
                    ).string,
                )
            }
            return ConnectionResult(true, sb.toString().trim())
        }

        // Nothing answers
        var msg = primaryChat.message
        when (primaryChat.kind) {
            ChatTestKind.MODEL -> {
                if (!modelFound) {
                    msg = "${Component.translatable("screen.agyassistant.connection.model_not_found", model).string} " +
                        Component.translatable("screen.agyassistant.connection.pick_hint").string
                } else {
                    msg = "$msg ${Component.translatable("screen.agyassistant.connection.pick_hint").string}"
                }
            }
            else -> Unit
        }
        if (fallbacks.isNotEmpty()) {
            msg = "$msg ${Component.translatable("screen.agyassistant.connection.fallbacks_all_failed").string}"
        }
        if (missingFallbacks.isNotEmpty()) {
            msg = "$msg ${
                Component.translatable("screen.agyassistant.connection.fallback_missing", missingFallbacks.joinToString(", ")).string
            }"
        }
        return ConnectionResult(false, msg.trim())
    }

    private fun testChat(url: String, apiKey: String, model: String): CompletableFuture<ChatTestResult> {
        val startMs = System.currentTimeMillis()
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "Reply with exactly one word: pong")
                })
            })
            addProperty("max_tokens", 16)
            addProperty("stream", false)
        }

        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$url/chat/completions"))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (url.contains("openrouter.ai")) {
            reqBuilder.header("HTTP-Referer", "https://github.com/pixiemays/agy-assistant-mc")
            reqBuilder.header("X-Title", "AGY Assistant MC")
        }

        return apiClient().sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply { response ->
                val latency = System.currentTimeMillis() - startMs
                val status = response.statusCode()
                val respBody = response.body()

                if (status == 200) {
                    // 200 already proves auth + model + region + quota work end-to-end
                    val reply = runCatching {
                        JsonParser.parseString(respBody).asJsonObject
                            .getAsJsonArray("choices")[0].asJsonObject
                            .getAsJsonObject("message").get("content")?.asString
                    }.getOrNull()
                    ChatTestResult(ChatTestKind.OK, "", reply, latency)
                } else {
                    val lowerBody = respBody.lowercase()
                    when (status) {
                        401 -> ChatTestResult(ChatTestKind.INVALID_KEY, classifyHttpError(401, respBody, model), null, latency)
                        403 -> ChatTestResult(ChatTestKind.REGION, classifyHttpError(403, respBody, model), null, latency)
                        404 -> ChatTestResult(ChatTestKind.MODEL, classifyHttpError(404, respBody, model), null, latency)
                        400 -> {
                            val kind = if (lowerBody.contains("model")) ChatTestKind.MODEL else ChatTestKind.OTHER
                            ChatTestResult(kind, classifyHttpError(400, respBody, model), null, latency)
                        }
                        429 -> {
                            val retryAfter = response.headers().firstValue("retry-after").orElse(null)
                            var msg = classifyHttpError(429, respBody, model)
                            if (retryAfter != null) {
                                msg = "$msg ${Component.translatable("screen.agyassistant.connection.retry_after", retryAfter).string}"
                            }
                            ChatTestResult(ChatTestKind.RATE_LIMIT, msg, null, latency)
                        }
                        in 500..599 -> ChatTestResult(ChatTestKind.SERVER, classifyHttpError(status, respBody, model), null, latency)
                        else -> ChatTestResult(ChatTestKind.OTHER, classifyHttpError(status, respBody, model), null, latency)
                    }
                }
            }
            .exceptionally { ex ->
                val cause = ex.cause ?: ex
                val msg = cause.message ?: cause.javaClass.simpleName
                ChatTestResult(ChatTestKind.OTHER, connectionFailedMessage(msg))
            }
    }

    private fun visionNote(entry: JsonObject?): String? {
        if (entry == null) return null
        // OpenRouter: architecture.input_modalities
        runCatching {
            val mods = entry.getAsJsonObject("architecture")?.getAsJsonArray("input_modalities")
            if (mods != null) {
                val hasImage = mods.any { !it.isJsonNull && it.asString == "image" }
                return Component.translatable(
                    if (hasImage) "screen.agyassistant.connection.vision_yes"
                    else "screen.agyassistant.connection.vision_no",
                ).string
            }
        }
        // Google AI Studio (OpenAI-compat): supported_generation_methods
        runCatching {
            val methods = entry.getAsJsonArray("supported_generation_methods") ?: return@runCatching
            val id = entry.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val hasGenerate = methods.any { !it.isJsonNull && it.asString == "generateContent" }
            if (hasGenerate && id.startsWith("gemini") && !id.contains("embedding") && !id.contains("aqa")) {
                return Component.translatable("screen.agyassistant.connection.vision_yes").string
            }
        }
        return null
    }

    fun fetchModels(
        baseUrl: String,
        apiKey: String,
    ): CompletableFuture<ModelListResult> = fetchModelsRaw(baseUrl.trim().trimEnd('/'), apiKey)

    private fun fetchModelsRaw(
        url: String,
        apiKey: String,
    ): CompletableFuture<ModelListResult> {
        if (url.isEmpty()) {
            return CompletableFuture.completedFuture(
                ModelListResult.Error(Component.translatable("screen.agyassistant.connection.endpoint_not_found").string),
            )
        }

        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$url/models"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (url.contains("openrouter.ai")) {
            reqBuilder.header("HTTP-Referer", "https://github.com/pixiemays/agy-assistant-mc")
            reqBuilder.header("X-Title", "AGY Assistant MC")
        }

        return apiClient().sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply { response ->
                val body = response.body()
                when (response.statusCode()) {
                    200 -> {
                        val entries = runCatching {
                            JsonParser.parseString(body).asJsonObject
                                .getAsJsonArray("data")
                                ?.mapNotNull { el -> el.takeIf { !el.isJsonNull }?.asJsonObject }
                                ?.sortedBy { it.get("id")?.takeIf { e -> !e.isJsonNull }?.asString.orEmpty() }
                                ?: emptyList()
                        }.getOrDefault(emptyList())
                        ModelListResult.Success(entries)
                    }
                    else -> ModelListResult.Error(classifyHttpError(response.statusCode(), body, ""))
                }
            }
            .exceptionally { ex ->
                val cause = ex.cause ?: ex
                val msg = cause.message ?: cause.javaClass.simpleName
                ModelListResult.Error(connectionFailedMessage(msg))
            }
    }

    @Synchronized
    fun resetConversation() {
        history.clear()
    }

    fun isConfigured(): Boolean {
        val cfg = ModConfig.data
        if (cfg.openaiBaseUrl.isBlank() || cfg.openaiModel.isBlank()) return false
        // Local endpoints (e.g. Ollama) do not need a key
        val url = cfg.openaiBaseUrl.trim().lowercase()
        val localService = url.startsWith("http://127.0.0.1") ||
            url.startsWith("http://localhost") ||
            url.startsWith("http://[::1]")
        return localService || cfg.openaiApiKey.isNotBlank()
    }

    fun streamChat(
        message: String,
        context: AttachedContext,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): CompletableFuture<Void> {
        val cfg = ModConfig.data
        val baseUrl = cfg.openaiBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty() || cfg.openaiModel.isBlank()) {
            onError("OpenAI Base URL and Model must be configured in Config.")
            return CompletableFuture.completedFuture(null)
        }

        val userText = buildUserText(message, context)
        val userMsg = JsonObject().apply {
            addProperty("role", "user")
            val shot = if (cfg.privacyBlockScreenshots) null else context.screenshot
            if (shot != null) {
                val parts = JsonArray()
                parts.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", userText)
                })
                parts.add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:${shot.mimeType};base64,${shot.base64}")
                    })
                })
                add("content", parts)
            } else {
                addProperty("content", userText)
            }
        }

        val messagesArray = JsonArray()
        val sysPrompt = buildString {
            append(DEFAULT_SYSTEM_PROMPT)
            if (cfg.clarifyVagueQuestions) {
                append(CLARIFY_INSTRUCTIONS)
            }
            if (cfg.systemPromptExtra.isNotBlank()) {
                append("\n\n")
                append(cfg.systemPromptExtra.trim())
            }
        }
        messagesArray.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", sysPrompt)
        })

        synchronized(this) {
            for (m in history) {
                messagesArray.add(m)
            }
            messagesArray.add(userMsg)
        }

        val primaryList = cfg.openaiModel.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val fallbackList = cfg.openaiFallbackModels.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val modelList = (primaryList + fallbackList).distinct()
        val primaryModel = modelList.firstOrNull().orEmpty()
        val body = JsonObject().apply {
            if (baseUrl.contains("openrouter.ai") && modelList.size > 1) {
                // OpenRouter-native fallback routing: primary first, then fallbacks
                add("models", JsonArray().apply { for (m in modelList) add(m) })
            } else {
                addProperty("model", primaryModel)
            }
            add("messages", messagesArray)
            addProperty("stream", true)
        }

        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofMinutes(3))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))

        if (cfg.openaiApiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${cfg.openaiApiKey.trim()}")
        }
        if (baseUrl.contains("openrouter.ai")) {
            reqBuilder.header("HTTP-Referer", "https://github.com/pixiemays/agy-assistant-mc")
            reqBuilder.header("X-Title", "AGY Assistant MC")
        }

        val fullReply = StringBuilder()

        return apiClient().sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                if (response.statusCode() != 200) {
                    val preview = runCatching {
                        response.body().use { it.limit(5).toList().joinToString(" ") }
                    }.getOrDefault("").take(220)
                    onError(classifyHttpError(response.statusCode(), preview, cfg.openaiModel.trim()))
                    return@thenAccept
                }

                response.body().use { lines ->
                    val iter = lines.iterator()
                    while (iter.hasNext()) {
                        val line = iter.next().trim()
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        try {
                            val obj = JsonParser.parseString(payload).asJsonObject
                            val choices = obj.getAsJsonArray("choices") ?: continue
                            if (choices.size() == 0) continue
                            val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: continue
                            val contentEl = delta.get("content")
                            if (contentEl != null && !contentEl.isJsonNull) {
                                val token = contentEl.asString
                                if (token.isNotEmpty()) {
                                    fullReply.append(token)
                                    onToken(token)
                                }
                            }
                        } catch (e: Exception) {
                            logger.debug("Skipping non-delta SSE line: {}", payload)
                        }
                    }
                }

                synchronized(this) {
                    // Store lightweight text-only user message in history to avoid resending old base64 images
                    history.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userText)
                    })
                    if (fullReply.isNotEmpty()) {
                        history.add(JsonObject().apply {
                            addProperty("role", "assistant")
                            addProperty("content", fullReply.toString())
                        })
                    }
                    while (history.size > 20) {
                        history.removeAt(0)
                    }
                }
                onDone()
            }
            .exceptionally { ex ->
                val cause = ex.cause ?: ex
                val msg = cause.message ?: cause.javaClass.simpleName
                logger.warn("OpenAI-compatible request failed: {}", msg)
                onError(connectionFailedMessage(msg))
                null
            }
    }

    private fun buildUserText(message: String, context: AttachedContext): String {
        val parts = mutableListOf<String>()
        context.target?.let { t ->
            val safe = if (ModConfig.data.privacyStripCoordinates) t.copy(pos = null) else t
            parts.add(formatTarget(safe))
        }
        context.item?.let { parts.add(formatItem(it)) }
        if (parts.isEmpty()) return message
        return "## In-Game Context\n${parts.joinToString("\n\n")}\n\n## Question\n$message"
    }

    private fun formatTarget(t: TargetInfo): String = buildString {
        appendLine("Targeted ${t.kind}: `${t.id}` (${t.displayName ?: t.id})")
        t.pos?.let { appendLine("Pos: $it") }
        if (t.blockState.isNotEmpty()) {
            appendLine("BlockState: ${t.blockState.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
        t.entityHealth?.let { hp ->
            val max = t.entityMaxHealth?.let { "/$it" } ?: ""
            appendLine("Health: $hp$max")
        }
        if (t.extraTags.isNotEmpty()) {
            appendLine("Info: ${t.extraTags.joinToString(", ")}")
        }
    }.trim()

    private fun formatItem(i: ItemInfo): String = buildString {
        appendLine("Item (${i.source}): `${i.id}` x${i.count} (${i.displayName ?: i.id})")
        if (i.tooltip.isNotEmpty()) {
            appendLine("Tooltip: ${i.tooltip.joinToString(" | ")}")
        }
        if (i.components.isNotEmpty()) {
            appendLine("Components: ${i.components}")
        }
    }.trim()
}
