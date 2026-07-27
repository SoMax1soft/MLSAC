/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
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
 * This file is based on GPLv3 licensed work and includes modifications.
 * Derived from:
 *   - Shard (© 2025 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 *   - Client-side project (GPLv3: https://github.com/MLSAC/client-side)
 *
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.checks;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.alert.AlertManager;
import wtf.mlsac.compat.WorldGuardCompat;
import wtf.mlsac.config.Config;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.data.TickData;
import wtf.mlsac.scheduler.SchedulerAdapter;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.server.AIClientProvider;
import wtf.mlsac.server.AIResponse;
import wtf.mlsac.server.FlatBufferSerializer;
import wtf.mlsac.server.IAIClient;
import wtf.mlsac.violation.ViolationManager;
import wtf.mlsac.util.GeyserUtil;
import wtf.mlsac.util.SecurityUtil;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AICheck {
    private static final int MIN_SEQUENCE = 5;
    private static final int MAX_SEQUENCE = 200;
    private final Main plugin;
    private final AIClientProvider clientProvider;
    private final AlertManager alertManager;
    private final ViolationManager violationManager;
    private final Logger logger;
    private final SchedulerAdapter schedulerAdapter;
    private final Map<UUID, AIPlayerData> playerData;
    private volatile Config config;
    private WorldGuardCompat worldGuardCompat;
    private volatile int sequence;
    private volatile int step;

    public AICheck(Main plugin, Config config,
            AIClientProvider clientProvider,
            AlertManager alertManager, ViolationManager violationManager) {
        this.plugin = plugin;
        this.config = config;
        this.clientProvider = clientProvider;
        this.alertManager = alertManager;
        this.violationManager = violationManager;
        this.logger = plugin.getLogger();
        this.schedulerAdapter = SchedulerManager.getAdapter();
        this.playerData = new ConcurrentHashMap<>();
        this.sequence = config.getAiSequence();
        this.step = config.getAiStep();
        this.worldGuardCompat = new WorldGuardCompat(
                plugin.getLogger(),
                config.isWorldGuardEnabled(),
                config.getWorldGuardDisabledRegions());
    }

    public void setConfig(Config config) {
        this.config = config;
        this.sequence = config.getAiSequence();
        this.step = config.getAiStep();
        this.worldGuardCompat = new WorldGuardCompat(
                plugin.getLogger(),
                config.isWorldGuardEnabled(),
                config.getWorldGuardDisabledRegions());
    }

    public void onAttack(Player player, Entity target) {
        if (!config.isAiEnabled()) {
            return;
        }
        if (!(target instanceof Player)) {
            return;
        }
        if (worldGuardCompat.shouldBypassAICheck(player)) {
            plugin.debug("[AI] Skipping attack for " + player.getName() + " - in disabled WorldGuard region");
            return;
        }
        if (!player.isValid()) {
            return;
        }

        AIPlayerData data = getOrCreatePlayerData(player);
        if (data.isBedrock()) {
            plugin.debug("[AI] Skipping attack for " + player.getName() + " - Bedrock player detected");
            return;
        }
        if (!data.isInCombat()) {
            data.clearBuffer();
            data.getAimProcessor().reset();
            plugin.debug("[AI] New combat started for " + player.getName() + ", cleared old data");
        }
        data.onAttack();
        plugin.debug("[AI] Attack registered for " + player.getName() +
                ", buffer=" + data.getBufferSize() + "/" + sequence);
    }

    public void onTeleport(Player player) {
        if (!config.isAiEnabled()) {
            return;
        }
        if (!player.isValid()) {
            return;
        }
        AIPlayerData data = playerData.get(player.getUniqueId());
        if (data != null) {
            data.onTeleport();
            plugin.debug("[AI] Teleport registered for " + player.getName() + ", resetting data");
        }
    }

    public void onTick(Player player) {
        if (!config.isAiEnabled() || !isClientAvailable() || !player.isValid()) {
            return;
        }

        AIPlayerData data = getOrCreatePlayerData(player);
        if (data.isBedrock())
            return;

        data.incrementTicksSinceAttack();
        if (data.getTicksSinceAttack() > sequence) {
            if (data.getBufferSize() > 0) {
                data.clearBuffer();
            }
            data.resetStepCounter();
        }
    }

    public void onRotationPacket(Player player, float yaw, float pitch) {
        if (!config.isAiEnabled() || !isClientAvailable() || !player.isValid()) {
            return;
        }

        AIPlayerData data = playerData.get(player.getUniqueId());
        if (data == null || !data.isInCombat() || data.isBedrock()) {
            return;
        }

        if (worldGuardCompat.shouldBypassAICheck(player)) {
            return;
        }

        data.processTick(yaw, pitch);
        data.incrementStepCounter();
        if (data.shouldSendData(step, sequence)) {
            sendDataToAI(player, data);
            data.resetStepCounter();
        }
    }

    private void sendDataToAI(Player player, AIPlayerData data) {
        try {
            List<TickData> ticks = data.getTickBuffer();
            if (ticks.size() < sequence) {
                plugin.debug("[AI] Not enough ticks for " + player.getName() +
                        ": " + ticks.size() + "/" + sequence);
                return;
            }
            IAIClient client = clientProvider.get();
            if (client == null) {
                plugin.debug("[AI] Client unavailable (null) for " + player.getName());
                return;
            }
            plugin.debug("[AI] Sending " + ticks.size() + " ticks for " + player.getName() +
                    " (ticksSinceAttack=" + data.getTicksSinceAttack() + ")");
            if (config.isDebug()) {
                logTickBuffer(ticks);
            }
            byte[] serialized = FlatBufferSerializer.serialize(ticks);
            final Player playerRef = player;
            final UUID playerUuid = player.getUniqueId();
            final String playerName = player.getName();
            
            // Increment request counter
            if (plugin.getDailyStats() != null) {
                plugin.getDailyStats().incrementRequests();
            }
            
            client.predict(serialized, playerUuid.toString(), playerName)
                    .subscribe(response -> {
                        processResponse(playerRef, playerUuid, playerName, data, response);
                    }, error -> {
                        handleError(playerName, error);
                    });
        } catch (Exception e) {
            plugin.getLogger().warning("[AI] Unexpected error in sendDataToAI: " + e.getMessage());
        }
    }

    private void logTickBuffer(List<TickData> ticks) {
        plugin.debug("[AI] === TICK BUFFER START ===");
        int i = 0;
        for (TickData tick : ticks) {
            plugin.debug("[AI] Tick[" + i + "]: dYaw=" + String.format(Locale.ROOT, "%.4f", tick.deltaYaw) +
                    ", dPitch=" + String.format(Locale.ROOT, "%.4f", tick.deltaPitch) +
                    ", aYaw=" + String.format(Locale.ROOT, "%.4f", tick.accelYaw) +
                    ", aPitch=" + String.format(Locale.ROOT, "%.4f", tick.accelPitch) +
                    ", jYaw=" + String.format(Locale.ROOT, "%.4f", tick.jerkYaw) +
                    ", jPitch=" + String.format(Locale.ROOT, "%.4f", tick.jerkPitch) +
                    ", gcdYaw=" + String.format(Locale.ROOT, "%.4f", tick.gcdErrorYaw) +
                    ", gcdPitch=" + String.format(Locale.ROOT, "%.4f", tick.gcdErrorPitch));
            i++;
        }
        plugin.debug("[AI] === TICK BUFFER END ===");
    }

    private boolean isClientAvailable() {
        if (clientProvider == null) return false;
        if (!clientProvider.isAvailable()) return false;
        if (clientProvider.isServerErrorState()) return false;
        
        IAIClient client = clientProvider.get();
        if (client != null && client.isInStasisMode()) return false;
        
        return true;
    }

    private void processResponse(Player playerRef, UUID playerUuid, String playerName, AIPlayerData data, AIResponse response) {
        if (response.getError() != null && response.getError().contains("INVALID_SEQUENCE")) {
            handleInvalidSequence(response.getError());
            return;
        }
        double probability = response.getProbability();
        // NaN/Infinity/out-of-range probabilities would blow up the violation buffer in a single
        // response (instant kick/ban). Only [0,1] may enter the pipeline.
        if (!SecurityUtil.isValidProbability(probability)) {
            logger.warning("[AI] Rejected response for " + playerName + ": probability " + probability
                    + " is outside [0,1] - corrupt or tampered API payload");
            return;
        }
        String modelName = response.getModel();
        // A model switched off in the preset acts on nothing: drop the prediction before it can
        // reach the monitor, the violation buffer or the punishment ladder.
        if (!config.isModelEnabled(modelName)) {
            plugin.debug("[AI] Model " + modelName + " is disabled, dropping response for " + playerName);
            return;
        }
        boolean isOnlyAlert = config.isOnlyAlertForModel(modelName);

        plugin.debug("[AI] Response for " + playerName + ": probability=" +
                String.format(Locale.ROOT, "%.3f", probability) + ", model=" + modelName +
                ", onlyAlert=" + isOnlyAlert);

        if (isOnlyAlert) {
            plugin.debug("[AI] Only-alert mode for model " + modelName + ", skipping buffer/punishment");
        } else {
            data.updateBuffer(probability, modelName, config.getAiBufferMultiplier(),
                    config.getAiBufferDecrease(), config.getAiAlertThreshold(),
                    config.getAiBufferDecreaseThreshold());
        }

        dispatchDetectionOutcome(playerRef, playerUuid, playerName, data, probability, modelName);

        if (!isOnlyAlert) {
            maybeFlag(playerRef, playerName, data, probability);
        }
    }

    /**
     * Buffer-driven alert pipeline: every configured step (e.g. 33/66/99% of the flag threshold)
     * sends one chat alert per upward crossing. Monitor subscribers still get every detection.
     * Damage-reduction / troll triggers are delegated to {@link wtf.mlsac.response.DetectionResponseManager}.
     */
    private void dispatchDetectionOutcome(Player playerRef, UUID playerUuid, String playerName,
            AIPlayerData data, double probability, String modelName) {
        alertManager.sendMonitorOnly(playerName, probability, modelName);

        double buffer = data.getBuffer();
        double flag = config.getAiBufferFlag();
        double step = config.getAiAlertBufferStepPercent();
        boolean anyFired = false;
        if (flag > 0 && step > 0) {
            for (double s = step; s < 1.0 + 1e-9; s += step) {
                double frac = Math.min(s, 1.0);
                double thresholdValue = flag * frac;
                String key = "alert-step-" + (int) Math.round(frac * 1000);
                if (data.consumeBufferCrossing(key, thresholdValue)) {
                    alertManager.sendBufferStepAlert(playerName, probability, buffer, frac, modelName);
                    anyFired = true;
                }
            }
        }

        if (anyFired) {
            if (plugin.getDailyStats() != null) {
                plugin.getDailyStats().incrementDetections();
            }
            IAIClient client = clientProvider.get();
            if (client != null) {
                client.reportAlert(playerUuid.toString(), playerName, modelName, probability, buffer);
            }
        }

        if (playerRef != null && playerRef.isOnline() && plugin.getDetectionResponseManager() != null) {
            schedulerAdapter.runEntitySync(playerRef, () -> {
                if (playerRef.isOnline()) {
                    plugin.getDetectionResponseManager().onBufferUpdated(playerRef, data, probability);
                }
            });
        }
    }

    /** Escalates to the violation/punishment system once the buffer crosses the flag threshold. */
    private void maybeFlag(Player playerRef, String playerName, AIPlayerData data, double probability) {
        if (!data.shouldFlag(config.getAiBufferFlag())) {
            return;
        }
        if (playerRef != null && playerRef.isOnline()) {
            schedulerAdapter.runEntitySync(playerRef, () -> {
                if (playerRef.isOnline()) {
                    violationManager.handleFlag(playerRef, probability, data.getBuffer());
                }
            });
        } else {
            logger.warning("[AI] Player " + playerName + " went offline before punishment");
        }
        data.resetBuffer(config.getAiBufferResetOnFlag());
    }

    private void handleInvalidSequence(String error) {
        try {
            String[] parts = error.split(":");
            if (parts.length >= 2) {
                int newSequence = Integer.parseInt(parts[1].trim());
                if (newSequence < MIN_SEQUENCE || newSequence > MAX_SEQUENCE) {
                    logger.warning("[AI] Ignoring INVALID_SEQUENCE update to " + newSequence
                            + " (allowed range " + MIN_SEQUENCE + "-" + MAX_SEQUENCE + ")");
                    return;
                }
                if (newSequence != this.sequence) {
                    logger.info("[AI] Updating sequence from " + this.sequence + " to " + newSequence);
                    this.sequence = newSequence;
                    for (AIPlayerData data : playerData.values()) {
                        data.resetSequence(newSequence);
                    }
                }
            }
        } catch (NumberFormatException e) {
            logger.warning("[AI] Failed to parse new sequence from error: " + error);
        }
    }

    private void handleError(String playerName, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        logger.warning("[AI] Error for " + playerName + ": " + cause.getMessage());
    }

    private AIPlayerData getOrCreatePlayerData(Player player) {
        return playerData.computeIfAbsent(player.getUniqueId(),
                uuid -> {
                    AIPlayerData data = new AIPlayerData(uuid, sequence);
                    if (GeyserUtil.isBedrockPlayer(uuid)) {
                        data.setBedrock(true);
                        logger.info("[AI] Detected Bedrock player: " + player.getName() + " - bypassing checks");
                    }
                    return data;
                });
    }

    public AIPlayerData getPlayerData(UUID playerId) {
        return playerData.get(playerId);
    }

    public void handlePlayerQuit(Player player) {
        if (player != null) {
            playerData.remove(player.getUniqueId());
            if (worldGuardCompat != null) {
                worldGuardCompat.clearCache(player.getUniqueId());
            }
        }
    }

    public void clearAll() {
        playerData.clear();
    }

    public int getSequence() {
        return sequence;
    }

    public int getStep() {
        return step;
    }

    public WorldGuardCompat getWorldGuardCompat() {
        return worldGuardCompat;
    }
}
