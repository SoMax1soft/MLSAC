/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.potion.PotionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Mirror of everything the server has told clients about one player entity.
 *
 * <p>Hiding a player means sending a destroy packet and dropping the update packets that follow.
 * The server's entity tracker still believes the client has the entity, so it will never re-send
 * the spawn burst — the plugin has to. Rebuilding that burst from scratch is what made revealed
 * players show up as a nameless, unarmoured default skin (and made potion invisibility leak),
 * because a bare spawn packet carries none of that state.
 *
 * <p>So instead of guessing, the metadata, equipment and effect packets the server emits are
 * mirrored here — recorded even when the packet is about to be cancelled — and replayed verbatim on
 * reveal. The values are server-authored, which also means they are correct for whatever protocol
 * version the server speaks, on every version. Converting a Bukkit item into a packet item is the
 * one step that can quietly produce the wrong thing on some versions, and replaying the server's own
 * bytes skips it entirely.
 *
 * <p>State is shared across viewers: the server broadcasts identical entity state to every tracker,
 * so a value learned from one viewer's stream is valid for all of them.
 */
final class TrackedPlayerState {

    /** Which packet the server actually used to spawn this player (SPAWN_PLAYER before 1.20.2). */
    private volatile PacketTypeCommon spawnPacketType;

    /** Latest value of each metadata index, ordered by index. */
    private final Map<Integer, EntityData<?>> metadata = new ConcurrentSkipListMap<>();

    /**
     * Latest item per slot. The whole {@link Equipment} is stored because an empty slot may carry a
     * null item, which a concurrent map cannot hold as a value.
     */
    private final Map<EquipmentSlot, Equipment> equipment = new ConcurrentHashMap<>();

    private final Map<PotionType, ActiveEffect> effects = new ConcurrentHashMap<>();

    /** Entity id of the vehicle this player rides, or -1. */
    private volatile int vehicleId = -1;

    /** Full passenger list of {@link #vehicleId}, needed because the packet is per-vehicle. */
    private volatile int[] vehiclePassengers;

    PacketTypeCommon getSpawnPacketType() {
        return spawnPacketType;
    }

    void setSpawnPacketType(PacketTypeCommon type) {
        this.spawnPacketType = type;
    }

    void recordMetadata(List<EntityData<?>> entries) {
        if (entries == null) {
            return;
        }
        for (EntityData<?> entry : entries) {
            if (entry != null) {
                metadata.put(entry.getIndex(), entry);
            }
        }
    }

    List<EntityData<?>> snapshotMetadata() {
        return new ArrayList<>(metadata.values());
    }

    void recordEquipment(List<Equipment> entries) {
        if (entries == null) {
            return;
        }
        for (Equipment entry : entries) {
            if (entry != null && entry.getSlot() != null) {
                equipment.put(entry.getSlot(), entry);
            }
        }
    }

    /**
     * Every known slot, ordered by protocol slot id.
     *
     * <p>The 1.16+ equipment packet marks the final entry by clearing the high bit of the slot id,
     * so the list is written as a sequence; sending it in the same ascending order vanilla uses
     * keeps it identical to what a client would normally receive.
     */
    List<Equipment> snapshotEquipment(ServerVersion version) {
        if (equipment.isEmpty()) {
            return null;
        }
        List<Equipment> out = new ArrayList<>(equipment.values());
        out.sort(Comparator.comparingInt(entry -> entry.getSlot().getId(version)));
        return out;
    }

    void recordEffect(PotionType type, int amplifier, int durationTicks, byte flags) {
        if (type != null) {
            effects.put(type, new ActiveEffect(type, amplifier, durationTicks, flags));
        }
    }

    void removeEffect(PotionType type) {
        if (type != null) {
            effects.remove(type);
        }
    }

    List<ActiveEffect> snapshotEffects() {
        if (effects.isEmpty()) {
            return null;
        }
        return new ArrayList<>(effects.values());
    }

    void recordVehicle(int vehicleId, int[] passengers) {
        this.vehicleId = vehicleId;
        this.vehiclePassengers = passengers;
    }

    void clearVehicle() {
        this.vehicleId = -1;
        this.vehiclePassengers = null;
    }

    int getVehicleId() {
        return vehicleId;
    }

    int[] getVehiclePassengers() {
        return vehiclePassengers;
    }

    /** One active potion effect, in the exact shape the entity effect packet needs. */
    static final class ActiveEffect {
        final PotionType type;
        final int amplifier;
        final int durationTicks;
        final byte flags;

        ActiveEffect(PotionType type, int amplifier, int durationTicks, byte flags) {
            this.type = type;
            this.amplifier = amplifier;
            this.durationTicks = durationTicks;
            this.flags = flags;
        }
    }
}
