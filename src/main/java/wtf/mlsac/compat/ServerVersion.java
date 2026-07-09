/*
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


package wtf.mlsac.compat;
public enum ServerVersion {
    V1_16(16, 0),
    V1_16_5(16, 5),
    V1_17(17, 0),
    V1_18(18, 0),
    V1_19(19, 0),
    V1_20(20, 0),
    V1_20_5(20, 5),
    V1_21(21, 0),
    V1_21_1(21, 1),
    V1_21_4(21, 4),
    V1_21_5(21, 5),
    V1_21_6(21, 6),
    V1_21_7(21, 7),
    V1_21_8(21, 8),
    V1_21_9(21, 9),
    V1_21_10(21, 10),
    V1_21_11(21, 11),
    UNKNOWN(0, 0);
    private final int minor;
    private final int patch;
    ServerVersion(int minor, int patch) {
        this.minor = minor;
        this.patch = patch;
    }
    public int getMinor() {
        return minor;
    }
    public int getPatch() {
        return patch;
    }
    public boolean isAtLeast(ServerVersion other) {
        if (this == UNKNOWN || other == UNKNOWN) {
            return this == other;
        }
        if (this.minor != other.minor) {
            return this.minor > other.minor;
        }
        return this.patch >= other.patch;
    }
    public boolean isBelow(ServerVersion other) {
        if (this == UNKNOWN || other == UNKNOWN) {
            return false;
        }
        return !isAtLeast(other);
    }
    public boolean isBetween(ServerVersion min, ServerVersion max) {
        return isAtLeast(min) && isBelow(max);
    }
    public static ServerVersion fromString(String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            return UNKNOWN;
        }
        try {
            String version = versionString.split("-")[0];
            String[] parts = version.split("\\.");
            if (parts.length < 2) {
                return UNKNOWN;
            }
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major != 1) {
                return UNKNOWN;
            }
            return findBestMatch(minor, patch);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }
    private static ServerVersion findBestMatch(int minor, int patch) {
        ServerVersion bestMatch = UNKNOWN;
        for (ServerVersion v : values()) {
            if (v == UNKNOWN) continue;
            if (v.minor == minor && v.patch <= patch) {
                if (v.patch == patch) {
                    return v;
                }
                if (bestMatch == UNKNOWN || bestMatch.minor < minor || v.patch > bestMatch.patch) {
                    bestMatch = v;
                }
            } else if (v.minor < minor) {
                if (bestMatch == UNKNOWN || (bestMatch.minor < minor && v.minor > bestMatch.minor)) {
                    bestMatch = v;
                }
            }
        }
        return bestMatch;
    }
}