/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The split configuration layout: {@code modules.yml} for what runs, {@code modules/<name>.yml}
 * for how each module behaves.
 *
 * <p>Everything is folded back into the same flat key space {@link Config} already reads, so a
 * module file's {@code max_distance} lands on {@code anti_esp.max_distance} exactly as it did when
 * it lived in config.yml. Splitting the files therefore costs nothing at the read side.
 *
 * <p>On/off lives in exactly one place. Module files carry no {@code enabled} key: a server owner
 * looking for "is this on?" has one file to open, and there is no second switch to contradict it.
 *
 * <p>Only a module with enough settings to be worth the indirection gets its own file. The rest
 * keep theirs in modules.yml next to the switch, written as {@code <module>-<setting>} and mounted
 * onto {@code <section>.<setting>} — one setting does not justify a file of its own.
 *
 * <p>An existing config.yml is migrated on first start — values move into the module file, the
 * {@code enabled} flag moves into modules.yml, and the old section is dropped. The migration
 * rewrites the module file from its bundled template rather than re-serialising it, so the
 * comments survive.
 */
public final class ModuleLayout {

    public static final String MODULES_FILE = "modules.yml";

    private ModuleLayout() {
    }

    /** A togglable module. */
    public enum Module {
        DETECTION("detection", "detection", null),
        ANTI_ESP("anti-esp", "anti_esp", "modules/anti-esp.yml"),
        REPORTS("reports", "reports", null),
        MENUS("menus", "menus", null),
        VISION("vision", "vision", null),
        UPDATES("updates", "updates", null);

        private final String key;
        private final String mount;
        private final String resource;

        Module(String key, String mount, String resource) {
            this.key = key;
            this.mount = mount;
            this.resource = resource;
        }

        /** The switch in modules.yml. */
        public String key() {
            return key;
        }

        /** The section this module's settings are mounted under. */
        public String mount() {
            return mount;
        }

        /** Where {@link Config} reads the switch from. */
        public String enabledPath() {
            return mount + ".enabled";
        }

        /** The settings file shipped for this module, or null when it keeps them in modules.yml. */
        public String resource() {
            return resource;
        }
    }

    /**
     * Creates any missing file from the jar and migrates a pre-split config.yml.
     *
     * <p>Called before the configuration is read, and again on reload so a file deleted by hand
     * comes back. Migration only happens for files that did not exist yet, so it runs once.
     */
    public static void install(JavaPlugin plugin) {
        File dataFolder = plugin.getDataFolder();
        File configFile = new File(dataFolder, "config.yml");
        YamlConfiguration userConfig = configFile.exists()
                ? YamlConfiguration.loadConfiguration(configFile) : null;
        boolean configChanged = false;

        File modulesFile = new File(dataFolder, MODULES_FILE);
        boolean freshModules = !modulesFile.exists();
        if (freshModules) {
            plugin.saveResource(MODULES_FILE, false);
        }

        // Collected across all modules and written in one go: each rewrite starts from the bundled
        // template, so writing them one at a time would discard the previous module's switch.
        Map<String, Object> toggles = new LinkedHashMap<>();

        for (Module module : Module.values()) {
            Boolean toggleVal = null;
            if (userConfig != null) {
                if (userConfig.isBoolean(module.enabledPath())) {
                    toggleVal = userConfig.getBoolean(module.enabledPath());
                    userConfig.set(module.enabledPath(), null);
                    configChanged = true;
                } else if (userConfig.isBoolean(module.mount)) {
                    toggleVal = userConfig.getBoolean(module.mount);
                    userConfig.set(module.mount, null);
                    configChanged = true;
                } else if ("detection".equals(module.key) && userConfig.isBoolean("ai.enabled")) {
                    toggleVal = userConfig.getBoolean("ai.enabled");
                    userConfig.set("ai.enabled", null);
                    configChanged = true;
                } else if ("anti-esp".equals(module.key) && userConfig.isBoolean("anti-esp.enabled")) {
                    toggleVal = userConfig.getBoolean("anti-esp.enabled");
                    userConfig.set("anti-esp.enabled", null);
                    configChanged = true;
                }

                // Migrate inline settings for modules without dedicated files
                if ("reports".equals(module.key)) {
                    if (userConfig.isSet("reports.cooldown-seconds") || userConfig.isSet("reports.cooldown_seconds")) {
                        int cooldown = userConfig.getInt("reports.cooldown-seconds",
                                userConfig.getInt("reports.cooldown_seconds", 30));
                        if (freshModules) {
                            toggles.put("reports-cooldown-seconds", cooldown);
                        }
                        userConfig.set("reports.cooldown-seconds", null);
                        userConfig.set("reports.cooldown_seconds", null);
                        configChanged = true;
                    }
                } else if ("vision".equals(module.key)) {
                    if (userConfig.isSet("vision.region-scan-minutes") || userConfig.isSet("vision.region_scan_minutes")) {
                        int scanMinutes = userConfig.getInt("vision.region-scan-minutes",
                                userConfig.getInt("vision.region_scan_minutes", 5));
                        if (freshModules) {
                            toggles.put("vision-region-scan-minutes", scanMinutes);
                        }
                        userConfig.set("vision.region-scan-minutes", null);
                        userConfig.set("vision.region_scan_minutes", null);
                        configChanged = true;
                    }
                }

                if (userConfig.isConfigurationSection(module.mount)
                        && userConfig.getConfigurationSection(module.mount).getKeys(false).isEmpty()) {
                    userConfig.set(module.mount, null);
                    configChanged = true;
                }
            }

            if (toggleVal != null && freshModules) {
                toggles.put(module.key, toggleVal);
            }

            if (module.resource == null) {
                continue;
            }
            File file = new File(dataFolder, module.resource);
            if (!file.exists()) {
                plugin.saveResource(module.resource, false);
                if (userConfig != null && migrateSettings(plugin, module, file, userConfig)) {
                    configChanged = true;
                }
            }
        }

        if (!toggles.isEmpty()) {
            try {
                rewriteFromTemplate(plugin, MODULES_FILE, modulesFile, toggles);
                plugin.getLogger().info("[Config] Module switches moved from config.yml to "
                        + MODULES_FILE + ": " + toggles);
            } catch (Exception exception) {
                plugin.getLogger().warning("[Config] Could not write " + MODULES_FILE + ": "
                        + exception.getMessage());
            }
        }

        if (configChanged) {
            try {
                userConfig.save(configFile);
            } catch (Exception exception) {
                plugin.getLogger().warning("[Config] Could not rewrite config.yml: " + exception.getMessage());
            }
        }
    }

    /**
     * Moves one legacy section's settings out of config.yml into its own file.
     *
     * @return true if config.yml was changed and needs saving
     */
    private static boolean migrateSettings(JavaPlugin plugin, Module module, File moduleFile,
                                           YamlConfiguration userConfig) {
        ConfigurationSection legacy = userConfig.getConfigurationSection(module.mount);
        if (legacy == null && module.mount.contains("_")) {
            legacy = userConfig.getConfigurationSection(module.mount.replace('_', '-'));
        }
        if (legacy == null) {
            return false;
        }
        try {
            Map<String, Object> moved = new LinkedHashMap<>();
            for (String key : legacy.getKeys(true)) {
                Object value = legacy.get(key);
                if (value instanceof ConfigurationSection || "enabled".equals(key)) {
                    continue;
                }
                moved.put(key, value);
            }
            if (!moved.isEmpty()) {
                rewriteFromTemplate(plugin, module.resource, moduleFile, moved);
            }
            userConfig.set(module.mount, null);
            if (module.mount.contains("_")) {
                userConfig.set(module.mount.replace('_', '-'), null);
            }
            plugin.getLogger().info("[Config] Moved " + module.mount + " from config.yml to "
                    + module.resource + " (" + moved.size() + " settings)");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("[Config] Could not migrate " + module.mount + ": "
                    + exception.getMessage());
            return false;
        }
    }

    private static final Pattern TOP_LEVEL_ENTRY = Pattern.compile("^([A-Za-z0-9_.-]+):\\s*(.*)$");

    /**
     * Writes {@code overrides} into a copy of the bundled template, line by line.
     *
     * <p>Re-serialising a {@link YamlConfiguration} would drop every comment in the file, and these
     * files are mostly documentation. The bundled templates are flat, so matching the key at the
     * start of a line is enough to substitute a value while leaving the rest of the file alone.
     */
    private static void rewriteFromTemplate(JavaPlugin plugin, String resource, File target,
                                            Map<String, Object> overrides) throws Exception {
        List<String> lines;
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                return;
            }
            List<String> template = new ArrayList<>();
            for (String line : new String(readAll(stream), StandardCharsets.UTF_8).split("\n", -1)) {
                template.add(line);
            }
            lines = template;
        }

        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher matcher = TOP_LEVEL_ENTRY.matcher(line.replace("\r", ""));
            if (matcher.matches() && overrides.containsKey(matcher.group(1))) {
                out.add(matcher.group(1) + ": " + format(overrides.get(matcher.group(1))));
            } else {
                out.add(line.replace("\r", ""));
            }
        }
        Files.write(target.toPath(), String.join(System.lineSeparator(), out).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String format(Object value) {
        if (value instanceof String) {
            return "\"" + ((String) value).replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }

    /**
     * Folds the split files into {@code merged}, on top of whatever config.yml already put there.
     *
     * <p>Module files first, modules.yml last: modules.yml is the final word, both on whether
     * something runs and on the settings it carries inline.
     */
    public static void apply(JavaPlugin plugin, YamlConfiguration merged) {
        for (Module module : Module.values()) {
            if (module.resource == null) {
                continue;
            }
            File file = new File(plugin.getDataFolder(), module.resource);
            if (!file.exists()) {
                continue;
            }
            YamlConfiguration settings = YamlConfiguration.loadConfiguration(file);
            for (Map.Entry<String, Object> entry : settings.getValues(true).entrySet()) {
                if (entry.getValue() instanceof ConfigurationSection) {
                    continue;
                }
                merged.set(module.mount + "." + entry.getKey(), entry.getValue());
            }
        }

        File modulesFile = new File(plugin.getDataFolder(), MODULES_FILE);
        if (!modulesFile.exists()) {
            return;
        }
        YamlConfiguration modules = YamlConfiguration.loadConfiguration(modulesFile);
        for (String key : modules.getKeys(false)) {
            Object value = modules.get(key);
            if (value instanceof ConfigurationSection) {
                continue;
            }
            String path = resolve(key);
            if (path != null) {
                merged.set(path, value);
            }
        }
    }

    /**
     * Maps a modules.yml key onto the path {@link Config} reads.
     *
     * <p>{@code reports} is the switch, {@code reports-cooldown-seconds} is one of that module's
     * settings. Anything that matches no module is ignored rather than guessed at.
     */
    private static String resolve(String key) {
        for (Module module : Module.values()) {
            if (key.equals(module.key)) {
                return module.enabledPath();
            }
            String prefix = module.key + "-";
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                return module.mount + "." + key.substring(prefix.length());
            }
        }
        return null;
    }

    /** The bundled defaults, used when a module file is missing entirely. */
    public static YamlConfiguration loadBundled(JavaPlugin plugin, String resource) {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return new YamlConfiguration();
        }
    }
}
