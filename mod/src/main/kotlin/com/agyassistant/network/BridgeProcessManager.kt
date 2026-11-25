package com.agyassistant.network

import com.agyassistant.config.ModConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object BridgeProcessManager {
    private val logger = LoggerFactory.getLogger("agyassistant.bridge")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var shutdownHookRegistered = false

    fun startIfNeededAsync(): CompletableFuture<Boolean> {
        if (!ModConfig.data.autoStartBridge) {
            return BridgeHttpClient.checkHealth()
        }

        return BridgeHttpClient.checkHealth().thenApply { alreadyUp ->
            if (alreadyUp) {
                logger.info("agy-bridge is already running on {}:{}", ModConfig.data.bridgeHost, ModConfig.data.bridgePort)
                return@thenApply true
            }
            startProcessAndWait()
        }
    }

    @Synchronized
    private fun startProcessAndWait(): Boolean {
        val current = process
        if (current != null && current.isAlive) {
            return waitForHealth(10)
        }

        val cmdSpec = resolveCommand()
        if (cmdSpec == null) {
            logger.warn(
                "Could not find agy-bridge.exe in {} and no dev bridge/ folder found.",
                ModConfig.configDir
            )
            return false
        }

        val (command, workDir) = cmdSpec
        return try {
            Files.createDirectories(ModConfig.configDir)
            val logFile = ModConfig.configDir.resolve("bridge.log").toFile()

            val pb = ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

            logger.info("Launching agy-bridge: {} (cwd={})", command.joinToString(" "), workDir)
            process = pb.start()
            registerShutdownHook()
            waitForHealth(20)
        } catch (e: Exception) {
            logger.error("Failed to launch agy-bridge process", e)
            false
        }
    }

    private fun waitForHealth(attempts: Int): Boolean {
        for (i in 0 until attempts) {
            val alive = process?.isAlive ?: false
            if (!alive) {
                logger.warn("agy-bridge exited prematurely with code {}", process?.exitValue())
                return false
            }
            val ok = runCatching { BridgeHttpClient.checkHealth().get(2, TimeUnit.SECONDS) }.getOrDefault(false)
            if (ok) {
                logger.info("agy-bridge became healthy after {} checks", i + 1)
                return true
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun resolveCommand(): Pair<List<String>, Path>? {
        val cfg = ModConfig.data
        val jvmPid = ProcessHandle.current().pid().toString()
        val commonArgs = listOf("--host", cfg.bridgeHost, "--port", cfg.bridgePort.toString(), "--parent-pid", jvmPid)

        // 1. User-specified custom command
        if (cfg.customBridgeCommand.isNotBlank()) {
            val parts = cfg.customBridgeCommand.trim().split(Regex("\\s+"))
            return (parts + commonArgs) to ModConfig.configDir
        }

        // 2. Packaged agy-bridge.exe in config/agyassistant/
        val exeName = if (isWindows) "agy-bridge.exe" else "agy-bridge"
        val packagedExe = ModConfig.configDir.resolve(exeName)
        if (Files.isRegularFile(packagedExe)) {
            return (listOf(packagedExe.toAbsolutePath().toString()) + commonArgs) to ModConfig.configDir
        }

        // 3. Dev-mode fallback: locate bridge/pyproject.toml near working directory
        val devBridgeDir = findDevBridgeDir()
        if (devBridgeDir != null) {
            val venvPython = if (isWindows) {
                devBridgeDir.resolve(".venv/Scripts/python.exe")
            } else {
                devBridgeDir.resolve(".venv/bin/python")
            }
            if (Files.isRegularFile(venvPython)) {
                val cmd = listOf(venvPython.toAbsolutePath().toString(), "-m", "agy_bridge.main") + commonArgs
                return cmd to devBridgeDir
            }

            val uvExe = findUvExecutable()
            if (uvExe != null) {
                val cmd = listOf(uvExe, "run", "agy-bridge") + commonArgs
                return cmd to devBridgeDir
            }
        }

        return null
    }

    private fun findDevBridgeDir(): Path? {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val candidates = listOf(
            cwd.resolve("bridge"),
            cwd.parent?.resolve("bridge"),
            cwd.parent?.parent?.resolve("bridge"),
        )
        return candidates.firstOrNull { dir ->
            dir != null && Files.isRegularFile(dir.resolve("pyproject.toml"))
        }
    }

    private fun findUvExecutable(): String? {
        val homeUv = Paths.get(System.getProperty("user.home"), ".local", "bin", if (isWindows) "uv.exe" else "uv")
        if (Files.isRegularFile(homeUv)) return homeUv.toAbsolutePath().toString()
        val pathEnv = System.getenv("PATH") ?: return null
        val exeName = if (isWindows) "uv.exe" else "uv"
        for (dir in pathEnv.split(File.pathSeparator)) {
            val candidate = Paths.get(dir, exeName)
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().toString()
        }
        return null
    }

    private fun registerShutdownHook() {
        if (shutdownHookRegistered) return
        shutdownHookRegistered = true
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    @Synchronized
    fun stop() {
        val proc = process ?: return
        process = null
        if (proc.isAlive) {
            logger.info("Stopping agy-bridge process (pid={})", proc.pid())
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
    }
}
