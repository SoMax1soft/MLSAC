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

package wtf.mlsac.listeners;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.alert.AlertManager;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.session.SessionManager;
import wtf.mlsac.violation.ViolationManager;

public class PlayerListener implements Listener {
    private final Main plugin;
    private final AICheck aiCheck;
    private final AlertManager alertManager;
    private final ViolationManager violationManager;
    private final SessionManager sessionManager;
    private final TickListener tickListener;
    private wtf.mlsac.hologram.HologramManager hologramManager;
    private final RotationListener rotationListener;
    private HitListener hitListener;

    public PlayerListener(Main plugin, AICheck aiCheck, AlertManager alertManager,
            ViolationManager violationManager, SessionManager sessionManager,
            TickListener tickListener, wtf.mlsac.hologram.HologramManager hologramManager,
            RotationListener rotationListener) {
        this.plugin = plugin;
        this.aiCheck = aiCheck;
        this.alertManager = alertManager;
        this.violationManager = violationManager;
        this.sessionManager = sessionManager;
        this.tickListener = tickListener;
        this.hologramManager = hologramManager;
        this.rotationListener = rotationListener;
    }

    public void setHitListener(HitListener hitListener) {
        this.hitListener = hitListener;
    }

    public void setHologramManager(wtf.mlsac.hologram.HologramManager hologramManager) {
        this.hologramManager = hologramManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.getAiClientProvider() != null) {
            plugin.getAiClientProvider().handlePlayerJoin(player.getUniqueId());
        }
        if (hitListener != null) {
            hitListener.cacheEntity(player);
        }
        if (tickListener != null) {
            tickListener.startPlayerTask(player);
        }

        try {
            SchedulerManager.getAdapter().runEntitySyncDelayed(player, () -> {
                if (player.isOnline()) {
                    if (player.hasPermission(Permissions.ALERTS) || player.hasPermission(Permissions.ADMIN)) {
                        alertManager.enableAlerts(player);

                        if (plugin.getUpdateChecker() != null && plugin.getUpdateChecker().isUpdateAvailable()) {
                            player.sendMessage(
                                    ChatColor.GOLD + "=================================================");
                            player.sendMessage(ChatColor.YELLOW + "A NEW MLSAC UPDATE IS AVAILABLE: "
                                    + ChatColor.WHITE + plugin.getUpdateChecker().getLatestVersion());
                            player.sendMessage(ChatColor.YELLOW + "The updater downloads it automatically. Restart the server to apply it.");
                            player.sendMessage(
                                    ChatColor.GOLD + "=================================================");
                        }
                    }
                }
            }, 20L);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to schedule player join task: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerLeave(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        handlePlayerLeave(event.getPlayer());
    }

    private void handlePlayerLeave(Player player) {
        if (hitListener != null) {
            hitListener.uncachePlayer(player);
        }
        if (tickListener != null) {
            tickListener.stopPlayerTask(player);
        }
        if (rotationListener != null) {
            rotationListener.handlePlayerQuit(player);
        }
        if (aiCheck != null) {
            aiCheck.handlePlayerQuit(player);
        }
        if (alertManager != null) {
            alertManager.handlePlayerQuit(player);
        }
        if (violationManager != null) {
            violationManager.handlePlayerQuit(player);
        }
        if (plugin.getAiClientProvider() != null) {
            plugin.getAiClientProvider().handlePlayerQuit(player.getUniqueId());
        }
        if (plugin.getDetectionResponseManager() != null) {
            plugin.getDetectionResponseManager().handlePlayerQuit(player);
        }
        if (sessionManager != null) {
            sessionManager.removeAimProcessor(player.getUniqueId());
        }
        if (hologramManager != null) {
            hologramManager.handleQuit(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (hologramManager != null) {
            hologramManager.handleDeath(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Entity id changes on respawn - refresh the hit cache so attacks on this player resolve.
        if (hitListener != null) {
            hitListener.cacheEntity(event.getPlayer());
        }
        if (hologramManager != null) {
            hologramManager.handleRespawn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (hitListener != null) {
            hitListener.cacheEntity(event.getPlayer());
        }
        if (hologramManager != null) {
            hologramManager.handleWorldChange(event.getPlayer());
        }
    }
}
