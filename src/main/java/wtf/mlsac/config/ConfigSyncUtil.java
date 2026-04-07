package wtf.mlsac.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public final class ConfigSyncUtil {
    private ConfigSyncUtil() {
    }

    public static void syncPluginConfig(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        try (InputStream inputStream = plugin.getResource("config.yml")) {
            if (inputStream == null) {
                return;
            }

            FileConfiguration current = plugin.getConfig();
            YamlConfiguration defaults = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            boolean changed = copyMissing(current, defaults, "");
            if (changed) {
                plugin.saveConfig();
                plugin.reloadConfig();
                plugin.getLogger().info("Added missing entries to config.yml");
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to sync config.yml: " + exception.getMessage());
        }
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

    private static boolean copyMissing(FileConfiguration target, FileConfiguration defaults, String path) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            if (!target.contains(fullPath)) {
                target.set(fullPath, defaults.get(fullPath));
                changed = true;
                continue;
            }

            Object defaultValue = defaults.get(fullPath);
            if (defaultValue instanceof ConfigurationSection && target.isConfigurationSection(fullPath)) {
                changed |= copyMissing(target, defaults, fullPath);
            }
        }
        return changed;
    }
}
