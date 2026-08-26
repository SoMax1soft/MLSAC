/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the split config layout: where a value ends up, which file wins, and what happens to a
 * config.yml written before the split existed.
 */
class ModuleLayoutTest {

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("ModuleLayoutTest"));
        Mockito.when(plugin.getResource(Mockito.anyString()))
                .thenAnswer(call -> bundled(call.getArgument(0)));
        // The real saveResource copies a jar entry into the data folder, subdirectories included.
        Mockito.doAnswer(call -> {
            String name = call.getArgument(0);
            File target = new File(dataFolder.toFile(), name);
            target.getParentFile().mkdirs();
            try (InputStream in = bundled(name); OutputStream out = Files.newOutputStream(target.toPath())) {
                if (in == null) {
                    throw new IllegalArgumentException("missing bundled resource: " + name);
                }
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    out.write(chunk, 0, read);
                }
            }
            return null;
        }).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
    }

    private static InputStream bundled(String name) {
        return ModuleLayoutTest.class.getClassLoader().getResourceAsStream(name);
    }

    private File file(String name) {
        return new File(dataFolder.toFile(), name);
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8);
    }

    private void writeConfigYml(String body) throws Exception {
        Files.write(file("config.yml").toPath(), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A fresh install lays down modules.yml and every module file")
    void testFreshInstall() {
        ModuleLayout.install(plugin);

        assertTrue(file(ModuleLayout.MODULES_FILE).exists());
        for (ModuleLayout.Module module : ModuleLayout.Module.values()) {
            if (module.resource() != null) {
                assertTrue(file(module.resource()).exists(), module.resource() + " must be created");
            }
        }
    }

    @Test
    @DisplayName("Module settings are mounted under the section Config reads")
    void testSettingsAreMounted() throws Exception {
        ModuleLayout.install(plugin);
        Files.write(file("modules/anti-esp.yml").toPath(),
                "max_distance: 12.5\nray_count: 3\n".getBytes(StandardCharsets.UTF_8));

        YamlConfiguration merged = new YamlConfiguration();
        ModuleLayout.apply(plugin, merged);

        assertEquals(12.5, merged.getDouble("anti_esp.max_distance"), 1.0E-9);
        assertEquals(3, merged.getInt("anti_esp.ray_count"));
    }

    @Test
    @DisplayName("modules.yml has the final word on whether a module runs")
    void testTogglesWinOverEverythingBelow() throws Exception {
        ModuleLayout.install(plugin);
        Files.write(file(ModuleLayout.MODULES_FILE).toPath(),
                "anti-esp: true\nreports: false\n".getBytes(StandardCharsets.UTF_8));

        YamlConfiguration merged = new YamlConfiguration();
        // Whatever a lower layer (config.yml, managed defaults) claimed must lose.
        merged.set("anti_esp.enabled", false);
        merged.set("reports.enabled", true);
        ModuleLayout.apply(plugin, merged);

        assertTrue(merged.getBoolean("anti_esp.enabled"));
        assertFalse(merged.getBoolean("reports.enabled"));
    }

    @Test
    @DisplayName("A module's inline settings are mounted onto its section")
    void testInlineSettingsAreMounted() throws Exception {
        // Modules too small to deserve a file keep their settings next to the switch.
        ModuleLayout.install(plugin);
        Files.write(file(ModuleLayout.MODULES_FILE).toPath(), String.join("\n",
                "reports: true",
                "reports-cooldown-seconds: 7",
                "vision-region-scan-minutes: 0",
                "").getBytes(StandardCharsets.UTF_8));

        YamlConfiguration merged = new YamlConfiguration();
        ModuleLayout.apply(plugin, merged);

        assertEquals(7, merged.getInt("reports.cooldown-seconds"));
        assertEquals(0, merged.getInt("vision.region-scan-minutes"));
        assertTrue(merged.getBoolean("reports.enabled"));
    }

    @Test
    @DisplayName("An unrecognised key in modules.yml is ignored, not guessed at")
    void testUnknownKeyIsIgnored() throws Exception {
        ModuleLayout.install(plugin);
        Files.write(file(ModuleLayout.MODULES_FILE).toPath(),
                "nonsense: 5\nnonsense-thing: 9\n".getBytes(StandardCharsets.UTF_8));

        YamlConfiguration merged = new YamlConfiguration();
        ModuleLayout.apply(plugin, merged);

        for (String path : merged.getKeys(true)) {
            assertFalse(path.contains("nonsense"), "Unmapped key leaked into the config: " + path);
        }
    }

    @Test
    @DisplayName("A module absent from modules.yml leaves the lower layer alone")
    void testUnsetToggleIsNotForced() throws Exception {
        ModuleLayout.install(plugin);
        Files.write(file(ModuleLayout.MODULES_FILE).toPath(), "anti-esp: true\n".getBytes(StandardCharsets.UTF_8));

        YamlConfiguration merged = new YamlConfiguration();
        merged.set("reports.enabled", true);
        ModuleLayout.apply(plugin, merged);

        assertTrue(merged.getBoolean("reports.enabled"), "An unlisted module must not be silently turned off");
    }

    @Test
    @DisplayName("A pre-split config.yml is moved into the module files")
    void testLegacyMigration() throws Exception {
        writeConfigYml(String.join("\n",
                "debug: true",
                "anti_esp:",
                "  enabled: true",
                "  max_distance: 24.0",
                "  ray_count: 9",
                ""));

        ModuleLayout.install(plugin);

        YamlConfiguration moved = YamlConfiguration.loadConfiguration(file("modules/anti-esp.yml"));
        assertEquals(24.0, moved.getDouble("max_distance"), 1.0E-9);
        assertEquals(9, moved.getInt("ray_count"));
        assertFalse(moved.isSet("enabled"), "The switch belongs in modules.yml, not here");

        YamlConfiguration modules = YamlConfiguration.loadConfiguration(file(ModuleLayout.MODULES_FILE));
        assertTrue(modules.getBoolean("anti-esp"), "The old enabled flag becomes the module switch");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file("config.yml"));
        assertFalse(config.isSet("anti_esp"), "The migrated section is removed from config.yml");
        assertTrue(config.getBoolean("debug"), "Untouched keys stay put");
    }

    @Test
    @DisplayName("Every legacy switch is carried over, not just the last one")
    void testAllTogglesMigrateTogether() throws Exception {
        // Each rewrite starts from the bundled template, so a per-module write would drop the
        // switches handled before it.
        writeConfigYml(String.join("\n",
                "detection:",
                "  enabled: false",
                "  api-key: keep-me",
                "anti_esp:",
                "  enabled: true",
                "vision:",
                "  enabled: true",
                ""));

        ModuleLayout.install(plugin);

        YamlConfiguration modules = YamlConfiguration.loadConfiguration(file(ModuleLayout.MODULES_FILE));
        assertFalse(modules.getBoolean("detection"), "detection was off and must stay off");
        assertTrue(modules.getBoolean("anti-esp"));
        assertTrue(modules.getBoolean("vision"));
    }

    @Test
    @DisplayName("A switch is removed from config.yml but its neighbours survive")
    void testEnabledFlagLeavesConfigYml() throws Exception {
        writeConfigYml(String.join("\n",
                "detection:",
                "  enabled: false",
                "  api-key: keep-me",
                "  endpoint: https://example.invalid",
                ""));

        ModuleLayout.install(plugin);

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file("config.yml"));
        assertFalse(config.isSet("detection.enabled"), "The switch moved to modules.yml");
        assertEquals("keep-me", config.getString("detection.api-key"));
        assertEquals("https://example.invalid", config.getString("detection.endpoint"));
    }

    @Test
    @DisplayName("Migration keeps the documentation in the module file")
    void testMigrationPreservesComments() throws Exception {
        writeConfigYml(String.join("\n", "anti_esp:", "  max_distance: 24.0", ""));

        ModuleLayout.install(plugin);

        String written = read("modules/anti-esp.yml");
        assertTrue(written.contains("max_distance: 24.0"), "The migrated value is written");
        assertTrue(written.contains("# Safe proximity radius"),
                "Re-serialising would have dropped every comment in the file");
    }

    @Test
    @DisplayName("An existing modules.yml is never overwritten by a legacy flag")
    void testOperatorTogglesWin() throws Exception {
        writeConfigYml(String.join("\n", "anti_esp:", "  enabled: true", "  max_distance: 24.0", ""));
        Files.write(file(ModuleLayout.MODULES_FILE).toPath(), "anti-esp: false\n".getBytes(StandardCharsets.UTF_8));

        ModuleLayout.install(plugin);

        YamlConfiguration modules = YamlConfiguration.loadConfiguration(file(ModuleLayout.MODULES_FILE));
        assertFalse(modules.getBoolean("anti-esp"), "The operator's own switch is the answer");
    }

    @Test
    @DisplayName("Migration runs once, not on every start")
    void testMigrationIsOneShot() throws Exception {
        writeConfigYml(String.join("\n", "anti_esp:", "  max_distance: 24.0", ""));
        ModuleLayout.install(plugin);

        // The operator retunes the module, then the server restarts.
        Files.write(file("modules/anti-esp.yml").toPath(), "max_distance: 64.0\n".getBytes(StandardCharsets.UTF_8));
        ModuleLayout.install(plugin);

        YamlConfiguration moved = YamlConfiguration.loadConfiguration(file("modules/anti-esp.yml"));
        assertEquals(64.0, moved.getDouble("max_distance"), 1.0E-9);
    }

    @Test
    @DisplayName("A missing module file is recreated without touching the rest")
    void testDeletedFileComesBack() {
        ModuleLayout.install(plugin);
        assertTrue(file("modules/anti-esp.yml").delete());

        ModuleLayout.install(plugin);

        assertTrue(file("modules/anti-esp.yml").exists());
    }

    @Test
    @DisplayName("Every module switch points at a path Config can read")
    void testEnabledPathsAreDistinct() {
        java.util.Set<String> paths = new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (ModuleLayout.Module module : ModuleLayout.Module.values()) {
            assertTrue(paths.add(module.enabledPath()), "duplicate path: " + module.enabledPath());
            assertTrue(keys.add(module.key()), "duplicate key: " + module.key());
            assertTrue(module.enabledPath().endsWith(".enabled"),
                    module.key() + " must map onto an .enabled path");
        }
    }

    @Test
    @DisplayName("Reports module disabled in modules.yml is mapped correctly")
    void testReportsDisabledMapping() throws Exception {
        Files.write(file(ModuleLayout.MODULES_FILE).toPath(),
                "reports: false\nreports-cooldown-seconds: 15\n".getBytes(StandardCharsets.UTF_8));

        YamlConfiguration merged = new YamlConfiguration();
        ModuleLayout.apply(plugin, merged);

        assertFalse(merged.getBoolean("reports.enabled"));
        assertEquals(15, merged.getInt("reports.cooldown-seconds"));
    }

    @Test
    @DisplayName("Legacy config.yml switches and inline settings migrate to modules.yml and are removed from config.yml")
    void testLegacyModulesMigration() throws Exception {
        writeConfigYml(String.join("\n",
                "detection:",
                "  enabled: false",
                "anti_esp:",
                "  enabled: true",
                "  max_distance: 35.0",
                "reports:",
                "  enabled: false",
                "  cooldown-seconds: 45",
                "vision:",
                "  enabled: true",
                "  region-scan-minutes: 12",
                "menus:",
                "  enabled: false",
                "updates:",
                "  enabled: false",
                ""));

        ModuleLayout.install(plugin);

        YamlConfiguration modules = YamlConfiguration.loadConfiguration(file(ModuleLayout.MODULES_FILE));
        assertFalse(modules.getBoolean("detection"));
        assertTrue(modules.getBoolean("anti-esp"));
        assertFalse(modules.getBoolean("reports"));
        assertEquals(45, modules.getInt("reports-cooldown-seconds"));
        assertTrue(modules.getBoolean("vision"));
        assertEquals(12, modules.getInt("vision-region-scan-minutes"));
        assertFalse(modules.getBoolean("menus"));
        assertFalse(modules.getBoolean("updates"));

        YamlConfiguration antiEsp = YamlConfiguration.loadConfiguration(file("modules/anti-esp.yml"));
        assertEquals(35.0, antiEsp.getDouble("max_distance"), 1.0E-9);

        YamlConfiguration cleanedConfig = YamlConfiguration.loadConfiguration(file("config.yml"));
        assertFalse(cleanedConfig.isSet("detection.enabled"));
        assertFalse(cleanedConfig.isSet("anti_esp"));
        assertFalse(cleanedConfig.isSet("reports"));
        assertFalse(cleanedConfig.isSet("vision"));
        assertFalse(cleanedConfig.isSet("menus"));
        assertFalse(cleanedConfig.isSet("updates"));
    }
}
