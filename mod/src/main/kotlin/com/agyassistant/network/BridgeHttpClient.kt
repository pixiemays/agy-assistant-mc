package com.agyassistant.network

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.context.ItemInfo
import com.agyassistant.context.TargetInfo
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

private data class ChatPayload(
    val message: String,
    @SerializedName("target_data") val targetData: TargetInfo? = null,
    @SerializedName("item_data") val itemData: ItemInfo? = null,
    @SerializedName("screenshot_base64") val screenshotBase64: String? = null,
    @SerializedName("screenshot_mime") val screenshotMime: String = "image/jpeg",
    @SerializedName("game_dir") val gameDir: String? = null,
    @SerializedName("allow_game_dir_read") val allowGameDirRead: Boolean = false,
    @SerializedName("system_prompt_extra") val systemPromptExtra: String? = null,
    val model: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    val provider: String = "bridge",
    @SerializedName("clarify_vague") val clarifyVague: Boolean = true,
)

object BridgeHttpClient {
    private val logger = LoggerFactory.getLogger("agyassistant.http")
    private val gson = Gson()
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private fun baseUrl(): String {
        val cfg = ModConfig.data
        return "http://${cfg.bridgeHost}:${cfg.bridgePort}"
    }

    fun checkHealth(): CompletableFuture<Boolean> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/health"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()

        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenApply { it.statusCode() == 200 }
            .exceptionally { false }
    }

    fun resetConversation(): CompletableFuture<Boolean> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/reset"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenApply { it.statusCode() == 200 }
            .exceptionally { false }
    }

    fun streamChat(
        message: String,
        context: AttachedContext,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): CompletableFuture<Void> {
        val cfg = ModConfig.data
        val gameDir = if (cfg.allowGameDirRead) {
            Minecraft.getInstance().gameDirectory.absolutePath
        } else {
            null
        }

        // Privacy: never send world coordinates or screenshots if the player disabled them
        val safeTarget = context.target?.let {
            if (cfg.privacyStripCoordinates) it.copy(pos = null) else it
        }
        val safeShot = if (cfg.privacyBlockScreenshots) null else context.screenshot

        val payload = ChatPayload(
            message = message,
            targetData = safeTarget,
            itemData = context.item,
            screenshotBase64 = safeShot?.base64,
            screenshotMime = safeShot?.mimeType ?: "image/jpeg",
            gameDir = gameDir,
            allowGameDirRead = cfg.allowGameDirRead,
            systemPromptExtra = cfg.systemPromptExtra.ifBlank { null },
            model = cfg.model.ifBlank { null },
            apiKey = cfg.apiKey.ifBlank { null },
            provider = cfg.provider,
            clarifyVague = cfg.clarifyVagueQuestions,
        )

        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl()}/chat"))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build()

        return client.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                if (response.statusCode() != 200) {
                    val bodyPreview = runCatching {
                        response.body().use { it.limit(5).toList().joinToString(" ") }
                    }.getOrDefault("").take(200)
                    onError("HTTP ${response.statusCode()}${if (bodyPreview.isNotBlank()) ": $bodyPreview" else ""}")
                    return@thenAccept
                }
                var finished = false
                response.body().use { lines ->
                    val iterator = lines.iterator()
                    while (iterator.hasNext()) {
                        val line = iterator.next()
                        if (!line.startsWith("data:")) continue
                        val jsonStr = line.removePrefix("data:").trim()
                        if (jsonStr.isEmpty()) continue
                        try {
                            val obj = JsonParser.parseString(jsonStr).asJsonObject
                            when (obj.get("type")?.asString) {
                                "token" -> {
                                    val content = obj.get("content")?.asString.orEmpty()
                                    if (content.isNotEmpty()) onToken(content)
                                }
                                "done" -> {
                                    finished = true
                                    onDone()
                                }
                                "error" -> {
                                    finished = true
                                    onError(obj.get("message")?.asString ?: "Unknown bridge error")
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("Malformed SSE chunk: {}", jsonStr, e)
                        }
                    }
                }
                if (!finished) {
                    onDone()
                }
            }
            .exceptionally { ex ->
                val msg = ex.cause?.message ?: ex.message ?: "Connection failed"
                logger.warn("Bridge chat request failed: {}", msg)
                onError(msg)
                null
            }
    }
}
