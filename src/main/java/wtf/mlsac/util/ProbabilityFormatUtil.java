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

package wtf.mlsac.util;

import wtf.mlsac.data.AIPlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class ProbabilityFormatUtil {
    private ProbabilityFormatUtil() {
    }

    public static String formatPercent(double probability) {
        double percent = probability * 100.0D;
        if (Math.abs(percent - Math.rint(percent)) < 0.0001D) {
            return String.valueOf((int) Math.rint(percent));
        }
        return String.format(Locale.US, "%.1f", percent);
    }

    public static String applyModelPlaceholders(String template, AIPlayerData data) {
        return applyModelPlaceholders(template, data, ProbabilityFormatUtil::formatPercent);
    }

    public static String applyModelPlaceholders(String template, AIPlayerData data,
            Function<Double, String> formatter) {
        return template
                // {MODEL}: probability of the most recent response, regardless of which model.
                .replace("{MODEL}", formatter.apply(data.getLastProbability()))
                .replace("{AVG}", formatter.apply(data.getAverageProbability()))
                .replace("{LAST-FAST}", formatter.apply(getFastOrMiniLast(data)))
                .replace("{FAST}", formatter.apply(getFastOrMiniLast(data)))
                .replace("{LAST-PRO}", formatter.apply(data.getLastProbability("pro")))
                .replace("{LAST-ULTRA}", formatter.apply(data.getLastProbability("ultra")))
                .replace("{AVG-FAST}", formatter.apply(getFastOrMiniAvg(data)))
                .replace("{AVG-PRO}", formatter.apply(data.getAverageProbability("pro")))
                .replace("{AVG-ULTRA}", formatter.apply(data.getAverageProbability("ultra")));
    }

    public static String formatHistory(AIPlayerData data, String fastFormat, String proFormat, String ultraFormat,
            int limit) {
        return formatHistory(data, fastFormat, proFormat, ultraFormat, limit, ProbabilityFormatUtil::formatPercent);
    }

    public static String formatHistory(AIPlayerData data, String fastFormat, String proFormat, String ultraFormat,
            int limit, Function<Double, String> formatter) {
        List<String> parts = new ArrayList<>();
        List<AIPlayerData.ModelProbabilityEntry> history = data.getModelProbabilityHistory();
        int startIndex = Math.max(0, history.size() - Math.max(1, limit));
        for (int i = startIndex; i < history.size(); i++) {
            AIPlayerData.ModelProbabilityEntry entry = history.get(i);
            String template = resolveTemplate(entry.getModelName(), fastFormat, proFormat, ultraFormat);
            String value = formatter.apply(entry.getProbability());
            parts.add(template.replace("{RESULT}", value)
                    .replace("[RESULT]", value));
        }
        return parts.isEmpty() ? "-" : String.join(" ", parts);
    }

    private static double getFastOrMiniLast(AIPlayerData data) {
        double fast = data.getLastProbabilityContains("fast");
        return fast > 0.0D ? fast : data.getLastProbabilityContains("mini");
    }

    private static double getFastOrMiniAvg(AIPlayerData data) {
        double fast = data.getAverageProbability("fast");
        return fast > 0.0D ? fast : data.getAverageProbability("mini");
    }

    private static String resolveTemplate(String modelName, String fastFormat, String proFormat, String ultraFormat) {
        String normalized = modelName == null ? "unknown" : modelName.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "fast":
            case "mini":
                return fastFormat;
            case "pro":
                return proFormat;
            case "ultra":
                return ultraFormat;
            default:
                return normalized.substring(0, Math.min(normalized.length(), 1)).toUpperCase(Locale.ROOT) + "{RESULT}%";
        }
    }
}
