/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;

/**
 * Reads the leading entity id of an outgoing packet without building a
 * {@link com.github.retrooper.packetevents.wrapper.PacketWrapper}.
 *
 * <p>This matters a lot on the hot path. PacketEvents re-serializes a packet from scratch as soon
 * as a listener touches a wrapper ("last used wrapper" rewriting), so parsing every entity
 * movement packet just to read its id costs a full decode plus a full encode per packet per
 * viewer. Peeking the first VarInt costs a handful of byte reads and leaves the buffer untouched,
 * which is what the anti-ESP filter needs for the overwhelming majority of packets.
 */
final class PacketPeek {

    private PacketPeek() {
    }

    /**
     * Reads the VarInt entity id at the start of the packet payload and restores the reader index.
     *
     * @return the entity id, or -1 if the buffer could not be read
     */
    static int peekEntityId(PacketSendEvent event) {
        Object buffer = event.getByteBuf();
        if (buffer == null) {
            return -1;
        }
        int start = ByteBufHelper.readerIndex(buffer);
        try {
            return readVarInt(buffer);
        } catch (Throwable ignored) {
            return -1;
        } finally {
            ByteBufHelper.readerIndex(buffer, start);
        }
    }

    /**
     * Reads the effect position out of a pre-1.19 named sound effect packet.
     *
     * <p>PacketEvents ships no wrapper for this packet, and on 1.16-1.18 servers it is the one most
     * sounds travel on — decoding it by hand is what keeps sound muting behaving the same there as
     * on newer versions.
     *
     * <p>Layout: sound name (string), category (VarInt), x/y/z (ints in eighths of a block).
     *
     * @return the position in blocks, or null if the packet could not be read
     */
    static double[] peekNamedSoundPosition(PacketSendEvent event) {
        Object buffer = event.getByteBuf();
        if (buffer == null) {
            return null;
        }
        int start = ByteBufHelper.readerIndex(buffer);
        try {
            int nameLength = readVarInt(buffer);
            if (nameLength < 0 || nameLength > ByteBufHelper.readableBytes(buffer)) {
                return null;
            }
            ByteBufHelper.readBytes(buffer, nameLength);
            readVarInt(buffer); // sound category
            if (ByteBufHelper.readableBytes(buffer) < 12) {
                return null;
            }
            return new double[]{readInt(buffer) / 8.0, readInt(buffer) / 8.0, readInt(buffer) / 8.0};
        } catch (Throwable ignored) {
            return null;
        } finally {
            ByteBufHelper.readerIndex(buffer, start);
        }
    }

    private static int readVarInt(Object buffer) {
        int value = 0;
        int position = 0;
        while (position < 35) {
            if (ByteBufHelper.readableBytes(buffer) <= 0) {
                return -1;
            }
            byte current = ByteBufHelper.readByte(buffer);
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
    }

    private static int readInt(Object buffer) {
        return (ByteBufHelper.readByte(buffer) & 0xFF) << 24
                | (ByteBufHelper.readByte(buffer) & 0xFF) << 16
                | (ByteBufHelper.readByte(buffer) & 0xFF) << 8
                | (ByteBufHelper.readByte(buffer) & 0xFF);
    }
}
