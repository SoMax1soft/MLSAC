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


package wtf.mlsac.penalty;
public enum ActionType {
    ANIMATION("{ANIMATION}"),
    BAN("{BAN}"),           // Legacy - works as ANIMATION
    KICK("{KICK}"),         // Legacy - works as ANIMATION
    CUSTOM_ALERT("{CUSTOM_ALERT}"),
    RAW(null);
    private final String prefix;
    ActionType(String prefix) {
        this.prefix = prefix;
    }
    public String getPrefix() {
        return prefix;
    }
    public static ActionType fromCommand(String command) {
        if (command == null || command.isEmpty()) {
            return RAW;
        }
        String trimmed = command.trim();
        for (ActionType type : values()) {
            if (type.prefix != null && trimmed.startsWith(type.prefix)) {
                // Legacy support: {BAN} and {KICK} work as {ANIMATION}
                if (type == BAN || type == KICK) {
                    return ANIMATION;
                }
                return type;
            }
        }
        return RAW;
    }
    public String stripPrefix(String command) {
        if (command == null || prefix == null) {
            return command != null ? command.trim() : "";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith(prefix)) {
            return trimmed.substring(prefix.length()).trim();
        }
        // Legacy support: also strip {BAN} and {KICK} prefixes
        if (this == ANIMATION) {
            if (trimmed.startsWith("{BAN}")) {
                return trimmed.substring(5).trim();
            }
            if (trimmed.startsWith("{KICK}")) {
                return trimmed.substring(6).trim();
            }
        }
        return trimmed;
    }
    public boolean isConsoleCommand() {
        return this == ANIMATION || this == BAN || this == KICK || this == RAW;
    }
    public boolean isPunishment() {
        return this == ANIMATION || this == BAN || this == KICK;
    }
    public boolean isAnimation() {
        return this == ANIMATION || this == BAN || this == KICK;
    }
}