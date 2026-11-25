package com.agyassistant.ui

import com.mojang.blaze3d.platform.NativeImage
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

data class ExtractedLink(
    val label: String?,
    val url: String,
)

data class LinkMetadata(
    val siteName: String,
    val title: String,
    val description: String?,
    val imageUrl: String?,
)

object LinkPreviewComponent {
    private val MD_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    private val BARE_URL_REGEX = Regex("(?<!\\()\\b(https?://[^\\s)\\]>]+)")

    private val YT_VIDEO_REGEX = Regex("""(?:youtube\.com/watch\?[^#\s]*v=|youtu\.be/|youtube\.com/shorts/)([A-Za-z0-9_-]{11})""")
    private val OG_TITLE_REGEX = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:title|twitter:title)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_DESC_REGEX = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:description|description)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_IMAGE_REGEX = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val HTML_TITLE_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

    private val metaCache = ConcurrentHashMap<String, LinkMetadata>()
    private val thumbCache = ConcurrentHashMap<String, ResourceLocation>()
    private val thumbCounter = AtomicInteger(0)

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun extractLinks(text: String, maxCount: Int = 2): List<ExtractedLink> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<ExtractedLink>()

        for (m in MD_LINK_REGEX.findAll(text)) {
            if (out.size >= maxCount) break
            val url = m.groupValues[2].trimEnd('.', ',', ';')
            if (seen.add(url)) {
                out.add(ExtractedLink(label = m.groupValues[1].trim(), url = url))
            }
        }
        for (m in BARE_URL_REGEX.findAll(text)) {
            if (out.size >= maxCount) break
            val url = m.groupValues[1].trimEnd('.', ',', ';')
            if (seen.add(url)) {
                out.add(ExtractedLink(label = null, url = url))
            }
        }
        return out
    }

    private fun cardSurface(bgArgb: Int, borderArgb: Int, accentArgb: Int): Surface =
        Surface { context, component ->
            val x = component.x()
            val y = component.y()
            val w = component.width()
            val h = component.height()
            context.fill(x, y, x + w, y + h, bgArgb)
            context.drawRectOutline(x, y, w, h, borderArgb)
            context.fill(x, y, x + 3, y + h, accentArgb)
        }

    fun createCard(link: ExtractedLink, onOpenUrl: (String) -> Unit): FlowLayout {
        val host = extractHost(link.url)
        val (accentColor, siteBadge, iconName) = siteStyle(host)

        val outer = Containers.horizontalFlow(Sizing.fill(66), Sizing.content())
        val normalBg = cardSurface(0xCC0B1120.toInt(), 0xFF334155.toInt(), accentColor)
        val hoverBg = cardSurface(0xEE131C31.toInt(), accentColor, accentColor)
        outer.surface(normalBg)
        outer.padding(Insets.of(3, 3, 6, 4))
        outer.gap(5)
        outer.verticalAlignment(VerticalAlignment.CENTER)
        outer.cursorStyle(CursorStyle.HAND)
        outer.margins(Insets.top(2))

        val body = Containers.verticalFlow(Sizing.expand(100), Sizing.content())
        body.gap(1)

        val ytThumb = youtubeThumbUrl(link.url)
        val initialMeta = metaCache[link.url] ?: LinkMetadata(
            siteName = siteBadge,
            title = (link.label ?: host).take(46),
            description = prettyUrlPreview(link.url),
            imageUrl = ytThumb,
        )

        val siteRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
        siteRow.verticalAlignment(VerticalAlignment.CENTER)
        siteRow.gap(3)

        val texLoc = ResourceLocation.fromNamespaceAndPath("agyassistant", "textures/gui/$iconName.png")
        val iconComp = Components.texture(texLoc, 0, 0, 16, 16, 16, 16)
        iconComp.sizing(Sizing.fixed(9), Sizing.fixed(9))
        siteRow.child(iconComp)

        val siteLbl = ScaledLabelComponent(
            text = Component.literal("${initialMeta.siteName} ↗"),
            scale = 0.70f,
            defaultColor = accentColor and 0xFFFFFF,
            shadow = false,
        )
        siteRow.child(siteLbl)

        val titleLbl = ScaledLabelComponent(
            text = Component.literal(initialMeta.title),
            scale = 0.80f,
            defaultColor = 0x60A5FA,
            shadow = false,
        )
        titleLbl.horizontalSizing(Sizing.fill(100))

        val descLbl = ScaledLabelComponent(
            text = Component.literal(initialMeta.description ?: ""),
            scale = 0.72f,
            defaultColor = 0x94A3B8,
            shadow = false,
        )
        descLbl.horizontalSizing(Sizing.fill(100))

        body.child(siteRow)
        body.child(titleLbl)
        if (!initialMeta.description.isNullOrBlank()) {
            body.child(descLbl)
        }
        outer.child(body)

        // Optional thumbnail slot on the right (56x32)
        val thumbSlot = Containers.horizontalFlow(Sizing.content(), Sizing.content())
        outer.child(thumbSlot)

        fun attachThumbTexture(tex: ResourceLocation) {
            thumbSlot.clearChildren()
            val img = Components.texture(tex, 0, 0, 56, 32, 56, 32)
            img.sizing(Sizing.fixed(56), Sizing.fixed(32))
            thumbSlot.child(img)
        }

        val initialImgUrl = initialMeta.imageUrl
        if (initialImgUrl != null) {
            val cachedTex = thumbCache[initialImgUrl]
            if (cachedTex != null) {
                attachThumbTexture(cachedTex)
            } else {
                loadThumbAsync(initialImgUrl).thenAccept { tex ->
                    if (tex != null) {
                        Minecraft.getInstance().execute { attachThumbTexture(tex) }
                    }
                }
            }
        }

        outer.tooltip(
            listOf(
                Component.translatable("screen.agyassistant.link.open_browser"),
                Component.literal(link.url).withStyle(Style.EMPTY.withColor(0x94A3B8)),
            )
        )
        outer.mouseEnter().subscribe { outer.surface(hoverBg) }
        outer.mouseLeave().subscribe { outer.surface(normalBg) }
        outer.mouseDown().subscribe { _, _, button ->
            if (button == 0) {
                onOpenUrl(link.url)
                true
            } else false
        }

        // Async OpenGraph metadata fetch
        if (!metaCache.containsKey(link.url)) {
            fetchMetadataAsync(link, siteBadge, ytThumb).thenAccept { meta ->
                if (meta != null) {
                    Minecraft.getInstance().execute {
                        titleLbl.text(Component.literal(meta.title))
                        if (!meta.description.isNullOrBlank()) {
                            descLbl.text(Component.literal(meta.description))
                        }
                    }
                    val imgUrl = meta.imageUrl
                    if (imgUrl != null && imgUrl != initialImgUrl) {
                        val existing = thumbCache[imgUrl]
                        if (existing != null) {
                            Minecraft.getInstance().execute { attachThumbTexture(existing) }
                        } else {
                            loadThumbAsync(imgUrl).thenAccept { tex ->
                                if (tex != null) {
                                    Minecraft.getInstance().execute { attachThumbTexture(tex) }
                                }
                            }
                        }
                    }
                }
            }
        }

        return outer
    }

    private fun youtubeThumbUrl(url: String): String? {
        val m = YT_VIDEO_REGEX.find(url) ?: return null
        val vid = m.groupValues[1]
        return "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
    }

    private fun fetchMetadataAsync(
        link: ExtractedLink,
        fallbackSite: String,
        ytThumb: String?,
    ): CompletableFuture<LinkMetadata?> {
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(link.url))
                .header("User-Agent", "Mozilla/5.0 (compatible; AGYAssistantMC/0.1)")
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()

            client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply { resp ->
                    if (resp.statusCode() !in 200..299) return@thenApply null
                    val html = resp.body().take(28_000)
                    val title = decodeHtml(
                        OG_TITLE_REGEX.find(html)?.groupValues?.get(1)
                            ?: HTML_TITLE_REGEX.find(html)?.groupValues?.get(1)
                            ?: link.label
                            ?: fallbackSite
                    ).take(54)
                    val desc = OG_DESC_REGEX.find(html)?.groupValues?.get(1)
                        ?.let { decodeHtml(it).take(84) }
                        ?: prettyUrlPreview(link.url)
                    val ogImg = ytThumb ?: OG_IMAGE_REGEX.find(html)?.groupValues?.get(1)
                        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    val meta = LinkMetadata(fallbackSite, title, desc, ogImg)
                    metaCache[link.url] = meta
                    meta
                }
                .exceptionally { null }
        }.getOrElse { CompletableFuture.completedFuture(null) }
    }

    private fun loadThumbAsync(imageUrl: String): CompletableFuture<ResourceLocation?> {
        thumbCache[imageUrl]?.let { return CompletableFuture.completedFuture(it) }
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .header("User-Agent", "Mozilla/5.0 (compatible; AGYAssistantMC/0.1)")
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build()

            client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenCompose { resp ->
                    if (resp.statusCode() !in 200..299) {
                        return@thenCompose CompletableFuture.completedFuture<ResourceLocation?>(null)
                    }
                    val src = runCatching { ImageIO.read(ByteArrayInputStream(resp.body())) }.getOrNull()
                        ?: return@thenCompose CompletableFuture.completedFuture<ResourceLocation?>(null)

                    val tw = 56
                    val th = 32
                    val scaled = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
                    val g2 = scaled.createGraphics()
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g2.drawImage(src, 0, 0, tw, th, null)
                    g2.dispose()

                    val nativeImg = NativeImage(tw, th, false)
                    for (py in 0 until th) {
                        for (px in 0 until tw) {
                            val argb = scaled.getRGB(px, py)
                            val a = (argb ushr 24) and 0xFF
                            val r = (argb ushr 16) and 0xFF
                            val g = (argb ushr 8) and 0xFF
                            val b = argb and 0xFF
                            val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                            nativeImg.setPixelRGBA(px, py, abgr)
                        }
                    }

                    val resultFuture = CompletableFuture<ResourceLocation?>()
                    Minecraft.getInstance().execute {
                        try {
                            val loc = ResourceLocation.fromNamespaceAndPath(
                                "agyassistant",
                                "dynamic/link_thumb_${thumbCounter.incrementAndGet()}",
                            )
                            val dyn = DynamicTexture(nativeImg)
                            Minecraft.getInstance().textureManager.register(loc, dyn)
                            thumbCache[imageUrl] = loc
                            resultFuture.complete(loc)
                        } catch (_: Exception) {
                            nativeImg.close()
                            resultFuture.complete(null)
                        }
                    }
                    resultFuture
                }
                .exceptionally { null }
        }.getOrElse { CompletableFuture.completedFuture(null) }
    }

    private fun extractHost(url: String): String =
        runCatching { URI.create(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)

    private fun prettyUrlPreview(url: String): String {
        val clean = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return if (clean.length > 48) clean.take(48) + "…" else clean
    }

    private fun siteStyle(host: String): Triple<Int, String, String> = when {
        host.contains("youtube.com") || host.contains("youtu.be") -> Triple(0xFFEF4444.toInt(), "YouTube", "site_youtube")
        host.contains("minecraft.wiki") -> Triple(0xFF22C55E.toInt(), "Minecraft Wiki", "site_wiki")
        host.contains("createmod.net") || host.contains("create.fandom") || host.contains("wiki") -> Triple(0xFF22C55E.toInt(), host, "site_wiki")
        host.contains("github.com") || host.contains("github.io") -> Triple(0xFFA855F7.toInt(), "GitHub", "site_github")
        host.contains("modrinth.com") -> Triple(0xFF10B981.toInt(), "Modrinth", "site_modrinth")
        host.contains("curseforge.com") -> Triple(0xFFF97316.toInt(), "CurseForge", "site_curseforge")
        else -> Triple(0xFF3B82F6.toInt(), host, "site_link")
    }

    private fun decodeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}
