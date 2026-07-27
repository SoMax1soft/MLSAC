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

package wtf.mlsac.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class MenuConfig {
    private static final java.util.Set<String> SUPPORTED_LANGS = java.util.Set.of("en", "ru", "vi");

    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;
    private String language;

    public MenuConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.language = "en";
        this.configFile = new File(plugin.getDataFolder(), "menu_en.yml");
    }

    /** Picks the menu file for a language, mirroring {@link MessagesConfig#setLanguage(String)}. */
    public void setLanguage(String lang) {
        this.language = SUPPORTED_LANGS.contains(lang) ? lang : "en";
        this.configFile = new File(plugin.getDataFolder(), "menu_" + this.language + ".yml");
    }

    public void load() {
        String fileName = "menu_" + language + ".yml";
        if (!configFile.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Could not save " + fileName + ": " + e.getMessage());
            }
        }
        config = ConfigSyncUtil.loadAndSync(plugin, fileName, configFile);
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
            plugin.getLogger().severe("Could not save " + configFile.getName() + "!");
        }
    }

    public void reload() {
        load();
    }
}
