package com.agyassistant.context

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.neoforged.fml.ModList

object GuiContextCollector {
    fun collectHoveredItem(): ItemInfo? {
        val mc = Minecraft.getInstance()
        val screen = mc.screen

        // 1. Vanilla / modded container slot under cursor
        if (screen is AbstractContainerScreen<*>) {
            val slot = screen.slotUnderMouse
            if (slot != null && slot.hasItem()) {
                return ItemStackSerializer.toItemInfo(slot.item, "inventory")
            }
        }

        // 2. JEI ingredient list / bookmarks (soft-depend)
        if (ModList.get().isLoaded("jei")) {
            val stack = runCatching { JeiCompat.getHoveredItemStack() }.getOrNull()
            if (stack != null && !stack.isEmpty) {
                return ItemStackSerializer.toItemInfo(stack, "jei")
            }
        }

        // 3. EMI ingredient list / favorites (soft-depend)
        if (ModList.get().isLoaded("emi")) {
            val stack = runCatching { EmiCompat.getHoveredItemStack() }.getOrNull()
            if (stack != null && !stack.isEmpty) {
                return ItemStackSerializer.toItemInfo(stack, "emi")
            }
        }

        return null
    }
}
