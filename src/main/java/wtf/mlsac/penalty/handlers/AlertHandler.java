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


package wtf.mlsac.penalty.handlers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.Permissions;
import wtf.mlsac.penalty.ActionHandler;
import wtf.mlsac.penalty.ActionType;
import wtf.mlsac.penalty.PenaltyContext;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.util.ColorUtil;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
public class AlertHandler implements ActionHandler {
    private final JavaPlugin plugin;
    private final Logger logger;
    private String alertPrefix;
    private Set<UUID> alertRecipients;
    private boolean consoleAlerts;
    public AlertHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.alertPrefix = "&6[ALERT] &f";
        this.consoleAlerts = true;
    }
    public void setAlertPrefix(String prefix) {
        this.alertPrefix = prefix != null ? prefix : "&6[ALERT] &f";
    }
    public void setAlertRecipients(Set<UUID> recipients) {
        this.alertRecipients = recipients;
    }
    public void setConsoleAlerts(boolean enabled) {
        this.consoleAlerts = enabled;
    }
    @Override
    public void handle(String message, PenaltyContext context) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String formattedMessage = ColorUtil.colorize(alertPrefix + message);
        if (SchedulerManager.getServerType() == ServerType.FOLIA) {
            sendFolia(formattedMessage);
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            SchedulerManager.getAdapter().runSync(() -> sendNow(formattedMessage));
            return;
        }
        sendNow(formattedMessage);
    }

    private void sendNow(String formattedMessage) {
        if (alertRecipients != null) {
            for (UUID uuid : alertRecipients) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && canReceiveAlerts(player)) {
                    player.sendMessage(formattedMessage);
                }
            }
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (canReceiveAlerts(player)) {
                    player.sendMessage(formattedMessage);
                }
            }
        }
        if (consoleAlerts) {
            logger.info(ColorUtil.stripColors(formattedMessage));
        }
    }

    private void sendFolia(String formattedMessage) {
        if (alertRecipients != null) {
            for (UUID uuid : alertRecipients) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    SchedulerManager.getAdapter().runEntitySync(player, () -> {
                        if (player.isOnline() && canReceiveAlerts(player)) {
                            player.sendMessage(formattedMessage);
                        }
                    });
                }
            }
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                SchedulerManager.getAdapter().runEntitySync(player, () -> {
                    if (player.isOnline() && canReceiveAlerts(player)) {
                        player.sendMessage(formattedMessage);
                    }
                });
            }
        }
        if (consoleAlerts) {
            logger.info(ColorUtil.stripColors(formattedMessage));
        }
    }
    private boolean canReceiveAlerts(Player player) {
        return Permissions.canSeeAlerts(player);
    }
    @Override
    public ActionType getActionType() {
        return ActionType.CUSTOM_ALERT;
    }
}
