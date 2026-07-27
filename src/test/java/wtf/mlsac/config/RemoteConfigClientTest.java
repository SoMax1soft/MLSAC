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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preset arrives over the network, so these tests pin down what the plugin refuses to accept
 * even when the backend says it is fine.
 */
class RemoteConfigClientTest {

    private final RemoteConfigClient client = new RemoteConfigClient(Logger.getLogger("test"), false);

    private RemoteConfigClient.Snapshot parse(String config) {
        return client.parse("{\"success\":true,\"data\":{\"preset\":\"main\",\"hash\":\"abc123\",\"config\":"
                + config + "}}");
    }

    @Test
    void parsesAFullPresetIntoConfigPaths() {
        RemoteConfigClient.Snapshot snapshot = parse("{"
                + "\"detection\":{\"worldguard\":{\"enabled\":false,\"disabled-regions\":[\"world:spawn\"]}},"
                + "\"alerts\":{\"threshold\":0.9,\"console\":true},"
                + "\"violation\":{\"threshold\":50,\"reset-value\":10}"
                + "}");

        assertTrue(snapshot.hasConfig());
        assertEquals("main", snapshot.getPreset());
        assertEquals("abc123", snapshot.getHash());

        Map<String, Object> overlay = snapshot.getOverlay();
        assertEquals(false, overlay.get("detection.worldguard.enabled"));
        assertEquals(List.of("world:spawn"), overlay.get("detection.worldguard.disabled-regions"));
        assertEquals(0.9, (Double) overlay.get("alerts.threshold"), 1e-9);
        assertEquals(true, overlay.get("alerts.console"));
        assertEquals(50.0, (Double) overlay.get("violation.threshold"), 1e-9);
    }

    @Test
    void reportsNoConfigWhenTestModeIsOff() {
        RemoteConfigClient.Snapshot snapshot =
                client.parse("{\"success\":true,\"data\":{\"preset\":null,\"config\":null}}");

        assertFalse(snapshot.hasConfig());
    }

    @Test
    void rejectsMalformedBodies() {
        assertNull(client.parse("not json"));
        assertNull(client.parse("[]"));
        assertNull(client.parse("{\"success\":true}"));
    }

    @Test
    void stripsControlCharactersFromPenaltyCommands() {
        RemoteConfigClient.Snapshot snapshot = parse(
                "{\"penalties\":{\"actions\":{\"1\":\"kick {PLAYER}\\nop attacker\"}}}");

        assertEquals("kick {PLAYER}op attacker", snapshot.getOverlay().get("penalties.actions.1"));
    }

    @Test
    void dropsPenaltyLevelsThatAreNotViolationNumbers() {
        RemoteConfigClient.Snapshot snapshot = parse(
                "{\"penalties\":{\"actions\":{\"1\":\"kick {PLAYER}\",\"../evil\":\"op attacker\"}}}");

        Map<String, Object> overlay = snapshot.getOverlay();
        assertEquals("kick {PLAYER}", overlay.get("penalties.actions.1"));
        assertFalse(overlay.containsKey("penalties.actions.../evil"));
    }

    @Test
    void dropsAnimationNamesThatCouldEscapeTheAnimationsFolder() {
        RemoteConfigClient.Snapshot snapshot = parse(
                "{\"penalties\":{\"animation\":{\"enabled\":true,\"type\":\"../../../plugins/config\"}}}");

        Map<String, Object> overlay = snapshot.getOverlay();
        assertEquals(true, overlay.get("penalties.animation.enabled"));
        assertFalse(overlay.containsKey("penalties.animation.type"), "traversal name must not be applied");
    }

    @Test
    void neverSilencesTheStablePunishingModels() {
        RemoteConfigClient.Snapshot snapshot = parse("{\"detection\":{\"models\":{"
                + "\"fast\":{\"name\":\"Fast\",\"only-alert\":true},"
                + "\"pro\":{\"name\":\"Pro\",\"only-alert\":true},"
                + "\"ultra\":{\"name\":\"Ultra\",\"only-alert\":true}}}}");

        Map<String, Object> overlay = snapshot.getOverlay();
        assertEquals(false, overlay.get("detection.models.fast.only-alert"));
        assertEquals(false, overlay.get("detection.models.pro.only-alert"));
        assertEquals(true, overlay.get("detection.models.ultra.only-alert"));
    }

    @Test
    void clampsOutOfRangeNumbersInsteadOfTrustingThem() {
        RemoteConfigClient.Snapshot snapshot = parse("{"
                + "\"alerts\":{\"threshold\":9.5,\"sound\":{\"pitch\":-3}},"
                + "\"violation\":{\"multiplier\":1e9}"
                + "}");

        Map<String, Object> overlay = snapshot.getOverlay();
        assertEquals(1.0, (Double) overlay.get("alerts.threshold"), 1e-9);
        assertEquals(0.5, (Double) overlay.get("alerts.sound.pitch"), 1e-9);
        assertEquals(10000.0, (Double) overlay.get("violation.multiplier"), 1e-9);
    }

    @Test
    void halvesAResetValueThatWouldRetriggerEveryPenalty() {
        RemoteConfigClient.Snapshot snapshot =
                parse("{\"violation\":{\"threshold\":40,\"reset-value\":40}}");

        assertEquals(20.0, (Double) snapshot.getOverlay().get("violation.reset-value"), 1e-9);
    }

    @Test
    void dropsRegionNamesOutsideTheWorldGuardCharacterSet() {
        RemoteConfigClient.Snapshot snapshot = parse(
                "{\"detection\":{\"worldguard\":{\"disabled-regions\":[\"ok:region\",\"bad region\",\"a/../b\"]}}}");

        assertEquals(List.of("ok:region"),
                snapshot.getOverlay().get("detection.worldguard.disabled-regions"));
    }

    @Test
    void dropsTrollActionsWithAnUnknownType() {
        RemoteConfigClient.Snapshot snapshot = parse("{\"alert-responses\":{\"troll\":{\"enabled\":true,"
                + "\"actions\":[{\"type\":\"run_command\",\"buffer\":10},"
                + "{\"type\":\"launch\",\"buffer\":20}]}}}");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions =
                (List<Map<String, Object>>) snapshot.getOverlay().get("alert-responses.troll.actions");
        assertEquals(1, actions.size());
        assertEquals("launch", actions.get(0).get("type"));
    }

    @Test
    void carriesThePerModelEnabledSwitch() {
        RemoteConfigClient.Snapshot snapshot = parse("{\"detection\":{\"models\":{"
                + "\"fast\":{\"enabled\":false,\"only-alert\":false},"
                + "\"pro\":{\"only-alert\":false}}}}");

        Map<String, Object> overlay = snapshot.getOverlay();
        assertEquals(false, overlay.get("detection.models.fast.enabled"));
        assertEquals(true, overlay.get("detection.models.pro.enabled"), "absent means enabled");
    }

    @Test
    void marksSectionsThePresetOwnsSoStaleLocalEntriesAreRemoved() {
        RemoteConfigClient.Snapshot snapshot =
                parse("{\"penalties\":{\"actions\":{\"1\":\"kick {PLAYER}\"}}}");

        assertTrue(snapshot.getReplacedSections().contains("penalties.actions"));
    }
}
