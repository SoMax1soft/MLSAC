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

import org.bukkit.plugin.Plugin;

public class SchedulerManager {
    private static SchedulerAdapter adapter;
    private static ServerType serverType;
    private static boolean initialized = false;

    public static void initialize(Plugin plugin) {
        if (initialized) {
            throw new IllegalStateException("SchedulerManager is already initialized");
        }
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        try {
            serverType = detectServerType(plugin);
            if (serverType == ServerType.FOLIA) {
                // Use reflection to avoid NoClassDefFoundError on non-Folia servers when
                // verifying the class
                adapter = (SchedulerAdapter) Class.forName("wtf.mlsac.scheduler.FoliaSchedulerAdapter")
                        .getConstructor(Plugin.class)
                        .newInstance(plugin);
            } else {
                adapter = new BukkitSchedulerAdapter(plugin);
            }
            initialized = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SchedulerManager", e);
        }
    }

    public static SchedulerAdapter getAdapter() {
        if (!initialized || adapter == null) {
            throw new IllegalStateException(
                    "SchedulerManager has not been initialized. Call initialize(plugin) first.");
        }
        return adapter;
    }

    public static ServerType getServerType() {
        if (!initialized) {
            throw new IllegalStateException(
                    "SchedulerManager has not been initialized. Call initialize(plugin) first.");
        }
        return serverType;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static ServerType detectServerType(Plugin plugin) {
        return hasFoliaApi() ? ServerType.FOLIA : ServerType.BUKKIT;
    }

    public static boolean hasFoliaApi() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void reset() {
        adapter = null;
        serverType = null;
        initialized = false;
    }
}
