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
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the effective configuration by stacking three layers, lowest priority first:
 *
 * <ol>
 *   <li>{@code managed-defaults.yml} bundled in the jar. Never written to disk, so it always
 *       matches the plugin build.</li>
 *   <li>{@code plugins/MLSAC/config.yml} — the operator's local file, including any legacy copies
 *       of the managed sections.</li>
 *   <li>The preset fetched from the MLSAC API, validated by {@link RemoteConfigClient}.</li>
 * </ol>
 *
 * The result is an ordinary {@link YamlConfiguration}, so {@link Config} reads it with the same
 * calls regardless of which layer a value came from.
 */
public final class ConfigLayers {
    private static final String MANAGED_DEFAULTS_RESOURCE = "managed-defaults.yml";

    private ConfigLayers() {
    }

    /**
     * @param snapshot the active API preset, or {@code null} for local configuration only.
     */
    public static YamlConfiguration build(JavaPlugin plugin, RemoteConfigClient.Snapshot snapshot) {
        YamlConfiguration merged = new YamlConfiguration();
        applyLayer(merged, loadManagedDefaults(plugin));
        applyLayer(merged, plugin.getConfig());

        if (snapshot != null && snapshot.hasConfig()) {
            // Sections the preset owns outright are wiped first: a key-by-key merge would
            // resurrect entries deleted on the site but still present in config.yml.
            for (String section : snapshot.getReplacedSections()) {
                merged.set(section, null);
            }
            for (Map.Entry<String, Object> entry : snapshot.getOverlay().entrySet()) {
                merged.set(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /** Loads the bundled defaults. An empty configuration is returned if the resource is missing. */
    public static YamlConfiguration loadManagedDefaults(JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource(MANAGED_DEFAULTS_RESOURCE)) {
            if (stream == null) {
                plugin.getLogger().warning("[Config] " + MANAGED_DEFAULTS_RESOURCE
                        + " missing from the jar - falling back to hardcoded defaults");
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.getLogger().warning("[Config] Failed to read " + MANAGED_DEFAULTS_RESOURCE + ": "
                    + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    /**
     * Copies every leaf value of {@code source} into {@code target}. Sections are skipped: writing
     * a leaf creates its parents, while copying a section object would drop keys the lower layer
     * already set inside it.
     */
    private static void applyLayer(YamlConfiguration target, FileConfiguration source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.getValues(true).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection) {
                continue;
            }
            target.set(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Flattens a nested {@code Map} (decoded from the API's JSON) into {@code path -> value}
     * entries. Lists are copied as-is, since a list is replaced wholesale rather than merged.
     */
    public static Map<String, Object> flatten(Map<String, Object> nested) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flattenInto(flat, "", nested);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(Map<String, Object> out, String prefix, Map<String, Object> nested) {
        for (Map.Entry<String, Object> entry : nested.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenInto(out, path, (Map<String, Object>) value);
            } else {
                out.put(path, value);
            }
        }
    }
}
