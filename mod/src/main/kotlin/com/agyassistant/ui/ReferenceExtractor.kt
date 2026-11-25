package com.agyassistant.ui

import com.agyassistant.context.EmiCompat
import com.agyassistant.context.ItemInfo
import com.agyassistant.context.ItemStackSerializer
import com.agyassistant.context.JeiCompat
import com.agyassistant.context.TargetInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    /** Set when the model wrote a slightly wrong ID and we substituted the closest real item */
    val resolvedFrom: String? = null,
)

object ReferenceExtractor {
    const val CLICK_PREFIX = "agyref://"
    private val NULL_SENTINEL = Any()

    private val rawCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val locCache = java.util.concurrent.ConcurrentHashMap<ResourceLocation, Any>()
    private val similarCache = java.util.concurrent.ConcurrentHashMap<ResourceLocation, Any>()

    @Volatile
    private var nameIndex: Map<String, ResourceLocation>? = null
    private val indexBuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    fun warmUpNameIndexAsync() {
        if (nameIndex != null || !indexBuilding.compareAndSet(false, true)) return
        val thread = Thread({
            try {
                val map = HashMap<String, ResourceLocation>(4096)
                for (loc in BuiltInRegistries.ITEM.keySet()) {
                    val item = BuiltInRegistries.ITEM.get(loc)
                    if (item == Items.AIR) continue
                    val pathName = loc.path.replace('_', ' ').lowercase()
                    map.putIfAbsent(pathName, loc)
                    val display = runCatching { item.description.string.trim().lowercase() }.getOrNull()
                    if (!display.isNullOrBlank()) {
                        map.putIfAbsent(display, loc)
                    }
                }
                nameIndex = map
                rawCache.clear()
            } catch (_: Throwable) {
                indexBuilding.set(false)
            }
        }, "agy-ref-index")
        thread.isDaemon = true
        thread.start()
    }

    fun resolve(raw: String, allowNameLookup: Boolean = false): GameReference? {
        val cleaned = raw.trim().trimEnd('.', ',', ')', ']', ';')
        if (cleaned.isEmpty()) return null
        val cacheKey = if (allowNameLookup) "n:$cleaned" else "i:$cleaned"
        rawCache[cacheKey]?.let { return if (it === NULL_SENTINEL) null else it as GameReference }

        val resolved = if (cleaned.contains(':')) {
            val loc = ResourceLocation.tryParse(cleaned)
            if (loc != null) resolve(loc) else null
        } else if (allowNameLookup && cleaned.length in 3..48) {
            val idx = nameIndex
            if (idx == null) {
                warmUpNameIndexAsync()
                return null
            }
            val loc = idx[cleaned.lowercase()]
            if (loc != null) resolve(loc) else null
        } else {
            null
        }
        rawCache[cacheKey] = resolved ?: NULL_SENTINEL
        return resolved
    }

    fun resolve(loc: ResourceLocation): GameReference? {
        locCache[loc]?.let { return if (it === NULL_SENTINEL) null else it as GameReference }
        val resolved = resolveUncached(loc)
        locCache[loc] = resolved ?: NULL_SENTINEL
        return resolved
    }

    private fun resolveUncached(loc: ResourceLocation): GameReference? {
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

    @Volatile
    private var itemNamespaceCache: Set<String>? = null

    private fun itemNamespaces(): Set<String> {
        itemNamespaceCache?.let { return it }
        val set = BuiltInRegistries.ITEM.keySet().map { it.namespace }.toHashSet()
        itemNamespaceCache = set
        return set
    }

    /**
     * Best-effort nearest registered item for an ID the model got slightly wrong —
     * typos in the path (e.g. `ars_nouveau:spell_book` -> `ars_nouveau:novice_spell_book`,
     * `create:engineer_manual` -> `create:engineers_manual`) and even in the namespace
     * itself (e.g. `ars_noveau:spellbook` -> `ars_nouveau:novice_spell_book`).
     */
    fun findSimilar(loc: ResourceLocation): ResourceLocation? {
        similarCache[loc]?.let { return if (it === NULL_SENTINEL) null else it as ResourceLocation }
        val targetNs = loc.namespace.replace("_", "").lowercase()
        val targetPath = loc.path.replace("_", "").lowercase()
        val best: ResourceLocation? = if (targetPath.length < 4 || targetNs.isEmpty()) {
            null
        } else {
            // Accept the exact namespace plus namespaces within a couple of typos
            val nsTypoLimit = maxOf(2, targetNs.length / 4)
            val candidateNamespaces = HashSet<String>()
            for (ns in itemNamespaces()) {
                val nsNorm = ns.replace("_", "")
                val nsDist = if (nsNorm == targetNs) 0 else levenshtein(nsNorm, targetNs)
                if (nsDist in 0..nsTypoLimit) candidateNamespaces.add(ns)
            }
            if (candidateNamespaces.isEmpty()) {
                null
            } else {
                // Allow a few typos for longer names: 1-2 chars is plenty for short ids,
                // up to ~1/3 of the length for long ones
                val maxTypos = maxOf(2, targetPath.length / 3)
                var bestLoc: ResourceLocation? = null
                var bestScore = Int.MAX_VALUE
                for (candidate in BuiltInRegistries.ITEM.keySet()) {
                    if (candidate.namespace !in candidateNamespaces) continue
                    val cNorm = candidate.path.replace("_", "")
                    val pathScore = when {
                        cNorm == targetPath -> 0
                        cNorm.contains(targetPath) || targetPath.contains(cNorm) ->
                            kotlin.math.abs(cNorm.length - targetPath.length)
                        else -> {
                            val d = levenshtein(targetPath, cNorm)
                            if (d <= maxTypos) d else continue
                        }
                    }
                    val nsScore = if (candidate.namespace == loc.namespace) 0 else 1
                    val score = nsScore + pathScore
                    if (score < bestScore) {
                        bestScore = score
                        bestLoc = candidate
                    }
                }
                bestLoc
            }
        }
        similarCache[loc] = best ?: NULL_SENTINEL
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    fun hasRecipeViewer(): Boolean =
        ModList.get().isLoaded("jei") || ModList.get().isLoaded("emi")

    fun buildTooltip(ref: GameReference): List<Component> {
        val mc = Minecraft.getInstance()
        val lines = mutableListOf<Component>()

        if (ref.resolvedFrom != null) {
            lines.add(
                Component.translatable("screen.agyassistant.ref.similar_note", ref.resolvedFrom)
                    .withStyle(Style.EMPTY.withColor(0xFBBF24))
            )
        }

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
        if (button == 2) {
            val url = buildWikiUrl(ref)
            val screen = Minecraft.getInstance().screen
            if (screen != null) {
                ConfirmLinkScreen.confirmLinkNow(screen, url)
            } else {
                Minecraft.getInstance().keyboardHandler.clipboard = url
            }
            return true
        }
        return false
    }

    fun buildWikiUrl(ref: GameReference): String {
        val ns = ref.id.namespace
        val pathWords = ref.id.path.replace('_', ' ')
        fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

        if (ns == "minecraft") {
            val lang = runCatching { Minecraft.getInstance().languageManager.selected }.getOrDefault("en_us")
            return if (lang.startsWith("ru", ignoreCase = true)) {
                "https://ru.minecraft.wiki/w/Special:Search?search=${enc(ref.displayName)}&go=Go"
            } else {
                "https://minecraft.wiki/w/Special:Search?search=${enc(pathWords)}&go=Go"
            }
        }
        return when (ns) {
            "create" -> "https://create.fandom.com/wiki/Special:Search?query=${enc(pathWords)}"
            "mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions" ->
                "https://wiki.aidancbrady.com/wiki/Special:Search?search=${enc(pathWords)}&go=Go"
            else -> {
                val modName = ModList.get().getModContainerById(ns)
                    .map { it.modInfo.displayName }
                    .orElse(ns.replace('_', ' '))
                "https://www.google.com/search?q=${enc("$modName $pathWords minecraft wiki")}"
            }
        }
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
