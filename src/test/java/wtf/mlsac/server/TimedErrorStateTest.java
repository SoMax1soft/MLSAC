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

package wtf.mlsac.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedErrorStateTest {

    @Test
    void inactiveByDefault() {
        TimedErrorState state = new TimedErrorState(1000);
        assertFalse(state.isActive());
        assertFalse(state.isExpired(10_000));
    }

    @Test
    void expiresStrictlyAfterTheWindow() {
        TimedErrorState state = new TimedErrorState(1000);
        state.enter(5000);
        assertTrue(state.isActive());
        assertFalse(state.isExpired(5000));
        assertFalse(state.isExpired(6000), "boundary is exclusive (uses > not >=)");
        assertTrue(state.isExpired(6001));
    }

    @Test
    void rawFlagIgnoresExpiryUntilCleared() {
        TimedErrorState state = new TimedErrorState(1000);
        state.enter(5000);
        // Even long past the window, the latched flag stays set until the owner clears it.
        assertTrue(state.isActive());
        assertTrue(state.isExpired(999_999));
        assertTrue(state.isActive());
    }

    @Test
    void clearResetsEverything() {
        TimedErrorState state = new TimedErrorState(1000);
        state.enter(5000);
        state.clear();
        assertFalse(state.isActive());
        assertFalse(state.isExpired(999_999));
    }
}
