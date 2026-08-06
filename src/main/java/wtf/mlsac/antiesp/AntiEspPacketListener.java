/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wtf.mlsac.Permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PacketEvents listener for Anti-ESP.
 * Intercepts entity spawn, movement, metadata, equipment, and sound packets sent from server to client,
 * cancelling them if the entity is currently hidden from the viewer player by occlusion.
 */
public class AntiEspPacketListener extends PacketListenerAbstract {
    private final AntiEspManager antiEspManager;

    public AntiEspPacketListener(AntiEspManager antiEspManager) {
        super(PacketListenerPriority.HIGHEST);
        this.antiEspManager = antiEspManager;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!antiEspManager.getConfig().isAntiEspEnabled()) {
            return;
        }

        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        boolean verbose = antiEspManager.getConfig().isDebug() || antiEspManager.getConfig().isAntiEspVerboseDebug();

        Player viewer = (Player) event.getPlayer();
        PacketTypeCommon packetType = event.getPacketType();
        int entityId = extractEntityId(event, packetType);

        if (entityId != -1) {
            // Self check: viewer is never hidden from themselves
            if (viewer.getEntityId() == entityId) {
                return;
            }

            boolean isHidden = antiEspManager.isEntityHiddenFrom(viewer, entityId);

            if (isHidden) {
                event.setCancelled(true);
                if (verbose) {
                    org.bukkit.Bukkit.getLogger().info("[Anti-ESP-Debug] CANCELLED packet " + packetType.getName() + " (entityId: " + entityId + ") for Viewer: " + viewer.getName() + " (Hidden)");
                }
                return;
            }
            return;
        }

        // TAB list protection: cancel PLAYER_INFO_REMOVE packets sent for hidden players so they stay in TAB
        if (packetType == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            try {
                WrapperPlayServerPlayerInfoRemove wrapper = new WrapperPlayServerPlayerInfoRemove(event);
                List<UUID> profileIds = wrapper.getProfileIds();
                if (profileIds != null && !profileIds.isEmpty()) {
                    List<UUID> remaining = new ArrayList<>(profileIds.size());
                    for (UUID uuid : profileIds) {
                        Player target = org.bukkit.Bukkit.getPlayer(uuid);
                        if (target != null && antiEspManager.isEntityHiddenFrom(viewer, target.getEntityId())) {
                            // Target is occluded by Anti-ESP, keep them in TAB list!
                            continue;
                        }
                        remaining.add(uuid);
                    }
                    if (remaining.isEmpty()) {
                        event.setCancelled(true);
                    } else if (remaining.size() < profileIds.size()) {
                        wrapper.setProfileIds(remaining);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Sound hiding for hidden players
        if (antiEspManager.getConfig().isAntiEspHideSounds()) {
            if (packetType == PacketType.Play.Server.SOUND_EFFECT || packetType == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                try {
                    WrapperPlayServerSoundEffect soundWrapper = new WrapperPlayServerSoundEffect(event);
                    Location soundLoc = new Location(viewer.getWorld(), soundWrapper.getPosition().getX(), soundWrapper.getPosition().getY(), soundWrapper.getPosition().getZ());
                    if (antiEspManager.isSoundFromHiddenPlayer(viewer, soundLoc)) {
                        event.setCancelled(true);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private int extractEntityId(PacketSendEvent event, PacketTypeCommon packetType) {
        try {
            if (packetType == PacketType.Play.Server.SPAWN_PLAYER) {
                return new WrapperPlayServerSpawnPlayer(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.SPAWN_ENTITY) {
                return new WrapperPlayServerSpawnEntity(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                return new WrapperPlayServerEntityRelativeMove(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                return new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_ROTATION) {
                return new WrapperPlayServerEntityRotation(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_TELEPORT) {
                return new WrapperPlayServerEntityTeleport(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                return new WrapperPlayServerEntityHeadLook(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_METADATA) {
                return new WrapperPlayServerEntityMetadata(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                return new WrapperPlayServerEntityEquipment(event).getEntityId();
            } else if (packetType == PacketType.Play.Server.ENTITY_VELOCITY) {
                return new WrapperPlayServerEntityVelocity(event).getEntityId();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
