package com.agyassistant.network

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.context.ItemInfo
import com.agyassistant.context.TargetInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
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
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val history = mutableListOf<JsonObject>()

    private const val DEFAULT_SYSTEM_PROMPT =
        "You are an expert in-game assistant for Minecraft 1.21.1 (NeoForge) and modded Minecraft. " +
            "Answer clearly and concisely. Match the user's language. " +
            "Do NOT use Markdown bold (**...** or __...__), because Minecraft's pixel font renders bold poorly. " +
            "Use plain text, short bullet lists (- ), and inline backticks for item/block IDs."

    @Synchronized
    fun resetConversation() {
        history.clear()
    }

    fun isConfigured(): Boolean {
        val cfg = ModConfig.data
        return cfg.openaiBaseUrl.isNotBlank() && cfg.openaiModel.isNotBlank()
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
            val shot = context.screenshot
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
        val sysPrompt = if (cfg.systemPromptExtra.isNotBlank()) {
            "$DEFAULT_SYSTEM_PROMPT\n\n${cfg.systemPromptExtra.trim()}"
        } else {
            DEFAULT_SYSTEM_PROMPT
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

        val body = JsonObject().apply {
            addProperty("model", cfg.openaiModel.trim())
            add("messages", messagesArray)
            addProperty("stream", true)
        }

        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofMinutes(3))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))

        if (cfg.openaiApiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${cfg.openaiApiKey.trim()}")
        }
        if (baseUrl.contains("openrouter.ai")) {
            reqBuilder.header("HTTP-Referer", "https://github.com/agy-assistant-mc")
            reqBuilder.header("X-Title", "AGY Assistant MC")
        }

        val fullReply = StringBuilder()

        return client.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                if (response.statusCode() != 200) {
                    val preview = runCatching {
                        response.body().use { it.limit(5).toList().joinToString(" ") }
                    }.getOrDefault("").take(220)
                    onError("HTTP ${response.statusCode()}${if (preview.isNotBlank()) ": $preview" else ""}")
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
                val msg = ex.cause?.message ?: ex.message ?: "Request failed"
                logger.warn("OpenAI-compatible request failed: {}", msg)
                onError(msg)
                null
            }
    }

    private fun buildUserText(message: String, context: AttachedContext): String {
        val parts = mutableListOf<String>()
        context.target?.let { parts.add(formatTarget(it)) }
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
