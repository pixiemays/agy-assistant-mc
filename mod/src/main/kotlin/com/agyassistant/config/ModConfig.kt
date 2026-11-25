package com.agyassistant.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

data class ModConfigData(
    var provider: String = "bridge", // "bridge" (Antigravity), "opencode", "openai_compat"
    var bridgeHost: String = "127.0.0.1",
    var bridgePort: Int = 18765,
    var autoStartBridge: Boolean = true,
    var allowGameDirRead: Boolean = false,
    var attachScreenshotByDefault: Boolean = false,
    var clarifyVagueQuestions: Boolean = true,
    var maxScreenshotWidth: Int = 1280,
    var screenshotJpegQuality: Float = 0.80f,
    var customBridgeCommand: String = "",
    var systemPromptExtra: String = "",
    var model: String = "",
    var apiKey: String = "",
    var openaiBaseUrl: String = "https://openrouter.ai/api/v1",
    var openaiApiKey: String = "",
    var openaiModel: String = "qwen/qwen3-235b-a22b:free",
)

object ModConfig {
    private val logger = LoggerFactory.getLogger("agyassistant.config")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val configDir: Path
        get() = FMLPaths.CONFIGDIR.get().resolve("agyassistant")

    val configFile: Path
        get() = configDir.resolve("config.json")

    @Volatile
    var data: ModConfigData = ModConfigData()
        private set

    fun load() {
        try {
            Files.createDirectories(configDir)
            if (Files.exists(configFile)) {
                val raw = Files.readString(configFile)
                val defaultsObj = gson.toJsonTree(ModConfigData()).asJsonObject
                val fileObj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                if (fileObj != null) {
                    for ((k, v) in fileObj.entrySet()) {
                        if (!v.isJsonNull) defaultsObj.add(k, v)
                    }
                }
                data = gson.fromJson(defaultsObj, ModConfigData::class.java) ?: ModConfigData()
                save()
            } else {
                save()
            }
        } catch (e: Exception) {
            logger.error("Failed to load config from {}", configFile, e)
            data = ModConfigData()
        }
    }

    fun save() {
        try {
            Files.createDirectories(configDir)
            Files.writeString(configFile, gson.toJson(data))
        } catch (e: Exception) {
            logger.error("Failed to save config to {}", configFile, e)
        }
    }

    fun update(mutator: ModConfigData.() -> Unit) {
        data.mutator()
        save()
    }
}
