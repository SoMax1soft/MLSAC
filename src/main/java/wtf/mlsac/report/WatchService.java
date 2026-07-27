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

package wtf.mlsac.report;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Makes an admin follow a suspect: switches them to spectator, teleports to the target, and keeps
 * the target visible via packets even while the suspect is under an invisibility effect. The reveal
 * is refreshed on a bounded loop because the server periodically re-sends the real entity metadata.
 */
public final class WatchService {
    // Entity base-flags metadata index (0 across all supported versions). Sending 0 clears the
    // invisible bit (0x20) so the viewer sees the entity even under an invisibility effect.
    private static final int ENTITY_FLAGS_INDEX = 0;
    private static final byte NO_FLAGS = 0x00;
    private static final long REVEAL_PERIOD_TICKS = 20L;
    private static final int REVEAL_MAX_CYCLES = 60; // ~60s of refreshed visibility per watch.

    private WatchService() {
    }

    /**
     * Starts watching {@code target} for {@code admin}. Both must be online on this server. Runs on
     * the entity thread the platform requires.
     */
    public static void startWatch(Player admin, Player target) {
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target);
        revealOnce(admin, target);
        startRevealLoop(admin.getUniqueId(), target.getUniqueId());
    }

    private static void startRevealLoop(UUID viewerId, UUID targetId) {
        final int[] cycles = {0};
        final ScheduledTask[] holder = new ScheduledTask[1];
        Player anchor = Bukkit.getPlayer(viewerId);
        if (anchor == null) {
            return;
        }
        holder[0] = SchedulerManager.getAdapter().runEntitySyncRepeating(anchor, () -> {
            Player viewer = Bukkit.getPlayer(viewerId);
            Player target = Bukkit.getPlayer(targetId);
            boolean stop = viewer == null || target == null || !viewer.isOnline() || !target.isOnline()
                    || viewer.getGameMode() != GameMode.SPECTATOR
                    || cycles[0]++ >= REVEAL_MAX_CYCLES;
            if (stop) {
                if (holder[0] != null) {
                    holder[0].cancel();
                }
                return;
            }
            revealOnce(viewer, target);
        }, REVEAL_PERIOD_TICKS, REVEAL_PERIOD_TICKS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void revealOnce(Player viewer, Player target) {
        try {
            List<EntityData<?>> meta = new ArrayList<>();
            meta.add(new EntityData(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, NO_FLAGS));
            WrapperPlayServerEntityMetadata packet =
                    new WrapperPlayServerEntityMetadata(target.getEntityId(), meta);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        } catch (Exception ignored) {
            // Never let a packet failure break the watch action.
        }
    }
}
