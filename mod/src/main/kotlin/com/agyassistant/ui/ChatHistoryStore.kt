package com.agyassistant.ui

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.network.BridgeHttpClient
import com.agyassistant.network.OpenAiCompatClient
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
    ERROR,
}

data class ChatMessage(
    val role: ChatRole,
    val content: StringBuilder,
    val attachments: AttachedContext? = null,
)

object ChatHistoryStore {
    val messages: MutableList<ChatMessage> = mutableListOf()
    val inputHistory: MutableList<String> = mutableListOf()

    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile
    var receivedAnyToken: Boolean = false
        private set

    @Volatile
    var activeAssistantMsg: ChatMessage? = null
        private set

    @Volatile
    var activeScreen: AssistantScreen? = null

    private var activeFuture: CompletableFuture<Void>? = null
    private val requestSeq = AtomicLong(0)

    private var lastUserPrompt: String? = null
    private var lastUserContext: AttachedContext? = null

    fun add(role: ChatRole, text: String, attachments: AttachedContext? = null): ChatMessage {
        val msg = ChatMessage(role, StringBuilder(text), attachments)
        messages.add(msg)
        return msg
    }

    fun clear() {
        cancelActiveRequest()
        messages.clear()
        activeAssistantMsg = null
        lastUserPrompt = null
        lastUserContext = null
    }

    fun cancelActiveRequest() {
        if (!isStreaming) return
        requestSeq.incrementAndGet()
        activeFuture?.cancel(true)
        activeFuture = null
        isStreaming = false
        val msg = activeAssistantMsg
        if (msg != null && !receivedAnyToken) {
            msg.content.clear()
            msg.content.append(Component.translatable("screen.agyassistant.msg.stopped").string)
        }
        activeAssistantMsg = null
        activeScreen?.onStreamFinished()
    }

    fun canRegenerate(): Boolean = !isStreaming && lastUserPrompt != null

    fun regenerateLast() {
        if (isStreaming) return
        val prompt = lastUserPrompt ?: return
        val ctx = lastUserContext ?: AttachedContext()

        // Remove last assistant / error messages from the previous attempt
        while (messages.isNotEmpty() && (messages.last().role == ChatRole.ASSISTANT || messages.last().role == ChatRole.ERROR)) {
            messages.removeAt(messages.size - 1)
        }
        if (messages.isNotEmpty() && messages.last().role == ChatRole.USER) {
            messages.removeAt(messages.size - 1)
        }

        startStreamingRequest(prompt, ctx.copy())
    }

    fun startStreamingRequest(text: String, sendContext: AttachedContext) {
        if (isStreaming) return
        val reqId = requestSeq.incrementAndGet()
        isStreaming = true
        receivedAnyToken = false

        if (inputHistory.lastOrNull() != text) {
            inputHistory.add(text)
            if (inputHistory.size > 50) inputHistory.removeAt(0)
        }
        lastUserPrompt = text
        lastUserContext = sendContext.copy()

        add(
            ChatRole.USER,
            text,
            attachments = if (sendContext.isEmpty()) null else sendContext,
        )
        val thinkingText = Component.translatable("screen.agyassistant.msg.thinking").string + "."
        val assistantMsg = add(ChatRole.ASSISTANT, thinkingText)
        activeAssistantMsg = assistantMsg

        activeScreen?.onStreamStarted()

        val outgoingMessage = sendContext.quote?.let { q ->
            val preview = q.text.take(600)
            "Quoted message (${q.author}):\n> ${preview.replace("\n", "\n> ")}\n\n$text"
        } ?: text

        val onTokenCb: (String) -> Unit = { token ->
            Minecraft.getInstance().execute {
                if (requestSeq.get() != reqId) return@execute
                if (!receivedAnyToken) {
                    assistantMsg.content.clear()
                    receivedAnyToken = true
                }
                assistantMsg.content.append(token)
                activeScreen?.onStreamToken(assistantMsg)
            }
        }
        val onDoneCb: () -> Unit = {
            Minecraft.getInstance().execute {
                if (requestSeq.get() != reqId) return@execute
                isStreaming = false
                activeFuture = null
                activeAssistantMsg = null
                activeScreen?.onStreamFinished()
            }
        }
        val onErrorCb: (String) -> Unit = { err ->
            Minecraft.getInstance().execute {
                if (requestSeq.get() != reqId) return@execute
                isStreaming = false
                activeFuture = null
                activeAssistantMsg = null
                if (!receivedAnyToken) {
                    assistantMsg.content.clear()
                }
                val errText = Component.translatable("screen.agyassistant.err.request_failed", err).string
                add(ChatRole.ERROR, errText)
                activeScreen?.onStreamFinished()
            }
        }

        activeFuture = if (ModConfig.data.provider == "openai_compat") {
            OpenAiCompatClient.streamChat(
                message = outgoingMessage,
                context = sendContext,
                onToken = onTokenCb,
                onDone = onDoneCb,
                onError = onErrorCb,
            )
        } else {
            BridgeHttpClient.streamChat(
                message = outgoingMessage,
                context = sendContext,
                onToken = onTokenCb,
                onDone = onDoneCb,
                onError = onErrorCb,
            )
        }
    }
}
