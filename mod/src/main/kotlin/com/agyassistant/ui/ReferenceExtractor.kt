package com.agyassistant.ui

import com.agyassistant.context.EmiCompat
import com.agyassistant.context.ItemStackSerializer
import com.agyassistant.context.JeiCompat
import com.agyassistant.context.TargetInfo
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.TooltipFlag
import net.neoforged.fml.ModList

enum class RefKind {
    ITEM,
    BLOCK,
    ENTITY,
}

data class GameReference(
    val kind: RefKind,
    val id: ResourceLocation,
    val displayName: String,
    val stack: ItemStack,
)

object ReferenceExtractor {
    private val ID_REGEX = Regex("\\b([a-z0-9_.-]+:[a-z0-9_/.-]+)\\b")

    fun extract(text: String, maxCount: Int = 6): List<GameReference> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<GameReference>()

        for (match in ID_REGEX.findAll(text)) {
            if (results.size >= maxCount) break
            val raw = match.groupValues[1].trimEnd('.', ',', ')', ']', ';')
            if (!seen.add(raw)) continue

            val loc = ResourceLocation.tryParse(raw) ?: continue
            val ref = resolve(loc) ?: continue
            results.add(ref)
        }
        return results
    }

    private fun resolve(loc: ResourceLocation): GameReference? {
        if (BuiltInRegistries.ITEM.containsKey(loc)) {
            val item = BuiltInRegistries.ITEM.get(loc)
            if (item != Items.AIR) {
                val stack = ItemStack(item)
                return GameReference(RefKind.ITEM, loc, stack.hoverName.string, stack)
            }
        }
        if (BuiltInRegistries.BLOCK.containsKey(loc)) {
            val block = BuiltInRegistries.BLOCK.get(loc)
            val blockItem = block.asItem()
            val stack = if (blockItem != Items.AIR) ItemStack(blockItem) else ItemStack(Items.GRASS_BLOCK)
            return GameReference(RefKind.BLOCK, loc, block.name.string, stack)
        }
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(loc)) {
            val entityType = BuiltInRegistries.ENTITY_TYPE.get(loc)
            val egg = SpawnEggItem.byId(entityType)
            val stack = if (egg != null) ItemStack(egg) else ItemStack(Items.NAME_TAG)
            return GameReference(RefKind.ENTITY, loc, entityType.description.string, stack)
        }
        return null
    }

    fun hasRecipeViewer(): Boolean =
        ModList.get().isLoaded("jei") || ModList.get().isLoaded("emi")

    fun createChip(
        ref: GameReference,
        onAttachItem: (com.agyassistant.context.ItemInfo) -> Unit,
        onAttachTarget: (TargetInfo) -> Unit,
    ): FlowLayout {
        val (borderColor, bgColor, hoverBg) = when (ref.kind) {
            RefKind.ITEM -> Triple(0xFF8B5CF6.toInt(), 0xAA2E1065.toInt(), 0xDD4C1D95.toInt())
            RefKind.BLOCK -> Triple(0xFF3B82F6.toInt(), 0xAA172554.toInt(), 0xDD1E3A8A.toInt())
            RefKind.ENTITY -> Triple(0xFF14B8A6.toInt(), 0xAA134E4A.toInt(), 0xDD115E59.toInt())
        }

        val chip = Containers.horizontalFlow(Sizing.content(), Sizing.content())
        val normalSurface = Surface.flat(bgColor).and(Surface.outline(borderColor))
        val hoverSurface = Surface.flat(hoverBg).and(Surface.outline(0xFFF8FAFC.toInt()))
        chip.surface(normalSurface)
        chip.padding(Insets.of(2, 2, 4, 5))
        chip.gap(3)
        chip.verticalAlignment(VerticalAlignment.CENTER)
        chip.cursorStyle(CursorStyle.HAND)

        val itemComp = Components.item(ref.stack)
        itemComp.sizing(Sizing.fixed(11), Sizing.fixed(11))
        itemComp.showOverlay(false)
        chip.child(itemComp)

        val label = ScaledLabelComponent(
            text = Component.literal(ref.displayName),
            scale = 0.80f,
            defaultColor = 0xF8FAFC,
            shadow = false,
        )
        chip.child(label)

        chip.tooltip(buildTooltip(ref))

        chip.mouseEnter().subscribe { chip.surface(hoverSurface) }
        chip.mouseLeave().subscribe { chip.surface(normalSurface) }

        chip.mouseDown().subscribe { _, _, button ->
            val shift = Screen.hasShiftDown()
            if (shift && button == 0) {
                attachReference(ref, onAttachItem, onAttachTarget)
                return@subscribe true
            }

            if (button == 0) {
                val opened = openRecipes(ref.stack)
                if (!opened) {
                    Minecraft.getInstance().keyboardHandler.clipboard = ref.id.toString()
                }
                return@subscribe true
            }

            if (button == 1) {
                val opened = openUses(ref.stack)
                if (!opened) {
                    Minecraft.getInstance().keyboardHandler.clipboard = ref.id.toString()
                }
                return@subscribe true
            }
            false
        }

        return chip
    }

    private fun buildTooltip(ref: GameReference): List<Component> {
        val mc = Minecraft.getInstance()
        val lines = mutableListOf<Component>()

        if (ref.kind == RefKind.ITEM || ref.kind == RefKind.BLOCK) {
            runCatching {
                val ctx = mc.level?.let { Item.TooltipContext.of(it) } ?: Item.TooltipContext.EMPTY
                val itemLines = ref.stack.getTooltipLines(ctx, mc.player, TooltipFlag.Default.NORMAL)
                lines.addAll(itemLines.take(6))
            }
        }
        if (lines.isEmpty()) {
            lines.add(Component.literal(ref.displayName).withStyle(Style.EMPTY.withColor(0xF8FAFC)))
        }
        lines.add(Component.literal(ref.id.toString()).withStyle(Style.EMPTY.withColor(0x94A3B8)))

        val hintKey = if (hasRecipeViewer()) {
            "screen.agyassistant.ref.hint_viewer"
        } else {
            "screen.agyassistant.ref.hint_vanilla"
        }
        lines.add(Component.translatable(hintKey).withStyle(Style.EMPTY.withColor(0x38BDF8)))
        return lines
    }

    private fun openRecipes(stack: ItemStack): Boolean {
        if (ModList.get().isLoaded("jei") && JeiCompat.showRecipes(stack)) return true
        if (ModList.get().isLoaded("emi") && EmiCompat.showRecipes(stack)) return true
        return false
    }

    private fun openUses(stack: ItemStack): Boolean {
        if (ModList.get().isLoaded("jei") && JeiCompat.showUses(stack)) return true
        if (ModList.get().isLoaded("emi") && EmiCompat.showUses(stack)) return true
        return false
    }

    private fun attachReference(
        ref: GameReference,
        onAttachItem: (com.agyassistant.context.ItemInfo) -> Unit,
        onAttachTarget: (TargetInfo) -> Unit,
    ) {
        when (ref.kind) {
            RefKind.ITEM -> {
                ItemStackSerializer.toItemInfo(ref.stack, "chat_ref")?.let(onAttachItem)
            }
            RefKind.BLOCK -> {
                onAttachTarget(
                    TargetInfo(
                        kind = "block",
                        id = ref.id.toString(),
                        modId = ref.id.namespace,
                        displayName = ref.displayName,
                        iconStack = ref.stack,
                    )
                )
            }
            RefKind.ENTITY -> {
                onAttachTarget(
                    TargetInfo(
                        kind = "entity",
                        id = ref.id.toString(),
                        modId = ref.id.namespace,
                        displayName = ref.displayName,
                        iconStack = ref.stack,
                    )
                )
            }
        }
    }
}
