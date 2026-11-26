package com.agyassistant.network

import com.agyassistant.AgyAssistantMod
import com.agyassistant.config.ModConfig
import com.agyassistant.ui.AssistantNotifications
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks GitHub Releases for newer versions of agyassistant and stages the update in mods/.
 * On Windows, the running mod jar is locked by the JVM, so a hidden PowerShell shutdown hook
 * swaps the staged .pending jar in place right after the game process exits.
 */
object GitHubUpdater {
    private const val REPO = "pixiemays/agy-assistant-mc"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"

    private val logger = LoggerFactory.getLogger("agyassistant.updater")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val inProgress = AtomicBoolean(false)
    private val pendingVersion = AtomicReference<String?>(null)
    private val shutdownHookRegistered = AtomicBoolean(false)
    private val pendingSwapSpec = AtomicReference<SwapSpec?>(null)

    private data class SwapSpec(
        val oldJar: Path?,
        val pendingJar: Path?,
        val targetJar: Path?,
        val pendingBridge: Path?,
        val targetBridge: Path?,
    )

    sealed class UpdateResult {
        data class UpToDate(val version: String) : UpdateResult()
        data class Downloaded(val version: String, val restartRequired: Boolean) : UpdateResult()
        data class AvailableDev(val version: String, val htmlUrl: String) : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    fun isManagedByLoader(): Boolean =
        System.getProperty("agyassistant.loader.active") == "true"

    fun currentVersion(): String =
        ModList.get().getModContainerById(AgyAssistantMod.MOD_ID)
            .map { it.modInfo.version.toString() }
            .orElse("0.0.0")

    fun cleanupStalePending() {
        runCatching {
            val modsDir = FMLPaths.MODSDIR.get()
            if (Files.isDirectory(modsDir)) {
                Files.list(modsDir).use { stream ->
                    stream.filter { p ->
                        val name = p.fileName.toString()
                        name.startsWith("agyassistant-") && (name.endsWith(".jar.part") || name.endsWith(".jar.pending"))
                    }.forEach { Files.deleteIfExists(it) }
                }
            }
            val cfgPart = ModConfig.configDir.resolve("agy-bridge.exe.part")
            val cfgPending = ModConfig.configDir.resolve("agy-bridge.exe.pending")
            Files.deleteIfExists(cfgPart)
            Files.deleteIfExists(cfgPending)
        }.onFailure {
            logger.debug("Cleanup of stale update files failed", it)
        }
    }

    fun checkAndDownloadAsync(manual: Boolean = false): CompletableFuture<UpdateResult> {
        pendingVersion.get()?.let { readyVer ->
            return CompletableFuture.completedFuture(UpdateResult.Downloaded(readyVer, restartRequired = true))
        }

        if (!inProgress.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(UpdateResult.Error("Update check already in progress"))
        }

        return CompletableFuture.supplyAsync {
            try {
                checkAndDownloadSync(manual)
            } catch (e: Exception) {
                logger.warn("GitHub update check failed: {}", e.message)
                UpdateResult.Error(e.message ?: "Unknown error")
            } finally {
                inProgress.set(false)
            }
        }
    }

    private fun checkAndDownloadSync(manual: Boolean): UpdateResult {
        val current = currentVersion()
        val req = HttpRequest.newBuilder()
            .uri(URI.create(LATEST_RELEASE_API))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "agy-assistant-mc/$current")
            .GET()
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            return UpdateResult.Error("GitHub HTTP ${resp.statusCode()}")
        }

        val json = JsonParser.parseString(resp.body()).asJsonObject
        val tagName = json.get("tag_name")?.asString?.trim().orEmpty()
        if (tagName.isBlank()) {
            return UpdateResult.Error("Empty tag_name in release")
        }

        val remoteVersion = tagName.removePrefix("v").removePrefix("V")
        if (!isNewerVersion(remoteVersion, current)) {
            logger.info("AGY Assistant is up to date (current={}, latest={})", current, tagName)
            return UpdateResult.UpToDate(current)
        }

        val htmlUrl = json.get("html_url")?.asString ?: "https://github.com/$REPO/releases/latest"
        val currentJar = resolveCurrentJarPath()
        if (currentJar == null) {
            logger.info("New version {} available (dev environment, skipping jar download)", tagName)
            return UpdateResult.AvailableDev(remoteVersion, htmlUrl)
        }

        val assets = json.getAsJsonArray("assets") ?: return UpdateResult.Error("No assets in release $tagName")
        val jarAsset = findAsset(assets) { name ->
            name.startsWith("agyassistant-") &&
                name.endsWith(".jar") &&
                !name.startsWith("agyassistant-loader") &&
                !name.endsWith("-sources.jar")
        } ?: return UpdateResult.Error("Mod jar asset not found in release $tagName")

        val jarName = jarAsset.get("name").asString
        val jarUrl = jarAsset.get("browser_download_url").asString
        val jarSize = jarAsset.get("size")?.asLong ?: -1L

        val modsDir = FMLPaths.MODSDIR.get()
        val managedByLoader = isManagedByLoader()
        val targetDir = if (managedByLoader) ModConfig.configDir.resolve("updates") else modsDir
        Files.createDirectories(targetDir)

        val partJar = targetDir.resolve("$jarName.part")
        downloadFile(jarUrl, partJar, jarSize, current)

        // Optional: update agy-bridge.exe if the user already has it installed locally
        var pendingBridge: Path? = null
        var targetBridge: Path? = null
        if (isWindows) {
            val existingBridge = findInstalledBridgeExe(modsDir)
            if (existingBridge != null) {
                val bridgeAsset = findAsset(assets) { it.equals("agy-bridge.exe", ignoreCase = true) }
                if (bridgeAsset != null) {
                    val bUrl = bridgeAsset.get("browser_download_url").asString
                    val bSize = bridgeAsset.get("size")?.asLong ?: -1L
                    val bPart = existingBridge.parent.resolve("agy-bridge.exe.part")
                    runCatching {
                        downloadFile(bUrl, bPart, bSize, current)
                        val bPending = existingBridge.parent.resolve("agy-bridge.exe.pending")
                        Files.move(bPart, bPending, StandardCopyOption.REPLACE_EXISTING)
                        pendingBridge = bPending
                        targetBridge = existingBridge
                    }.onFailure {
                        Files.deleteIfExists(bPart)
                        logger.warn("Failed to stage agy-bridge.exe update: {}", it.message)
                    }
                }
            }
        }

        val targetJar = targetDir.resolve(jarName)
        if (managedByLoader) {
            // Loader picks the highest version jar in config/agyassistant/updates/ on next launch
            Files.move(partJar, targetJar, StandardCopyOption.REPLACE_EXISTING)
            if (pendingBridge != null && targetBridge != null) {
                pendingSwapSpec.set(
                    SwapSpec(
                        oldJar = null,
                        pendingJar = null,
                        targetJar = null,
                        pendingBridge = pendingBridge,
                        targetBridge = targetBridge,
                    )
                )
                registerWindowsSwapHook()
            }
        } else if (!isWindows) {
            Files.deleteIfExists(currentJar)
            Files.move(partJar, targetJar, StandardCopyOption.REPLACE_EXISTING)
        } else {
            val pendingJar = targetDir.resolve("$jarName.pending")
            Files.move(partJar, pendingJar, StandardCopyOption.REPLACE_EXISTING)
            pendingSwapSpec.set(
                SwapSpec(
                    oldJar = currentJar,
                    pendingJar = pendingJar,
                    targetJar = targetJar,
                    pendingBridge = pendingBridge,
                    targetBridge = targetBridge,
                )
            )
            registerWindowsSwapHook()
        }

        pendingVersion.set(remoteVersion)
        logger.info("Staged AGY Assistant update {} (current={})", remoteVersion, current)

        if (!manual) {
            Minecraft.getInstance().execute {
                AssistantNotifications.showUpdate(remoteVersion)
            }
        }

        return UpdateResult.Downloaded(remoteVersion, restartRequired = true)
    }

    private fun resolveCurrentJarPath(): Path? {
        val modFile = ModList.get().getModFileById(AgyAssistantMod.MOD_ID)?.file?.filePath ?: return null
        if (!Files.isRegularFile(modFile)) return null
        if (!modFile.fileName.toString().endsWith(".jar", ignoreCase = true)) return null
        return modFile
    }

    private fun findInstalledBridgeExe(modsDir: Path): Path? {
        val cfgExe = ModConfig.configDir.resolve("agy-bridge.exe")
        if (Files.isRegularFile(cfgExe)) return cfgExe
        val modsExe = modsDir.resolve("agy-bridge.exe")
        if (Files.isRegularFile(modsExe)) return modsExe
        return null
    }

    private fun findAsset(assets: com.google.gson.JsonArray, predicate: (String) -> Boolean): JsonObject? {
        for (el in assets) {
            val obj = el.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            if (predicate(name)) return obj
        }
        return null
    }

    private fun downloadFile(url: String, dest: Path, expectedSize: Long, currentVersion: String) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("User-Agent", "agy-assistant-mc/$currentVersion")
            .GET()
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
        if (resp.statusCode() !in 200..299) {
            Files.deleteIfExists(dest)
            throw IllegalStateException("Download HTTP ${resp.statusCode()}")
        }
        val actualSize = Files.size(dest)
        if (actualSize <= 0 || (expectedSize > 0 && actualSize != expectedSize)) {
            Files.deleteIfExists(dest)
            throw IllegalStateException("Downloaded file size mismatch ($actualSize vs $expectedSize)")
        }
    }

    private fun registerWindowsSwapHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) return

        val jvmPid = ProcessHandle.current().pid()
        Runtime.getRuntime().addShutdownHook(Thread({
            val spec = pendingSwapSpec.get() ?: return@Thread
            try {
                fun psQuote(p: Path): String = "'" + p.toAbsolutePath().toString().replace("'", "''") + "'"
                val script = buildString {
                    appendLine("Wait-Process -Id $jvmPid -ErrorAction SilentlyContinue")
                    appendLine("Start-Sleep -Milliseconds 500")
                    if (spec.oldJar != null) {
                        appendLine("Remove-Item -LiteralPath ${psQuote(spec.oldJar)} -Force -ErrorAction SilentlyContinue")
                    }
                    if (spec.pendingJar != null && spec.targetJar != null) {
                        appendLine("Move-Item -LiteralPath ${psQuote(spec.pendingJar)} -Destination ${psQuote(spec.targetJar)} -Force -ErrorAction SilentlyContinue")
                    }
                    if (spec.pendingBridge != null && spec.targetBridge != null) {
                        appendLine("Move-Item -LiteralPath ${psQuote(spec.pendingBridge)} -Destination ${psQuote(spec.targetBridge)} -Force -ErrorAction SilentlyContinue")
                    }
                }
                val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
                ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded,
                ).start()
            } catch (e: Exception) {
                logger.warn("Failed to launch Windows update swap helper: {}", e.message)
            }
        }, "agy-updater-swap"))
    }

    internal fun isNewerVersion(remote: String, current: String): Boolean {
        fun parseParts(v: String): List<Int> =
            v.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .split('.')
                .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        val rParts = parseParts(remote)
        val cParts = parseParts(current)
        val len = maxOf(rParts.size, cParts.size)
        for (i in 0 until len) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
