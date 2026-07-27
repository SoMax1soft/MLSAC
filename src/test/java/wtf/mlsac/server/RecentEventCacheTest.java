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

class RecentEventCacheTest {

    @Test
    void firstSightingIsNewSubsequentIsNot() {
        RecentEventCache cache = new RecentEventCache(8);
        assertTrue(cache.markIfNew("a"));
        assertFalse(cache.markIfNew("a"));
    }

    @Test
    void blankIdsAreNeverDeduplicated() {
        RecentEventCache cache = new RecentEventCache(8);
        assertTrue(cache.markIfNew(null));
        assertTrue(cache.markIfNew(null));
        assertTrue(cache.markIfNew(""));
        assertTrue(cache.markIfNew(""));
    }

    @Test
    void evictsOldestOnceCapacityExceeded() {
        RecentEventCache cache = new RecentEventCache(2);
        assertTrue(cache.markIfNew("a"));
        assertTrue(cache.markIfNew("b"));
        // "c" pushes the cache past capacity 2, evicting the oldest ("a").
        assertTrue(cache.markIfNew("c"));
        // "a" was evicted, so it now looks new again.
        assertTrue(cache.markIfNew("a"));
        // "b"/"c" are still remembered.
        assertFalse(cache.markIfNew("c"));
    }

    @Test
    void clearForgetsEverything() {
        RecentEventCache cache = new RecentEventCache(8);
        cache.markIfNew("a");
        cache.clear();
        assertTrue(cache.markIfNew("a"));
    }
}
