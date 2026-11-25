package com.agyassistant.context

import dev.emi.emi.api.EmiApi
import dev.emi.emi.api.stack.EmiStack
import net.minecraft.world.item.ItemStack

object EmiCompat {
    fun getHoveredItemStack(): ItemStack? {
        return runCatching {
            val hovered = EmiApi.getHoveredStack(true) ?: return@runCatching null
            val emiIngredient = hovered.stack
            if (emiIngredient.isEmpty) return@runCatching null
            val emiStack = emiIngredient.emiStacks.firstOrNull() ?: return@runCatching null
            val itemStack = emiStack.itemStack
            if (itemStack.isEmpty) null else itemStack
        }.getOrNull()
    }

    fun showRecipes(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return runCatching {
            EmiApi.displayRecipes(EmiStack.of(stack))
            true
        }.getOrDefault(false)
    }

    fun showUses(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return runCatching {
            EmiApi.displayUses(EmiStack.of(stack))
            true
        }.getOrDefault(false)
    }
}
