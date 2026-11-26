package com.agyassistant.context

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient

/**
 * Collects actual in-game recipes for an ItemStack from the synced client RecipeManager
 * (which includes vanilla, modded, and KubeJS/datapack recipes on servers like ATM10).
 */
object RecipeCollector {
    private const val MAX_RECIPES = 5
    private const val MAX_LINE_LEN = 240

    fun collectFor(stack: ItemStack): List<String> {
        if (stack.isEmpty) return emptyList()
        val level = Minecraft.getInstance().level ?: return emptyList()
        val registryAccess = level.registryAccess()
        val targetItem = stack.item

        val results = LinkedHashSet<String>()
        runCatching {
            for (holder in level.recipeManager.recipes) {
                if (results.size >= MAX_RECIPES) break
                val recipe = holder.value()
                val out = runCatching { recipe.getResultItem(registryAccess) }.getOrNull() ?: continue
                if (out.isEmpty || out.item != targetItem) continue

                val typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.type)?.toString() ?: "recipe"
                val shortType = typeId.removePrefix("minecraft:")
                val ingredients = runCatching { recipe.ingredients }.getOrNull().orEmpty()
                val formattedInputs = formatIngredients(ingredients)
                val outId = BuiltInRegistries.ITEM.getKey(out.item).toString()
                val outPart = if (out.count > 1) "${out.count}x $outId" else outId

                val line = if (formattedInputs.isNotEmpty()) {
                    "[$shortType] $formattedInputs -> $outPart"
                } else {
                    "[$shortType] (${holder.id()}) -> $outPart"
                }
                results.add(line.take(MAX_LINE_LEN))
            }
        }
        return results.toList()
    }

    private fun formatIngredients(ingredients: List<Ingredient>): String {
        val counts = LinkedHashMap<String, Int>()
        for (ing in ingredients) {
            if (ing.isEmpty) continue
            val label = describeIngredient(ing) ?: continue
            counts[label] = (counts[label] ?: 0) + 1
        }
        return counts.entries.joinToString(" + ") { (label, count) ->
            if (count > 1) "${count}x $label" else label
        }
    }

    private fun describeIngredient(ing: Ingredient): String? {
        val items = runCatching { ing.items }.getOrNull() ?: return null
        if (items.isEmpty()) return null
        val uniqueIds = items.asSequence()
            .filter { !it.isEmpty }
            .map { BuiltInRegistries.ITEM.getKey(it.item).toString() }
            .distinct()
            .take(3)
            .toList()
        if (uniqueIds.isEmpty()) return null
        return if (items.size > 3) {
            "${uniqueIds.joinToString("/")}/..."
        } else {
            uniqueIds.joinToString("/")
        }
    }
}
