package com.agyassistant.ui

import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class ExtractedLink(
    val label: String?,
    val url: String,
)

data class LinkMetadata(
    val siteName: String,
    val title: String,
    val description: String?,
)

object LinkPreviewComponent {
    private val MD_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    private val BARE_URL_REGEX = Regex("(?<!\\()\\b(https?://[^\\s)\\]>]+)")

    private val OG_TITLE_REGEX = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:title|twitter:title)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_DESC_REGEX = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:description|description)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val HTML_TITLE_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

    private val cache = ConcurrentHashMap<String, LinkMetadata>()
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

    fun createCard(link: ExtractedLink, onOpenUrl: (String) -> Unit): FlowLayout {
        val host = extractHost(link.url)
        val (accentColor, siteBadge, iconName) = siteStyle(host)

        val outer = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
        val normalBg = Surface.flat(0xCC0B1120.toInt()).and(Surface.outline(0xFF334155.toInt()))
        val hoverBg = Surface.flat(0xEE131C31.toInt()).and(Surface.outline(accentColor))
        outer.surface(normalBg)
        outer.cursorStyle(CursorStyle.HAND)
        outer.margins(Insets.top(2))

        // Discord-style colored left accent bar
        val bar = Containers.verticalFlow(Sizing.fixed(3), Sizing.fill(100))
        bar.surface(Surface.flat(accentColor))
        outer.child(bar)

        val body = Containers.verticalFlow(Sizing.expand(100), Sizing.content())
        body.padding(Insets.of(4, 4, 6, 6))
        body.gap(2)

        val initialMeta = cache[link.url] ?: LinkMetadata(
            siteName = siteBadge,
            title = link.label ?: host,
            description = prettyUrlPreview(link.url),
        )

        val siteRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
        siteRow.verticalAlignment(VerticalAlignment.CENTER)
        siteRow.gap(4)

        val texLoc = ResourceLocation.fromNamespaceAndPath("agyassistant", "textures/gui/$iconName.png")
        val iconComp = Components.texture(texLoc, 0, 0, 16, 16, 16, 16)
        iconComp.sizing(Sizing.fixed(10), Sizing.fixed(10))
        siteRow.child(iconComp)

        val siteLbl = ScaledLabelComponent(
            text = Component.literal("${initialMeta.siteName} ↗"),
            scale = 0.72f,
            defaultColor = accentColor and 0xFFFFFF,
            shadow = false,
        )
        siteRow.child(siteLbl)

        val titleLbl = ScaledLabelComponent(
            text = Component.literal(initialMeta.title),
            scale = 0.84f,
            defaultColor = 0x60A5FA,
            shadow = false,
        )
        titleLbl.horizontalSizing(Sizing.fill(100))

        val descLbl = ScaledLabelComponent(
            text = Component.literal(initialMeta.description ?: ""),
            scale = 0.75f,
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

        outer.tooltip(
            listOf(
                Component.translatable("screen.agyassistant.link.open_browser"),
                Component.literal(link.url).withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0x94A3B8)),
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

        // Async OpenGraph fetch if not yet cached
        if (!cache.containsKey(link.url)) {
            fetchMetadataAsync(link, siteBadge).thenAccept { meta ->
                if (meta != null) {
                    Minecraft.getInstance().execute {
                        titleLbl.text(Component.literal(meta.title))
                        if (!meta.description.isNullOrBlank()) {
                            descLbl.text(Component.literal(meta.description))
                        }
                    }
                }
            }
        }

        return outer
    }

    private fun fetchMetadataAsync(link: ExtractedLink, fallbackSite: String): CompletableFuture<LinkMetadata?> {
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
                    ).take(80)
                    val desc = OG_DESC_REGEX.find(html)?.groupValues?.get(1)
                        ?.let { decodeHtml(it).take(140) }
                        ?: prettyUrlPreview(link.url)
                    val meta = LinkMetadata(fallbackSite, title, desc)
                    cache[link.url] = meta
                    meta
                }
                .exceptionally { null }
        }.getOrElse { CompletableFuture.completedFuture(null) }
    }

    private fun extractHost(url: String): String =
        runCatching { URI.create(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)

    private fun prettyUrlPreview(url: String): String {
        val clean = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return if (clean.length > 68) clean.take(68) + "…" else clean
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
