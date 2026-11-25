package com.agyassistant.ui

import com.agyassistant.config.ModConfig
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class SettingsScreen(private val parent: Screen) : BaseOwoScreen<FlowLayout>() {

    private var selectedProvider: String = ModConfig.data.provider

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this, Containers::verticalFlow)

    private fun providerComponent(p: String): Component = when (p) {
        "opencode" -> Component.translatable("screen.agyassistant.settings.provider.opencode")
        "openai_compat" -> Component.translatable("screen.agyassistant.settings.provider.openai")
        else -> Component.translatable("screen.agyassistant.settings.provider.antigravity")
    }

    override fun build(rootComponent: FlowLayout) {
        val cfg = ModConfig.data
        selectedProvider = cfg.provider

        rootComponent
            .surface(Surface.VANILLA_TRANSLUCENT)
            .horizontalAlignment(HorizontalAlignment.CENTER)
            .verticalAlignment(VerticalAlignment.CENTER)

        val panel = Containers.verticalFlow(Sizing.fill(86), Sizing.fill(90))
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

        val providerBtn = Components.button(providerComponent(selectedProvider)) { btn ->
            selectedProvider = when (selectedProvider) {
                "bridge" -> "opencode"
                "opencode" -> "openai_compat"
                else -> "bridge"
            }
            btn.setMessage(providerComponent(selectedProvider))
        }.apply {
            renderer(accentBtn)
            (this as OwoComponent).sizing(Sizing.fill(100), Sizing.fixed(18))
        }
        form.child(providerBtn as OwoComponent)

        // OpenAI-compatible section (Direct, no bridge needed)
        val openaiUrlInput = Components.textBox(Sizing.fill(100), cfg.openaiBaseUrl).apply {
            setMaxLength(256)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val openaiKeyInput = Components.textBox(Sizing.fill(100), cfg.openaiApiKey).apply {
            setMaxLength(256)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val openaiModelInput = Components.textBox(Sizing.fill(100), cfg.openaiModel).apply {
            setMaxLength(256)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }

        form.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_url")).shadow(false))
        form.child(openaiUrlInput as OwoComponent)
        form.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_key")).shadow(false))
        form.child(openaiKeyInput as OwoComponent)
        form.child(Components.label(Component.translatable("screen.agyassistant.settings.openai_model")).shadow(false))
        form.child(openaiModelInput as OwoComponent)

        // Bridge section (Antigravity / OpenCode)
        val hostInput = Components.textBox(Sizing.fill(100), cfg.bridgeHost).apply {
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val portInput = Components.textBox(Sizing.fill(100), cfg.bridgePort.toString()).apply {
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val modelInput = Components.textBox(Sizing.fill(100), cfg.model).apply {
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val apiKeyInput = Components.textBox(Sizing.fill(100), cfg.apiKey).apply {
            setMaxLength(256)
            (this as OwoComponent).verticalSizing(Sizing.fixed(16))
        }
        val extraPromptInput = Components.textBox(Sizing.fill(100), cfg.systemPromptExtra).apply {
            setMaxLength(1000)
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

        form.child(hostPortRow)
        form.child(Components.label(Component.translatable("screen.agyassistant.settings.gemini_key")).shadow(false))
        form.child(apiKeyInput as OwoComponent)
        form.child(Components.label(Component.translatable("screen.agyassistant.settings.bridge_model")).shadow(false))
        form.child(modelInput as OwoComponent)
        form.child(Components.label(Component.translatable("screen.agyassistant.settings.extra_prompt")).shadow(false))
        form.child(extraPromptInput as OwoComponent)
        form.child(autoStartCheck as OwoComponent)
        form.child(readDirCheck as OwoComponent)
        form.child(defaultShotCheck as OwoComponent)
        form.child(clarifyCheck as OwoComponent)

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
                openaiBaseUrl = openaiUrlInput.value.trim()
                openaiApiKey = openaiKeyInput.value.trim()
                openaiModel = openaiModelInput.value.trim()
                bridgeHost = hostInput.value.trim().ifEmpty { "127.0.0.1" }
                bridgePort = portInput.value.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: 18765
                apiKey = apiKeyInput.value.trim()
                model = modelInput.value.trim()
                systemPromptExtra = extraPromptInput.value.trim()
                autoStartBridge = autoStartCheck.selected()
                allowGameDirRead = readDirCheck.selected()
                attachScreenshotByDefault = defaultShotCheck.selected()
                clarifyVagueQuestions = clarifyCheck.selected()
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
