package com.agyassistant.ui

import com.agyassistant.config.ModConfig
import com.agyassistant.context.AttachedContext
import com.agyassistant.context.QuoteInfo
import com.agyassistant.context.ScreenCapture
import com.agyassistant.context.ScreenshotInfo
import com.agyassistant.network.BridgeHttpClient
import com.agyassistant.network.BridgeProcessManager
import com.agyassistant.network.OpenAiCompatClient
import io.wispforest.owo.ui.base.BaseUIModelScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture

class AssistantScreen(
    private val context: AttachedContext = AttachedContext(),
    pendingScreenshot: CompletableFuture<ScreenshotInfo?>? = null,
) : BaseUIModelScreen<FlowLayout>(
    FlowLayout::class.java,
    DataSource.asset(ResourceLocation.fromNamespaceAndPath("agyassistant", "assistant_screen")),
) {
    private var titleLabel: LabelComponent? = null
    private var statusLabel: LabelComponent? = null
    private var chipsBar: FlowLayout? = null
    private var queueBar: FlowLayout? = null
    private var messagesScroll: ScrollContainer<FlowLayout>? = null
    private var messagesContainer: FlowLayout? = null
    private var promptInputWrap: FlowLayout? = null
    private var promptInput: TextBoxComponent? = null
    private var captureScreenBtn: FlowLayout? = null
    private var sendButton: ButtonComponent? = null
    private var stopSlot: FlowLayout? = null

    private var bridgeOnline = false
    private var activeAssistantBody: RichMessageComponent? = null
    private var thinkingTick = 0
    private var historyCursor = -1
    private var pendingScrollToBottom = false
    private var userScrolledUp = false
    private var pendingStreamMsg: ChatMessage? = null
    private var lastStreamRenderMs: Long = 0L
    private var pendingCleanCapture: CompletableFuture<ScreenshotInfo?>? = null
    private var showingMiniStop = false

    private val sendBtnRenderer = ButtonComponent.Renderer.flat(0xCC2563EB.toInt(), 0xFF3B82F6.toInt(), 0x881E3A8A.toInt())
    private val queueBtnRenderer = ButtonComponent.Renderer.flat(0xCC0284C7.toInt(), 0xFF0EA5E9.toInt(), 0x880369A1.toInt())
    private val stopBtnRenderer = ButtonComponent.Renderer.flat(0xCCDC2626.toInt(), 0xFFEF4444.toInt(), 0x887F1D1D.toInt())

    private val aiAvatarStack: ItemStack = ItemStack(Items.COMMAND_BLOCK)
    private val systemAvatarStack: ItemStack = ItemStack(Items.COMPARATOR)
    private val errorAvatarStack: ItemStack = ItemStack(Items.BARRIER)

    private fun activeAiDisplayName(): String = when (ModConfig.data.provider) {
        "opencode" -> "OpenCode"
        "openai_compat" -> {
            val raw = ModConfig.data.openaiModel.trim()
            val first = raw.split(',').firstOrNull()?.trim().orEmpty().ifBlank { raw }
            if (first.equals("openrouter/free", ignoreCase = true)) return "OpenRouter Free"
            val noVendor = first.substringAfter('/').ifBlank { first }
            // Strip only OpenRouter's ":free" suffix; keep Ollama tags like "qwen3:8b"
            val name = if (noVendor.endsWith(":free")) noVendor.removeSuffix(":free") else noVendor
            name.ifBlank { "AI" }
        }
        else -> "Antigravity"
    }

    private fun activeModelId(): String? =
        if (ModConfig.data.provider == "openai_compat") ModConfig.data.openaiModel.trim().ifBlank { null } else null

    private fun isKeyConfigured(): Boolean = when (ModConfig.data.provider) {
        "openai_compat" -> ModConfig.data.openaiApiKey.isNotBlank()
        else -> true // bridge / opencode do not require a user-provided API key
    }
    private val playerAvatarStack: ItemStack by lazy {
        val head = ItemStack(Items.PLAYER_HEAD)
        Minecraft.getInstance().player?.let { p ->
            runCatching { head.set(DataComponents.PROFILE, ResolvableProfile(p.gameProfile)) }
        }
        head
    }

    init {
        pendingScreenshot?.thenAccept { info ->
            if (info != null && !ModConfig.data.privacyBlockScreenshots) {
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

        titleLabel = rootComponent.childById(LabelComponent::class.java, "title-label")
        titleLabel?.apply {
            text(Component.literal(activeAiDisplayName()))
            activeModelId()?.let { tooltip(Component.literal(it)) }
        }

        statusLabel = rootComponent.childById(LabelComponent::class.java, "status-label")
        chipsBar = rootComponent.childById(FlowLayout::class.java, "chips-bar")
        queueBar = rootComponent.childById(FlowLayout::class.java, "queue-bar")
        messagesScroll = rootComponent.childById(ScrollContainer::class.java, "messages-scroll") as? ScrollContainer<FlowLayout>
        messagesContainer = rootComponent.childById(FlowLayout::class.java, "messages-container")
        promptInputWrap = rootComponent.childById(FlowLayout::class.java, "prompt-input-wrap")
        promptInput = rootComponent.childById(OwoComponent::class.java, "prompt-input") as? TextBoxComponent
        captureScreenBtn = rootComponent.childById(FlowLayout::class.java, "capture-screen-button")
        sendButton = rootComponent.childById(OwoComponent::class.java, "send-button") as? ButtonComponent
        stopSlot = rootComponent.childById(FlowLayout::class.java, "stop-slot")

        ReferenceExtractor.warmUpNameIndexAsync()

        messagesScroll?.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF1E293B.toInt())))
        messagesScroll?.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofArgb(0x8864748B.toInt())))
        messagesScroll?.scrollbarThiccness(4)
        messagesContainer?.padding(Insets.right(4))

        val headerBtnRenderer = ButtonComponent.Renderer.flat(0xCC1E293B.toInt(), 0xFF334155.toInt(), 0x881E293B.toInt())

        promptInputWrap?.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF334155.toInt())))
        promptInput?.apply {
            setMaxLength(2000)
            setBordered(false)
            setTextColor(0xF8FAFC)
            setHint(
                Component.translatable("screen.agyassistant.input.placeholder")
                    .withStyle(Style.EMPTY.withColor(0x64748B))
            )
            onChanged().subscribe { syncSendButtonState() }
            (this as OwoComponent).focusGained().subscribe {
                promptInputWrap?.surface(Surface.flat(0xEE0F172A.toInt()).and(Surface.outline(0xFF3B82F6.toInt())))
            }
            (this as OwoComponent).focusLost().subscribe {
                promptInputWrap?.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF334155.toInt())))
            }
        }

        captureScreenBtn?.apply {
            clearChildren()
            val camTex = ResourceLocation.fromNamespaceAndPath("agyassistant", "textures/gui/camera.png")
            val camIcon = Components.texture(camTex, 0, 0, 16, 16, 16, 16)
            camIcon.sizing(Sizing.fixed(11), Sizing.fixed(11))
            child(camIcon)
            cursorStyle(CursorStyle.HAND)
            tooltip(Component.translatable("screen.agyassistant.btn.capture_screen_hint"))
            mouseEnter().subscribe { syncCaptureButtonStyle(hovered = true) }
            mouseLeave().subscribe { syncCaptureButtonStyle(hovered = false) }
            mouseDown().subscribe { _, _, button ->
                if (button == 0) {
                    captureScreenshotNow()
                    focusPromptInput()
                    true
                } else false
            }
            syncCaptureButtonStyle(hovered = false)
        }

        (rootComponent.childById(OwoComponent::class.java, "help-button") as? ButtonComponent)?.apply {
            renderer(headerBtnRenderer)
            (this as OwoComponent).tooltip(Component.translatable("screen.agyassistant.btn.help_hint"))
            onPress { Minecraft.getInstance().setScreen(GuideScreen(this@AssistantScreen)) }
        }

        (rootComponent.childById(OwoComponent::class.java, "reset-button") as? ButtonComponent)?.apply {
            renderer(headerBtnRenderer)
            onPress { confirmResetChat() }
        }

        (rootComponent.childById(OwoComponent::class.java, "settings-button") as? ButtonComponent)?.apply {
            renderer(headerBtnRenderer)
            onPress { Minecraft.getInstance().setScreen(SettingsScreen(this@AssistantScreen)) }
        }

        sendButton?.apply {
            onPress {
                if (ChatHistoryStore.isStreaming && promptInput?.value?.isNotBlank() != true) {
                    ChatHistoryStore.cancelActiveRequest()
                } else {
                    sendMessage()
                }
            }
        }

        val shouldOpenGuide = ModConfig.data.firstLaunch && !isKeyConfigured()
        if (ChatHistoryStore.messages.isEmpty()) {
            if (shouldOpenGuide) {
                ChatHistoryStore.add(ChatRole.SYSTEM, Component.translatable("screen.agyassistant.msg.setup_prompt").string)
            } else {
                ChatHistoryStore.add(ChatRole.SYSTEM, Component.translatable("screen.agyassistant.msg.welcome").string)
            }
        }
        if (shouldOpenGuide) {
            ModConfig.update { firstLaunch = false }
            Minecraft.getInstance().execute {
                if (Minecraft.getInstance().screen === this) {
                    Minecraft.getInstance().setScreen(GuideScreen(this))
                }
            }
        }

        ChatHistoryStore.activeScreen = this
        syncSendButtonState()

        rebuildChips()
        rebuildQueueBar()
        rebuildMessageFeed()
        refreshBridgeStatus()
    }

    private fun syncSendButtonState() {
        val btn = sendButton ?: return
        btn.active = true
        val streaming = ChatHistoryStore.isStreaming
        val hasDraft = promptInput?.value?.isNotBlank() == true
        if (streaming) {
            if (hasDraft) {
                btn.setMessage(Component.translatable("screen.agyassistant.btn.queue"))
                btn.renderer(queueBtnRenderer)
            } else {
                btn.setMessage(Component.translatable("screen.agyassistant.btn.stop"))
                btn.renderer(stopBtnRenderer)
            }
        } else {
            btn.setMessage(Component.translatable("screen.agyassistant.btn.send"))
            btn.renderer(sendBtnRenderer)
        }
        val needMiniStop = streaming && hasDraft
        if (needMiniStop != showingMiniStop) {
            showingMiniStop = needMiniStop
            val slot = stopSlot ?: return
            slot.clearChildren()
            if (needMiniStop) {
                val miniStop = Containers.horizontalFlow(Sizing.fixed(16), Sizing.fixed(16))
                val normalStop = Surface.flat(0xCCDC2626.toInt()).and(Surface.outline(0xFFEF4444.toInt()))
                val hoverStop = Surface.flat(0xFFEF4444.toInt()).and(Surface.outline(0xFFFCA5A5.toInt()))
                miniStop.surface(normalStop)
                miniStop.horizontalAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER)
                miniStop.verticalAlignment(VerticalAlignment.CENTER)
                miniStop.cursorStyle(CursorStyle.HAND)
                miniStop.tooltip(Component.translatable("screen.agyassistant.btn.stop_hint"))
                miniStop.child(ScaledLabelComponent(Component.literal("■"), scale = 0.80f, defaultColor = 0xFFFFFF, shadow = false))
                miniStop.mouseEnter().subscribe { miniStop.surface(hoverStop) }
                miniStop.mouseLeave().subscribe { miniStop.surface(normalStop) }
                miniStop.mouseDown().subscribe { _, _, button ->
                    if (button == 0) {
                        ChatHistoryStore.cancelActiveRequest()
                        true
                    } else false
                }
                slot.child(miniStop)
            }
        }
    }

    override fun removed() {
        super.removed()
        if (ChatHistoryStore.activeScreen === this) {
            ChatHistoryStore.activeScreen = null
        }
    }

    override fun init() {
        super.init()
        if (ChatHistoryStore.activeScreen !== this) {
            ChatHistoryStore.activeScreen = this
            titleLabel?.text(Component.literal(activeAiDisplayName()))
            syncSendButtonState()
            rebuildChips()
            rebuildQueueBar()
            rebuildMessageFeed()
            refreshBridgeStatus()
        }
        focusPromptInput()
        scrollToBottom()
    }

    private fun focusPromptInput() {
        val input = promptInput ?: return
        uiAdapter.rootComponent.focusHandler()?.focus(input as OwoComponent, OwoComponent.FocusSource.MOUSE_CLICK)
        input.isFocused = true
        // Do NOT call Screen.setFocused(input): BaseOwoScreen uses uiAdapter as its Screen child,
        // so ContainerEventHandler.mouseClicked() calls setFocused(uiAdapter) right after handling a click,
        // which would invoke input.setFocused(false) on the previous Screen.focused listener.
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val input = promptInput
        val wrap = promptInputWrap
        if (input != null && ((input as OwoComponent).isInBoundingBox(mouseX, mouseY) || wrap?.isInBoundingBox(mouseX, mouseY) == true)) {
            focusPromptInput()
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val handled = super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        if (scrollY > 0) {
            userScrolledUp = true
        } else if (scrollY < 0 && isAtScrollBottom()) {
            userScrolledUp = false
        }
        return handled
    }

    private fun isAtScrollBottom(): Boolean {
        val scroll = messagesScroll ?: return true
        val content = messagesContainer ?: return true
        return content.y() + content.height() <= scroll.y() + scroll.height() + 18
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
        val pending = pendingStreamMsg
        if (pending != null && System.currentTimeMillis() - lastStreamRenderMs >= 65L) {
            flushStreamToken(pending)
        }
        if (pendingScrollToBottom) {
            pendingScrollToBottom = false
            messagesScroll?.scrollTo(1.0)
        }
        if (ChatHistoryStore.isStreaming && !ChatHistoryStore.receivedAnyToken) {
            thinkingTick++
            if (thinkingTick % 6 == 0) {
                val dots = ".".repeat((thinkingTick / 6) % 3 + 1)
                val base = Component.translatable("screen.agyassistant.msg.thinking").string
                activeAssistantBody?.markdown("$base$dots")
            }
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val input = promptInput
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (input != null && input.value.isNotBlank()) {
                sendMessage()
                return true
            }
        }
        if (input != null && input.isFocused && ChatHistoryStore.inputHistory.isNotEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                if (historyCursor == -1) {
                    historyCursor = ChatHistoryStore.inputHistory.size - 1
                } else if (historyCursor > 0) {
                    historyCursor--
                }
                input.value = ChatHistoryStore.inputHistory[historyCursor]
                input.moveCursorToEnd(false)
                syncSendButtonState()
                return true
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                if (historyCursor != -1) {
                    if (historyCursor < ChatHistoryStore.inputHistory.size - 1) {
                        historyCursor++
                        input.value = ChatHistoryStore.inputHistory[historyCursor]
                    } else {
                        historyCursor = -1
                        input.value = ""
                    }
                    input.moveCursorToEnd(false)
                    syncSendButtonState()
                    return true
                }
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

    private fun syncCaptureButtonStyle(hovered: Boolean = false) {
        val btn = captureScreenBtn ?: return
        val hasShot = context.screenshot != null
        val surface = when {
            hasShot && hovered -> Surface.flat(0xEE166534.toInt()).and(Surface.outline(0xFF4ADE80.toInt()))
            hasShot -> Surface.flat(0xCC14532D.toInt()).and(Surface.outline(0xFF22C55E.toInt()))
            hovered -> Surface.flat(0xFF334155.toInt()).and(Surface.outline(0xFF64748B.toInt()))
            else -> Surface.flat(0xCC1E293B.toInt()).and(Surface.outline(0xFF334155.toInt()))
        }
        btn.surface(surface)
    }

    private fun rebuildChips() {
        val bar = chipsBar ?: return
        bar.clearChildren()
        syncCaptureButtonStyle(hovered = false)
        ContextChipComponent.populateChips(
            bar = bar,
            ctx = context,
            removable = true,
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
        ) {
            rebuildChips()
            focusPromptInput()
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val capturePromise = pendingCleanCapture
        if (capturePromise != null) {
            pendingCleanCapture = null
            guiGraphics.flush()
            ScreenCapture.captureAsync().whenComplete { info, err ->
                if (err != null) capturePromise.complete(null) else capturePromise.complete(info)
            }
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    private fun captureCleanGameFrame(): CompletableFuture<ScreenshotInfo?> {
        pendingCleanCapture?.let { return it }
        val future = CompletableFuture<ScreenshotInfo?>()
        pendingCleanCapture = future
        return future
    }

    private fun captureScreenshotNow() {
        if (ModConfig.data.privacyBlockScreenshots) {
            ChatHistoryStore.add(
                ChatRole.ERROR,
                Component.translatable("screen.agyassistant.msg.shots_blocked").string,
            )
            rebuildMessageFeed()
            focusPromptInput()
            return
        }
        captureCleanGameFrame().thenAccept { info ->
            if (info != null) {
                Minecraft.getInstance().execute {
                    context.screenshot = info
                    rebuildChips()
                }
            }
        }
    }

    private fun confirmResetChat() {
        val mc = Minecraft.getInstance()
        mc.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        resetChat()
                    }
                    mc.setScreen(this)
                },
                Component.translatable("screen.agyassistant.reset_confirm.title"),
                Component.translatable("screen.agyassistant.reset_confirm.body"),
                Component.translatable("screen.agyassistant.btn.reset"),
                Component.translatable("screen.agyassistant.settings.cancel"),
            )
        )
    }

    private fun resetChat() {
        pendingStreamMsg = null
        activeAssistantBody = null
        ChatHistoryStore.clear()
        ChatHistoryStore.add(ChatRole.SYSTEM, Component.translatable("screen.agyassistant.msg.reset_done").string)
        syncSendButtonState()
        rebuildQueueBar()
        rebuildMessageFeed()
        if (ModConfig.data.provider == "openai_compat") {
            OpenAiCompatClient.resetConversation()
        } else {
            BridgeHttpClient.resetConversation()
        }
    }

    private fun sendMessage() {
        val input = promptInput ?: return
        val text = input.value.trim()
        if (text.isEmpty()) return

        if (!bridgeOnline) {
            if (ModConfig.data.provider == "openai_compat") {
                ChatHistoryStore.add(
                    ChatRole.ERROR,
                    Component.translatable("screen.agyassistant.err.direct_unconfigured").string,
                )
            } else {
                ChatHistoryStore.add(
                    ChatRole.ERROR,
                    Component.translatable("screen.agyassistant.err.bridge_offline", ModConfig.data.bridgePort).string,
                )
            }
            rebuildMessageFeed()
            refreshBridgeStatus()
            return
        }

        if (ModConfig.data.attachScreenshotByDefault &&
            !ModConfig.data.privacyBlockScreenshots &&
            context.screenshot == null
        ) {
            sendButton?.active = false
            captureCleanGameFrame().thenAccept { shot ->
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
        historyCursor = -1

        val sendContext = AttachedContext(
            target = context.target,
            item = context.item,
            screenshot = context.screenshot,
            quote = context.quote,
        )
        context.target = null
        context.item = null
        context.screenshot = null
        context.quote = null
        rebuildChips()

        if (ChatHistoryStore.isStreaming) {
            ChatHistoryStore.enqueue(text, sendContext)
            syncSendButtonState()
            focusPromptInput()
        } else {
            ChatHistoryStore.startStreamingRequest(text, sendContext)
        }
    }

    internal fun onQueueChanged() {
        rebuildQueueBar()
        syncSendButtonState()
    }

    private fun rebuildQueueBar() {
        val bar = queueBar ?: return
        bar.clearChildren()
        val queued = ChatHistoryStore.queuedPrompts
        if (queued.isEmpty()) return

        val wrapper = Containers.verticalFlow(Sizing.fill(100), Sizing.content())
        wrapper.surface(Surface.flat(0xBB0F172A.toInt()).and(Surface.outline(0xFF1E3A5F.toInt())))
        wrapper.padding(Insets.of(3, 3, 5, 5))
        wrapper.gap(2)

        val header = ScaledLabelComponent(
            text = Component.translatable("screen.agyassistant.queue.header", queued.size),
            scale = 0.72f,
            defaultColor = 0x38BDF8,
            shadow = false,
        )
        wrapper.child(header)

        for ((idx, item) in queued.withIndex()) {
            val row = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
            row.verticalAlignment(VerticalAlignment.CENTER)
            row.gap(4)
            row.padding(Insets.of(1, 1, 3, 2))
            row.surface(Surface.flat(0x661E293B.toInt()))

            val badges = mutableListOf<String>()
            if (item.context.screenshot != null) badges.add(Component.translatable("screen.agyassistant.cat.screenshot").string)
            if (item.context.item != null) badges.add(Component.translatable("screen.agyassistant.cat.item").string)
            if (item.context.target != null) {
                val k = if (item.context.target?.kind == "entity") "screen.agyassistant.cat.entity" else "screen.agyassistant.cat.block"
                badges.add(Component.translatable(k).string)
            }
            if (item.context.quote != null) badges.add(Component.translatable("screen.agyassistant.btn.quote_short").string)
            val badgePrefix = if (badges.isNotEmpty()) "[${badges.joinToString("+")}] " else ""

            val preview = item.text.replace('\n', ' ').let { if (it.length > 64) it.take(64) + "…" else it }
            val textBox = Containers.horizontalFlow(Sizing.expand(100), Sizing.content())
            textBox.cursorStyle(CursorStyle.HAND)
            textBox.tooltip(Component.translatable("screen.agyassistant.queue.edit_hint"))
            val styledLine = Component.empty()
                .append(Component.literal("${idx + 1}. ").withStyle(Style.EMPTY.withColor(0x94A3B8)))
                .apply {
                    if (badgePrefix.isNotEmpty()) {
                        append(Component.literal(badgePrefix).withStyle(Style.EMPTY.withColor(0x4ADE80)))
                    }
                }
                .append(Component.literal(preview).withStyle(Style.EMPTY.withColor(0xE2E8F0)))
            val lbl = ScaledLabelComponent(
                text = styledLine,
                scale = 0.78f,
                defaultColor = 0xE2E8F0,
                shadow = false,
            )
            lbl.horizontalSizing(Sizing.fill(100))
            textBox.child(lbl)
            textBox.mouseDown().subscribe { _, _, button ->
                if (button == 0) {
                    val taken = ChatHistoryStore.takeQueued(idx)
                    if (taken != null) {
                        promptInput?.value = taken.text
                        promptInput?.moveCursorToEnd(false)
                        if (taken.context.target != null) context.target = taken.context.target
                        if (taken.context.item != null) context.item = taken.context.item
                        if (taken.context.screenshot != null) context.screenshot = taken.context.screenshot
                        if (taken.context.quote != null) context.quote = taken.context.quote
                        rebuildChips()
                        syncSendButtonState()
                        focusPromptInput()
                    }
                    true
                } else false
            }
            row.child(textBox)

            if (!ChatHistoryStore.isStreaming) {
                val playBtn = Containers.horizontalFlow(Sizing.fixed(12), Sizing.fixed(11))
                playBtn.horizontalAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER)
                playBtn.verticalAlignment(VerticalAlignment.CENTER)
                playBtn.cursorStyle(CursorStyle.HAND)
                playBtn.tooltip(Component.translatable("screen.agyassistant.queue.send_now_hint"))
                playBtn.child(ScaledLabelComponent(Component.literal("▶"), scale = 0.72f, defaultColor = 0x4ADE80, shadow = false))
                playBtn.mouseEnter().subscribe { playBtn.surface(Surface.flat(0x55334155)) }
                playBtn.mouseLeave().subscribe { playBtn.surface(Surface.BLANK) }
                playBtn.mouseDown().subscribe { _, _, button ->
                    if (button == 0) {
                        ChatHistoryStore.sendQueuedNow(idx)
                        true
                    } else false
                }
                row.child(playBtn)
            }

            val delBtn = Containers.horizontalFlow(Sizing.fixed(12), Sizing.fixed(11))
            delBtn.horizontalAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER)
            delBtn.verticalAlignment(VerticalAlignment.CENTER)
            delBtn.cursorStyle(CursorStyle.HAND)
            delBtn.tooltip(Component.translatable("screen.agyassistant.queue.remove_hint"))
            delBtn.child(ScaledLabelComponent(Component.literal("×"), scale = 0.80f, defaultColor = 0xF87171, shadow = false))
            delBtn.mouseEnter().subscribe { delBtn.surface(Surface.flat(0x55334155)) }
            delBtn.mouseLeave().subscribe { delBtn.surface(Surface.BLANK) }
            delBtn.mouseDown().subscribe { _, _, button ->
                if (button == 0) {
                    ChatHistoryStore.removeQueued(idx)
                    focusPromptInput()
                    true
                } else false
            }
            row.child(delBtn)

            wrapper.child(row)
        }

        bar.child(wrapper)
    }

    internal fun onStreamStarted() {
        pendingStreamMsg = null
        lastStreamRenderMs = 0L
        userScrolledUp = false
        syncSendButtonState()
        thinkingTick = 0
        rebuildQueueBar()
        rebuildMessageFeed()
    }

    internal fun onStreamToken(msg: ChatMessage) {
        pendingStreamMsg = msg
        val now = System.currentTimeMillis()
        if (now - lastStreamRenderMs >= 65L) {
            flushStreamToken(msg)
        }
    }

    private fun flushStreamToken(msg: ChatMessage) {
        pendingStreamMsg = null
        lastStreamRenderMs = System.currentTimeMillis()
        val bodyComp = activeAssistantBody
        if (bodyComp != null) {
            bodyComp.color(0xF8FAFC)
            val parsed = QuickOptionsParser.parse(msg.content.toString())
            bodyComp.markdown(parsed.displayText)
            if (!userScrolledUp) {
                scrollToBottom()
            }
        } else {
            rebuildMessageFeed()
        }
    }

    internal fun onStreamFinished() {
        pendingStreamMsg = null
        syncSendButtonState()
        activeAssistantBody = null
        rebuildQueueBar()
        rebuildMessageFeed()
        focusPromptInput()
    }

    private fun rebuildMessageFeed() {
        val container = messagesContainer ?: return
        container.clearChildren()

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

            val headerRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
            headerRow.verticalAlignment(VerticalAlignment.CENTER)
            headerRow.gap(3)

            val avatarStack = when (msg.role) {
                ChatRole.USER -> playerAvatarStack
                ChatRole.ASSISTANT -> aiAvatarStack
                ChatRole.SYSTEM -> systemAvatarStack
                ChatRole.ERROR -> errorAvatarStack
            }
            val avatarComp = Components.item(avatarStack)
            avatarComp.sizing(Sizing.fixed(11), Sizing.fixed(11))
            avatarComp.showOverlay(false)
            headerRow.child(avatarComp)

            val headerText = if (msg.role == ChatRole.ASSISTANT) {
                Component.literal(activeAiDisplayName())
            } else {
                Component.translatable(headerKey)
            }
            val header = ScaledLabelComponent(
                text = headerText,
                scale = 0.78f,
                defaultColor = headerColor,
                shadow = false,
            )
            if (msg.role == ChatRole.ASSISTANT) {
                activeModelId()?.let { header.tooltip(Component.literal(it)) }
            }
            headerRow.child(header)

            val spacer = Containers.horizontalFlow(Sizing.expand(100), Sizing.fixed(1))
            headerRow.child(spacer)

            val isThinkingMsg = (msg === ChatHistoryStore.activeAssistantMsg) &&
                ChatHistoryStore.isStreaming && !ChatHistoryStore.receivedAnyToken

            val parsedReply = if (msg.role == ChatRole.ASSISTANT && !isThinkingMsg) {
                QuickOptionsParser.parse(msg.content.toString())
            } else {
                null
            }
            val cleanCopyText = parsedReply?.displayText ?: msg.content.toString()

            if (!isThinkingMsg && (msg.role == ChatRole.USER || msg.role == ChatRole.ASSISTANT)) {
                val authorName = headerText.string

                fun addIconAction(iconName: String, tooltipKey: String, onClick: (FlowLayout) -> Unit) {
                    val btn = Containers.horizontalFlow(Sizing.fixed(13), Sizing.fixed(13))
                    btn.horizontalAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER)
                    btn.verticalAlignment(VerticalAlignment.CENTER)
                    btn.cursorStyle(CursorStyle.HAND)
                    btn.tooltip(Component.translatable(tooltipKey))

                    val tex = ResourceLocation.fromNamespaceAndPath("agyassistant", "textures/gui/$iconName.png")
                    val icon = Components.texture(tex, 0, 0, 16, 16, 16, 16)
                    icon.sizing(Sizing.fixed(9), Sizing.fixed(9))
                    btn.child(icon)

                    btn.mouseEnter().subscribe { btn.surface(Surface.flat(0x55334155)) }
                    btn.mouseLeave().subscribe { btn.surface(Surface.BLANK) }
                    btn.mouseDown().subscribe { _, _, button ->
                        if (button == 0) {
                            onClick(btn)
                            true
                        } else false
                    }
                    headerRow.child(btn)
                }

                addIconAction("copy", "screen.agyassistant.action.copy") { btn ->
                    Minecraft.getInstance().keyboardHandler.clipboard = cleanCopyText
                    btn.clearChildren()
                    val okTex = ResourceLocation.fromNamespaceAndPath("agyassistant", "textures/gui/check.png")
                    val okIcon = Components.texture(okTex, 0, 0, 16, 16, 16, 16)
                    okIcon.sizing(Sizing.fixed(9), Sizing.fixed(9))
                    btn.child(okIcon)
                    focusPromptInput()
                }

                addIconAction("quote", "screen.agyassistant.action.quote") {
                    context.quote = QuoteInfo(author = authorName, text = cleanCopyText)
                    rebuildChips()
                    focusPromptInput()
                }

                if (msg.role == ChatRole.USER) {
                    addIconAction("edit", "screen.agyassistant.action.edit") {
                        promptInput?.value = cleanCopyText
                        promptInput?.moveCursorToEnd(false)
                        syncSendButtonState()
                        focusPromptInput()
                    }
                }

                if (msg.role == ChatRole.ASSISTANT && isLastMessage && ChatHistoryStore.canRegenerate()) {
                    addIconAction("retry", "screen.agyassistant.action.retry") {
                        ChatHistoryStore.regenerateLast()
                    }
                }
            }

            card.child(headerRow)
            if (msg.attachments != null && !msg.attachments.isEmpty()) {
                val attachRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
                attachRow.gap(3)
                ContextChipComponent.populateChips(
                    bar = attachRow,
                    ctx = msg.attachments,
                    removable = false,
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
                card.child(attachRow)
            }

            if (msg.role == ChatRole.ASSISTANT) {
                val richBody = RichMessageComponent(
                    markdown = parsedReply?.displayText ?: msg.content.toString(),
                    scale = 0.85f,
                    defaultColor = if (isThinkingMsg) 0x94A3B8 else 0xF8FAFC,
                )
                richBody.horizontalSizing(Sizing.fill(100))
                richBody.onGameRefClick = { ref, button, shift ->
                    ReferenceExtractor.handleClick(
                        ref = ref,
                        button = button,
                        shiftDown = shift,
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
                }
                richBody.onUrlClick = { url ->
                    ConfirmLinkScreen.confirmLinkNow(this, url)
                }
                if (msg === ChatHistoryStore.activeAssistantMsg) {
                    activeAssistantBody = richBody
                }
                card.child(richBody)
            } else {
                val body = ScaledLabelComponent(
                    text = Component.literal(msg.content.toString()),
                    scale = 0.85f,
                    defaultColor = 0xF8FAFC,
                    shadow = false,
                )
                body.horizontalSizing(Sizing.fill(100))
                card.child(body)
            }

            if (msg.role == ChatRole.ASSISTANT) {
                if (!ChatHistoryStore.isStreaming || msg !== ChatHistoryStore.activeAssistantMsg) {
                    val links = LinkPreviewComponent.extractLinks(msg.content.toString(), maxCount = 2)
                    for (link in links) {
                        card.child(
                            LinkPreviewComponent.createCard(link) { url ->
                                ConfirmLinkScreen.confirmLinkNow(this, url)
                            }
                        )
                    }
                }

                if (isLastMessage && !ChatHistoryStore.isStreaming && parsedReply != null && parsedReply.options.isNotEmpty()) {
                    val optWrapper = Containers.verticalFlow(Sizing.fill(100), Sizing.content())
                    optWrapper.gap(3)
                    optWrapper.margins(Insets.top(3))
                    val normalOptSurface = Surface.flat(0xCC1E3A5F.toInt()).and(Surface.outline(0xFF3B82F6.toInt()))
                    val hoverOptSurface = Surface.flat(0xEE2563EB.toInt()).and(Surface.outline(0xFF93C5FD.toInt()))

                    val font = Minecraft.getInstance().font
                    val maxRowW = ((messagesScroll?.width() ?: 320) - 34).coerceAtLeast(160)
                    var currentRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply { gap(4) }
                    var currentRowW = 0

                    for (option in parsedReply.options) {
                        val estW = (font.width(option) * 0.85f).toInt() + 16
                        if (currentRowW > 0 && currentRowW + estW > maxRowW) {
                            optWrapper.child(currentRow)
                            currentRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply { gap(4) }
                            currentRowW = 0
                        }
                        val optBtn = Containers.horizontalFlow(Sizing.content(), Sizing.content())
                        optBtn.surface(normalOptSurface)
                        optBtn.padding(Insets.of(3, 3, 6, 6))
                        optBtn.verticalAlignment(VerticalAlignment.CENTER)
                        optBtn.cursorStyle(CursorStyle.HAND)
                        optBtn.child(
                            ScaledLabelComponent(
                                text = Component.literal(option),
                                scale = 0.80f,
                                defaultColor = 0xF8FAFC,
                                shadow = false,
                            )
                        )
                        optBtn.mouseEnter().subscribe { optBtn.surface(hoverOptSurface) }
                        optBtn.mouseLeave().subscribe { optBtn.surface(normalOptSurface) }
                        optBtn.mouseDown().subscribe { _, _, button ->
                            if (button == 0 && !ChatHistoryStore.isStreaming) {
                                dispatchChatRequest(option)
                                true
                            } else false
                        }
                        currentRow.child(optBtn)
                        currentRowW += estW + 4
                    }
                    if (currentRowW > 0) {
                        optWrapper.child(currentRow)
                    }
                    card.child(optWrapper)
                }
            }

            container.child(card)
        }

        scrollToBottom()
    }

    private fun scrollToBottom() {
        messagesScroll?.scrollTo(1.0)
        pendingScrollToBottom = true
    }
}
