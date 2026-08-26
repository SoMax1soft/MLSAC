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

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class ConfigSyncUtil {
    private static final String SCHEMA_VERSION_KEY = "_schema-version";
    // Paths preserved across a config schema wipe. Anything else is regenerated from defaults.
    //
    // The b49 schema moved worldguard, models, alerts, violation, penalties and alert-responses to
    // the site, so the bundled config.yml no longer defines them. They stay listed here so an
    // existing server keeps its locally tuned values after the update; a preset then takes
    // priority over them.
    private static final Set<String> CONFIG_PRESERVED_PATHS = Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "language",
                    "outputDirectory",
                    "debug",
                    "detection.api-key",
                    "detection.endpoint",
                    "detection.reserve-endpoint",
                    "detection.worldguard",
                    "detection.models",
                    "remote-config.preset",
                    "remote-config.refresh-minutes",
                    "punishments",
                    "alerts",
                    "violation",
                    "penalties",
                    "alert-responses")));

    private ConfigSyncUtil() {
    }

    /**
     * One-shot schema wipe. If the bundled {@value #SCHEMA_VERSION_KEY} differs from the value in
     * the user's config.yml (or it's missing), the user's config is regenerated from defaults —
     * but {@link #CONFIG_PRESERVED_PATHS} (API key, detection on/off, penalties.actions) are kept.
     * Designed for breaking schema changes (e.g. b44's switch from detection-counts to buffer values).
     */
    public static boolean migrateConfigSchemaIfNeeded(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return false;
        }
        try (InputStream bundledStream = plugin.getResource("config.yml")) {
            if (bundledStream == null) {
                return false;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
            String bundledSchema = bundled.getString(SCHEMA_VERSION_KEY);
            if (bundledSchema == null || bundledSchema.isEmpty()) {
                return false;
            }

            YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
            String existingSchema = existing.getString(SCHEMA_VERSION_KEY);
            if (bundledSchema.equals(existingSchema)) {
                return false;
            }

            java.util.Map<String, Object> preserved = new java.util.LinkedHashMap<>();
            for (String path : CONFIG_PRESERVED_PATHS) {
                if (!existing.isSet(path)) {
                    continue;
                }
                ConfigurationSection section = existing.getConfigurationSection(path);
                if (section != null) {
                    preserved.put(path, section.getValues(true));
                } else {
                    preserved.put(path, existing.get(path));
                }
            }

            if (!configFile.delete()) {
                plugin.getLogger().warning("Schema migration: failed to delete old config.yml");
                return false;
            }
            plugin.saveResource("config.yml", false);
            YamlConfiguration fresh = YamlConfiguration.loadConfiguration(configFile);

            for (java.util.Map.Entry<String, Object> entry : preserved.entrySet()) {
                String path = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof java.util.Map) {
                    fresh.set(path, null);
                    fresh.createSection(path, (java.util.Map<?, ?>) value);
                } else {
                    fresh.set(path, value);
                }
            }

            fresh.save(configFile);
            plugin.reloadConfig();
            plugin.getLogger().info("Schema migration (" + existingSchema + " -> " + bundledSchema
                    + "): config.yml regenerated; preserved " + preserved.keySet());
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("Schema migration failed for config.yml: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Wipe-and-replace localized messages files (messages_ru.yml, messages_en.yml, messages_vi.yml)
     * the moment ANY bundled key is missing from the user's file.
     */
    public static boolean wipeMessagesIfAnyKeyMissing(JavaPlugin plugin) {
        boolean anyRegenerated = false;
        for (String lang : new String[]{"ru", "en", "vi"}) {
            String fileName = "messages_" + lang + ".yml";
            File messagesFile = new File(plugin.getDataFolder(), fileName);
            if (!messagesFile.exists()) {
                try {
                    plugin.saveResource(fileName, false);
                    anyRegenerated = true;
                } catch (Exception ignored) {
                }
                continue;
            }
            try (InputStream bundledStream = plugin.getResource(fileName)) {
                if (bundledStream == null) {
                    continue;
                }
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
                YamlConfiguration existing = YamlConfiguration.loadConfiguration(messagesFile);
                String missing = firstMissingLeafKey(bundled, existing, "");
                if (missing == null) {
                    continue;
                }
                if (!messagesFile.delete()) {
                    plugin.getLogger().warning(fileName + " wipe failed: cannot delete file");
                    continue;
                }
                plugin.saveResource(fileName, false);
                plugin.getLogger().info(fileName + " fully regenerated (missing key: " + missing + ")");
                anyRegenerated = true;
            } catch (Exception exception) {
                plugin.getLogger().warning("Failed to wipe " + fileName + ": " + exception.getMessage());
            }
        }
        return anyRegenerated;
    }

    private static String firstMissingLeafKey(FileConfiguration bundled, FileConfiguration existing, String path) {
        ConfigurationSection section = path.isEmpty() ? bundled : bundled.getConfigurationSection(path);
        if (section == null) {
            return null;
        }
        for (String key : section.getKeys(false)) {
            String full = path.isEmpty() ? key : path + "." + key;
            if (bundled.getConfigurationSection(full) != null) {
                String nested = firstMissingLeafKey(bundled, existing, full);
                if (nested != null) {
                    return nested;
                }
            } else if (!existing.isSet(full)) {
                return full;
            }
        }
        return null;
    }

    public static boolean syncPluginConfig(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try (InputStream inputStream = plugin.getResource("config.yml")) {
            if (inputStream == null) {
                return false;
            }

            YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
            YamlConfiguration defaults = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            boolean changed = migrateLegacyDetectionSettings(current);
            changed |= copyMissing(current, defaults, "");
            if (changed) {
                current.save(configFile);
                plugin.reloadConfig();
                plugin.getLogger().info("Added or migrated missing entries in config.yml");
            }
            return changed;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to sync config.yml: " + exception.getMessage());
            return false;
        }
    }

    public static boolean syncResourceConfig(JavaPlugin plugin, String resourceName, File configFile) {
        if (!configFile.exists()) {
            plugin.saveResource(resourceName, false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream inputStream = plugin.getResource(resourceName)) {
            if (inputStream == null) {
                return false;
            }

            YamlConfiguration defaults = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            boolean changed = copyMissing(config, defaults, "");
            if (changed) {
                config.save(configFile);
                plugin.getLogger().info("Added missing entries to " + resourceName);
            }
            return changed;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to sync " + resourceName + ": " + exception.getMessage());
            return false;
        }
    }

    public static boolean syncAllPluginConfigs(JavaPlugin plugin) {
        File dataFolder = plugin.getDataFolder();
        boolean changed = syncPluginConfig(plugin);
        for (String lang : new String[]{"ru", "en", "vi"}) {
            String fileName = "messages_" + lang + ".yml";
            changed |= syncResourceConfig(plugin, fileName, new File(dataFolder, fileName));
        }
        for (String lang : new String[]{"ru", "en", "vi"}) {
            String fileName = "menu_" + lang + ".yml";
            changed |= syncResourceConfig(plugin, fileName, new File(dataFolder, fileName));
        }
        changed |= syncResourceConfig(plugin, "holograms.yml", new File(dataFolder, "holograms.yml"));
        // The split module layout: modules.yml and every modules/<name>.yml the build ships.
        changed |= syncResourceConfig(plugin, ModuleLayout.MODULES_FILE,
                new File(dataFolder, ModuleLayout.MODULES_FILE));
        for (ModuleLayout.Module module : ModuleLayout.Module.values()) {
            String resource = module.resource();
            if (resource != null) {
                changed |= syncResourceConfig(plugin, resource, new File(dataFolder, resource));
            }
        }
        return changed;
    }

    public static FileConfiguration loadAndSync(JavaPlugin plugin, String resourceName, File configFile) {
        if (!configFile.exists()) {
            plugin.saveResource(resourceName, false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream inputStream = plugin.getResource(resourceName)) {
            if (inputStream != null) {
                YamlConfiguration defaults = YamlConfiguration
                        .loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                boolean changed = copyMissing(config, defaults, "");
                if (changed) {
                    config.save(configFile);
                    plugin.getLogger().info("Added missing entries to " + resourceName);
                }
                config.setDefaults(defaults);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to sync " + resourceName + ": " + exception.getMessage());
        }

        return config;
    }

    private static boolean migrateLegacyDetectionSettings(FileConfiguration target) {
        boolean changed = false;

        changed |= copyLegacyApiKey(target);
        changed |= copyLegacyIfMissing(target, "ai.enabled", "detection.enabled");
        changed |= copyLegacyIfMissing(target, "ai.server", "detection.endpoint");
        changed |= copyLegacyIfMissing(target, "ai.sequence", "detection.sample-size");
        changed |= copyLegacyIfMissing(target, "ai.step", "detection.sample-interval");
        changed |= copyLegacyIfMissing(target, "ai.alert.threshold", "alerts.threshold");
        changed |= copyLegacyIfMissing(target, "ai.alert.console", "alerts.console");
        changed |= copyLegacyIfMissing(target, "ai.buffer.flag", "violation.threshold");
        changed |= copyLegacyIfMissing(target, "ai.buffer.reset-on-flag", "violation.reset-value");
        changed |= copyLegacyIfMissing(target, "ai.buffer.multiplier", "violation.multiplier");
        
        // Migrate legacy decay format (double) to section (threshold, amount)
        if (target.isSet("ai.buffer.decrease") && (target.isDouble("ai.buffer.decrease") || target.isInt("ai.buffer.decrease"))) {
            if (!target.isSet("violation.decay")) {
                double val = target.getDouble("ai.buffer.decrease");
                target.set("violation.decay.amount", val);
                target.set("violation.decay.threshold", 0.10);
                changed = true;
            }
            target.set("ai.buffer.decrease", null);
            changed = true;
        }
        
        if (target.isSet("violation.decay") && (target.isDouble("violation.decay") || target.isInt("violation.decay"))) {
            double val = target.getDouble("violation.decay");
            target.set("violation.decay", null);
            target.set("violation.decay.amount", val);
            target.set("violation.decay.threshold", 0.10);
            changed = true;
        }

        changed |= copyLegacySectionIfMissing(target, "ai.models", "detection.models");
        changed |= copyLegacySectionIfMissing(target, "ai.punishment.commands", "penalties.actions");

        return changed;
    }

    private static boolean copyLegacyApiKey(FileConfiguration target) {
        if (!target.isSet("ai.api-key")) {
            return false;
        }

        String legacyApiKey = target.getString("ai.api-key", "").trim();
        if (isApiKeyPlaceholder(legacyApiKey)) {
            return false;
        }

        String currentApiKey = target.getString("detection.api-key", "");
        if (target.isSet("detection.api-key") && !isApiKeyPlaceholder(currentApiKey)) {
            return false;
        }

        target.set("detection.api-key", legacyApiKey);
        return true;
    }

    private static boolean copyLegacyIfMissing(FileConfiguration target, String legacyPath, String newPath) {
        if (target.isSet(newPath) || !target.isSet(legacyPath)) {
            return false;
        }

        target.set(newPath, target.get(legacyPath));
        return true;
    }

    private static boolean copyLegacySectionIfMissing(FileConfiguration target, String legacyPath, String newPath) {
        if (target.isSet(newPath)) {
            return false;
        }

        ConfigurationSection legacySection = target.getConfigurationSection(legacyPath);
        if (legacySection == null) {
            return false;
        }

        copySectionContents(target, legacySection, newPath);
        return true;
    }

    private static void copySectionContents(FileConfiguration target, ConfigurationSection source, String targetPath) {
        for (String key : source.getKeys(false)) {
            String childTargetPath = targetPath + "." + key;
            ConfigurationSection childSection = source.getConfigurationSection(key);
            if (childSection != null) {
                copySectionContents(target, childSection, childTargetPath);
            } else {
                target.set(childTargetPath, source.get(key));
            }
        }
    }

    private static boolean isApiKeyPlaceholder(String value) {
        if (value == null) {
            return true;
        }

        String normalized = value.trim();
        return normalized.isEmpty() || "your-api-key".equalsIgnoreCase(normalized);
    }

    private static boolean copyMissing(FileConfiguration target, FileConfiguration defaults, String path) {
        boolean changed = false;
        ConfigurationSection defaultSection = path.isEmpty()
                ? defaults
                : defaults.getConfigurationSection(path);
        if (defaultSection == null) {
            return false;
        }

        for (String key : defaultSection.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            ConfigurationSection childDefaults = defaults.getConfigurationSection(fullPath);
            if (childDefaults != null) {
                boolean createdOrRepairedSection = false;
                if (!target.isSet(fullPath)) {
                    target.createSection(fullPath);
                    changed = true;
                    createdOrRepairedSection = true;
                } else if (!target.isConfigurationSection(fullPath)) {
                    target.set(fullPath, null);
                    target.createSection(fullPath);
                    changed = true;
                    createdOrRepairedSection = true;
                }
                // Numeric-key scalar maps are user-defined thresholds, not fixed child fields.
                if (!createdOrRepairedSection && isUserKeyedScalarMap(defaults, fullPath)) {
                    continue;
                }
                changed |= copyMissing(target, defaults, fullPath);
                continue;
            }

            Object defaultValue = defaults.get(fullPath);
            if (!target.isSet(fullPath)) {
                target.set(fullPath, defaultValue);
                changed = true;
            } else if (defaultValue instanceof List && !(target.get(fullPath) instanceof List)) {
                target.set(fullPath, defaultValue);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isUserKeyedScalarMap(FileConfiguration defaults, String path) {
        ConfigurationSection section = defaults.getConfigurationSection(path);
        if (section == null) {
            return false;
        }

        boolean hasKeys = false;
        for (String key : section.getKeys(false)) {
            hasKeys = true;
            String childPath = path + "." + key;
            if (!isIntegerKey(key) || defaults.getConfigurationSection(childPath) != null) {
                return false;
            }
        }
        return hasKeys;
    }

    private static boolean isIntegerKey(String key) {
        try {
            Integer.parseInt(key);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
