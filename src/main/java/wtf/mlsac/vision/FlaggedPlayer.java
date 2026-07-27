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
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.vision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One player MLS VISION flagged, as served by {@code GET /api/v1/vision/flagged}.
 *
 * <p>The verdict text arrives already rendered by the backend: the wording lives in one place
 * there, so the in-game menu never has to keep a second copy of the templates in step.
 */
public final class FlaggedPlayer {
    public final String playerName;
    public final String risk;
    public final String verdict;
    public final List<String> involved;
    public final String logRef;
    public final int trustScore;
    public final int playtimeHours;

    private FlaggedPlayer(String playerName, String risk, String verdict, List<String> involved,
            String logRef, int trustScore, int playtimeHours) {
        this.playerName = playerName;
        this.risk = risk;
        this.verdict = verdict;
        this.involved = involved;
        this.logRef = logRef;
        this.trustScore = trustScore;
        this.playtimeHours = playtimeHours;
    }

    public boolean isConfirmed() {
        return "confirmed".equalsIgnoreCase(risk);
    }

    public static List<FlaggedPlayer> parseList(String responseBody) {
        List<FlaggedPlayer> result = new ArrayList<>();
        if (responseBody == null || responseBody.isEmpty()) {
            return result;
        }
        try {
            JsonObject root = new com.google.gson.JsonParser().parse(responseBody).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                return result;
            }
            JsonObject data = root.getAsJsonObject("data");
            if (!data.has("players") || !data.get("players").isJsonArray()) {
                return result;
            }
            for (JsonElement element : data.getAsJsonArray("players")) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                String name = string(json, "playerName");
                if (name == null || name.isEmpty()) continue;

                List<String> involved = new ArrayList<>();
                if (json.has("involved") && json.get("involved").isJsonArray()) {
                    JsonArray array = json.getAsJsonArray("involved");
                    for (JsonElement entry : array) {
                        if (entry != null && entry.isJsonPrimitive()) {
                            involved.add(entry.getAsString());
                        }
                    }
                }

                result.add(new FlaggedPlayer(
                        name,
                        string(json, "risk"),
                        string(json, "verdict"),
                        Collections.unmodifiableList(involved),
                        string(json, "logRef"),
                        number(json, "trustScore"),
                        number(json, "playtimeHours")
                ));
            }
        } catch (Exception ignored) {
            // A malformed page is not worth a stack trace on a menu the admin can just reopen.
        }
        return result;
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static int number(JsonObject json, String key) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
