/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file contains code derived from:
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package wtf.mlsac;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import wtf.mlsac.alert.AlertManager;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.commands.CommandHandler;
import wtf.mlsac.compat.VersionAdapter;
import wtf.mlsac.config.Config;
import wtf.mlsac.config.ConfigSyncUtil;
import wtf.mlsac.config.HologramConfig;
import wtf.mlsac.config.MenuConfig;
import wtf.mlsac.config.MessagesConfig;
import wtf.mlsac.datacollector.DataCollectorFactory;
import wtf.mlsac.hologram.NametagManager;
import wtf.mlsac.listeners.HitListener;
import wtf.mlsac.listeners.PlayerListener;
import wtf.mlsac.listeners.RotationListener;
import wtf.mlsac.listeners.TeleportListener;
import wtf.mlsac.listeners.TickListener;
import wtf.mlsac.listeners.CombatPenaltyListener;
import wtf.mlsac.response.DetectionResponseManager;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.server.AIClientProvider;
import wtf.mlsac.server.AnalyticsClient;
import wtf.mlsac.session.ISessionManager;
import wtf.mlsac.session.SessionManager;
import wtf.mlsac.violation.ViolationManager;
import wtf.mlsac.util.FeatureCalculator;
import wtf.mlsac.util.UpdateChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;

public final class Main extends JavaPlugin {
    private Config config;
    private MenuConfig menuConfig;
    private MessagesConfig messagesConfig;
    private HologramConfig hologramConfig;
    private ISessionManager sessionManager;
    private FeatureCalculator featureCalculator;
    private TickListener tickListener;
    private HitListener hitListener;
    private RotationListener rotationListener;
    private PlayerListener playerListener;
    private TeleportListener teleportListener;
    private CommandHandler commandHandler;
    private AIClientProvider aiClientProvider;
    private AlertManager alertManager;
    private ViolationManager violationManager;
    private NametagManager nametagManager;
    private wtf.mlsac.hologram.PacketHologramManager packetHologramManager;
    private AICheck aiCheck;
    private UpdateChecker updateChecker;
    private AnalyticsClient analyticsClient;
    private DetectionResponseManager detectionResponseManager;
    private CombatPenaltyListener combatPenaltyListener;

    @Override
    public void onLoad() {
        VersionAdapter.init(getLogger());
        // PacketEvents loading moved to onEnable to avoid ClassLoader issues with
        // PlugMan
    }

    @Override
    public void onEnable() {
        try {
            SchedulerManager.reset();
            SchedulerManager.initialize(this);
            getLogger().info("SchedulerManager initialized for " + SchedulerManager.getServerType());
        } catch (Throwable e) {
            getLogger().severe("Failed to initialize SchedulerManager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            // Check if API is already set (reloading)
            if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isLoaded()) {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
                PacketEvents.getAPI().getSettings()
                        .reEncodeByDefault(false)
                        .checkForUpdates(false)
                        .bStats(false)
                        .debug(false);
                PacketEvents.getAPI().load();
            }

            if (!PacketEvents.getAPI().isInitialized()) {
                PacketEvents.getAPI().init();
            }
        } catch (Exception e) {
            getLogger().severe("Failed to initialize PacketEvents: " + e.getMessage());
            e.printStackTrace();
        }
        VersionAdapter.get().logCompatibilityInfo();
        ConfigSyncUtil.syncPluginConfig(this);
        this.config = new Config(this, getLogger());
        this.menuConfig = new MenuConfig(this);
        this.menuConfig.load();
        this.messagesConfig = new MessagesConfig(this);
        this.messagesConfig.load();
        this.hologramConfig = new HologramConfig(this);
        this.hologramConfig.load();

        File outputDir = new File(config.getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        this.featureCalculator = new FeatureCalculator();
        this.sessionManager = DataCollectorFactory.createSessionManager(this);
        this.aiClientProvider = new AIClientProvider(this, config);
        this.alertManager = new AlertManager(this, config);
        this.violationManager = new ViolationManager(this, config, alertManager);
        this.aiCheck = new AICheck(this, config, aiClientProvider, alertManager, violationManager);
        this.violationManager.setAICheck(aiCheck);
        this.detectionResponseManager = new DetectionResponseManager(this, config);

        this.nametagManager = new NametagManager(this, aiCheck);
        this.packetHologramManager = new wtf.mlsac.hologram.PacketHologramManager(this, aiCheck);
        
        String holoMode = hologramConfig.getConfig().getString("nametags.mode", "NAMETAG");
        if (holoMode.equalsIgnoreCase("PACKET")) {
            this.packetHologramManager.start();
        } else {
            this.nametagManager.start();
        }

        if (config.isAiEnabled()) {
            aiClientProvider.initialize().thenAccept(success -> {
                if (success) {
                    getLogger().info(aiClientProvider.getClientType() + ": Connected to " + config.getServerAddress());
                } else {
                    getLogger().warning(aiClientProvider.getClientType() + ": Failed to connect to InferenceServer");
                }
            });
        }
        this.tickListener = new TickListener(this, sessionManager, aiCheck, nametagManager);
        this.hitListener = new HitListener(sessionManager, aiCheck);
        this.rotationListener = new RotationListener(sessionManager, aiCheck);
        this.analyticsClient = new AnalyticsClient(config.getServerAddress(), getLogger());
        this.playerListener = new PlayerListener(this, aiCheck, alertManager, violationManager,
                sessionManager instanceof SessionManager ? (SessionManager) sessionManager : null, tickListener,
                nametagManager, rotationListener, analyticsClient);
        this.teleportListener = new TeleportListener(aiCheck);
        this.combatPenaltyListener = new CombatPenaltyListener(detectionResponseManager);
        this.tickListener.setHitListener(hitListener);
        this.playerListener.setHitListener(hitListener);
        this.hitListener.cacheOnlinePlayers();
        this.tickListener.start();
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            this.tickListener.startPlayerTask(p);
        }
        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(teleportListener, this);
        getServer().getPluginManager().registerEvents(combatPenaltyListener, this);
        PacketEvents.getAPI().getEventManager().registerListener(hitListener);
        PacketEvents.getAPI().getEventManager().registerListener(rotationListener);
        this.commandHandler = new CommandHandler(sessionManager, alertManager, aiCheck, this);
        PluginCommand command = getCommand("mlsac");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        }
        getLogger().info("MLSAC enabled successfully!");
        getLogger().info("Data collector: ENABLED (output: " + config.getOutputDirectory() + ")");
        if (config.isAiEnabled()) {
            getLogger().info("AI detection: ENABLED (threshold: " + config.getAiAlertThreshold() + ")");
        } else {
            getLogger().info("AI detection: DISABLED");
        }

        this.updateChecker = new UpdateChecker(this);
        updateChecker.checkForUpdates().thenAccept(available -> {
            if (available) {
                getLogger().warning("=================================================");
                getLogger().warning("A NEW UPDATE IS AVAILABLE: " + updateChecker.getLatestVersion());
                getLogger().warning("Get it from GitHub: https://github.com/MLSAC/client-side/releases");
                getLogger().warning("=================================================");
            }
        });
    }

    @Override
    public void onDisable() {
        if (tickListener != null) {
            tickListener.stop();
        }
        if (nametagManager != null) {
            nametagManager.stop();
        }
        if (packetHologramManager != null) {
            packetHologramManager.stop();
        }
        if (sessionManager != null) {
            getLogger().info("Stopping all active sessions...");
            sessionManager.stopAllSessions();
        }
        if (aiCheck != null) {
            aiCheck.clearAll();
        }
        if (commandHandler != null) {
            commandHandler.cleanup();
        }
        if (aiClientProvider != null) {
            getLogger().info("Shutting down HTTP client...");
            try {
                aiClientProvider.shutdown().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e.getMessage() != null) {
                    getLogger().warning("Error shutting down HTTP client: " + e.getMessage());
                } else {
                    getLogger().warning("Error shutting down HTTP client during disable:");
                    e.printStackTrace();
                }
            }
        }
        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            PacketEvents.getAPI().terminate();
        }
        SchedulerManager.reset();

        getLogger().info("MLSAC disabled successfully!");
    }

    public void reloadPluginConfig() {
        SchedulerManager.getAdapter().runSync(() -> {
            try {
                reloadConfig();
                ConfigSyncUtil.syncPluginConfig(this);
                this.config = new Config(this, getLogger());
                if (menuConfig != null)
                    menuConfig.reload();
                if (messagesConfig != null)
                    messagesConfig.reload();
                if (hologramConfig != null)
                    hologramConfig.reload();

                if (nametagManager != null) {
                    nametagManager.stop();
                }
                if (packetHologramManager != null) {
                    packetHologramManager.stop();
                }

                String holoMode = hologramConfig.getConfig().getString("nametags.mode", "NAMETAG");
                if (holoMode.equalsIgnoreCase("PACKET")) {
                    packetHologramManager.start();
                } else {
                    nametagManager.start();
                }

                alertManager.setConfig(config);
                violationManager.setConfig(config);
                aiCheck.setConfig(config);
                if (detectionResponseManager != null) {
                    detectionResponseManager.setConfig(config);
                }
                if (aiClientProvider != null) {
                    aiClientProvider.setConfig(config);
                    if (config.isAiEnabled()) {
                        aiClientProvider.reload().thenAccept(success -> {
                            if (success) {
                                getLogger().info(aiClientProvider.getClientType() + ": Reconnected to " + config.getServerAddress());
                            }
                        });
                    } else {
                        aiClientProvider.shutdown();
                    }
                }
                getLogger().info("Configuration reloaded!");
            } catch (Exception e) {
                getLogger().severe("Failed to reload configuration: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public boolean reinstallPluginConfig() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            String preservedApiKey = currentConfig.getString("detection.api-key",
                    currentConfig.getString("ai.api-key", Config.DEFAULT_AI_API_KEY));
            boolean preservedAiDetection = currentConfig.getBoolean("detection.enabled",
                    currentConfig.getBoolean("ai.enabled", Config.DEFAULT_AI_ENABLED));

            if (!reinstallResourceFile("config.yml")) {
                return false;
            }
            reloadConfig();

            FileConfiguration reinstalledConfig = getConfig();
            reinstalledConfig.set("detection.api-key", preservedApiKey);
            reinstalledConfig.set("detection.enabled", preservedAiDetection);
            reinstalledConfig.set("ai", null);
            saveConfig();

            if (!reinstallResourceFile("messages.yml")) {
                return false;
            }
            if (!reinstallResourceFile("menu.yml")) {
                return false;
            }
            if (!reinstallResourceFile("holograms.yml")) {
                return false;
            }

            reloadPluginConfig();
            getLogger().info("All configuration YAML files were reinstalled. API key and AI detection state were preserved.");
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to reinstall configuration: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean reinstallResourceFile(String resourceName) {
        try {
            File targetFile = new File(getDataFolder(), resourceName);
            if (targetFile.exists() && !targetFile.delete()) {
                getLogger().warning("Failed to delete " + resourceName + " during reinstall");
                return false;
            }
            saveResource(resourceName, false);
            return true;
        } catch (Exception exception) {
            getLogger().warning("Failed to reinstall " + resourceName + ": " + exception.getMessage());
            return false;
        }
    }

    public MenuConfig getMenuConfig() {
        return menuConfig;
    }

    public MessagesConfig getMessagesConfig() {
        return messagesConfig;
    }

    public HologramConfig getHologramConfig() {
        return hologramConfig;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }

    public Config getPluginConfig() {
        return config;
    }

    public ISessionManager getSessionManager() {
        return sessionManager;
    }

    public FeatureCalculator getFeatureCalculator() {
        return featureCalculator;
    }

    public AICheck getAiCheck() {
        return aiCheck;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public AIClientProvider getAiClientProvider() {
        return aiClientProvider;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public AnalyticsClient getAnalyticsClient() {
        return analyticsClient;
    }

    public DetectionResponseManager getDetectionResponseManager() {
        return detectionResponseManager;
    }

    public void debug(String message) {
        if (config != null && config.isDebug()) {
            getLogger().info("[Debug] " + message);
        }
    }
}
