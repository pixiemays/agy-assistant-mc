package com.agyassistant.context

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

object ItemStackSerializer {
    fun toItemInfo(stack: ItemStack, source: String): ItemInfo? {
        if (stack.isEmpty) return null

        val mc = Minecraft.getInstance()
        val key = BuiltInRegistries.ITEM.getKey(stack.item)

        val componentsMap = mutableMapOf<String, String>()
        runCatching {
            for ((type, optValue) in stack.componentsPatch.entrySet()) {
                val compKey = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)?.toString() ?: continue
                if (optValue.isPresent) {
                    componentsMap[compKey] = optValue.get().toString().take(300)
                } else {
                    componentsMap[compKey] = "<removed>"
                }
            }
        }

        val tooltipLines = runCatching {
            val ctx = mc.level?.let { Item.TooltipContext.of(it) } ?: Item.TooltipContext.EMPTY
            stack.getTooltipLines(ctx, mc.player, TooltipFlag.Default.NORMAL)
                .map { it.string }
                .filter { it.isNotBlank() }
                .take(12)
        }.getOrDefault(emptyList())

        val tags = stack.tags
            .limit(20)
            .map { it.location.toString() }
            .toList()

        return ItemInfo(
            id = key.toString(),
            modId = key.namespace,
            displayName = stack.hoverName.string,
            count = stack.count,
            source = source,
            components = componentsMap,
            tooltip = tooltipLines,
            tags = tags,
            recipes = RecipeCollector.collectFor(stack),
            iconStack = stack.copyWithCount(1),
        )
    }
}
