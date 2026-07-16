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

package wtf.mlsac.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVersionTest {

    @Test
    void parsesLegacyOneDotXVersions() {
        assertEquals(ServerVersion.V1_16_5, ServerVersion.fromString("1.16.5-R0.1-SNAPSHOT"));
        assertEquals(ServerVersion.V1_20_5, ServerVersion.fromString("1.20.5"));
        assertEquals(ServerVersion.V1_21_4, ServerVersion.fromString("1.21.4-R0.1-SNAPSHOT"));
    }

    @Test
    void postOneDotXSchemeMapsToLatestNotUnknown() {
        // The new year-based scheme (e.g. Leaf/Paper on "26.1.2") must not fall back to 1.16.5
        // legacy mode - it should be treated as newer than everything we know about.
        for (String raw : new String[] {"26.1.2", "26.2", "26.1.2-R0.1-SNAPSHOT", "27.0"}) {
            ServerVersion parsed = ServerVersion.fromString(raw);
            assertEquals(ServerVersion.V1_21_11, parsed, "unexpected mapping for " + raw);
            assertTrue(parsed.isAtLeast(ServerVersion.V1_20_5),
                    raw + " must resolve to modern (>= 1.20.5) compatibility mode");
        }
    }

    @Test
    void malformedVersionsAreUnknown() {
        assertEquals(ServerVersion.UNKNOWN, ServerVersion.fromString(""));
        assertEquals(ServerVersion.UNKNOWN, ServerVersion.fromString(null));
        assertEquals(ServerVersion.UNKNOWN, ServerVersion.fromString("not-a-version"));
        assertEquals(ServerVersion.UNKNOWN, ServerVersion.fromString("0.9"));
    }
}
