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

package wtf.mlsac.menu;

import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * Shared rendering for detection-probability values across the suspects/reports GUIs: consistent
 * colors, formatting, and the stained-glass material used to visualise a single check.
 */
final class CheckDisplay {
    private CheckDisplay() {
    }

    static ChatColor color(double value) {
        if (value >= 0.9D) {
            return ChatColor.DARK_RED;
        }
        if (value >= 0.8D) {
            return ChatColor.RED;
        }
        if (value >= 0.6D) {
            return ChatColor.GOLD;
        }
        if (value >= 0.4D) {
            return ChatColor.YELLOW;
        }
        return ChatColor.GREEN;
    }

    /** A colored two-decimal representation, e.g. {@code §c0.87}, terminated by a reset code. */
    static String format(double value) {
        return color(value) + String.format(Locale.ROOT, "%.2f", value) + ChatColor.RESET;
    }

    /** The percent form used in hover lore, e.g. {@code §c87%}. */
    static String percent(double value) {
        int percent = (int) Math.round(value * 100.0D);
        return color(value).toString() + percent + "%" + ChatColor.RESET;
    }

    /** The stained-glass pane material representing a single check by severity. */
    static Material glass(double value) {
        if (value >= 0.9D) {
            return Material.RED_STAINED_GLASS_PANE;
        }
        if (value >= 0.8D) {
            return Material.ORANGE_STAINED_GLASS_PANE;
        }
        if (value >= 0.6D) {
            return Material.YELLOW_STAINED_GLASS_PANE;
        }
        if (value >= 0.4D) {
            return Material.LIME_STAINED_GLASS_PANE;
        }
        return Material.GREEN_STAINED_GLASS_PANE;
    }

    /** Builds a single space-separated colored history string from the given values. */
    static String history(List<Double> values) {
        StringBuilder builder = new StringBuilder();
        for (Double value : values) {
            if (value == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(format(value));
        }
        return builder.toString();
    }
}
