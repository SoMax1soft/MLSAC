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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSyncUtilTest {
    @Test
    void copyMissingAddsNestedFieldsInsideExistingSections() throws Exception {
        YamlConfiguration target = new YamlConfiguration();
        target.set("server-identity.name", "box1");
        target.set("server-identity.interserver.enabled", true);

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("server-identity.name", "default");
        defaults.set("server-identity.family", "default");
        defaults.set("server-identity.interserver.enabled", false);
        defaults.set("server-identity.reporting.events-enabled", true);
        defaults.set("server-identity.reporting.alert-threshold", 0.75);

        boolean changed = invokeCopyMissing(target, defaults);

        assertTrue(changed);
        assertEquals("box1", target.getString("server-identity.name"));
        assertEquals(true, target.getBoolean("server-identity.interserver.enabled"));
        assertEquals("default", target.getString("server-identity.family"));
        assertEquals(true, target.getBoolean("server-identity.reporting.events-enabled"));
        assertEquals(0.75, target.getDouble("server-identity.reporting.alert-threshold"));
    }

    @Test
    void copyMissingRepairsWrongSectionTypesSoChildrenCanBeAdded() throws Exception {
        YamlConfiguration target = new YamlConfiguration();
        target.set("messages", "bad-value");

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("messages.alert.enabled", true);
        defaults.set("messages.alert.format", "{PLAYER}");

        boolean changed = invokeCopyMissing(target, defaults);

        assertTrue(changed);
        assertTrue(target.isConfigurationSection("messages"));
        assertEquals(true, target.getBoolean("messages.alert.enabled"));
        assertEquals("{PLAYER}", target.getString("messages.alert.format"));
    }

    @Test
    void copyMissingDoesNotBackfillDefaultNumericKeysIntoUserMaps() throws Exception {
        YamlConfiguration target = new YamlConfiguration();
        target.set("penalties.actions.155", "{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})");
        target.set("penalties.actions.255", "{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})");

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("penalties.actions.1", "{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})");
        defaults.set("penalties.actions.2", "{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})");

        boolean changed = invokeCopyMissing(target, defaults);

        assertFalse(changed);
        assertEquals("{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})",
                target.getString("penalties.actions.155"));
        assertEquals("{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})",
                target.getString("penalties.actions.255"));
        assertFalse(target.isSet("penalties.actions.1"));
        assertFalse(target.isSet("penalties.actions.2"));
    }

    @Test
    void copyMissingAddsDefaultNumericMapWhenWholeSectionIsMissing() throws Exception {
        YamlConfiguration target = new YamlConfiguration();
        target.set("penalties.animation.enabled", true);

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("penalties.actions.1", "{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})");
        defaults.set("penalties.actions.2", "{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})");

        boolean changed = invokeCopyMissing(target, defaults);

        assertTrue(changed);
        assertEquals("{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})",
                target.getString("penalties.actions.1"));
        assertEquals("{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})",
                target.getString("penalties.actions.2"));
    }

    @Test
    void migratedLegacyActionMapIsNotBackfilledWithDefaultThresholds() throws Exception {
        YamlConfiguration target = new YamlConfiguration();
        target.set("ai.punishment.commands.155", "{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})");
        target.set("ai.punishment.commands.255", "{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})");

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("penalties.actions.1", "{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})");
        defaults.set("penalties.actions.2", "{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})");

        assertTrue(invokeMigrateLegacyDetectionSettings(target));
        boolean changed = invokeCopyMissing(target, defaults);

        assertFalse(changed);
        assertEquals("{KICK} kick {PLAYER} MLSAC Detection (VL:{VL})",
                target.getString("penalties.actions.155"));
        assertEquals("{BAN} ban {PLAYER} Cheating (MLSAC VL:{VL})",
                target.getString("penalties.actions.255"));
        assertFalse(target.isSet("penalties.actions.1"));
        assertFalse(target.isSet("penalties.actions.2"));
    }

    @Test
    void copyMissingRepairsWrongListTypesWithDefaultResourceValue() throws Exception {
        YamlConfiguration target = new YamlConfiguration();
        target.set("alert-responses.troll.actions", "bad-value");

        Map<String, Object> defaultAction = new LinkedHashMap<>();
        defaultAction.put("type", "drop_weapon");
        defaultAction.put("detections", 3);
        defaultAction.put("message", "resource message");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("alert-responses.troll.actions", List.of(defaultAction));

        boolean changed = invokeCopyMissing(target, defaults);

        assertTrue(changed);
        assertEquals(1, target.getMapList("alert-responses.troll.actions").size());
        assertEquals("resource message",
                target.getMapList("alert-responses.troll.actions").get(0).get("message"));
    }

    private boolean invokeCopyMissing(FileConfiguration target, FileConfiguration defaults) throws Exception {
        Method method = ConfigSyncUtil.class.getDeclaredMethod(
                "copyMissing",
                FileConfiguration.class,
                FileConfiguration.class,
                String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, target, defaults, "");
    }

    private boolean invokeMigrateLegacyDetectionSettings(FileConfiguration target) throws Exception {
        Method method = ConfigSyncUtil.class.getDeclaredMethod(
                "migrateLegacyDetectionSettings",
                FileConfiguration.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, target);
    }
}
