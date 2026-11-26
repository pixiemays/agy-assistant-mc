package com.agyassistant.ui

import com.agyassistant.config.DirectServicePreset
import com.agyassistant.config.ModConfig
import com.agyassistant.network.GitHubUpdater
import com.agyassistant.network.OpenAiCompatClient
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class SettingsScreen(private val parent: Screen) : BaseOwoScreen<FlowLayout>() {

    private enum class Tab { CONNECTION, BEHAVIOR, ADVANCED }

    private var activeTab: Tab = Tab.CONNECTION
    private var selectedProvider: String = ModConfig.data.provider
    private var currentPreset: DirectServicePreset = DirectServicePreset.byKey(ModConfig.data.directServicePreset)

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this, Containers::verticalFlow)

    override fun isPauseScreen(): Boolean = false

    private fun providerComponent(p: String): Component = when (p) {
        "opencode" -> Component.translatable("screen.agyassistant.settings.provider.opencode")
        "openai_compat" -> Component.translatable("screen.agyassistant.settings.provider.openai")
        else -> Component.translatable("screen.agyassistant.settings.provider.antigravity")
    }

    private fun syncValueTooltip(input: TextBoxComponent) {
        val comp = input as OwoComponent
        comp.tooltip(Component.literal(input.value))
        input.onChanged().subscribe {
            comp.tooltip(Component.literal(input.value))
        }
    }

    private fun fieldLabel(translationKey: String): ScaledLabelComponent =
        ScaledLabelComponent(
            text = Component.translatable(translationKey),
            scale = 0.82f,
            defaultColor = 0xCBD5E1,
            shadow = false,
        ).apply { horizontalSizing(Sizing.fill(100)) }

    override fun build(rootComponent: FlowLayout) {
        val cfg = ModConfig.data
        selectedProvider = cfg.provider
        currentPreset = DirectServicePreset.byKey(cfg.directServicePreset)

        val fallbackModels: MutableList<String> = cfg.openaiFallbackModels.split(',')
            .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

        rootComponent
            .surface(Surface.VANILLA_TRANSLUCENT)
            .horizontalAlignment(HorizontalAlignment.CENTER)
            .verticalAlignment(VerticalAlignment.CENTER)

        // Compact centered dialog — stays comfortable even at GUI Scale 3+
        val panelW = minOf((width * 0.84f).toInt().coerceAtLeast(240), 340)
        val panelH = minOf((height * 0.86f).toInt().coerceAtLeast(180), 270)

        val panel = Containers.verticalFlow(Sizing.fixed(panelW), Sizing.fixed(panelH))
        panel.surface(Surface.flat(0xEC0B1120.toInt()).and(Surface.outline(0xFF334155.toInt())))
        panel.padding(Insets.of(6))
        panel.gap(4)

        val flatBtn = ButtonComponent.Renderer.flat(0xCC1E293B.toInt(), 0xFF334155.toInt(), 0x881E293B.toInt())
        val accentBtn = ButtonComponent.Renderer.flat(0xCC2563EB.toInt(), 0xFF3B82F6.toInt(), 0x881E3A8A.toInt())
        val tabActiveRenderer = ButtonComponent.Renderer.flat(0xEE1D4ED8.toInt(), 0xFF2563EB.toInt(), 0xCC1E3A8A.toInt())
        val tabIdleRenderer = ButtonComponent.Renderer.flat(0x991E293B.toInt(), 0xCC334155.toInt(), 0x661E293B.toInt())

        // Top header: title + compact tab bar
        val topBar = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
            verticalAlignment(VerticalAlignment.CENTER)
            gap(4)
        }
        topBar.child(
            ScaledLabelComponent(
                text = Component.translatable("screen.agyassistant.btn.settings"),
                scale = 0.95f,
                defaultColor = 0xF8FAFC,
                shadow = false,
            )
        )
        topBar.child(Containers.horizontalFlow(Sizing.expand(100), Sizing.fixed(1)))

        lateinit var tabConnBtn: ButtonComponent
        lateinit var tabBehavBtn: ButtonComponent
        lateinit var tabAdvBtn: ButtonComponent
        var rebuildBody: () -> Unit = {}

        fun syncTabStyles() {
            tabConnBtn.renderer(if (activeTab == Tab.CONNECTION) tabActiveRenderer else tabIdleRenderer)
            tabBehavBtn.renderer(if (activeTab == Tab.BEHAVIOR) tabActiveRenderer else tabIdleRenderer)
            tabAdvBtn.renderer(if (activeTab == Tab.ADVANCED) tabActiveRenderer else tabIdleRenderer)
        }

        tabConnBtn = Components.button(Component.translatable("screen.agyassistant.settings.tab.connection")) {
            activeTab = Tab.CONNECTION
            syncTabStyles()
            rebuildBody()
        }.apply {
            (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(14))
        }
        tabBehavBtn = Components.button(Component.translatable("screen.agyassistant.settings.tab.behavior")) {
            activeTab = Tab.BEHAVIOR
            syncTabStyles()
            rebuildBody()
        }.apply {
            (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(14))
        }
        tabAdvBtn = Components.button(Component.translatable("screen.agyassistant.settings.tab.advanced")) {
            activeTab = Tab.ADVANCED
            syncTabStyles()
            rebuildBody()
        }.apply {
            (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(14))
        }
        syncTabStyles()

        topBar.child(tabConnBtn as OwoComponent)
        topBar.child(tabBehavBtn as OwoComponent)
        topBar.child(tabAdvBtn as OwoComponent)
        panel.child(topBar)

        // --- Inputs (persisted across tab switches) ---
        val openaiUrlInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2048)
            text(cfg.openaiBaseUrl)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }
        val openaiKeyInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2048)
            text(cfg.openaiApiKey)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
            syncValueTooltip(this)
        }
        val openaiModelInput = Components.textBox(Sizing.expand(100)).apply {
            setMaxLength(512)
            text(cfg.openaiModel)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }
        val openaiProxyInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(512)
            text(cfg.openaiProxyUrl)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }

        val freeKeyHintLabel = ScaledLabelComponent(
            text = if (currentPreset == DirectServicePreset.OPENROUTER) {
                Component.translatable("screen.agyassistant.settings.openai_key_free_hint")
            } else {
                Component.empty()
            },
            scale = 0.70f,
            defaultColor = 0x94A3B8,
            shadow = false,
        ).apply { horizontalSizing(Sizing.fill(100)) }

        val getKeyBtn = Components.button(Component.translatable("screen.agyassistant.btn.get_key")) {
            val url = currentPreset.keyUrl
            if (url != null) {
                ConfirmLinkScreen.confirmLinkNow(this@SettingsScreen, url)
            }
        }.apply {
            renderer(flatBtn)
            active = currentPreset.keyUrl != null
            (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(16))
        }

        val serviceBtn = Components.button(Component.translatable(currentPreset.translationKey)) { btn ->
            currentPreset = DirectServicePreset.next(currentPreset)
            btn.setMessage(Component.translatable(currentPreset.translationKey))
            openaiUrlInput.text(currentPreset.baseUrl)
            openaiModelInput.text(currentPreset.defaultModel)
            getKeyBtn.active = currentPreset.keyUrl != null
            freeKeyHintLabel.text(
                if (currentPreset == DirectServicePreset.OPENROUTER) {
                    Component.translatable("screen.agyassistant.settings.openai_key_free_hint")
                } else {
                    Component.empty()
                }
            )
        }.apply {
            renderer(accentBtn)
            (this as OwoComponent).sizing(Sizing.expand(100), Sizing.fixed(16))
        }

        val pickModelBtn = Components.button(Component.translatable("screen.agyassistant.settings.btn.models_short")) {
            Minecraft.getInstance().setScreen(
                ModelPickerScreen(
                    parent = this@SettingsScreen,
                    baseUrl = openaiUrlInput.value,
                    apiKey = openaiKeyInput.value,
                    currentModel = openaiModelInput.value,
                    existing = fallbackModels.toSet(),
                ) { selected ->
                    openaiModelInput.text(selected)
                },
            )
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).tooltip(Component.translatable("screen.agyassistant.btn.pick_model"))
            (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(15))
        }

        val checkResultLabel = ScaledLabelComponent(
            text = Component.empty(),
            scale = 0.76f,
            defaultColor = 0x94A3B8,
            shadow = false,
        ).apply { horizontalSizing(Sizing.fill(100)) }

        fun modelChain(): String =
            (listOf(openaiModelInput.value.trim()) + fallbackModels)
                .filter { it.isNotBlank() }
                .joinToString(",")

        val checkBtn = Components.button(Component.translatable("screen.agyassistant.btn.check_connection")) { btn ->
            btn.active = false
            checkResultLabel.color(0xFBBF24)
            checkResultLabel.text(Component.translatable("screen.agyassistant.connection.checking"))
            OpenAiCompatClient.checkConnection(
                openaiUrlInput.value,
                openaiKeyInput.value,
                modelChain(),
            ).thenAccept { result ->
                Minecraft.getInstance().execute {
                    btn.active = true
                    checkResultLabel.text(Component.literal(result.message))
                    checkResultLabel.color(if (result.ok) 0x4ADE80 else 0xF87171)
                }
            }
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(16))
        }

        // Bridge inputs
        val hostInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(256)
            text(cfg.bridgeHost)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }
        val portInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(8)
            text(cfg.bridgePort.toString())
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }
        val modelInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(512)
            text(cfg.model)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }
        val apiKeyInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2048)
            text(cfg.apiKey)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
            syncValueTooltip(this)
        }
        val extraPromptInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2000)
            text(cfg.systemPromptExtra)
            (this as OwoComponent).verticalSizing(Sizing.fixed(15))
        }

        val autoStartCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.auto_start")).checked(cfg.autoStartBridge)
        val readDirCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.read_dir")).checked(cfg.allowGameDirRead)
        val defaultShotCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.default_shot")).checked(cfg.attachScreenshotByDefault)
        val clarifyCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.clarify_vague")).checked(cfg.clarifyVagueQuestions)
        val privacyCoordsCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.privacy_strip_coords")).checked(cfg.privacyStripCoordinates)
        val privacyShotsCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.privacy_block_shots")).checked(cfg.privacyBlockScreenshots)
        val autoUpdateCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.auto_update")).checked(cfg.autoUpdate)

        val updateResultLabel = ScaledLabelComponent(
            text = Component.empty(),
            scale = 0.76f,
            defaultColor = 0x94A3B8,
            shadow = false,
        ).apply { horizontalSizing(Sizing.fill(100)) }

        val checkUpdateBtn = Components.button(Component.translatable("screen.agyassistant.settings.btn.check_updates")) { btn ->
            btn.active = false
            updateResultLabel.color(0xFBBF24)
            updateResultLabel.text(Component.translatable("screen.agyassistant.settings.update.checking"))
            GitHubUpdater.checkAndDownloadAsync(manual = true).thenAccept { res ->
                Minecraft.getInstance().execute {
                    btn.active = true
                    when (res) {
                        is GitHubUpdater.UpdateResult.UpToDate -> {
                            updateResultLabel.color(0x4ADE80)
                            updateResultLabel.text(Component.translatable("screen.agyassistant.settings.update.up_to_date", res.version))
                        }
                        is GitHubUpdater.UpdateResult.Downloaded -> {
                            updateResultLabel.color(0x38BDF8)
                            updateResultLabel.text(Component.translatable("screen.agyassistant.settings.update.downloaded", res.version))
                        }
                        is GitHubUpdater.UpdateResult.AvailableDev -> {
                            updateResultLabel.color(0xFBBF24)
                            updateResultLabel.text(Component.translatable("screen.agyassistant.settings.update.dev", res.version))
                        }
                        is GitHubUpdater.UpdateResult.Error -> {
                            updateResultLabel.color(0xF87171)
                            updateResultLabel.text(Component.translatable("screen.agyassistant.settings.update.error", res.message))
                        }
                    }
                }
            }
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(16))
        }

        // Fallback list
        val fallbackListContainer = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply { gap(2) }
        var rebuildFallbackList: () -> Unit = {}

        val addFallbackBtn = Components.button(Component.translatable("screen.agyassistant.btn.add_fallback")) {
            Minecraft.getInstance().setScreen(
                ModelPickerScreen(
                    parent = this@SettingsScreen,
                    baseUrl = openaiUrlInput.value,
                    apiKey = openaiKeyInput.value,
                    currentModel = openaiModelInput.value,
                    existing = fallbackModels.toSet(),
                    titleKey = "screen.agyassistant.model_picker.fallback_title",
                ) { selected ->
                    if (selected !in fallbackModels) {
                        fallbackModels.add(selected)
                        rebuildFallbackList()
                    }
                },
            )
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(16))
        }

        rebuildFallbackList = {
            fallbackListContainer.clearChildren()
            if (fallbackModels.isEmpty()) {
                fallbackListContainer.child(
                    ScaledLabelComponent(
                        text = Component.translatable("screen.agyassistant.settings.fallback_empty"),
                        scale = 0.70f,
                        defaultColor = 0x64748B,
                        shadow = false,
                    ).apply { horizontalSizing(Sizing.fill(100)) }
                )
            } else {
                for ((idx, model) in fallbackModels.withIndex()) {
                    val row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(13))
                    row.surface(Surface.flat(0x661E293B.toInt()))
                    row.verticalAlignment(VerticalAlignment.CENTER)
                    row.padding(Insets.of(1, 1, 4, 4))
                    row.child(
                        ScaledLabelComponent(
                            text = Component.literal("${idx + 1}. $model"),
                            scale = 0.76f,
                            defaultColor = 0xE2E8F0,
                            shadow = false,
                        )
                    )
                    row.child(Containers.horizontalFlow(Sizing.expand(100), Sizing.fixed(1)))
                    val delBtn = Containers.horizontalFlow(Sizing.fixed(12), Sizing.fixed(11))
                    delBtn.horizontalAlignment(HorizontalAlignment.CENTER)
                    delBtn.verticalAlignment(VerticalAlignment.CENTER)
                    delBtn.cursorStyle(CursorStyle.HAND)
                    delBtn.tooltip(Component.translatable("screen.agyassistant.model_picker.remove_fallback"))
                    delBtn.child(ScaledLabelComponent(Component.literal("×"), scale = 0.80f, defaultColor = 0xF87171, shadow = false))
                    delBtn.mouseEnter().subscribe { delBtn.surface(Surface.flat(0x55334155)) }
                    delBtn.mouseLeave().subscribe { delBtn.surface(Surface.BLANK) }
                    delBtn.mouseDown().subscribe { _, _, button ->
                        if (button == 0) {
                            fallbackModels.removeAt(idx)
                            rebuildFallbackList()
                            true
                        } else false
                    }
                    row.child(delBtn)
                    fallbackListContainer.child(row)
                }
            }
        }
        rebuildFallbackList()

        val providerBtn = Components.button(providerComponent(selectedProvider)) { btn ->
            selectedProvider = when (selectedProvider) {
                "bridge" -> "opencode"
                "opencode" -> "openai_compat"
                else -> "bridge"
            }
            btn.setMessage(providerComponent(selectedProvider))
            rebuildBody()
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(16))
        }

        // --- Tab 1: Connection (clean & minimal) ---
        val serviceRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(4)
            verticalAlignment(VerticalAlignment.CENTER)
            child(serviceBtn as OwoComponent)
            child(getKeyBtn as OwoComponent)
        }
        val modelRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(4)
            verticalAlignment(VerticalAlignment.CENTER)
            child(openaiModelInput as OwoComponent)
            child(pickModelBtn as OwoComponent)
        }

        val hostPortRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(6)
            child(
                Containers.verticalFlow(Sizing.fill(68), Sizing.content()).apply {
                    gap(2)
                    child(fieldLabel("screen.agyassistant.settings.bridge_host"))
                    child(hostInput as OwoComponent)
                }
            )
            child(
                Containers.verticalFlow(Sizing.fill(30), Sizing.content()).apply {
                    gap(2)
                    child(fieldLabel("screen.agyassistant.settings.bridge_port"))
                    child(portInput as OwoComponent)
                }
            )
        }

        // Scrollable tab content
        val form = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply {
            padding(Insets.of(4, 4, 4, 6))
            gap(4)
        }

        rebuildBody = {
            form.clearChildren()
            when (activeTab) {
                Tab.CONNECTION -> {
                    if (selectedProvider == "openai_compat") {
                        form.child(fieldLabel("screen.agyassistant.settings.service"))
                        form.child(serviceRow)
                        form.child(fieldLabel("screen.agyassistant.settings.openai_key"))
                        form.child(openaiKeyInput as OwoComponent)
                        form.child(freeKeyHintLabel)
                        form.child(fieldLabel("screen.agyassistant.settings.openai_model"))
                        form.child(modelRow)
                        form.child(checkBtn as OwoComponent)
                        form.child(checkResultLabel)
                    } else {
                        form.child(fieldLabel("screen.agyassistant.settings.bridge_active_note"))
                        form.child(hostPortRow)
                        form.child(fieldLabel("screen.agyassistant.settings.gemini_key"))
                        form.child(apiKeyInput as OwoComponent)
                        form.child(fieldLabel("screen.agyassistant.settings.bridge_model"))
                        form.child(modelInput as OwoComponent)
                    }
                }
                Tab.BEHAVIOR -> {
                    form.child(defaultShotCheck as OwoComponent)
                    form.child(clarifyCheck as OwoComponent)
                    form.child(privacyCoordsCheck as OwoComponent)
                    form.child(privacyShotsCheck as OwoComponent)
                    form.child(fieldLabel("screen.agyassistant.settings.extra_prompt"))
                    form.child(extraPromptInput as OwoComponent)
                }
                Tab.ADVANCED -> {
                    form.child(autoUpdateCheck as OwoComponent)
                    form.child(checkUpdateBtn as OwoComponent)
                    form.child(updateResultLabel)
                    form.child(fieldLabel("screen.agyassistant.settings.provider_mode"))
                    form.child(providerBtn as OwoComponent)
                    if (selectedProvider == "openai_compat") {
                        form.child(fieldLabel("screen.agyassistant.settings.openai_url"))
                        form.child(openaiUrlInput as OwoComponent)
                        form.child(fieldLabel("screen.agyassistant.settings.openai_proxy"))
                        form.child(openaiProxyInput as OwoComponent)
                        form.child(fieldLabel("screen.agyassistant.settings.fallback_label"))
                        form.child(fallbackListContainer)
                        form.child(addFallbackBtn as OwoComponent)
                    } else {
                        form.child(autoStartCheck as OwoComponent)
                        form.child(readDirCheck as OwoComponent)
                    }
                }
            }
        }
        rebuildBody()

        val scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), form)
        scroll.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF1E293B.toInt())))
        scroll.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofArgb(0x8864748B.toInt())))
        scroll.scrollbarThiccness(4)
        panel.child(scroll)

        // Footer
        val btnRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(6)
            horizontalAlignment(HorizontalAlignment.RIGHT)
        }

        btnRow.child(Components.button(Component.translatable("screen.agyassistant.settings.cancel")) {
            Minecraft.getInstance().setScreen(parent)
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fixed(68), Sizing.fixed(16))
        } as OwoComponent)

        btnRow.child(Components.button(Component.translatable("screen.agyassistant.settings.save")) {
            ModConfig.update {
                provider = selectedProvider
                directServicePreset = currentPreset.key
                openaiBaseUrl = openaiUrlInput.value.trim()
                openaiApiKey = openaiKeyInput.value.trim()
                openaiModel = openaiModelInput.value.trim()
                openaiFallbackModels = fallbackModels.joinToString(",")
                openaiProxyUrl = openaiProxyInput.value.trim()
                bridgeHost = hostInput.value.trim().ifEmpty { "127.0.0.1" }
                bridgePort = portInput.value.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: 18765
                apiKey = apiKeyInput.value.trim()
                model = modelInput.value.trim()
                systemPromptExtra = extraPromptInput.value.trim()
                autoStartBridge = autoStartCheck.selected()
                allowGameDirRead = readDirCheck.selected()
                attachScreenshotByDefault = defaultShotCheck.selected()
                clarifyVagueQuestions = clarifyCheck.selected()
                privacyStripCoordinates = privacyCoordsCheck.selected()
                privacyBlockScreenshots = privacyShotsCheck.selected()
                autoUpdate = autoUpdateCheck.selected()
            }
            Minecraft.getInstance().setScreen(parent)
        }.apply {
            renderer(accentBtn)
            (this as OwoComponent).sizing(Sizing.fixed(68), Sizing.fixed(16))
        } as OwoComponent)

        panel.child(btnRow)
        rootComponent.child(panel)
    }
}
