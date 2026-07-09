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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate-limits a repeated warning: at most {@code maxWarnings} emissions, and no more often than
 * {@code intervalMs}. The gating decision is separated from the actual side effect (logging,
 * broadcasting) so the policy can be unit-tested in isolation.
 */
final class WarningThrottle {
    private final int maxWarnings;
    private final long intervalMs;
    private final AtomicInteger remaining;
    private volatile long lastFireTime = 0L;

    WarningThrottle(int maxWarnings, long intervalMs) {
        this.maxWarnings = maxWarnings;
        this.intervalMs = intervalMs;
        this.remaining = new AtomicInteger(maxWarnings);
    }

    /**
     * Decides whether a warning may be emitted at {@code now}. When it returns {@code true} the
     * remaining quota is consumed and the cooldown window restarts.
     *
     * @return {@code true} if the caller should emit the warning now
     */
    boolean shouldFire(long now) {
        if (remaining.get() <= 0) {
            return false;
        }
        if (now - lastFireTime < intervalMs) {
            return false;
        }
        lastFireTime = now;
        remaining.decrementAndGet();
        return true;
    }

    /** Restores the full quota (call when the underlying condition clears). */
    void reset() {
        remaining.set(maxWarnings);
    }
}
