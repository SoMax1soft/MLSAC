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

package wtf.mlsac.scheduler;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.TimeUnit;
public class FoliaSchedulerAdapter implements SchedulerAdapter {
    private final Plugin plugin;
    private final GlobalRegionScheduler globalScheduler;
    private final AsyncScheduler asyncScheduler;
    private final RegionScheduler regionScheduler;
    public FoliaSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        this.globalScheduler = Bukkit.getGlobalRegionScheduler();
        this.asyncScheduler = Bukkit.getAsyncScheduler();
        this.regionScheduler = Bukkit.getRegionScheduler();
    }
    @Override
    public ScheduledTask runSync(Runnable task) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                globalScheduler.run(plugin, (scheduledTask) -> task.run());
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runSyncDelayed(Runnable task, long delayTicks) {
        long adjustedDelay = delayTicks <= 0 ? 1 : delayTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                globalScheduler.runDelayed(plugin, (scheduledTask) -> task.run(), adjustedDelay);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runSyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        long adjustedDelay = delayTicks <= 0 ? 1 : delayTicks;
        long adjustedPeriod = periodTicks <= 0 ? 1 : periodTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                globalScheduler.runAtFixedRate(plugin, (scheduledTask) -> task.run(), adjustedDelay, adjustedPeriod);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runAsync(Runnable task) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                asyncScheduler.runNow(plugin, (scheduledTask) -> task.run());
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runAsyncDelayed(Runnable task, long delayTicks) {
        long delayMs = delayTicks <= 0 ? 50L : delayTicks * 50L;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                asyncScheduler.runDelayed(plugin, (scheduledTask) -> task.run(), delayMs, TimeUnit.MILLISECONDS);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        long delayMs = delayTicks <= 0 ? 50L : delayTicks * 50L;
        long periodMs = periodTicks <= 0 ? 50L : periodTicks * 50L;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                asyncScheduler.runAtFixedRate(plugin, (scheduledTask) -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runEntitySync(Entity entity, Runnable task) {
        if (entity == null || !entity.isValid()) {
            return NoOpScheduledTask.INSTANCE;
        }
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                entity.getScheduler().run(plugin, (scheduledTask) -> task.run(), null);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runEntitySyncDelayed(Entity entity, Runnable task, long delayTicks) {
        if (entity == null || !entity.isValid()) {
            return NoOpScheduledTask.INSTANCE;
        }
        long adjustedDelay = delayTicks <= 0 ? 1 : delayTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                entity.getScheduler().runDelayed(plugin, (scheduledTask) -> task.run(), null, adjustedDelay);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runEntitySyncRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (entity == null || !entity.isValid()) {
            return NoOpScheduledTask.INSTANCE;
        }
        long adjustedDelay = delayTicks <= 0 ? 1 : delayTicks;
        long adjustedPeriod = periodTicks <= 0 ? 1 : periodTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                entity.getScheduler().runAtFixedRate(plugin, (scheduledTask) -> task.run(), null, adjustedDelay, adjustedPeriod);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runRegionSync(Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            return NoOpScheduledTask.INSTANCE;
        }
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                regionScheduler.run(plugin, location, (scheduledTask) -> task.run());
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runRegionSyncDelayed(Location location, Runnable task, long delayTicks) {
        if (location == null || location.getWorld() == null) {
            return NoOpScheduledTask.INSTANCE;
        }
        long adjustedDelay = delayTicks <= 0 ? 1 : delayTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                regionScheduler.runDelayed(plugin, location, (scheduledTask) -> task.run(), adjustedDelay);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ScheduledTask runRegionSyncRepeating(Location location, Runnable task, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null) {
            return NoOpScheduledTask.INSTANCE;
        }
        long adjustedDelay = delayTicks <= 0 ? 1 : delayTicks;
        long adjustedPeriod = periodTicks <= 0 ? 1 : periodTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask =
                regionScheduler.runAtFixedRate(plugin, location, (scheduledTask) -> task.run(), adjustedDelay, adjustedPeriod);
        return new FoliaScheduledTask(foliaTask);
    }
    @Override
    public ServerType getServerType() {
        return ServerType.FOLIA;
    }
}
