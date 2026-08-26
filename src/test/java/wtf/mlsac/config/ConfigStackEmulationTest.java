/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.config;

import org.bukkit.configuration.file.FileConfiguration;
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
 * Emulates a whole server's configuration being loaded: bundled defaults, the operator's files, the
 * split module layout and an API preset, stacked the way the plugin stacks them at boot.
 *
 * <p>The upgrade case is the one worth pinning down. A server that has been running since before
 * the split has all its settings in config.yml, and getting that wrong silently resets an entire
 * module to defaults — including turning detection back on for someone who deliberately had it off.
 */
class ConfigStackEmulationTest {

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigStackEmulationTest"));
        Mockito.when(plugin.getResource(Mockito.anyString()))
                .thenAnswer(call -> bundled(call.getArgument(0)));
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
        // getConfig() is the operator's config.yml, re-read from disk so a migration is visible.
        Mockito.when(plugin.getConfig()).thenAnswer(call ->
                YamlConfiguration.loadConfiguration(new File(dataFolder.toFile(), "config.yml")));
    }

    private static InputStream bundled(String name) {
        return ConfigStackEmulationTest.class.getClassLoader().getResourceAsStream(name);
    }

    private void writeConfigYml(String... lines) throws Exception {
        Files.write(new File(dataFolder.toFile(), "config.yml").toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private FileConfiguration load() {
        ModuleLayout.install(plugin);
        return ConfigLayers.build(plugin, null);
    }

    @Test
    @DisplayName("A server upgrading from before the split keeps every setting it had")
    void testUpgradeFromInlineConfig() throws Exception {
        writeConfigYml(
                "debug: true",
                "language: \"en\"",
                "detection:",
                "  enabled: false",
                "  api-key: my-secret-key",
                "anti_esp:",
                "  enabled: true",
                "  max_distance: 24.0",
                "  ray_count: 9",
                "  hide_sounds: false",
                "vision:",
                "  enabled: true",
                "");

        FileConfiguration effective = load();

        assertFalse(effective.getBoolean("detection.enabled"),
                "detection was deliberately off and must not come back on");
        assertEquals("my-secret-key", effective.getString("detection.api-key"));
        assertTrue(effective.getBoolean("anti_esp.enabled"));
        assertEquals(24.0, effective.getDouble("anti_esp.max_distance"), 1.0E-9);
        assertEquals(9, effective.getInt("anti_esp.ray_count"));
        assertFalse(effective.getBoolean("anti_esp.hide_sounds"));
        assertTrue(effective.getBoolean("vision.enabled"));
        assertTrue(effective.getBoolean("debug"));
        assertEquals("en", effective.getString("language"));
    }

    @Test
    @DisplayName("A fresh install lands on the shipped defaults")
    void testFreshInstallDefaults() {
        FileConfiguration effective = load();

        assertFalse(effective.getBoolean("anti_esp.enabled"), "anti-esp ships off");
        assertTrue(effective.getBoolean("reports.enabled"), "reports ship on");
        assertTrue(effective.getBoolean("menus.enabled"));
        assertEquals(30, effective.getInt("reports.cooldown-seconds"));
        assertEquals(48.0, effective.getDouble("anti_esp.max_distance"), 1.0E-9);
        assertEquals(5, effective.getInt("vision.region-scan-minutes"));
    }

    @Test
    @DisplayName("Editing a module file changes the effective configuration")
    void testModuleFileIsAuthoritativeForSettings() throws Exception {
        load();
        Files.write(new File(dataFolder.toFile(), "modules/anti-esp.yml").toPath(),
                "max_distance: 100.0\nray_count: 1\n".getBytes(StandardCharsets.UTF_8));

        FileConfiguration effective = ConfigLayers.build(plugin, null);

        assertEquals(100.0, effective.getDouble("anti_esp.max_distance"), 1.0E-9);
        assertEquals(1, effective.getInt("anti_esp.ray_count"));
    }

    @Test
    @DisplayName("modules.yml overrules a stale switch left in config.yml")
    void testModulesYmlWinsOverConfigYml() throws Exception {
        writeConfigYml("anti_esp:", "  enabled: false", "");
        load();

        // Someone puts the old section back by hand after the migration.
        writeConfigYml("anti_esp:", "  enabled: false", "");
        Files.write(new File(dataFolder.toFile(), ModuleLayout.MODULES_FILE).toPath(),
                "anti-esp: true\n".getBytes(StandardCharsets.UTF_8));

        FileConfiguration effective = ConfigLayers.build(plugin, null);

        assertTrue(effective.getBoolean("anti_esp.enabled"),
                "one file decides what runs, and it is modules.yml");
    }

    @Test
    @DisplayName("Config reads the same values the stack produced")
    void testConfigObjectMatchesTheStack() throws Exception {
        writeConfigYml(
                "detection:",
                "  api-key: key-123",
                "  reserve-endpoint: \"https://backup.example\"",
                "punishments:",
                "  safe-name-check: false",
                "anti_esp:",
                "  enabled: true",
                "  ray_count: 7",
                "");

        Mockito.doNothing().when(plugin).saveDefaultConfig();
        Config config = new Config(plugin, Logger.getLogger("ConfigStackEmulationTest"));

        assertTrue(config.isAntiEspEnabled());
        assertEquals(7, config.getAntiEspRayCount());
        assertEquals("https://backup.example", config.getReserveServerAddress());
        assertFalse(config.isSafeNameCheckEnabled());
        assertTrue(config.isReportsEnabled(), "untouched modules keep their shipped default");
        assertEquals(30, config.getReportCooldownSeconds());
    }

    @Test
    @DisplayName("Out-of-range values are clamped rather than trusted")
    void testValuesAreClamped() throws Exception {
        writeConfigYml(
                "anti_esp:",
                "  ray_count: 999",
                "  budget_micros_per_pass: -5",
                "  move_threshold: -1.0",
                "");

        Mockito.doNothing().when(plugin).saveDefaultConfig();
        Config config = new Config(plugin, Logger.getLogger("ConfigStackEmulationTest"));

        assertEquals(9, config.getAntiEspRayCount(), "the sample builder only has nine points");
        assertTrue(config.getAntiEspBudgetMicros() >= 100, "a non-positive budget would stall the engine");
        assertEquals(0.0, config.getAntiEspMoveThreshold(), 1.0E-9);
    }

    @Test
    @DisplayName("Reloading twice changes nothing")
    void testReloadIsIdempotent() throws Exception {
        writeConfigYml("anti_esp:", "  enabled: true", "  max_distance: 33.0", "");

        FileConfiguration first = load();
        String modulesAfterFirst = new String(Files.readAllBytes(
                new File(dataFolder.toFile(), ModuleLayout.MODULES_FILE).toPath()), StandardCharsets.UTF_8);
        FileConfiguration second = load();
        String modulesAfterSecond = new String(Files.readAllBytes(
                new File(dataFolder.toFile(), ModuleLayout.MODULES_FILE).toPath()), StandardCharsets.UTF_8);

        assertEquals(first.getDouble("anti_esp.max_distance"), second.getDouble("anti_esp.max_distance"), 1.0E-9);
        assertTrue(second.getBoolean("anti_esp.enabled"));
        assertEquals(modulesAfterFirst, modulesAfterSecond, "a second boot must not rewrite the file");
    }
}
