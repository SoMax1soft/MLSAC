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
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.util.ProbabilityFormatUtil;

import java.io.File;
import java.util.Set;

public class MessagesConfig {
    private static final Set<String> SUPPORTED_LANGS = Set.of("en", "ru", "vi");

    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;
    private String language;

    public MessagesConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.language = "en";
    }

    /**
     * Sets the language and resolves the messages file name.
     * Must be called before {@link #load()}.
     */
    public void setLanguage(String lang) {
        this.language = SUPPORTED_LANGS.contains(lang) ? lang : "en";
    }

    public void load() {
        String fileName = "messages_" + language + ".yml";
        this.configFile = new File(plugin.getDataFolder(), fileName);
        // Save bundled resource if it doesn't exist on disk yet
        if (!configFile.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (Exception e) {
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

    public void reload() {
        load();
    }

    public String getPrefix() {
        return getConfig().getString("prefix", "&6[MLSAC] &r");
    }

    /** Separate configurable prefix for report messages. Falls back to the main prefix. */
    public String getReportPrefix() {
        return getConfig().getString("report-prefix", getPrefix());
    }

    public String getMessage(String key) {
        return getConfig().getString(key, "&cMessage not found: " + key);
    }

    public String getMessage(String key, String player, double probability, double buffer, int vl) {
        String msg = getMessage(key);
        String playerValue = player != null ? player : "";
        String probValue = ProbabilityFormatUtil.formatPercent(probability) + "%";
        String bufferValue = String.format("%.1f", buffer);
        String vlValue = String.valueOf(vl);
        return msg
                .replace("{PLAYER}", playerValue)
                .replace("{PROBABILITY}", probValue)
                .replace("{BUFFER}", bufferValue)
                .replace("{VL}", vlValue)
                .replace("<player>", playerValue)
                .replace("<probability>", probValue)
                .replace("<buffer>", bufferValue)
                .replace("<vl>", vlValue);
    }

    public String getMessage(String key, String... replacements) {
        String msg = getMessage(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public java.util.List<String> getMessageList(String key) {
        return getConfig().getStringList(key);
    }
}
