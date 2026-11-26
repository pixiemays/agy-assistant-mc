package com.agyassistant

import com.agyassistant.config.ModConfig
import com.agyassistant.input.KeyBindings
import com.agyassistant.network.BridgeProcessManager
import com.agyassistant.network.GitHubUpdater
import com.agyassistant.ui.AssistantNotifications
import com.agyassistant.ui.SettingsScreen
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.event.GameShuttingDownEvent
import org.slf4j.LoggerFactory
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(AgyAssistantMod.MOD_ID)
object AgyAssistantMod {
    const val MOD_ID = "agyassistant"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    init {
        ModConfig.load()

        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory::class.java) {
            IConfigScreenFactory { _, parent -> SettingsScreen(parent) }
        }

        MOD_BUS.addListener(::onClientSetup)
        MOD_BUS.addListener(KeyBindings::onRegisterKeyMappings)

        FORGE_BUS.addListener(KeyBindings::onClientTick)
        FORGE_BUS.addListener(KeyBindings::onScreenKeyPressed)
        FORGE_BUS.addListener(AssistantNotifications::onRenderGui)
        FORGE_BUS.addListener(::onGameShuttingDown)

        logger.info("Antigravity In-Game Assistant initialized (bridge port={})", ModConfig.data.bridgePort)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        GitHubUpdater.cleanupStalePending()
        val updatedAtLaunch = System.getProperty("agyassistant.loader.updated")?.trim().orEmpty()
        if (updatedAtLaunch.isNotEmpty()) {
            net.minecraft.client.Minecraft.getInstance().execute {
                AssistantNotifications.showUpdatedAtLaunch(updatedAtLaunch)
            }
        } else if (!GitHubUpdater.isManagedByLoader() && ModConfig.data.autoUpdate) {
            GitHubUpdater.checkAndDownloadAsync(manual = false)
        }
        if (ModConfig.data.provider != "openai_compat" && ModConfig.data.autoStartBridge) {
            BridgeProcessManager.startIfNeededAsync()
        }
    }

    private fun onGameShuttingDown(event: GameShuttingDownEvent) {
        BridgeProcessManager.stop()
    }
}
