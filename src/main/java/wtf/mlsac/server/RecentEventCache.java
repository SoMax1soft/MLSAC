/*
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

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe bounded cache of recently seen identifiers, used to suppress duplicate
 * inter-server events. Eviction is FIFO once {@code capacity} is exceeded.
 */
final class RecentEventCache {
    private final int capacity;
    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    private final Queue<String> order = new ConcurrentLinkedQueue<>();

    RecentEventCache(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Records {@code id} as seen.
     *
     * @return {@code true} if the id was not seen before (or is blank, which is never de-duplicated);
     *         {@code false} if it was already present.
     */
    boolean markIfNew(String id) {
        if (id == null || id.isEmpty()) {
            return true;
        }
        if (!ids.add(id)) {
            return false;
        }
        order.add(id);
        while (order.size() > capacity) {
            String evicted = order.poll();
            if (evicted == null) {
                break;
            }
            ids.remove(evicted);
        }
        return true;
    }

    void clear() {
        ids.clear();
        order.clear();
    }
}
