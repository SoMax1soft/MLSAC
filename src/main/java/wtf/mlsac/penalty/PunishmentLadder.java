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

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable view over the configured violation-level → punishment-command mapping.
 *
 * <p>Replaces the two hand-rolled "find the right threshold" loops that previously lived in
 * {@code ViolationManager}. A {@link TreeMap} gives us O(log n) threshold lookups and removes the
 * old edge case where a threshold of {@code 0} was silently ignored.
 */
public final class PunishmentLadder {
    private final TreeMap<Integer, String> rungs;

    public PunishmentLadder(Map<Integer, String> commands) {
        this.rungs = new TreeMap<>(commands == null ? Collections.emptyMap() : commands);
    }

    public boolean isEmpty() {
        return rungs.isEmpty();
    }

    /**
     * Returns the command for the highest configured threshold that is still {@code <= vl}
     * (an exact match wins, otherwise the nearest rung below). Empty when no rung applies.
     */
    public Optional<String> commandFor(int vl) {
        Map.Entry<Integer, String> rung = rungs.floorEntry(vl);
        return rung == null ? Optional.empty() : Optional.of(rung.getValue());
    }

    /** The highest configured threshold, or empty when the ladder has no rungs. */
    public Optional<Integer> maxThreshold() {
        return rungs.isEmpty() ? Optional.empty() : Optional.of(rungs.lastKey());
    }

    /** The command bound to the highest configured threshold. */
    public Optional<String> maxCommand() {
        return rungs.isEmpty() ? Optional.empty() : Optional.of(rungs.lastEntry().getValue());
    }
}
