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

package wtf.mlsac.server;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.config.Config;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AIClientProvider {
    private final Main plugin;
    private final Logger logger;
    private IAIClient currentClient;
    private Config config;
    private final Set<UUID> onlinePlayers;
    private volatile boolean connecting = false;
    private volatile String clientType = "none";

    public AIClientProvider(Main plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.logger = plugin.getLogger();
        this.onlinePlayers = ConcurrentHashMap.newKeySet();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.onlinePlayers.add(player.getUniqueId());
        }
    }

    public CompletableFuture<Boolean> initialize() {
        return initialize(true);
    }

    public CompletableFuture<Boolean> initialize(boolean checkHttpMode) {
        if (!config.isAiEnabled()) {
            plugin.debug("[AI] AI is disabled, skipping client initialization");
            return CompletableFuture.completedFuture(false);
        }

        return shutdown().thenCompose(v -> {
            String serverAddress = config.getServerAddress();
            String apiKey = config.getAiApiKey();

            if (serverAddress == null || serverAddress.isEmpty()) {
                logger.warning("[AI] Server address is not configured!");
                return CompletableFuture.completedFuture(false);
            }
            if (apiKey == null || apiKey.isEmpty()) {
                logger.warning("[AI] API key is not configured!");
                return CompletableFuture.completedFuture(false);
            }

            connecting = true;

            return initializeHttpClient(serverAddress, apiKey);
        });
    }

    private CompletableFuture<Boolean> initializeHttpClient(String serverAddress, String apiKey) {
        HttpAIClient httpClient = new HttpAIClient(
                plugin,
                serverAddress,
                apiKey,
                () -> onlinePlayers.size(),
                config.isDebug());
        this.currentClient = httpClient;
        this.clientType = "HTTP";
        logger.info("[HTTP] Connecting to " + serverAddress + "...");
        return httpClient.connectWithRetry()
                .thenApply(success -> {
                    connecting = false;
                    if (success) {
                        logger.info("[HTTP] Successfully connected to InferenceServer");
                    } else {
                        logger.warning("[HTTP] Failed to connect to InferenceServer after retries");
                    }
                    return success;
                })
                .exceptionally(e -> {
                    connecting = false;
                    logger.severe("[HTTP] Connection error: " + e.getMessage());
                    return false;
                });
    }

    public CompletableFuture<Void> shutdown() {
        if (currentClient != null) {
            logger.info("[AI] Shutting down " + clientType + " client...");
            return currentClient.disconnect()
                    .thenRun(() -> {
                        currentClient = null;
                        clientType = "none";
                        logger.info("[AI] Client shutdown complete");
                    });
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Boolean> reload() {
        return shutdown().thenCompose(v -> initialize());
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public IAIClient get() {
        return currentClient;
    }

    public boolean isAvailable() {
        return currentClient != null && currentClient.isConnected();
    }

    public boolean isEnabled() {
        return config.isAiEnabled();
    }

    public boolean isConnecting() {
        return connecting;
    }

    public boolean isLimitExceeded() {
        return currentClient != null && currentClient.isLimitExceeded();
    }

    public boolean isServerErrorState() {
        return currentClient != null && currentClient.isServerErrorState();
    }

    public String getClientType() {
        return clientType;
    }

    public void handlePlayerJoin(UUID playerId) {
        onlinePlayers.add(playerId);
    }

    public void handlePlayerQuit(UUID playerId) {
        onlinePlayers.remove(playerId);
    }
}
