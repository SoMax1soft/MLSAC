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

import wtf.mlsac.scheduler.ScheduledTask;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class ManagedTask {
    private final AtomicReference<ScheduledTask> ref = new AtomicReference<>();

    void reschedule(Supplier<ScheduledTask> scheduler) {
        cancel();
        ref.set(scheduler.get());
    }

    void cancel() {
        ScheduledTask previous = ref.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }
    }

    void clearReference() {
        ref.set(null);
    }

    boolean isScheduled() {
        return ref.get() != null;
    }
}
