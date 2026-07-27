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

import wtf.mlsac.checks.AICheck;
import wtf.mlsac.compat.EventCompat;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.session.ISessionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import wtf.mlsac.data.AIPlayerData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TickListener {
    private final ISessionManager sessionManager;
    private final AICheck aiCheck;
    private final EventCompat.TickHandler tickHandler;
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();
    private final boolean usePerPlayerTasks;
    private HitListener hitListener;

    public TickListener(JavaPlugin plugin, ISessionManager sessionManager, AICheck aiCheck) {
        this.sessionManager = sessionManager;
        this.aiCheck = aiCheck;
        this.tickHandler = EventCompat.createTickHandler(plugin, this::onTick);
        this.usePerPlayerTasks = SchedulerManager.getServerType() == ServerType.FOLIA;
    }

    public void start() {
        tickHandler.start();
    }

    public void stop() {
        tickHandler.stop();
        for (ScheduledTask task : playerTasks.values()) {
            task.cancel();
        }
        playerTasks.clear();
    }

    public void setHitListener(HitListener hitListener) {
        this.hitListener = hitListener;
    }

    private void onTick() {
        int currentTick = tickHandler.getCurrentTick();
        if (hitListener != null) {
            hitListener.setCurrentTick(currentTick);
        }
        if (!usePerPlayerTasks) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                aiCheck.onTick(player);
            }
        }
    }

    public void startPlayerTask(Player player) {
        if (!usePerPlayerTasks) {
            return;
        }
        if (playerTasks.containsKey(player.getUniqueId())) {
            return;
        }

        AIPlayerData data = aiCheck.getPlayerData(player.getUniqueId());
        if (data != null && data.isBedrock()) return;

        try {
            ScheduledTask task = SchedulerManager.getAdapter().runEntitySyncRepeating(player, () -> {
                aiCheck.onTick(player);
            }, 1L, 1L);
            playerTasks.put(player.getUniqueId(), task);
        } catch (Exception ignored) {
        }
    }

    public void stopPlayerTask(Player player) {
        ScheduledTask task = playerTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public int getCurrentTick() {
        return tickHandler.getCurrentTick();
    }
}
