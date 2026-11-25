package com.agyassistant.ui

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.network.BridgeHttpClient
import com.agyassistant.network.OpenAiCompatClient
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

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

    fun add(role: ChatRole, text: String, attachments: AttachedContext? = null): ChatMessage {
        val msg = ChatMessage(role, StringBuilder(text), attachments)
        messages.add(msg)
        return msg
    }

    fun clear() {
        messages.clear()
        activeAssistantMsg = null
    }

    fun startStreamingRequest(text: String, sendContext: AttachedContext) {
        if (isStreaming) return
        isStreaming = true
        receivedAnyToken = false

        add(
            ChatRole.USER,
            text,
            attachments = if (sendContext.isEmpty()) null else sendContext,
        )
        val thinkingText = Component.translatable("screen.agyassistant.msg.thinking").string + "."
        val assistantMsg = add(ChatRole.ASSISTANT, thinkingText)
        activeAssistantMsg = assistantMsg

        activeScreen?.onStreamStarted()

        val onTokenCb: (String) -> Unit = { token ->
            Minecraft.getInstance().execute {
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
                isStreaming = false
                activeAssistantMsg = null
                activeScreen?.onStreamFinished()
            }
        }
        val onErrorCb: (String) -> Unit = { err ->
            Minecraft.getInstance().execute {
                isStreaming = false
                activeAssistantMsg = null
                if (!receivedAnyToken) {
                    assistantMsg.content.clear()
                }
                val errText = Component.translatable("screen.agyassistant.err.request_failed", err).string
                add(ChatRole.ERROR, errText)
                activeScreen?.onStreamFinished()
            }
        }

        if (ModConfig.data.provider == "openai_compat") {
            OpenAiCompatClient.streamChat(
                message = text,
                context = sendContext,
                onToken = onTokenCb,
                onDone = onDoneCb,
                onError = onErrorCb,
            )
        } else {
            BridgeHttpClient.streamChat(
                message = text,
                context = sendContext,
                onToken = onTokenCb,
                onDone = onDoneCb,
                onError = onErrorCb,
            )
        }
    }
}
