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
                .replace("{AVG}", formatter.apply(data.getAverageProbability()))
                .replace("{LAST-FAST}", formatter.apply(data.getLastProbability("fast")))
                .replace("{LAST-PRO}", formatter.apply(data.getLastProbability("pro")))
                .replace("{LAST-ULTRA}", formatter.apply(data.getLastProbability("ultra")))
                .replace("{AVG-FAST}", formatter.apply(data.getAverageProbability("fast")))
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

    private static String resolveTemplate(String modelName, String fastFormat, String proFormat, String ultraFormat) {
        String normalized = modelName == null ? "unknown" : modelName.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "fast":
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
