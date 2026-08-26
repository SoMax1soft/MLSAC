/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;

import wtf.mlsac.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Packet filter for the Anti-ESP engine.
 *
 * <p>Two things happen here. Packets that would tell a client about a player it is not allowed to
 * see are cancelled, and the metadata and effect packets that describe a player's appearance are
 * mirrored into {@link TrackedPlayerState} so the engine can rebuild them on reveal.
 *
 * <p>The filter is on the path of every outgoing packet on the server, so the common case has to
 * be nearly free. Entity movement — by far the highest-volume traffic — is resolved by peeking the
 * leading VarInt instead of decoding the packet, and is skipped outright while the viewer hides
 * nobody. Only the handful of packet types whose payload the engine actually needs are fully
 * decoded, and only when they belong to a player entity: decoding a packet makes PacketEvents
 * re-encode it from the wrapper, which is wasted work for a mob's metadata.
 */
public class AntiEspPacketListener extends PacketListenerAbstract {

    /** Metadata index 0 is the shared flags byte on every supported version. */
    private static final int SHARED_FLAGS_INDEX = 0;
    /** Bit 0x40 of the shared flags is "glowing", which renders through walls. */
    private static final int GLOWING_FLAG = 0x40;

    private final AntiEspManager manager;

    public AntiEspPacketListener(AntiEspManager manager) {
        super(PacketListenerPriority.HIGHEST);
        this.manager = manager;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Config config = manager.getConfig();
        if (!manager.isRunning() || !config.isAntiEspEnabled() || event.isCancelled()) {
            return;
        }

        User user = event.getUser();
        if (user == null) {
            return;
        }
        UUID viewerId = user.getUUID();
        ViewerVisibility visibility = manager.viewerState(viewerId);
        if (visibility == null) {
            return;
        }

        PacketTypeCommon type = event.getPacketType();

        if (isPositionalEntityPacket(type)) {
            if (visibility.getHiddenCount() == 0) {
                return;
            }
            int entityId = PacketPeek.peekEntityId(event);
            if (entityId != -1 && visibility.isHidden(entityId)) {
                event.setCancelled(true);
            }
            return;
        }

        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            handleMetadata(event, visibility, config);
            return;
        }
        if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            handleEquipment(event, visibility);
            return;
        }
        if (isSpawnPacket(type)) {
            handleSpawn(event, visibility, type);
            return;
        }
        if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            handleDestroy(event, visibility);
            return;
        }
        if (type == PacketType.Play.Server.ENTITY_EFFECT) {
            handleEffect(event, visibility);
            return;
        }
        if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
            handleEffectRemoval(event, visibility);
            return;
        }
        if (type == PacketType.Play.Server.SET_PASSENGERS) {
            handlePassengers(event, visibility);
            return;
        }

        if (visibility.getHiddenCount() == 0) {
            return;
        }

        if (type == PacketType.Play.Server.ENTITY_STATUS) {
            cancelIfHidden(event, visibility, new WrapperPlayServerEntityStatus(event).getEntityId());
            return;
        }
        if (type == PacketType.Play.Server.ATTACH_ENTITY) {
            cancelIfHidden(event, visibility, new WrapperPlayServerAttachEntity(event).getAttachedId());
            return;
        }
        if (type == PacketType.Play.Server.COLLECT_ITEM) {
            cancelIfHidden(event, visibility, new WrapperPlayServerCollectItem(event).getCollectorEntityId());
            return;
        }
        if (type == PacketType.Play.Server.ENTITY_SOUND_EFFECT) {
            cancelIfHidden(event, visibility, new WrapperPlayServerEntitySoundEffect(event).getEntityId());
            return;
        }

        if (config.isAntiEspHideSounds()) {
            handleSound(event, visibility, type);
        }
    }

    // ---------------------------------------------------------------- handlers

    /**
     * Mirrors the target's metadata and, while it is hidden, drops the packet.
     *
     * <p>Metadata carries invisibility, pose, skin layers and the custom name. Without this mirror
     * a revealed player would be rebuilt from an empty state — which is what made returning players
     * lose their nametag, and would have made a potion-invisible player fully visible again.
     */
    private void handleMetadata(PacketSendEvent event, ViewerVisibility visibility, Config config) {
        int entityId = PacketPeek.peekEntityId(event);
        if (entityId == -1 || !manager.isPlayerEntity(entityId)) {
            return;
        }

        boolean hidden = visibility.isHidden(entityId);
        boolean mirror = config.isAntiEspRestoreMetadata();
        if (hidden && !mirror) {
            event.setCancelled(true);
            return;
        }
        boolean stripGlow = config.isAntiEspHideGlowing()
                && manager.isOccludedButVisible(visibility, entityId);
        if (!hidden && !stripGlow && !mirror) {
            return;
        }

        WrapperPlayServerEntityMetadata wrapper;
        try {
            wrapper = new WrapperPlayServerEntityMetadata(event);
        } catch (Exception ignored) {
            return;
        }

        List<EntityData<?>> entries = wrapper.getEntityMetadata();
        if (mirror) {
            TrackedPlayerState state = manager.trackedState(entityId);
            if (state != null) {
                state.recordMetadata(entries);
            }
        }

        if (hidden) {
            event.setCancelled(true);
            return;
        }
        if (stripGlow) {
            List<EntityData<?>> stripped = withoutGlow(entries);
            if (stripped != null) {
                wrapper.setEntityMetadata(stripped);
            }
        }
    }

    /**
     * Mirrors the target's gear and, while it is hidden, drops the packet.
     *
     * <p>The mirror is what the reveal replays. Deriving the gear from the player's inventory
     * instead would mean converting Bukkit items into packet items, and that conversion is the one
     * step in the reveal that can quietly yield the wrong item on some versions — which showed up
     * as armour that only appeared on the next equipment change rather than straight away.
     */
    private void handleEquipment(PacketSendEvent event, ViewerVisibility visibility) {
        int entityId = PacketPeek.peekEntityId(event);
        if (entityId == -1 || !manager.isPlayerEntity(entityId)) {
            return;
        }
        TrackedPlayerState state = manager.trackedState(entityId);
        try {
            WrapperPlayServerEntityEquipment wrapper = new WrapperPlayServerEntityEquipment(event);
            if (state != null) {
                state.recordEquipment(wrapper.getEquipment());
            }
        } catch (Exception ignored) {
            return;
        }
        if (visibility.isHidden(entityId)) {
            event.setCancelled(true);
        }
    }

    /**
     * Returns a copy of the metadata with the glowing flag cleared, or null when nothing glows.
     * The original entries are left alone because they are shared with the mirrored state.
     */
    private static List<EntityData<?>> withoutGlow(List<EntityData<?>> entries) {
        if (entries == null) {
            return null;
        }
        for (int i = 0; i < entries.size(); i++) {
            EntityData<?> entry = entries.get(i);
            if (entry == null || entry.getIndex() != SHARED_FLAGS_INDEX
                    || !(entry.getValue() instanceof Byte)) {
                continue;
            }
            byte flags = (Byte) entry.getValue();
            if ((flags & GLOWING_FLAG) == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            EntityDataType<Byte> dataType = (EntityDataType<Byte>) entry.getType();
            List<EntityData<?>> copy = new ArrayList<>(entries);
            copy.set(i, new EntityData<>(SHARED_FLAGS_INDEX, dataType, (byte) (flags & ~GLOWING_FLAG)));
            return copy;
        }
        return null;
    }

    /**
     * Notes that the server tracker handed this entity to the viewer, and remembers which spawn
     * packet it used — that choice differs by server version and is what the reveal replays.
     */
    private void handleSpawn(PacketSendEvent event, ViewerVisibility visibility, PacketTypeCommon type) {
        int entityId = PacketPeek.peekEntityId(event);
        if (entityId == -1 || !manager.isPlayerEntity(entityId)) {
            return;
        }
        if (manager.handleSpawnObserved(visibility, entityId, type)) {
            event.setCancelled(true);
        }
    }

    private void handleDestroy(PacketSendEvent event, ViewerVisibility visibility) {
        try {
            manager.handleDestroyObserved(visibility, new WrapperPlayServerDestroyEntities(event).getEntityIds());
        } catch (Exception ignored) {
        }
    }

    private void handleEffect(PacketSendEvent event, ViewerVisibility visibility) {
        int entityId = PacketPeek.peekEntityId(event);
        if (entityId == -1 || !manager.isPlayerEntity(entityId)) {
            return;
        }
        TrackedPlayerState state = manager.trackedState(entityId);
        try {
            WrapperPlayServerEntityEffect wrapper = new WrapperPlayServerEntityEffect(event);
            if (state != null) {
                byte flags = 0;
                if (wrapper.isAmbient()) flags |= 0x01;
                if (wrapper.isVisible()) flags |= 0x02;
                if (wrapper.isShowIcon()) flags |= 0x04;
                state.recordEffect(wrapper.getPotionType(), wrapper.getEffectAmplifier(),
                        wrapper.getEffectDurationTicks(), flags);
            }
        } catch (Exception ignored) {
            return;
        }
        if (visibility.isHidden(entityId)) {
            event.setCancelled(true);
        }
    }

    private void handleEffectRemoval(PacketSendEvent event, ViewerVisibility visibility) {
        int entityId = PacketPeek.peekEntityId(event);
        if (entityId == -1 || !manager.isPlayerEntity(entityId)) {
            return;
        }
        TrackedPlayerState state = manager.trackedState(entityId);
        try {
            WrapperPlayServerRemoveEntityEffect wrapper = new WrapperPlayServerRemoveEntityEffect(event);
            if (state != null) {
                state.removeEffect(wrapper.getPotionType());
            }
        } catch (Exception ignored) {
            return;
        }
        if (visibility.isHidden(entityId)) {
            event.setCancelled(true);
        }
    }

    private void handlePassengers(PacketSendEvent event, ViewerVisibility visibility) {
        WrapperPlayServerSetPassengers wrapper;
        try {
            wrapper = new WrapperPlayServerSetPassengers(event);
        } catch (Exception ignored) {
            return;
        }

        int vehicleId = wrapper.getEntityId();
        manager.handlePassengerUpdate(vehicleId, wrapper.getPassengers());

        if (visibility.isHidden(vehicleId)) {
            event.setCancelled(true);
        }
    }

    private void handleSound(PacketSendEvent event, ViewerVisibility visibility, PacketTypeCommon type) {
        if (type == PacketType.Play.Server.SOUND_EFFECT) {
            try {
                Vector3d position = new WrapperPlayServerSoundEffect(event).getPosition();
                if (manager.isSoundFromHiddenPlayer(visibility, position.getX(), position.getY(), position.getZ())) {
                    event.setCancelled(true);
                }
            } catch (Exception ignored) {
            }
            return;
        }
        // Pre-1.19 servers route most sounds through the named variant, which PacketEvents has no
        // wrapper for. Reading the position straight out of the buffer keeps sound muting working
        // on those versions instead of silently doing nothing.
        if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
            double[] position = PacketPeek.peekNamedSoundPosition(event);
            if (position != null
                    && manager.isSoundFromHiddenPlayer(visibility, position[0], position[1], position[2])) {
                event.setCancelled(true);
            }
        }
    }

    private void cancelIfHidden(PacketSendEvent event, ViewerVisibility visibility, int entityId) {
        if (entityId != -1 && visibility.isHidden(entityId)) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- packet groups

    /**
     * Packets that only leak a player's position/rotation and start with a VarInt entity id, so
     * they can be filtered without decoding.
     */
    private static boolean isPositionalEntityPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION
                || type == PacketType.Play.Server.ENTITY_ROTATION
                || type == PacketType.Play.Server.ENTITY_HEAD_LOOK
                || type == PacketType.Play.Server.ENTITY_TELEPORT
                || type == PacketType.Play.Server.ENTITY_POSITION_SYNC
                || type == PacketType.Play.Server.ENTITY_VELOCITY
                || type == PacketType.Play.Server.ENTITY_MOVEMENT
                || type == PacketType.Play.Server.ENTITY_ANIMATION
                || type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION
                || type == PacketType.Play.Server.HURT_ANIMATION
                || type == PacketType.Play.Server.DAMAGE_EVENT;
    }

    private static boolean isSpawnPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Server.SPAWN_PLAYER
                || type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.SPAWN_LIVING_ENTITY;
    }
}
