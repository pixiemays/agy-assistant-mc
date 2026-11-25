package com.agyassistant.ui

import com.agyassistant.context.AttachedContext
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object ContextChipComponent {
    fun create(
        category: String,
        name: String,
        categoryColorRgb: Int,
        bgArgb: Int,
        borderArgb: Int,
        icon: ItemStack?,
        onRemove: (() -> Unit)? = null,
    ): FlowLayout {
        val chip = Containers.horizontalFlow(Sizing.content(), Sizing.content())
        chip.surface(Surface.flat(bgArgb).and(Surface.outline(borderArgb)))
        chip.padding(Insets.of(2, 2, 4, 4))
        chip.gap(4)
        chip.verticalAlignment(VerticalAlignment.CENTER)

        if (icon != null && !icon.isEmpty) {
            val itemComp = Components.item(icon)
            itemComp.sizing(Sizing.fixed(12), Sizing.fixed(12))
            itemComp.showOverlay(false)
            chip.child(itemComp)
        }

        val styledText = Component.empty()
            .append(Component.literal(category).withStyle(Style.EMPTY.withColor(categoryColorRgb)))
            .append(Component.literal(" $name").withStyle(Style.EMPTY.withColor(0xF8FAFC)))

        val text = ScaledLabelComponent(styledText, scale = 0.82f, defaultColor = 0xF8FAFC, shadow = false)
        chip.child(text)

        if (onRemove != null) {
            val removeBtn = Components.button(Component.literal("×")) { onRemove() }
            removeBtn.renderer(io.wispforest.owo.ui.component.ButtonComponent.Renderer.flat(0x00000000, 0x66FFFFFF, 0x00000000))
            val removeBtnComp = removeBtn as OwoComponent
            removeBtnComp.sizing(Sizing.fixed(11), Sizing.fixed(11))
            chip.child(removeBtnComp)
        }

        return chip
    }

    fun populateChips(
        bar: FlowLayout,
        ctx: AttachedContext,
        removable: Boolean,
        onChanged: (() -> Unit)? = null,
    ) {
        ctx.target?.let { target ->
            val isEntity = target.kind == "entity"
            val catKey = if (isEntity) "screen.agyassistant.cat.entity" else "screen.agyassistant.cat.block"
            val cat = Component.translatable(catKey).string
            val cleanName = cleanDisplayName(target.displayName, target.id)
            val fallbackIcon = if (isEntity) ItemStack(Items.NAME_TAG) else ItemStack(Items.GRASS_BLOCK)

            bar.child(
                create(
                    category = cat,
                    name = cleanName,
                    categoryColorRgb = if (isEntity) 0x5EEAD4 else 0x60A5FA,
                    bgArgb = if (isEntity) 0xCC134E4A.toInt() else 0xCC1E3A5F.toInt(),
                    borderArgb = if (isEntity) 0xFF2DD4BF.toInt() else 0xFF3B82F6.toInt(),
                    icon = target.iconStack ?: fallbackIcon,
                    onRemove = if (removable) {
                        {
                            ctx.target = null
                            onChanged?.invoke()
                        }
                    } else null,
                )
            )
        }

        ctx.item?.let { item ->
            val cat = Component.translatable("screen.agyassistant.cat.item").string
            val baseName = cleanDisplayName(item.displayName, item.id)
            val displayName = if (item.count > 1) "$baseName ×${item.count}" else baseName

            bar.child(
                create(
                    category = cat,
                    name = displayName,
                    categoryColorRgb = 0xC084FC,
                    bgArgb = 0xCC3B235A.toInt(),
                    borderArgb = 0xFFA855F7.toInt(),
                    icon = item.iconStack ?: ItemStack(Items.CHEST),
                    onRemove = if (removable) {
                        {
                            ctx.item = null
                            onChanged?.invoke()
                        }
                    } else null,
                )
            )
        }

        ctx.screenshot?.let { shot ->
            val cat = Component.translatable("screen.agyassistant.cat.screenshot").string
            bar.child(
                create(
                    category = cat,
                    name = "${shot.width}×${shot.height}",
                    categoryColorRgb = 0x4ADE80,
                    bgArgb = 0xCC14532D.toInt(),
                    borderArgb = 0xFF22C55E.toInt(),
                    icon = ItemStack(Items.PAINTING),
                    onRemove = if (removable) {
                        {
                            ctx.screenshot = null
                            onChanged?.invoke()
                        }
                    } else null,
                )
            )
        }

        ctx.quote?.let { q ->
            val oneLine = q.text.replace(Regex("\\s+"), " ").trim()
            val snippet = if (oneLine.length > 34) oneLine.take(34) + "…" else oneLine
            bar.child(
                create(
                    category = "↩ ${q.author}:",
                    name = "\"$snippet\"",
                    categoryColorRgb = 0x93C5FD,
                    bgArgb = 0xCC1E293B.toInt(),
                    borderArgb = 0xFF64748B.toInt(),
                    icon = ItemStack(Items.WRITABLE_BOOK),
                    onRemove = if (removable) {
                        {
                            ctx.quote = null
                            onChanged?.invoke()
                        }
                    } else null,
                )
            )
        }
    }

    private fun cleanDisplayName(displayName: String?, rawId: String): String {
        if (!displayName.isNullOrBlank()) return displayName
        return rawId.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
