package com.agyassistant.ui

import com.agyassistant.config.DirectServicePreset
import com.agyassistant.config.ModConfig
import com.agyassistant.network.OpenAiCompatClient
import io.wispforest.owo.ui.base.BaseOwoScreen
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

    private var selectedProvider: String = ModConfig.data.provider
    private var currentPreset: DirectServicePreset = DirectServicePreset.byKey(ModConfig.data.directServicePreset)

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this, Containers::verticalFlow)

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

        val panel = Containers.verticalFlow(Sizing.fill(96), Sizing.fill(92))
        panel.surface(Surface.flat(0xEC0B1120.toInt()).and(Surface.outline(0xFF334155.toInt())))
        panel.padding(Insets.of(7))
        panel.gap(5)

        val flatBtn = ButtonComponent.Renderer.flat(0xCC1E293B.toInt(), 0xFF334155.toInt(), 0x881E293B.toInt())
        val accentBtn = ButtonComponent.Renderer.flat(0xCC2563EB.toInt(), 0xFF3B82F6.toInt(), 0x881E3A8A.toInt())

        // Fixed header
        panel.child(
            Components.label(Component.translatable("screen.agyassistant.btn.settings")).shadow(false)
        )

        // Scrollable form body
        val form = Containers.verticalFlow(Sizing.fill(100), Sizing.content())
        form.padding(Insets.of(4, 4, 4, 6))
        form.gap(5)

        // Sections are shown/hidden depending on the selected provider
        val directSection = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply { gap(5) }
        val bridgeSection = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply { gap(5) }
        val commonSection = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply { gap(5) }

        var rebuildFormSections: () -> Unit = {}

        val providerBtn = Components.button(providerComponent(selectedProvider)) { btn ->
            selectedProvider = when (selectedProvider) {
                "bridge" -> "opencode"
                "opencode" -> "openai_compat"
                else -> "bridge"
            }
            btn.setMessage(providerComponent(selectedProvider))
            rebuildFormSections()
        }.apply {
            renderer(accentBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
        }

        // OpenAI-compatible section (Direct, no bridge needed)
        val openaiUrlInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2048)
            text(cfg.openaiBaseUrl)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val openaiKeyInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2048)
            text(cfg.openaiApiKey)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
            syncValueTooltip(this)
        }
        val openaiModelInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(512)
            text(cfg.openaiModel)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }

        // OpenRouter :free models require prompt logging enabled in the account's Privacy settings
        val freeKeyHintLabel = ScaledLabelComponent(
            text = if (currentPreset == DirectServicePreset.OPENROUTER) {
                Component.translatable("screen.agyassistant.settings.openai_key_free_hint")
            } else {
                Component.empty()
            },
            scale = 0.72f,
            defaultColor = 0x94A3B8,
            shadow = false,
        ).apply { horizontalSizing(Sizing.fill(100)) }

        val openaiProxyInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(512)
            text(cfg.openaiProxyUrl)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }

        val getKeyBtn = Components.button(Component.translatable("screen.agyassistant.btn.get_key")) {
            val url = currentPreset.keyUrl
            if (url != null) {
                ConfirmLinkScreen.confirmLinkNow(this@SettingsScreen, url)
            }
        }.apply {
            renderer(flatBtn)
            active = currentPreset.keyUrl != null
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
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
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
        }

        val checkResultLabel = Components.label(Component.empty()).apply {
            color(Color.ofRgb(0x94A3B8))
            (this as OwoComponent).horizontalSizing(Sizing.fill(100))
        }

        fun modelChain(): String =
            (listOf(openaiModelInput.value.trim()) + fallbackModels)
                .filter { it.isNotBlank() }
                .joinToString(",")

        val checkBtn = Components.button(Component.translatable("screen.agyassistant.btn.check_connection")) { btn ->
            btn.active = false
            checkResultLabel.color(Color.ofRgb(0xFBBF24))
            checkResultLabel.text(Component.translatable("screen.agyassistant.connection.checking"))
            OpenAiCompatClient.checkConnection(
                openaiUrlInput.value,
                openaiKeyInput.value,
                modelChain(),
            ).thenAccept { result ->
                Minecraft.getInstance().execute {
                    btn.active = true
                    checkResultLabel.text(Component.literal(result.message))
                    checkResultLabel.color(if (result.ok) Color.ofRgb(0x4ADE80) else Color.ofRgb(0xF87171))
                }
            }
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
        }

        // Fallback chain UI: rows with remove buttons + "add from model list" picker
        val fallbackListContainer = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply { gap(3) }
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
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
        }

        rebuildFallbackList = {
            val list = fallbackListContainer
            list.clearChildren()
            if (fallbackModels.isEmpty()) {
                list.child(
                    ScaledLabelComponent(
                        text = Component.translatable("screen.agyassistant.settings.fallback_empty"),
                        scale = 0.72f,
                        defaultColor = 0x64748B,
                        shadow = false,
                    ).apply { horizontalSizing(Sizing.fill(100)) }
                )
            } else {
                for ((idx, model) in fallbackModels.withIndex()) {
                    val row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(14))
                    row.surface(Surface.flat(0x661E293B.toInt()))
                    row.verticalAlignment(VerticalAlignment.CENTER)
                    row.padding(Insets.of(2, 2, 4, 4))
                    row.child(
                        ScaledLabelComponent(
                            text = Component.literal("${idx + 1}. $model"),
                            scale = 0.78f,
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
                    delBtn.child(
                        ScaledLabelComponent(Component.literal("×"), scale = 0.80f, defaultColor = 0xF87171, shadow = false)
                    )
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
                    list.child(row)
                }
            }
        }
        rebuildFallbackList()

        directSection.child(Components.label(Component.translatable("screen.agyassistant.settings.service")).shadow(false))
        directSection.child(serviceBtn as OwoComponent)
        directSection.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_url")).shadow(false))
        directSection.child(openaiUrlInput as OwoComponent)
        directSection.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_key")).shadow(false))
        directSection.child(openaiKeyInput as OwoComponent)
        directSection.child(freeKeyHintLabel)
        directSection.child(getKeyBtn as OwoComponent)
        val pickModelBtn = Components.button(Component.translatable("screen.agyassistant.btn.pick_model")) {
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
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
        }

        directSection.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_model")).shadow(false))
        directSection.child(openaiModelInput as OwoComponent)
        directSection.child(pickModelBtn as OwoComponent)
        directSection.child(Components.label(Component.translatable("screen.agyassistant.settings.fallback_label")).shadow(false))
        directSection.child(fallbackListContainer)
        directSection.child(addFallbackBtn as OwoComponent)
        directSection.child(checkBtn as OwoComponent)
        directSection.child(checkResultLabel as OwoComponent)
        directSection.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_proxy")).shadow(false))
        directSection.child(openaiProxyInput as OwoComponent)

        // Bridge section (Antigravity / OpenCode)
        val hostInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(256)
            text(cfg.bridgeHost)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val portInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(8)
            text(cfg.bridgePort.toString())
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val modelInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(512)
            text(cfg.model)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val apiKeyInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2048)
            text(cfg.apiKey)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
            syncValueTooltip(this)
        }
        val extraPromptInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(2000)
            text(cfg.systemPromptExtra)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }

        val hostPortRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
        hostPortRow.gap(6)

        val hostCol = Containers.verticalFlow(Sizing.fill(66), Sizing.content()).apply {
            gap(2)
            child(Components.label(Component.translatable("screen.agyassistant.settings.bridge_host")).shadow(false))
            child(hostInput as OwoComponent)
        }
        val portCol = Containers.verticalFlow(Sizing.fill(32), Sizing.content()).apply {
            gap(2)
            child(Components.label(Component.translatable("screen.agyassistant.settings.bridge_port")).shadow(false))
            child(portInput as OwoComponent)
        }
        hostPortRow.child(hostCol)
        hostPortRow.child(portCol)

        val autoStartCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.auto_start")).checked(cfg.autoStartBridge)
        val readDirCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.read_dir")).checked(cfg.allowGameDirRead)
        val defaultShotCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.default_shot")).checked(cfg.attachScreenshotByDefault)
        val clarifyCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.clarify_vague")).checked(cfg.clarifyVagueQuestions)
        val privacyCoordsCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.privacy_strip_coords")).checked(cfg.privacyStripCoordinates)
        val privacyShotsCheck = Components.checkbox(Component.translatable("screen.agyassistant.settings.privacy_block_shots")).checked(cfg.privacyBlockScreenshots)

        bridgeSection.child(hostPortRow)
        bridgeSection.child(Components.label(Component.translatable("screen.agyassistant.settings.gemini_key")).shadow(false))
        bridgeSection.child(apiKeyInput as OwoComponent)
        bridgeSection.child(Components.label(Component.translatable("screen.agyassistant.settings.bridge_model")).shadow(false))
        bridgeSection.child(modelInput as OwoComponent)
        bridgeSection.child(autoStartCheck as OwoComponent)
        bridgeSection.child(readDirCheck as OwoComponent)

        commonSection.child(Components.label(Component.translatable("screen.agyassistant.settings.extra_prompt")).shadow(false))
        commonSection.child(extraPromptInput as OwoComponent)
        commonSection.child(defaultShotCheck as OwoComponent)
        commonSection.child(clarifyCheck as OwoComponent)
        commonSection.child(privacyCoordsCheck as OwoComponent)
        commonSection.child(privacyShotsCheck as OwoComponent)

        // Show only the section matching the selected provider; inputs keep their state
        rebuildFormSections = {
            form.clearChildren()
            form.child(providerBtn as OwoComponent)
            if (selectedProvider == "openai_compat") {
                form.child(directSection)
            } else {
                form.child(bridgeSection)
            }
            form.child(commonSection)
        }
        rebuildFormSections()

        val scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), form)
        scroll.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofArgb(0x8864748B.toInt())))
        scroll.scrollbarThiccness(4)
        panel.child(scroll)

        // Fixed footer buttons
        val btnRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
        btnRow.gap(8)
        btnRow.horizontalAlignment(HorizontalAlignment.RIGHT)

        btnRow.child(Components.button(Component.translatable("screen.agyassistant.settings.cancel")) {
            Minecraft.getInstance().setScreen(parent)
        }.apply {
            renderer(flatBtn)
            (this as OwoComponent).sizing(Sizing.fixed(72), Sizing.fixed(16))
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
            }
            Minecraft.getInstance().setScreen(parent)
        }.apply {
            renderer(accentBtn)
            (this as OwoComponent).sizing(Sizing.fixed(72), Sizing.fixed(16))
        } as OwoComponent)

        panel.child(btnRow)
        rootComponent.child(panel)
    }
}
