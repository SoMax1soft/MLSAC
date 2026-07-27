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
import wtf.mlsac.scheduler.ScheduledTask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedTaskTest {

    /** Minimal stub that just records whether it was cancelled. */
    private static final class StubTask implements ScheduledTask {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }

    @Test
    void rescheduleCancelsThePreviousTask() {
        ManagedTask task = new ManagedTask();
        StubTask first = new StubTask();
        StubTask second = new StubTask();

        task.reschedule(() -> first);
        assertTrue(task.isScheduled());

        task.reschedule(() -> second);
        assertTrue(first.isCancelled(), "previous task must be cancelled on reschedule");
        assertFalse(second.isCancelled());
        assertTrue(task.isScheduled());
    }

    @Test
    void cancelCancelsAndForgets() {
        ManagedTask task = new ManagedTask();
        StubTask stub = new StubTask();
        task.reschedule(() -> stub);

        task.cancel();
        assertTrue(stub.isCancelled());
        assertFalse(task.isScheduled());

        // Cancelling again is a harmless no-op.
        task.cancel();
    }

    @Test
    void clearReferenceForgetsWithoutCancelling() {
        ManagedTask task = new ManagedTask();
        StubTask stub = new StubTask();
        task.reschedule(() -> stub);

        task.clearReference();
        assertFalse(task.isScheduled());
        assertFalse(stub.isCancelled(), "clearReference must not cancel an already-finished task");
    }
}
