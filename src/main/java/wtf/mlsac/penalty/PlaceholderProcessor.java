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
import java.util.Locale;
public class PlaceholderProcessor {
    private static final String PH_PLAYER = "{PLAYER}";
    private static final String PH_VL = "{VL}";
    private static final String PH_PROBABILITY = "{PROBABILITY}";
    private static final String PH_BUFFER = "{BUFFER}";
    private static final String LEGACY_PLAYER = "<player>";
    private static final String LEGACY_VL = "<vl>";
    private static final String LEGACY_PROBABILITY = "<probability>";
    private static final String LEGACY_BUFFER = "<buffer>";
    public String process(String template, PenaltyContext context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (context == null) {
            return template;
        }
        String result = template;
        result = result.replace(PH_PLAYER, context.getPlayerName());
        result = result.replace(PH_VL, String.valueOf(context.getViolationLevel()));
        result = result.replace(PH_PROBABILITY, formatDouble(context.getProbability()));
        result = result.replace(PH_BUFFER, formatDouble(context.getBuffer()));
        result = result.replace(LEGACY_PLAYER, context.getPlayerName());
        result = result.replace(LEGACY_VL, String.valueOf(context.getViolationLevel()));
        result = result.replace(LEGACY_PROBABILITY, formatDouble(context.getProbability()));
        result = result.replace(LEGACY_BUFFER, formatDouble(context.getBuffer()));
        return result;
    }
    private String formatDouble(double value) {
        // Locale.ROOT keeps a '.' decimal separator; the default locale would emit
        // ',' on RU/DE/etc. servers and corrupt the generated penalty command.
        return String.format(Locale.ROOT, "%.2f", value);
    }
    public boolean hasPlaceholders(String template) {
        if (template == null || template.isEmpty()) {
            return false;
        }
        return template.contains(PH_PLAYER) || template.contains(PH_VL) ||
               template.contains(PH_PROBABILITY) || template.contains(PH_BUFFER) ||
               template.contains(LEGACY_PLAYER) || template.contains(LEGACY_VL) ||
               template.contains(LEGACY_PROBABILITY) || template.contains(LEGACY_BUFFER);
    }
}