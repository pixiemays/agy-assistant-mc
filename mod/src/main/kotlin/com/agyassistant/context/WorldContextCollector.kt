package com.agyassistant.context

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

object WorldContextCollector {
    fun collectTarget(): TargetInfo? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val hit = mc.hitResult ?: return null

        return when (hit.type) {
            HitResult.Type.BLOCK -> {
                val blockHit = hit as BlockHitResult
                val pos = blockHit.blockPos
                val state = level.getBlockState(pos)
                val key = BuiltInRegistries.BLOCK.getKey(state.block)

                val props = state.values.entries.associate { (prop, value) ->
                    prop.name to value.toString()
                }
                val beNbt = runCatching {
                    level.getBlockEntity(pos)
                        ?.saveWithoutMetadata(level.registryAccess())
                        ?.toString()
                        ?.take(1500)
                }.getOrNull()

                val tags = state.tags
                    .limit(20)
                    .map { it.location.toString() }
                    .toList()

                val blockItem = state.block.asItem()
                val icon = if (blockItem != Items.AIR) ItemStack(blockItem) else ItemStack(Items.GRASS_BLOCK)

                TargetInfo(
                    kind = "block",
                    id = key.toString(),
                    modId = key.namespace,
                    displayName = state.block.name.string,
                    pos = listOf(pos.x, pos.y, pos.z),
                    blockState = props,
                    blockEntityNbt = beNbt,
                    extraTags = tags,
                    iconStack = icon,
                )
            }

            HitResult.Type.ENTITY -> {
                val entityHit = hit as EntityHitResult
                val entity = entityHit.entity
                val key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
                val living = entity as? LivingEntity

                val extraInfo = entity.tags.take(10).toMutableList()
                val icon: ItemStack = if (entity is Player) {
                    val head = ItemStack(Items.PLAYER_HEAD)
                    runCatching {
                        head.set(DataComponents.PROFILE, ResolvableProfile(entity.gameProfile))
                    }
                    collectPlayerExtraInfo(mc, entity, extraInfo)
                    head
                } else {
                    val egg = SpawnEggItem.byId(entity.type)
                    if (egg != null) ItemStack(egg) else ItemStack(Items.NAME_TAG)
                }

                TargetInfo(
                    kind = "entity",
                    id = key.toString(),
                    modId = key.namespace,
                    displayName = entity.displayName?.string ?: entity.name.string,
                    pos = listOf(entity.blockX, entity.blockY, entity.blockZ),
                    entityHealth = living?.health,
                    entityMaxHealth = living?.maxHealth,
                    extraTags = extraInfo.take(20),
                    iconStack = icon,
                )
            }

            else -> null
        }
    }

    private fun collectPlayerExtraInfo(mc: Minecraft, player: Player, out: MutableList<String>) {
        out.add("username=${player.gameProfile.name}")
        if (!player.mainHandItem.isEmpty) {
            val id = BuiltInRegistries.ITEM.getKey(player.mainHandItem.item)
            out.add("main_hand=$id (${player.mainHandItem.hoverName.string})")
        }
        if (!player.offhandItem.isEmpty) {
            val id = BuiltInRegistries.ITEM.getKey(player.offhandItem.item)
            out.add("off_hand=$id")
        }
        val armorPieces = player.armorSlots
            .filter { !it.isEmpty }
            .map { BuiltInRegistries.ITEM.getKey(it.item).toString() }
        if (armorPieces.isNotEmpty()) {
            out.add("armor=${armorPieces.joinToString(", ")}")
        }
        if (player.armorValue > 0) {
            out.add("armor_points=${player.armorValue}")
        }
        val info = mc.connection?.getPlayerInfo(player.uuid)
        if (info != null) {
            out.add("gamemode=${info.gameMode.getName()}")
            out.add("ping=${info.latency}ms")
        }
    }
}
