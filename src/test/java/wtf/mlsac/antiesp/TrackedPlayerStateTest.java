/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mirrored entity state is what a revealed player is rebuilt from, so these tests pin down the
 * merge behaviour that decides whether they come back looking like themselves.
 */
class TrackedPlayerStateTest {

    private TrackedPlayerState state;

    @BeforeEach
    void setUp() {
        state = new TrackedPlayerState();
    }

    private static EntityData<?> data(int index, Object value) {
        return new EntityData<>(index, null, value);
    }

    @Test
    @DisplayName("Metadata packets merge by index instead of replacing the snapshot")
    void testMetadataMergesByIndex() {
        // The server only sends the indices that changed. Keeping just the last packet would leave
        // a revealed player missing every field that has not been touched recently.
        state.recordMetadata(Arrays.asList(data(0, (byte) 0x20), data(2, "nametag"), data(6, (byte) 0)));
        state.recordMetadata(Collections.singletonList(data(6, (byte) 5)));

        List<EntityData<?>> snapshot = state.snapshotMetadata();

        assertEquals(3, snapshot.size(), "Untouched indices must survive a partial update");
        assertEquals((byte) 0x20, snapshot.get(0).getValue());
        assertEquals("nametag", snapshot.get(1).getValue());
        assertEquals((byte) 5, snapshot.get(2).getValue(), "The newer value for index 6 wins");
    }

    @Test
    @DisplayName("Metadata snapshot is ordered by index")
    void testMetadataSnapshotIsOrdered() {
        state.recordMetadata(Arrays.asList(data(9, 20.0f), data(0, (byte) 0), data(3, true)));

        List<EntityData<?>> snapshot = state.snapshotMetadata();

        assertEquals(0, snapshot.get(0).getIndex());
        assertEquals(3, snapshot.get(1).getIndex());
        assertEquals(9, snapshot.get(2).getIndex());
    }

    @Test
    @DisplayName("An empty state reports nothing to replay")
    void testEmptyState() {
        assertTrue(state.snapshotMetadata().isEmpty());
        assertNull(state.snapshotEquipment(ServerVersion.V_1_20_4));
        assertNull(state.snapshotEffects());
        assertEquals(-1, state.getVehicleId());
    }

    @Test
    @DisplayName("Null and empty packet contents are ignored")
    void testNullSafety() {
        state.recordMetadata(null);
        state.recordMetadata(Collections.emptyList());
        state.recordEquipment(null);
        state.recordEffect(null, 1, 100, (byte) 0);
        state.removeEffect(null);

        assertTrue(state.snapshotMetadata().isEmpty());
        assertNull(state.snapshotEquipment(ServerVersion.V_1_20_4));
        assertNull(state.snapshotEffects());
    }

    @Test
    @DisplayName("Equipment is tracked per slot and the newest packet wins")
    void testEquipmentPerSlot() {
        Equipment helmet = new Equipment(EquipmentSlot.HELMET, null);
        Equipment betterHelmet = new Equipment(EquipmentSlot.HELMET, null);
        Equipment sword = new Equipment(EquipmentSlot.MAIN_HAND, null);

        state.recordEquipment(Arrays.asList(helmet, sword));
        state.recordEquipment(Collections.singletonList(betterHelmet));

        List<Equipment> snapshot = state.snapshotEquipment(ServerVersion.V_1_20_4);

        assertNotNull(snapshot);
        assertEquals(2, snapshot.size(), "Replacing the helmet must not drop the held item");
        for (Equipment equipment : snapshot) {
            if (equipment.getSlot() == EquipmentSlot.HELMET) {
                assertSame(betterHelmet, equipment, "The newer helmet packet wins");
            } else {
                assertSame(sword, equipment);
            }
        }
    }

    @Test
    @DisplayName("Equipment snapshot is ordered by protocol slot id")
    void testEquipmentSnapshotIsOrdered() {
        // The 1.16+ packet writes the slots as a run and flags the last one, so vanilla's ascending
        // order is what clients and protocol translators expect to see.
        state.recordEquipment(Arrays.asList(
                new Equipment(EquipmentSlot.HELMET, null),
                new Equipment(EquipmentSlot.MAIN_HAND, null),
                new Equipment(EquipmentSlot.BOOTS, null),
                new Equipment(EquipmentSlot.OFF_HAND, null)));

        List<Equipment> snapshot = state.snapshotEquipment(ServerVersion.V_1_20_4);

        assertNotNull(snapshot);
        int previous = -1;
        for (Equipment equipment : snapshot) {
            int id = equipment.getSlot().getId(ServerVersion.V_1_20_4);
            assertTrue(id > previous, "Slots must be written in ascending id order");
            previous = id;
        }
    }

    @Test
    @DisplayName("Effects are added and removed by potion type")
    void testEffectLifecycle() {
        PotionType invisibility = Mockito.mock(PotionType.class);
        PotionType speed = Mockito.mock(PotionType.class);

        state.recordEffect(invisibility, 0, 600, (byte) 0x04);
        state.recordEffect(speed, 1, 200, (byte) 0x02);

        List<TrackedPlayerState.ActiveEffect> effects = state.snapshotEffects();
        assertNotNull(effects);
        assertEquals(2, effects.size());

        state.removeEffect(speed);
        effects = state.snapshotEffects();

        assertNotNull(effects);
        assertEquals(1, effects.size());
        assertSame(invisibility, effects.get(0).type);
        assertEquals(600, effects.get(0).durationTicks);
        assertEquals((byte) 0x04, effects.get(0).flags);

        state.removeEffect(invisibility);
        assertNull(state.snapshotEffects(), "With no effects left there is nothing to replay");
    }

    @Test
    @DisplayName("Re-recording an effect refreshes its remaining duration")
    void testEffectRefresh() {
        PotionType invisibility = Mockito.mock(PotionType.class);

        state.recordEffect(invisibility, 0, 600, (byte) 0);
        state.recordEffect(invisibility, 1, 1200, (byte) 0);

        List<TrackedPlayerState.ActiveEffect> effects = state.snapshotEffects();
        assertNotNull(effects);
        assertEquals(1, effects.size());
        assertEquals(1, effects.get(0).amplifier);
        assertEquals(1200, effects.get(0).durationTicks);
    }

    @Test
    @DisplayName("Vehicle state round-trips and can be cleared")
    void testVehicleState() {
        state.recordVehicle(77, new int[]{5, 9});

        assertEquals(77, state.getVehicleId());
        assertArrayEquals(new int[]{5, 9}, state.getVehiclePassengers());

        state.clearVehicle();

        assertEquals(-1, state.getVehicleId());
        assertNull(state.getVehiclePassengers());
    }

    @Test
    @DisplayName("The observed spawn packet type is remembered for replay")
    void testSpawnPacketTypeIsRemembered() {
        assertNull(state.getSpawnPacketType(), "Nothing observed yet");

        state.setSpawnPacketType(PacketType.Play.Server.SPAWN_PLAYER);

        assertEquals(PacketType.Play.Server.SPAWN_PLAYER, state.getSpawnPacketType());
    }
}
