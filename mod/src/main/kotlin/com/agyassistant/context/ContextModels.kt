package com.agyassistant.context

import com.google.gson.annotations.SerializedName
import net.minecraft.world.item.ItemStack

data class TargetInfo(
    val kind: String,
    val id: String,
    @SerializedName("mod_id") val modId: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    val pos: List<Int>? = null,
    @SerializedName("block_state") val blockState: Map<String, String> = emptyMap(),
    @SerializedName("block_entity_nbt") val blockEntityNbt: String? = null,
    @SerializedName("entity_health") val entityHealth: Float? = null,
    @SerializedName("entity_max_health") val entityMaxHealth: Float? = null,
    @SerializedName("extra_tags") val extraTags: List<String> = emptyList(),
    @Transient val iconStack: ItemStack? = null,
)

data class ItemInfo(
    val id: String,
    @SerializedName("mod_id") val modId: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    val count: Int = 1,
    val source: String = "inventory",
    val components: Map<String, String> = emptyMap(),
    val tooltip: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @Transient val iconStack: ItemStack? = null,
)

data class ScreenshotInfo(
    val base64: String,
    val mimeType: String = "image/jpeg",
    val width: Int,
    val height: Int,
)

data class QuoteInfo(
    val author: String,
    val text: String,
)

data class AttachedContext(
    var target: TargetInfo? = null,
    var item: ItemInfo? = null,
    var screenshot: ScreenshotInfo? = null,
    @Transient var quote: QuoteInfo? = null,
) {
    fun isEmpty(): Boolean = target == null && item == null && screenshot == null && quote == null
}
