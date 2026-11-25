package com.agyassistant.ui

import com.agyassistant.context.EmiCompat
import com.agyassistant.context.ItemInfo
import com.agyassistant.context.ItemStackSerializer
import com.agyassistant.context.JeiCompat
import com.agyassistant.context.TargetInfo
import net.minecraft.client.Minecraft
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
    const val CLICK_PREFIX = "agyref://"

    private val nameIndex: Map<String, ResourceLocation> by lazy {
        val map = HashMap<String, ResourceLocation>(4096)
        for (loc in BuiltInRegistries.ITEM.keySet()) {
            val item = BuiltInRegistries.ITEM.get(loc)
            if (item == Items.AIR) continue
            val pathName = loc.path.replace('_', ' ').lowercase()
            map.putIfAbsent(pathName, loc)
            val display = runCatching { ItemStack(item).hoverName.string.trim().lowercase() }.getOrNull()
            if (!display.isNullOrBlank()) {
                map.putIfAbsent(display, loc)
            }
        }
        map
    }

    fun resolve(raw: String, allowNameLookup: Boolean = false): GameReference? {
        val cleaned = raw.trim().trimEnd('.', ',', ')', ']', ';')
        if (cleaned.isEmpty()) return null
        if (cleaned.contains(':')) {
            val loc = ResourceLocation.tryParse(cleaned) ?: return null
            return resolve(loc)
        }
        if (allowNameLookup && cleaned.length in 3..48) {
            val loc = nameIndex[cleaned.lowercase()] ?: return null
            return resolve(loc)
        }
        return null
    }

    fun resolve(loc: ResourceLocation): GameReference? {
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
            val stack = when {
                blockItem != Items.AIR -> ItemStack(blockItem)
                loc.path == "lava" -> ItemStack(Items.LAVA_BUCKET)
                loc.path == "water" -> ItemStack(Items.WATER_BUCKET)
                else -> ItemStack(Items.GRASS_BLOCK)
            }
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

    fun buildTooltip(ref: GameReference): List<Component> {
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

    fun handleClick(
        ref: GameReference,
        button: Int,
        shiftDown: Boolean,
        onAttachItem: (ItemInfo) -> Unit,
        onAttachTarget: (TargetInfo) -> Unit,
    ): Boolean {
        if (shiftDown && button == 0) {
            attachReference(ref, onAttachItem, onAttachTarget)
            return true
        }
        if (button == 0) {
            if (!openRecipes(ref.stack)) {
                Minecraft.getInstance().keyboardHandler.clipboard = ref.id.toString()
            }
            return true
        }
        if (button == 1) {
            if (!openUses(ref.stack)) {
                Minecraft.getInstance().keyboardHandler.clipboard = ref.id.toString()
            }
            return true
        }
        return false
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
        onAttachItem: (ItemInfo) -> Unit,
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
