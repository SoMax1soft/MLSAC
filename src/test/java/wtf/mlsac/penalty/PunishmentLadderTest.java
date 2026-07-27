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

package wtf.mlsac.penalty;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentLadderTest {

    private static Map<Integer, String> rungs(Object... pairs) {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Integer) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void emptyLadderYieldsNoCommands() {
        PunishmentLadder ladder = new PunishmentLadder(rungs());
        assertTrue(ladder.isEmpty());
        assertFalse(ladder.commandFor(5).isPresent());
        assertFalse(ladder.maxThreshold().isPresent());
        assertFalse(ladder.maxCommand().isPresent());
    }

    @Test
    void nullMapIsTreatedAsEmpty() {
        assertTrue(new PunishmentLadder(null).isEmpty());
    }

    @Test
    void exactThresholdMatchWins() {
        PunishmentLadder ladder = new PunishmentLadder(rungs(3, "kick", 6, "ban"));
        assertEquals("kick", ladder.commandFor(3).orElse(null));
        assertEquals("ban", ladder.commandFor(6).orElse(null));
    }

    @Test
    void usesNearestThresholdBelowWhenNoExactMatch() {
        PunishmentLadder ladder = new PunishmentLadder(rungs(3, "kick", 6, "ban"));
        assertEquals("kick", ladder.commandFor(4).orElse(null), "vl=4 should fall back to rung 3");
        assertEquals("kick", ladder.commandFor(5).orElse(null));
        assertEquals("ban", ladder.commandFor(99).orElse(null), "vl above the top rung keeps the top rung");
    }

    @Test
    void belowLowestThresholdYieldsNothing() {
        PunishmentLadder ladder = new PunishmentLadder(rungs(3, "kick"));
        assertFalse(ladder.commandFor(2).isPresent());
    }

    @Test
    void thresholdZeroIsHonored() {
        // Regression: the old loop returned null for a threshold of 0 (applicableThreshold > 0).
        PunishmentLadder ladder = new PunishmentLadder(rungs(0, "warn", 5, "ban"));
        assertEquals("warn", ladder.commandFor(0).orElse(null));
        assertEquals("warn", ladder.commandFor(3).orElse(null));
        assertEquals("ban", ladder.commandFor(5).orElse(null));
    }

    @Test
    void maxThresholdAndCommandTrackTheTopRung() {
        PunishmentLadder ladder = new PunishmentLadder(rungs(3, "kick", 6, "ban", 10, "ipban"));
        assertEquals(10, ladder.maxThreshold().orElse(-1));
        assertEquals("ipban", ladder.maxCommand().orElse(null));
    }
}
