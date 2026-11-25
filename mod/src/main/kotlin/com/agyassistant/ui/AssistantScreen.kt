package com.agyassistant.ui

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.context.ScreenCapture
import com.agyassistant.context.ScreenshotInfo
import com.agyassistant.network.BridgeHttpClient
import com.agyassistant.network.BridgeProcessManager
import com.agyassistant.network.OpenAiCompatClient
import io.wispforest.owo.ui.base.BaseUIModelScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.CheckboxComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture

class AssistantScreen(
    private val context: AttachedContext = AttachedContext(),
    pendingScreenshot: CompletableFuture<ScreenshotInfo?>? = null,
) : BaseUIModelScreen<FlowLayout>(
    FlowLayout::class.java,
    DataSource.asset(ResourceLocation.fromNamespaceAndPath("agyassistant", "assistant_screen")),
) {
    private var statusLabel: LabelComponent? = null
    private var chipsBar: FlowLayout? = null
    private var messagesScroll: ScrollContainer<FlowLayout>? = null
    private var messagesContainer: FlowLayout? = null
    private var promptInput: TextBoxComponent? = null
    private var screenshotCheckbox: CheckboxComponent? = null
    private var sendButton: ButtonComponent? = null

    private var bridgeOnline = false
    private var activeAssistantLabel: ScaledLabelComponent? = null
    private var thinkingTick = 0

    init {
        pendingScreenshot?.thenAccept { info ->
            if (info != null) {
                Minecraft.getInstance().execute {
                    context.screenshot = info
                    rebuildChips()
                }
            }
        }
    }

    override fun isPauseScreen(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun build(rootComponent: FlowLayout) {
        val mainPanel = rootComponent.childById(FlowLayout::class.java, "main-panel")
        mainPanel?.surface(Surface.flat(0xEC0B1120.toInt()).and(Surface.outline(0xFF334155.toInt())))

        statusLabel = rootComponent.childById(LabelComponent::class.java, "status-label")
        chipsBar = rootComponent.childById(FlowLayout::class.java, "chips-bar")
        messagesScroll = rootComponent.childById(ScrollContainer::class.java, "messages-scroll") as? ScrollContainer<FlowLayout>
        messagesContainer = rootComponent.childById(FlowLayout::class.java, "messages-container")
        promptInput = rootComponent.childById(OwoComponent::class.java, "prompt-input") as? TextBoxComponent
        screenshotCheckbox = rootComponent.childById(OwoComponent::class.java, "screenshot-checkbox") as? CheckboxComponent
        sendButton = rootComponent.childById(OwoComponent::class.java, "send-button") as? ButtonComponent

        messagesScroll?.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF1E293B.toInt())))
        messagesScroll?.scrollbar(ScrollContainer.Scrollbar.vanillaFlat())

        val headerBtnRenderer = ButtonComponent.Renderer.flat(0xCC1E293B.toInt(), 0xFF334155.toInt(), 0x881E293B.toInt())
        val sendBtnRenderer = ButtonComponent.Renderer.flat(0xCC2563EB.toInt(), 0xFF3B82F6.toInt(), 0x881E3A8A.toInt())

        promptInput?.setMaxLength(2000)
        screenshotCheckbox?.checked(ModConfig.data.attachScreenshotByDefault || context.screenshot != null)

        (rootComponent.childById(OwoComponent::class.java, "capture-screen-button") as? ButtonComponent)?.apply {
            renderer(headerBtnRenderer)
            onPress { captureScreenshotNow() }
        }

        (rootComponent.childById(OwoComponent::class.java, "reset-button") as? ButtonComponent)?.apply {
            renderer(headerBtnRenderer)
            onPress { resetChat() }
        }

        (rootComponent.childById(OwoComponent::class.java, "settings-button") as? ButtonComponent)?.apply {
            renderer(headerBtnRenderer)
            onPress { Minecraft.getInstance().setScreen(SettingsScreen(this@AssistantScreen)) }
        }

        sendButton?.apply {
            renderer(sendBtnRenderer)
            onPress { sendMessage() }
        }

        if (ChatHistoryStore.messages.isEmpty()) {
            ChatHistoryStore.add(ChatRole.SYSTEM, Component.translatable("screen.agyassistant.msg.welcome").string)
        }

        ChatHistoryStore.activeScreen = this
        sendButton?.active = !ChatHistoryStore.isStreaming

        rebuildChips()
        rebuildMessageFeed()
        refreshBridgeStatus()
    }

    override fun removed() {
        super.removed()
        if (ChatHistoryStore.activeScreen === this) {
            ChatHistoryStore.activeScreen = null
        }
    }

    override fun init() {
        super.init()
        focusPromptInput()
    }

    private fun focusPromptInput() {
        val input = promptInput ?: return
        uiAdapter.rootComponent.focusHandler()?.focus(input as OwoComponent, OwoComponent.FocusSource.MOUSE_CLICK)
        input.isFocused = true
        setFocused(input)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val input = promptInput
        if (input != null && !input.isFocused) {
            focusPromptInput()
        }
        return super.charTyped(codePoint, modifiers)
    }

    override fun tick() {
        super.tick()
        if (ChatHistoryStore.isStreaming && !ChatHistoryStore.receivedAnyToken) {
            thinkingTick++
            if (thinkingTick % 6 == 0) {
                val dots = ".".repeat((thinkingTick / 6) % 3 + 1)
                val base = Component.translatable("screen.agyassistant.msg.thinking").string
                activeAssistantLabel?.text(Component.literal("$base$dots"))
            }
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            val input = promptInput
            if (input != null && input.value.isNotBlank()) {
                sendMessage()
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun refreshBridgeStatus() {
        if (ModConfig.data.provider == "openai_compat") {
            val ok = OpenAiCompatClient.isConfigured()
            bridgeOnline = ok
            updateStatusLabel(connecting = false, online = ok)
            return
        }
        updateStatusLabel(connecting = true, online = false)
        BridgeHttpClient.checkHealth().thenAccept { ok ->
            if (ok) {
                Minecraft.getInstance().execute {
                    bridgeOnline = true
                    updateStatusLabel(connecting = false, online = true)
                }
            } else {
                BridgeProcessManager.startIfNeededAsync().thenAccept { started ->
                    Minecraft.getInstance().execute {
                        bridgeOnline = started
                        updateStatusLabel(connecting = false, online = started)
                    }
                }
            }
        }
    }

    private fun updateStatusLabel(connecting: Boolean, online: Boolean) {
        val label = statusLabel ?: return
        when {
            connecting -> {
                label.text(Component.literal("● ").append(Component.translatable("screen.agyassistant.status.connecting")))
                label.color(Color.ofRgb(0xFBBF24))
            }
            online -> {
                label.text(Component.literal("● ").append(Component.translatable("screen.agyassistant.status.connected")))
                label.color(Color.ofRgb(0x4ADE80))
            }
            else -> {
                label.text(Component.literal("● ").append(Component.translatable("screen.agyassistant.status.disconnected")))
                label.color(Color.ofRgb(0xF87171))
            }
        }
    }

    private fun rebuildChips() {
        val bar = chipsBar ?: return
        bar.clearChildren()
        ContextChipComponent.populateChips(bar, context, removable = true) {
            rebuildChips()
            focusPromptInput()
        }
    }

    private fun captureScreenshotNow() {
        ScreenCapture.captureAsync().thenAccept { info ->
            if (info != null) {
                Minecraft.getInstance().execute {
                    context.screenshot = info
                    screenshotCheckbox?.checked(true)
                    rebuildChips()
                }
            }
        }
    }

    private fun resetChat() {
        if (ChatHistoryStore.isStreaming) return
        ChatHistoryStore.clear()
        ChatHistoryStore.add(ChatRole.SYSTEM, Component.translatable("screen.agyassistant.msg.reset_done").string)
        rebuildMessageFeed()
        if (ModConfig.data.provider == "openai_compat") {
            OpenAiCompatClient.resetConversation()
        } else {
            BridgeHttpClient.resetConversation()
        }
    }

    private fun sendMessage() {
        if (ChatHistoryStore.isStreaming) return
        val input = promptInput ?: return
        val text = input.value.trim()
        if (text.isEmpty()) return

        if (!bridgeOnline) {
            ChatHistoryStore.add(
                ChatRole.ERROR,
                Component.translatable("screen.agyassistant.err.bridge_offline", ModConfig.data.bridgePort).string,
            )
            rebuildMessageFeed()
            refreshBridgeStatus()
            return
        }

        val wantScreenshot = screenshotCheckbox?.selected() == true
        if (wantScreenshot && context.screenshot == null) {
            sendButton?.active = false
            ScreenCapture.captureAsync().thenAccept { shot ->
                Minecraft.getInstance().execute {
                    context.screenshot = shot
                    rebuildChips()
                    dispatchChatRequest(text)
                }
            }
        } else {
            dispatchChatRequest(text)
        }
    }

    private fun dispatchChatRequest(text: String) {
        promptInput?.value = ""

        val sendContext = AttachedContext(
            target = context.target,
            item = context.item,
            screenshot = if (screenshotCheckbox?.selected() == true) context.screenshot else null,
        )
        context.target = null
        context.item = null
        context.screenshot = null
        rebuildChips()

        ChatHistoryStore.startStreamingRequest(text, sendContext)
    }

    internal fun onStreamStarted() {
        sendButton?.active = false
        thinkingTick = 0
        rebuildMessageFeed()
    }

    internal fun onStreamToken(msg: ChatMessage) {
        val label = activeAssistantLabel
        if (label != null) {
            label.color(0xF8FAFC)
            val parsed = QuickOptionsParser.parse(msg.content.toString())
            label.text(MarkdownFormatter.format(parsed.displayText))
            scrollToBottom()
        } else {
            rebuildMessageFeed()
        }
    }

    internal fun onStreamFinished() {
        sendButton?.active = true
        activeAssistantLabel = null
        rebuildMessageFeed()
        focusPromptInput()
    }

    private fun rebuildMessageFeed() {
        rebuildMessageFeedAndReturnLastLabel()
    }

    private fun rebuildMessageFeedAndReturnLastLabel(): ScaledLabelComponent? {
        val container = messagesContainer ?: return null
        container.clearChildren()

        var lastBodyLabel: ScaledLabelComponent? = null
        val totalMsgs = ChatHistoryStore.messages.size
        for ((index, msg) in ChatHistoryStore.messages.withIndex()) {
            val isLastMessage = index == totalMsgs - 1
            val (headerKey, headerColor, bgColor) = when (msg.role) {
                ChatRole.USER -> Triple("screen.agyassistant.msg.you", 0x93C5FD, 0x991E293B.toInt())
                ChatRole.ASSISTANT -> Triple("screen.agyassistant.msg.assistant", 0x86EFAC, 0x88111827.toInt())
                ChatRole.SYSTEM -> Triple("screen.agyassistant.msg.system", 0x94A3B8, 0x551E293B.toInt())
                ChatRole.ERROR -> Triple("screen.agyassistant.msg.system", 0xFCA5A5, 0x99450A0A.toInt())
            }

            val card = Containers.verticalFlow(Sizing.fill(100), Sizing.content())
            card.surface(Surface.flat(bgColor))
            card.padding(Insets.of(4))
            card.gap(2)

            val header = ScaledLabelComponent(
                text = Component.translatable(headerKey),
                scale = 0.78f,
                defaultColor = headerColor,
                shadow = false,
            )

            val isThinkingMsg = (msg === ChatHistoryStore.activeAssistantMsg) &&
                ChatHistoryStore.isStreaming && !ChatHistoryStore.receivedAnyToken

            val parsedReply = if (msg.role == ChatRole.ASSISTANT && !isThinkingMsg) {
                QuickOptionsParser.parse(msg.content.toString())
            } else {
                null
            }

            val bodyComponent = if (parsedReply != null) {
                MarkdownFormatter.format(parsedReply.displayText)
            } else {
                Component.literal(msg.content.toString())
            }
            val body = ScaledLabelComponent(
                text = bodyComponent,
                scale = 0.85f,
                defaultColor = if (isThinkingMsg) 0x94A3B8 else 0xF8FAFC,
                shadow = false,
            )
            body.horizontalSizing(Sizing.fill(100))

            if (msg === ChatHistoryStore.activeAssistantMsg) {
                activeAssistantLabel = body
            }

            card.child(header)
            if (msg.attachments != null && !msg.attachments.isEmpty()) {
                val attachRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
                attachRow.gap(3)
                ContextChipComponent.populateChips(attachRow, msg.attachments, removable = false)
                card.child(attachRow)
            }
            card.child(body)

            if (msg.role == ChatRole.ASSISTANT) {
                val refs = ReferenceExtractor.extract(msg.content.toString())
                if (refs.isNotEmpty()) {
                    val refsRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
                    refsRow.gap(3)
                    refsRow.margins(Insets.top(2))
                    for (ref in refs) {
                        refsRow.child(
                            ReferenceExtractor.createChip(
                                ref = ref,
                                onAttachItem = { item ->
                                    context.item = item
                                    rebuildChips()
                                    focusPromptInput()
                                },
                                onAttachTarget = { target ->
                                    context.target = target
                                    rebuildChips()
                                    focusPromptInput()
                                },
                            )
                        )
                    }
                    card.child(refsRow)
                }

                if (isLastMessage && !ChatHistoryStore.isStreaming && parsedReply != null && parsedReply.options.isNotEmpty()) {
                    val optRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
                    optRow.gap(4)
                    optRow.margins(Insets.top(3))
                    val optRenderer = ButtonComponent.Renderer.flat(0xCC1E3A5F.toInt(), 0xFF2563EB.toInt(), 0x881E293B.toInt())
                    for (option in parsedReply.options) {
                        val optBtn = Components.button(Component.literal(option)) {
                            if (!ChatHistoryStore.isStreaming) {
                                dispatchChatRequest(option)
                            }
                        }.apply {
                            renderer(optRenderer)
                            (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(15))
                        }
                        optRow.child(optBtn as OwoComponent)
                    }
                    card.child(optRow)
                }
            }

            container.child(card)
            lastBodyLabel = body
        }

        scrollToBottom()
        return lastBodyLabel
    }

    private fun scrollToBottom() {
        messagesScroll?.scrollTo(1.0)
    }
}
