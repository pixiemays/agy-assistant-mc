package com.agyassistant.ui

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.network.BridgeHttpClient
import com.agyassistant.network.OpenAiCompatClient
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
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

data class QueuedPrompt(
    val text: String,
    val context: AttachedContext,
)

object ChatHistoryStore {
    val messages: MutableList<ChatMessage> = mutableListOf()
    val inputHistory: MutableList<String> = mutableListOf()
    val queuedPrompts: MutableList<QueuedPrompt> = mutableListOf()

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

    private fun notifyOutsideChat(error: Boolean) {
        if (activeScreen != null) return
        AssistantNotifications.show(if (error) AssistantNotifications.Kind.ERROR else AssistantNotifications.Kind.ANSWER)
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, if (error) 0.6f else 1.4f),
        )
    }

    fun add(role: ChatRole, text: String, attachments: AttachedContext? = null): ChatMessage {
        val msg = ChatMessage(role, StringBuilder(text), attachments)
        messages.add(msg)
        return msg
    }

    fun clear() {
        cancelActiveRequest()
        queuedPrompts.clear()
        messages.clear()
        activeAssistantMsg = null
        lastUserPrompt = null
        lastUserContext = null
        activeScreen?.onQueueChanged()
    }

    fun enqueue(text: String, sendContext: AttachedContext) {
        if (inputHistory.lastOrNull() != text) {
            inputHistory.add(text)
            if (inputHistory.size > 50) inputHistory.removeAt(0)
        }
        queuedPrompts.add(QueuedPrompt(text, sendContext))
        activeScreen?.onQueueChanged()
    }

    fun removeQueued(index: Int) {
        if (index !in queuedPrompts.indices) return
        queuedPrompts.removeAt(index)
        activeScreen?.onQueueChanged()
    }

    fun takeQueued(index: Int): QueuedPrompt? {
        if (index !in queuedPrompts.indices) return null
        val item = queuedPrompts.removeAt(index)
        activeScreen?.onQueueChanged()
        return item
    }

    fun sendQueuedNow(index: Int) {
        if (isStreaming || index !in queuedPrompts.indices) return
        val item = queuedPrompts.removeAt(index)
        activeScreen?.onQueueChanged()
        startStreamingRequest(item.text, item.context)
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
                if (!receivedAnyToken) {
                    messages.remove(assistantMsg)
                    val errText = Component.translatable("screen.agyassistant.err.request_failed", "Empty response (try Retry/Reset)").string
                    add(ChatRole.ERROR, errText)
                }
                activeScreen?.onStreamFinished()
                notifyOutsideChat(error = !receivedAnyToken)
                if (queuedPrompts.isNotEmpty()) {
                    val next = queuedPrompts.removeAt(0)
                    activeScreen?.onQueueChanged()
                    startStreamingRequest(next.text, next.context)
                }
            }
        }
        val onErrorCb: (String) -> Unit = { err ->
            Minecraft.getInstance().execute {
                if (requestSeq.get() != reqId) return@execute
                isStreaming = false
                activeFuture = null
                activeAssistantMsg = null
                if (!receivedAnyToken) {
                    messages.remove(assistantMsg)
                }
                val errText = Component.translatable("screen.agyassistant.err.request_failed", err).string
                add(ChatRole.ERROR, errText)
                activeScreen?.onStreamFinished()
                notifyOutsideChat(error = true)
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
