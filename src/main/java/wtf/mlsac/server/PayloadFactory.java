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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import wtf.mlsac.Main;

import java.util.Base64;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Builds the JSON request bodies the HTTP client sends to the inference backend. Holds the static
 * server-identity context so the client no longer has to carry those fields or repeat the
 * "base payload + session + online count" boilerplate at every call site.
 */
final class PayloadFactory {
    private final Main plugin;
    private final String serverName;
    private final String serverFamily;
    private final boolean interServerEnabled;
    private final IntSupplier onlinePlayersSupplier;

    PayloadFactory(Main plugin, String serverName, String serverFamily,
            boolean interServerEnabled, IntSupplier onlinePlayersSupplier) {
        this.plugin = plugin;
        this.serverName = normalize(serverName);
        this.serverFamily = normalize(serverFamily);
        this.interServerEnabled = interServerEnabled;
        this.onlinePlayersSupplier = onlinePlayersSupplier;
    }

    private static String normalize(String value) {
        return value != null && !value.trim().isEmpty() ? value.trim() : "default";
    }

    /** The common envelope shared by every request. */
    JsonObject base() {
        JsonObject json = new JsonObject();
        json.addProperty("pluginVersion", plugin.getDescription().getVersion());
        json.addProperty("interserverEventsSupported", true);
        json.addProperty("serverName", serverName);
        json.addProperty("serverFamily", serverFamily);
        json.addProperty("family", serverFamily);
        json.addProperty("serverIp", advertisedServerIp());
        json.addProperty("serverPort", plugin.getServer().getPort());
        json.addProperty("interServer", interServerEnabled);
        return json;
    }

    void addOnline(JsonObject json) {
        int online = onlinePlayersSupplier.getAsInt();
        json.addProperty("onlinePlayers", online);
        json.addProperty("onlineCount", online);
    }

    void addSession(JsonObject json, String sessionId) {
        if (sessionId != null) {
            json.addProperty("sessionId", sessionId);
        }
    }

    JsonObject predict(byte[] playerData, String playerUuid, String playerName, String sessionId) {
        JsonObject json = base();
        addSession(json, sessionId);
        json.addProperty("playerData", Base64.getEncoder().encodeToString(playerData));
        json.addProperty("playerUuid", playerUuid);
        json.addProperty("playerName", playerName);
        return json;
    }

    JsonObject event(String type, String playerUuid, String playerName, String model,
            double probability, double buffer, int violationLevel,
            String action, String command, String sessionId) {
        JsonObject json = base();
        addSession(json, sessionId);
        json.addProperty("type", type);
        json.addProperty("playerUuid", playerUuid);
        json.addProperty("playerId", playerUuid);
        json.addProperty("playerName", playerName);
        json.addProperty("model", model);
        json.addProperty("probability", probability);
        json.addProperty("buffer", buffer);
        json.addProperty("vl", violationLevel);
        json.addProperty("violationLevel", violationLevel);
        json.addProperty("action", action != null ? action : type);
        json.addProperty("command", command != null ? command : "");
        return json;
    }

    JsonObject reportSubmission(String reporterUuid, String reporterName, String targetUuid,
            String targetName, String reason, List<Double> checks, boolean crossServer, String sessionId) {
        JsonObject json = base();
        addSession(json, sessionId);
        if (reporterUuid != null) {
            json.addProperty("reporterUuid", reporterUuid);
        }
        json.addProperty("reporterName", reporterName);
        if (targetUuid != null) {
            json.addProperty("targetUuid", targetUuid);
        }
        json.addProperty("targetName", targetName);
        json.addProperty("reason", reason);
        json.addProperty("crossServer", crossServer);
        JsonArray checksArray = new JsonArray();
        if (checks != null) {
            for (Double value : checks) {
                if (value != null) {
                    checksArray.add(value);
                }
            }
        }
        json.add("checks", checksArray);
        return json;
    }

    JsonObject reportsList(boolean crossReports, String sessionId) {
        JsonObject json = base();
        addSession(json, sessionId);
        json.addProperty("crossReports", crossReports);
        json.addProperty("status", "active");
        return json;
    }

    JsonObject reportTransition(String handlerName, String cancelReason, String sessionId) {
        JsonObject json = base();
        addSession(json, sessionId);
        if (handlerName != null) {
            json.addProperty("handlerName", handlerName);
        }
        if (cancelReason != null) {
            json.addProperty("cancelReason", cancelReason);
        }
        return json;
    }

    private String advertisedServerIp() {
        String bukkitIp = plugin.getServer().getIp();
        return bukkitIp != null && !bukkitIp.trim().isEmpty() ? bukkitIp.trim() : "unknown";
    }
}
