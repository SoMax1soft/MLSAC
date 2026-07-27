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

package wtf.mlsac.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSupportTest {

    private static JsonObject obj(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    @Test
    void getStringReadsPresentValueAndFallsBack() {
        assertEquals("hi", JsonSupport.getString(obj("{\"k\":\"hi\"}"), "k", "fb"));
        assertEquals("fb", JsonSupport.getString(obj("{}"), "k", "fb"));
        assertEquals("fb", JsonSupport.getString(obj("{\"k\":null}"), "k", "fb"));
    }

    @Test
    void getDoubleReadsPresentValueAndFallsBack() {
        assertEquals(1.5, JsonSupport.getDouble(obj("{\"k\":1.5}"), "k", -1.0), 1e-9);
        assertEquals(-1.0, JsonSupport.getDouble(obj("{}"), "k", -1.0), 1e-9);
        assertEquals(-1.0, JsonSupport.getDouble(obj("{\"k\":null}"), "k", -1.0), 1e-9);
    }

    @Test
    void parseReadsProbabilityAndModel() {
        AIResponse response = JsonSupport.parsePredictResponse("{\"probability\":0.9,\"model\":\"v2\"}");
        assertEquals(0.9, response.getProbability(), 1e-9);
        assertEquals("v2", response.getModel());
        assertNull(response.getError());
    }

    @Test
    void parseDefaultsWhenFieldsMissing() {
        AIResponse response = JsonSupport.parsePredictResponse("{}");
        assertEquals(0.0, response.getProbability(), 1e-9);
        // AIResponse.getModel() coalesces a null model to "unknown".
        assertEquals("unknown", response.getModel());
        assertNull(response.getError());
    }

    @Test
    void parseThrowsOnErrorField() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> JsonSupport.parsePredictResponse("{\"error\":\"INVALID_SEQUENCE:40\"}"));
        assertTrue(ex.getMessage().contains("INVALID_SEQUENCE:40"));
    }

    @Test
    void parseThrowsOnMalformedJson() {
        assertThrows(RuntimeException.class, () -> JsonSupport.parsePredictResponse("not json"));
    }
}
