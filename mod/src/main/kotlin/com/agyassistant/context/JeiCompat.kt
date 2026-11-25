package com.agyassistant.context

import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

@JeiPlugin
class JeiCompatPlugin : IModPlugin {
    override fun getPluginUid(): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("agyassistant", "jei_plugin")

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        JeiCompat.runtime = jeiRuntime
    }

    override fun onRuntimeUnavailable() {
        JeiCompat.runtime = null
    }
}

object JeiCompat {
    @Volatile
    internal var runtime: IJeiRuntime? = null

    fun getHoveredItemStack(): ItemStack? {
        val rt = runtime ?: return null
        val fromList = runCatching {
            rt.ingredientListOverlay.getIngredientUnderMouse(VanillaTypes.ITEM_STACK)
        }.getOrNull()
        if (fromList != null && !fromList.isEmpty) return fromList

        val fromBookmarks = runCatching {
            rt.bookmarkOverlay.getIngredientUnderMouse(VanillaTypes.ITEM_STACK)
        }.getOrNull()
        if (fromBookmarks != null && !fromBookmarks.isEmpty) return fromBookmarks

        return null
    }

    fun showRecipes(stack: ItemStack): Boolean = showFocus(stack, RecipeIngredientRole.OUTPUT)

    fun showUses(stack: ItemStack): Boolean = showFocus(stack, RecipeIngredientRole.INPUT)

    private fun showFocus(stack: ItemStack, role: RecipeIngredientRole): Boolean {
        val rt = runtime ?: return false
        if (stack.isEmpty) return false
        return runCatching {
            val focus = rt.jeiHelpers.focusFactory.createFocus(role, VanillaTypes.ITEM_STACK, stack)
            rt.recipesGui.show(focus)
            true
        }.getOrDefault(false)
    }
}
