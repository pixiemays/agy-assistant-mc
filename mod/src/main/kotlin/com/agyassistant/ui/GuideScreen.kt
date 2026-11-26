package com.agyassistant.ui

import com.agyassistant.config.DirectServicePreset
import com.agyassistant.config.ModConfig
import com.agyassistant.input.KeyBindings
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
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * First-launch onboarding & quick-start guide screen.
 * Shown automatically on first launch when no API key is configured,
 * and can be reopened anytime via the "?" button in AssistantScreen.
 */
class GuideScreen(private val parent: Screen) : BaseOwoScreen<FlowLayout>() {

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this, Containers::verticalFlow)

    override fun isPauseScreen(): Boolean = false

    override fun build(rootComponent: FlowLayout) {
        rootComponent
            .surface(Surface.VANILLA_TRANSLUCENT)
            .horizontalAlignment(HorizontalAlignment.CENTER)
            .verticalAlignment(VerticalAlignment.CENTER)

        val panel = Containers.verticalFlow(Sizing.fill(88), Sizing.fill(88))
        panel.surface(Surface.flat(0xEC0B1120.toInt()).and(Surface.outline(0xFF334155.toInt())))
        panel.padding(Insets.of(8))
        panel.gap(6)

        val flatBtn = ButtonComponent.Renderer.flat(0xCC1E293B.toInt(), 0xFF334155.toInt(), 0x881E293B.toInt())
        val accentBtn = ButtonComponent.Renderer.flat(0xCC2563EB.toInt(), 0xFF3B82F6.toInt(), 0x881E3A8A.toInt())

        // Header
        val header = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply { gap(2) }
        header.child(
            ScaledLabelComponent(
                text = Component.translatable("screen.agyassistant.guide.title"),
                scale = 1.0f,
                defaultColor = 0xF8FAFC,
                shadow = false,
            ).apply { horizontalSizing(Sizing.fill(100)) }
        )
        header.child(
            ScaledLabelComponent(
                text = Component.translatable("screen.agyassistant.guide.subtitle"),
                scale = 0.78f,
                defaultColor = 0x94A3B8,
                shadow = false,
            ).apply { horizontalSizing(Sizing.fill(100)) }
        )
        panel.child(header)

        // Scrollable cards
        val body = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(6)
            padding(Insets.of(2, 2, 2, 6))
        }

        val openKey = KeyBindings.OPEN_CHAT.translatedKeyMessage.string
        val captureKey = KeyBindings.CAPTURE_AND_OPEN.translatedKeyMessage.string

        // Card 1: Setup & Free model
        val card1 = makeCard(
            accentColor = 0xFF38BDF8.toInt(),
            title = Component.translatable("screen.agyassistant.guide.step1.title"),
            lines = listOf(
                Component.translatable("screen.agyassistant.guide.step1.line1"),
                Component.translatable("screen.agyassistant.guide.step1.line2"),
                Component.translatable("screen.agyassistant.guide.step1.line3"),
            ),
        )
        val openRouterUrl = DirectServicePreset.OPENROUTER.keyUrl
        if (openRouterUrl != null) {
            val quickRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
                gap(5)
                margins(Insets.top(3))
            }
            quickRow.child(
                Components.button(Component.translatable("screen.agyassistant.guide.btn.get_openrouter_key")) {
                    ConfirmLinkScreen.confirmLinkNow(this@GuideScreen, openRouterUrl)
                }.apply {
                    renderer(flatBtn)
                    (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(16))
                } as OwoComponent
            )
            card1.child(quickRow)
        }
        body.child(card1)

        // Card 2: Context & Controls
        body.child(
            makeCard(
                accentColor = 0xFF4ADE80.toInt(),
                title = Component.translatable("screen.agyassistant.guide.step2.title"),
                lines = listOf(
                    Component.translatable("screen.agyassistant.guide.step2.line1", openKey),
                    Component.translatable("screen.agyassistant.guide.step2.line2", captureKey),
                    Component.translatable("screen.agyassistant.guide.step2.line3"),
                ),
            )
        )

        // Card 3: Interactive answers & Tips
        body.child(
            makeCard(
                accentColor = 0xFFA78BFA.toInt(),
                title = Component.translatable("screen.agyassistant.guide.step3.title"),
                lines = listOf(
                    Component.translatable("screen.agyassistant.guide.step3.line1"),
                    Component.translatable("screen.agyassistant.guide.step3.line2"),
                    Component.translatable("screen.agyassistant.guide.step3.line3"),
                ),
            )
        )

        val scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), body)
        scroll.surface(Surface.flat(0xCC0F172A.toInt()).and(Surface.outline(0xFF1E293B.toInt())))
        scroll.scrollbar(ScrollContainer.Scrollbar.flat(Color.ofArgb(0xFF64748B.toInt())))
        scroll.scrollbarThiccness(5)
        scroll.padding(Insets.of(4))
        panel.child(scroll)

        // Footer buttons
        val footer = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).apply {
            gap(6)
            horizontalAlignment(HorizontalAlignment.RIGHT)
        }

        footer.child(
            Components.button(Component.translatable("screen.agyassistant.guide.btn.open_settings")) {
                markSeen()
                Minecraft.getInstance().setScreen(SettingsScreen(parent))
            }.apply {
                renderer(accentBtn)
                (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(18))
            } as OwoComponent
        )

        footer.child(
            Components.button(Component.translatable("screen.agyassistant.guide.btn.close")) {
                onClose()
            }.apply {
                renderer(flatBtn)
                (this as OwoComponent).sizing(Sizing.content(), Sizing.fixed(18))
            } as OwoComponent
        )

        panel.child(footer)
        rootComponent.child(panel)
    }

    private fun makeCard(accentColor: Int, title: Component, lines: List<Component>): FlowLayout {
        val card = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).apply {
            surface(Surface.flat(0x991E293B.toInt()).and(Surface.outline(0xFF334155.toInt())))
            padding(Insets.of(6))
            gap(3)
        }
        card.child(
            ScaledLabelComponent(
                text = title,
                scale = 0.88f,
                defaultColor = accentColor and 0xFFFFFF,
                shadow = false,
            ).apply { horizontalSizing(Sizing.fill(100)) }
        )
        for (line in lines) {
            card.child(
                ScaledLabelComponent(
                    text = line,
                    scale = 0.78f,
                    defaultColor = 0xE2E8F0,
                    shadow = false,
                ).apply { horizontalSizing(Sizing.fill(100)) }
            )
        }
        return card
    }

    private fun markSeen() {
        if (ModConfig.data.firstLaunch) {
            ModConfig.update { firstLaunch = false }
        }
    }

    override fun onClose() {
        markSeen()
        Minecraft.getInstance().setScreen(parent)
    }
}
