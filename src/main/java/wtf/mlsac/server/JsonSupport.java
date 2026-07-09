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

package wtf.mlsac.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Small JSON helpers shared by the HTTP client. {@link #getString} and {@link #getDouble} are
 * lenient accessors that fall back instead of throwing on missing/malformed fields.
 */
final class JsonSupport {
    private JsonSupport() {
    }

    static String getString(JsonObject object, String key, String fallback) {
        try {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static double getDouble(JsonObject object, String key, double fallback) {
        try {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsDouble();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * Strict prediction parser: a non-empty {@code error} field, malformed JSON, or a malformed
     * {@code probability} all surface as a {@link RuntimeException} so the caller fails the request
     * rather than acting on a bogus probability.
     */
    static AIResponse parsePredictResponse(String responseBody) {
        try {
            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            double probability = json.has("probability") ? json.get("probability").getAsDouble() : 0.0;
            String model = json.has("model") ? json.get("model").getAsString() : null;
            String error = json.has("error") ? json.get("error").getAsString() : null;

            if (error != null && !error.isEmpty()) {
                throw new RuntimeException(error);
            }

            return new AIResponse(probability, null, model);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response: " + e.getMessage());
        }
    }
}
