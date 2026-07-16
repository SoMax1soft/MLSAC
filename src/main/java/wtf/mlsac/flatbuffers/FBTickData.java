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


package wtf.mlsac.flatbuffers;
import com.google.flatbuffers.FlatBufferBuilder;
public final class FBTickData {
    public static void startFBTickData(FlatBufferBuilder builder) {
        builder.startTable(6);
    }
    public static void addDeltaYaw(FlatBufferBuilder builder, float deltaYaw) {
        builder.addFloat(0, deltaYaw, 0.0f);
    }
    public static void addDeltaPitch(FlatBufferBuilder builder, float deltaPitch) {
        builder.addFloat(1, deltaPitch, 0.0f);
    }
    public static void addAccelYaw(FlatBufferBuilder builder, float accelYaw) {
        builder.addFloat(2, accelYaw, 0.0f);
    }
    public static void addAccelPitch(FlatBufferBuilder builder, float accelPitch) {
        builder.addFloat(3, accelPitch, 0.0f);
    }
    public static void addJerkYaw(FlatBufferBuilder builder, float jerkYaw) {
        builder.addFloat(4, jerkYaw, 0.0f);
    }
    public static void addJerkPitch(FlatBufferBuilder builder, float jerkPitch) {
        builder.addFloat(5, jerkPitch, 0.0f);
    }
    public static int endFBTickData(FlatBufferBuilder builder) {
        return builder.endTable();
    }
}