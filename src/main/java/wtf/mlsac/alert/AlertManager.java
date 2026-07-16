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

package wtf.mlsac.alert;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.config.Config;
import wtf.mlsac.config.MessagesConfig;
import wtf.mlsac.scheduler.SchedulerAdapter;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.util.ColorUtil;
import wtf.mlsac.util.ProbabilityFormatUtil;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class AlertManager {
    private final Logger logger;
    private final Set<UUID> playersWithAlerts;
    private final Set<UUID> playersWithMonitor;
    private final SchedulerAdapter scheduler;
    private Config config;
    private MessagesConfig messagesConfig;

    public AlertManager(Main plugin, Config config) {
        this.config = config;
        this.messagesConfig = plugin.getMessagesConfig();
        this.logger = plugin.getLogger();
        this.playersWithAlerts = new CopyOnWriteArraySet<>();
        this.playersWithMonitor = new CopyOnWriteArraySet<>();
        this.scheduler = SchedulerManager.getAdapter();
    }

    private String getPrefix() {
        return ColorUtil.colorize(messagesConfig.getPrefix());
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public boolean toggleAlerts(Player player) {
        UUID uuid = player.getUniqueId();
        if (playersWithAlerts.contains(uuid)) {
            playersWithAlerts.remove(uuid);
            String msg = ColorUtil.colorize(messagesConfig.getMessage("alerts-disabled"));
            player.sendMessage(getPrefix() + msg);
            return false;
        } else {
            playersWithAlerts.add(uuid);
            String msg = ColorUtil.colorize(messagesConfig.getMessage("alerts-enabled"));
            player.sendMessage(getPrefix() + msg);
            return true;
        }
    }

    public boolean toggleMonitor(Player player) {
        UUID uuid = player.getUniqueId();
        if (playersWithMonitor.contains(uuid)) {
            playersWithMonitor.remove(uuid);
            String msg = ColorUtil.colorize(messagesConfig.getMessage("monitor-disabled"));
            player.sendMessage(getPrefix() + msg);
            return false;
        } else {
            playersWithMonitor.add(uuid);
            String msg = ColorUtil.colorize(messagesConfig.getMessage("monitor-enabled"));
            player.sendMessage(getPrefix() + msg);
            return true;
        }
    }

    public void enableAlerts(Player player) {
        playersWithAlerts.add(player.getUniqueId());
    }

    public void disableAlerts(Player player) {
        playersWithAlerts.remove(player.getUniqueId());
    }

    public boolean hasAlertsEnabled(Player player) {
        return playersWithAlerts.contains(player.getUniqueId());
    }

    private boolean canReceiveAlerts(Player player) {
        return player.isOp() || player.hasPermission(Permissions.ALERTS) || player.hasPermission(Permissions.ADMIN);
    }

    public void sendAlert(String suspectName, double probability, double buffer) {
        sendAlert(suspectName, probability, buffer, null);
    }

    public void sendAlert(String suspectName, double probability, double buffer, String modelName) {
        String message = formatAlertMessage(suspectName, probability, buffer, modelName);
        sendMessageToAlertSubscribers(message, config.isAiConsoleAlerts() ? ColorUtil.stripColors(message) : null);
        
        // Send to monitor mode players (all detections)
        sendMonitorMessage(suspectName, probability, modelName);
        
        // Play sound for alert subscribers
        playAlertSound();
    }

    public void sendMonitorOnly(String suspectName, double probability, String modelName) {
        // Only send to monitor mode (no alerts, no sound)
        sendMonitorMessage(suspectName, probability, modelName);
    }

    public void sendAlert(String suspectName, double probability, double buffer, int vl) {
        sendAlert(suspectName, probability, buffer, vl, null);
    }

    public void sendAlert(String suspectName, double probability, double buffer, int vl, String modelName) {
        String message = formatAlertMessage(suspectName, probability, buffer, vl, modelName);
        sendMessageToAlertSubscribers(message, config.isAiConsoleAlerts() ? ColorUtil.stripColors(message) : null);

        // Send to monitor mode players (all detections)
        sendMonitorMessage(suspectName, probability, modelName);

        // Play sound for alert subscribers
        playAlertSound();
    }

    /**
     * Buffer-step alert: fired when the violation buffer crosses a configured fraction of the flag
     * threshold (e.g. 33/66/99%). Reuses {@code alert-format}; templates may include the optional
     * {@code {STEP_PERCENT}} placeholder to show the crossed step.
     */
    public void sendBufferStepAlert(String suspectName, double probability, double buffer,
            double stepFraction, String modelName) {
        String message = formatAlertMessage(suspectName, probability, buffer, modelName);
        int percent = (int) Math.round(stepFraction * 100);
        message = message
                .replace("{STEP_PERCENT}", String.valueOf(percent))
                .replace("<step_percent>", String.valueOf(percent));
        sendMessageToAlertSubscribers(message, config.isAiConsoleAlerts() ? ColorUtil.stripColors(message) : null);
        playAlertSound();
    }

    public void sendInterServerEvent(String type, String sourceServerName, String suspectName, double probability,
            double buffer, int vl, String modelName, String action) {
        String message = formatInterServerEventMessage(type, sourceServerName, suspectName, probability,
                buffer, vl, modelName, action);
        sendMessageToPermittedPlayers(message, ColorUtil.stripColors(message));
    }

    public void sendMessageToPermittedPlayers(String message, String consoleMessage) {
        dispatch(null, true, player -> player.sendMessage(message), () -> logConsoleMessage(consoleMessage));
    }

    private void sendMessageToAlertSubscribers(String message, String consoleMessage) {
        dispatch(playersWithAlerts, true, player -> player.sendMessage(message),
                () -> logConsoleMessage(consoleMessage));
    }

    /**
     * Runs a per-player action on the thread the running platform requires.
     *
     * @param recipients        UUIDs to target, or {@code null} to target every online player
     * @param requirePermission only deliver to players passing {@link #canReceiveAlerts(Player)}
     * @param action            work to run for each eligible player
     * @param afterAll          optional callback executed once after dispatching (e.g. console log)
     */
    private void dispatch(Collection<UUID> recipients, boolean requirePermission,
            Consumer<Player> action, Runnable afterAll) {
        if (SchedulerManager.getServerType() == ServerType.FOLIA) {
            forEachTarget(recipients, player -> scheduler.runEntitySync(player, () -> {
                if (player.isOnline() && (!requirePermission || canReceiveAlerts(player))) {
                    action.accept(player);
                }
            }));
            if (afterAll != null) {
                afterAll.run();
            }
            return;
        }

        scheduler.runSync(() -> {
            forEachTarget(recipients, player -> {
                if (player.isOnline() && (!requirePermission || canReceiveAlerts(player))) {
                    action.accept(player);
                }
            });
            if (afterAll != null) {
                afterAll.run();
            }
        });
    }

    private void forEachTarget(Collection<UUID> recipients, Consumer<Player> consumer) {
        if (recipients == null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                consumer.accept(player);
            }
            return;
        }
        for (UUID uuid : recipients) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                consumer.accept(player);
            }
        }
    }

    private void logConsoleMessage(String consoleMessage) {
        if (consoleMessage != null && !consoleMessage.isEmpty()) {
            logger.info(consoleMessage);
        }
    }

    private String formatAlertMessage(String suspectName, double probability, double buffer, String modelName) {
        String template = messagesConfig.getMessage("alert-format", suspectName, probability, buffer, 0);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        template = template.replace("{MODEL}", modelDisplay).replace("<model>", modelDisplay);
        return getPrefix() + ColorUtil.colorize(template);
    }

    private String formatAlertMessage(String suspectName, double probability, double buffer, int vl, String modelName) {
        String template = messagesConfig.getMessage("alert-format-vl", suspectName, probability, buffer, vl);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        template = template.replace("{MODEL}", modelDisplay).replace("<model>", modelDisplay);
        return getPrefix() + ColorUtil.colorize(template);
    }

    private String formatInterServerEventMessage(String type, String sourceServerName, String suspectName,
            double probability, double buffer, int vl, String modelName, String action) {
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        String serverDisplay = sourceServerName != null && !sourceServerName.trim().isEmpty()
                ? sourceServerName.trim()
                : "unknown";
        String template = messagesConfig.getMessage("interserver-alert-format", suspectName, probability, buffer, vl);

        template = template
                .replace("{PLAYER}", suspectName != null ? suspectName : "Unknown")
                .replace("{ACTION}", action != null && !action.trim().isEmpty() ? action.trim() : type)
                .replace("{SERVER}", serverDisplay)
                .replace("{SERVER_NAME}", serverDisplay)
                .replace("{SOURCE_SERVER}", serverDisplay)
                .replace("<server>", serverDisplay)
                .replace("{PROBABILITY}", ProbabilityFormatUtil.formatPercent(probability) + "%")
                .replace("{BUFFER}", String.format("%.1f", buffer))
                .replace("{VL}", String.valueOf(vl))
                .replace("{MODEL}", modelDisplay)
                .replace("<model>", modelDisplay);
        return getPrefix() + ColorUtil.colorize(template);
    }

    public void handlePlayerQuit(Player player) {
        playersWithAlerts.remove(player.getUniqueId());
        playersWithMonitor.remove(player.getUniqueId());
    }

    private void sendMonitorMessage(String suspectName, double probability, String modelName) {
        if (playersWithMonitor.isEmpty()) {
            return;
        }
        
        String coloredProb = getColoredProbability(probability);
        String modelDisplay = modelName != null ? config.getModelDisplayName(modelName) : "Unknown";
        String template = messagesConfig.getMessage("monitor-format");
        String messageText = template
                .replace("{MODEL}", modelDisplay)
                .replace("{PLAYER}", suspectName)
                .replace("{PROBABILITY_COLORED}", coloredProb);
        final String message = ColorUtil.colorize(messageText);
        dispatch(playersWithMonitor, true, player -> player.sendMessage(message), null);
    }

    private String getColoredProbability(double probability) {
        int percent = (int) (probability * 100);
        String color;
        
        if (percent >= 90) {
            color = "&#FF0000"; // Красный
        } else if (percent >= 80) {
            color = "&#FF4500"; // Оранжево-красный
        } else if (percent >= 70) {
            color = "&#FFA500"; // Оранжевый
        } else if (percent >= 60) {
            color = "&#FFD700"; // Золотой
        } else if (percent >= 50) {
            color = "&#FFFF00"; // Желтый
        } else if (percent >= 40) {
            color = "&#90EE90"; // Светло-зеленый
        } else {
            color = "&#00FF00"; // Зеленый
        }
        
        return color + percent + "%";
    }

    private void playAlertSound() {
        if (!config.isAlertSoundEnabled()) {
            return;
        }
        
        Sound sound;
        try {
            sound = Sound.valueOf(config.getAlertSoundType());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid sound type: " + config.getAlertSoundType());
            return;
        }
        
        float volume = config.getAlertSoundVolume();
        float pitch = config.getAlertSoundPitch();
        dispatch(playersWithAlerts, false,
                player -> player.playSound(player.getLocation(), sound, volume, pitch), null);
    }

    public boolean shouldAlert(double probability) {
        return probability >= config.getAiAlertThreshold();
    }

    public double getAlertThreshold() {
        return config.getAiAlertThreshold();
    }
}
