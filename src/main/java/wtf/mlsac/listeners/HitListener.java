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

package wtf.mlsac.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.session.ISessionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HitListener extends PacketListenerAbstract {
    private final ISessionManager sessionManager;
    private final AICheck aiCheck;
    private final Map<Integer, UUID> playerIdCache = new ConcurrentHashMap<>();

    public HitListener(ISessionManager sessionManager, AICheck aiCheck) {
        super(PacketListenerPriority.MONITOR);
        this.sessionManager = sessionManager;
        this.aiCheck = aiCheck;
    }

    public void setCurrentTick(int tick) {
        if (tick % 200 == 0) {
            playerIdCache.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getValue()) == null);
        }
    }

    public void cachePlayer(Player player) {
        if (player != null) {
            playerIdCache.put(player.getEntityId(), player.getUniqueId());
        }
    }

    public void uncachePlayer(Player player) {
        if (player != null) {
            playerIdCache.remove(player.getEntityId());
        }
    }

    public void cacheOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            cachePlayer(player);
        }
    }

    public void cacheEntity(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player) {
            cachePlayer((Player) entity);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            int targetId;
            if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                // 1.21.6+/26.x send attacks as a dedicated ATTACK packet (no action field).
                targetId = new WrapperPlayClientAttack(event).getEntityId();
            } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                // Older clients (and via ViaVersion) still use INTERACT_ENTITY with an action.
                WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
                if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    return;
                }
                targetId = packet.getEntityId();
            } else {
                return;
            }

            Player attacker = (Player) event.getPlayer();
            if (attacker == null) {
                return;
            }
            Player target = getPlayerById(targetId);
            if (target == null) {
                return;
            }
            if (aiCheck != null) {
                aiCheck.onAttack(attacker, target);
            }
            sessionManager.onAttack(attacker);
        } catch (Exception e) {
        }
    }

    private Player getPlayerById(int entityId) {
        UUID uuid = playerIdCache.get(entityId);
        if (uuid != null) {
            Player cached = Bukkit.getPlayer(uuid);
            // Entity IDs are reused and change on respawn/world change, so a cached mapping can go
            // stale. If it no longer resolves to an online player with this exact entity id, drop
            // it and re-resolve - otherwise attacks on this target return null and the attacker
            // never enters combat, so their rotations are never collected.
            if (cached != null && cached.getEntityId() == entityId) {
                return cached;
            }
            playerIdCache.remove(entityId);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getEntityId() == entityId) {
                playerIdCache.put(entityId, player.getUniqueId());
                return player;
            }
        }
        return null;
    }
}