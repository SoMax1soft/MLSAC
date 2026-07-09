/*
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

package wtf.mlsac.scheduler;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
public interface SchedulerAdapter {
    ScheduledTask runSync(Runnable task);
    ScheduledTask runSyncDelayed(Runnable task, long delayTicks);
    ScheduledTask runSyncRepeating(Runnable task, long delayTicks, long periodTicks);
    ScheduledTask runAsync(Runnable task);
    ScheduledTask runAsyncDelayed(Runnable task, long delayTicks);
    ScheduledTask runAsyncRepeating(Runnable task, long delayTicks, long periodTicks);
    ScheduledTask runEntitySync(Entity entity, Runnable task);
    ScheduledTask runEntitySyncDelayed(Entity entity, Runnable task, long delayTicks);
    ScheduledTask runEntitySyncRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks);
    ScheduledTask runRegionSync(Location location, Runnable task);
    ScheduledTask runRegionSyncDelayed(Location location, Runnable task, long delayTicks);
    ScheduledTask runRegionSyncRepeating(Location location, Runnable task, long delayTicks, long periodTicks);
    ServerType getServerType();
}