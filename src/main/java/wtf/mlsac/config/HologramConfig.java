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

package wtf.mlsac.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class HologramConfig {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;

    public HologramConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "holograms.yml");
    }

    public void load() {
        config = ConfigSyncUtil.loadAndSync(plugin, "holograms.yml", configFile);
    }

    public FileConfiguration getConfig() {
        if (config == null) {
            load();
        }
        return config;
    }

    public void save() {
        if (config == null || configFile == null)
            return;
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save holograms.yml!");
        }
    }

    public void reload() {
        load();
    }
}
