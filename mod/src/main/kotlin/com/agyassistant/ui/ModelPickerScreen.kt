package com.agyassistant.ui

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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

class ModelPickerScreen(
    private val parent: Screen,
    private val baseUrl: String,
    private val apiKey: String,
    private val currentModel: String,
    private val existing: Set<String> = emptySet(),
    private val titleKey: String = "screen.agyassistant.model_picker.title",
    private val onSelect: (String) -> Unit,
) : BaseOwoScreen<FlowLayout>() {

    private var listContainer: FlowLayout? = null
    private var statusLabel: LabelComponent? = null
    private var searchInput: TextBoxComponent? = null
    private var searchWrap: FlowLayout? = null

    private var allModels: List<String> = emptyList()
    private var searchDirty = false
    private var lastSearchEditMs: Long = 0L

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this, Containers::verticalFlow)

    override fun build(rootComponent: FlowLayout) {
        rootComponent
            .surface(Surface.VANILLA_TRANSLUCENT)
            .horizontalAlignment(HorizontalAlignment.CENTER)
            .verticalAlignment(VerticalAlignment.CENTER)

        val panel = Containers.verticalFlow(Sizing.fill(96), Sizing.fill(92))
        panel.surface(Surface.flat(0xEC0B1120.toInt()).and(Surface.outline(0xFF334155.toInt())))
        panel.padding(Insets.of(7))
        panel.gap(5)

        val flatBtn = ButtonComponent.Renderer.flat(0xCC1E293B.toInt(), 0xFF334155.toInt(), 0x881E293B.toInt())

        panel.child(
            Components.label(Component.translatable(titleKey)).shadow(false)
        )

        searchInput = Components.textBox(Sizing.fill(100)).apply {
            setMaxLength(256)
            setBordered(false)
            setTextColor(0xF8FAFC)
            setHint(
                Component.translatable("screen.agyassistant.model_picker.search_hint")
                    .withStyle(Style.EMPTY.withColor(0x64748B))
            )
            onChanged().subscribe {
                searchDirty = true
                lastSearchEditMs = System.currentTimeMillis()
            }
            (this as OwoComponent).focusGained().subscribe {
                searchWrap?.surface(Surface.flat(0xEE0F172A.toInt()).and(Surface.outline(0xFF3B82F6.toInt())))
            }
            (this as OwoComponent).focusLost().subscribe {
                searchWrap?.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF334155.toInt())))
            }
        }
        searchWrap = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply {
            surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF334155.toInt())))
            padding(Insets.of(4))
            child(searchInput as OwoComponent)
        }
        panel.child(searchWrap as OwoComponent)

        statusLabel = Components.label(Component.translatable("screen.agyassistant.model_picker.loading")).apply {
            color(Color.ofRgb(0x94A3B8))
            (this as OwoComponent).horizontalSizing(Sizing.fill(100))
        }
        panel.child(statusLabel as OwoComponent)

        listContainer = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(3)
            padding(Insets.right(6))
        }

        val scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), listContainer)
        scroll.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF1E293B.toInt())))
        scroll.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofArgb(0xFF64748B.toInt())))
        scroll.scrollbarThiccness(6)
        panel.child(scroll)

        panel.child(
            Components.button(Component.translatable("screen.agyassistant.settings.cancel")) {
                Minecraft.getInstance().setScreen(parent)
            }.apply {
                renderer(flatBtn)
                (this as OwoComponent).sizing(Sizing.fixed(72), Sizing.fixed(16))
            } as OwoComponent
        )

        rootComponent.child(panel)

        loadModels()
    }

    override fun init() {
        super.init()
        focusSearchInput()
    }

    private fun focusSearchInput() {
        val input = searchInput ?: return
        uiAdapter.rootComponent.focusHandler()?.focus(input as OwoComponent, OwoComponent.FocusSource.MOUSE_CLICK)
        input.isFocused = true
    }

    override fun tick() {
        super.tick()
        if (searchDirty && System.currentTimeMillis() - lastSearchEditMs >= 150L) {
            searchDirty = false
            rebuildList()
        }
    }

    private fun loadModels() {
        OpenAiCompatClient.fetchModels(baseUrl, apiKey).thenAccept { result ->
            Minecraft.getInstance().execute {
                val list = listContainer ?: return@execute
                list.clearChildren()
                when (result) {
                    is OpenAiCompatClient.ModelListResult.Success -> {
                        allModels = result.models
                        if (allModels.isEmpty()) {
                            statusLabel?.text(Component.translatable("screen.agyassistant.model_picker.empty"))
                            statusLabel?.color(Color.ofRgb(0xFBBF24))
                        } else {
                            rebuildList()
                        }
                    }
                    is OpenAiCompatClient.ModelListResult.Error -> {
                        statusLabel?.text(Component.literal(result.message))
                        statusLabel?.color(Color.ofRgb(0xF87171))
                    }
                }
            }
        }
    }

    private fun rebuildList() {
        val list = listContainer ?: return
        list.clearChildren()
        if (allModels.isEmpty()) return

        val query = searchInput?.value?.trim()?.lowercase().orEmpty()
        val filtered = if (query.isEmpty()) {
            allModels
        } else {
            allModels.filter { it.lowercase().contains(query) }
        }

        if (filtered.isEmpty()) {
            statusLabel?.text(Component.translatable("screen.agyassistant.model_picker.no_match"))
            statusLabel?.color(Color.ofRgb(0xFBBF24))
        } else {
            statusLabel?.text(Component.translatable("screen.agyassistant.model_picker.hint", filtered.size, allModels.size))
            statusLabel?.color(Color.ofRgb(0x94A3B8))
            for (model in filtered) {
                list.child(modelRow(model))
            }
        }
    }

    private fun modelRow(model: String): OwoComponent {
        // Already picked as primary or present in the chain — dimmed, not selectable
        if (model == currentModel.trim() || model in existing) {
            val row = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
            row.surface(Surface.flat(0x551E293B.toInt()))
            row.verticalAlignment(VerticalAlignment.CENTER)
            row.padding(Insets.of(3, 3, 6, 6))
            row.cursorStyle(CursorStyle.POINTER)
            row.tooltip(Component.translatable("screen.agyassistant.model_picker.already_in_chain"))
            row.child(
                ScaledLabelComponent(
                    text = Component.literal("✓ $model"),
                    scale = 0.80f,
                    defaultColor = 0x64748B,
                    shadow = false,
                )
            )
            return row
        }

        val row = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.surface(Surface.flat(0xCC1E293B.toInt()))
        row.verticalAlignment(VerticalAlignment.CENTER)
        row.padding(Insets.of(3, 3, 6, 6))
        row.cursorStyle(CursorStyle.HAND)
        row.tooltip(Component.literal(model))
        row.child(
            ScaledLabelComponent(
                text = Component.literal(model),
                scale = 0.80f,
                defaultColor = 0xE2E8F0,
                shadow = false,
            )
        )
        row.mouseEnter().subscribe { row.surface(Surface.flat(0xFF334155.toInt())) }
        row.mouseLeave().subscribe { row.surface(Surface.flat(0xCC1E293B.toInt())) }
        row.mouseDown().subscribe { _, _, button ->
            if (button == 0) {
                onSelect(model)
                Minecraft.getInstance().setScreen(parent)
                true
            } else false
        }
        return row
    }
}
