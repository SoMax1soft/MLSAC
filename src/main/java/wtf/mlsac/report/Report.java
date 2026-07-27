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

package wtf.mlsac.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single player report as tracked by the backend queue. Immutable snapshot decoded from the
 * {@code /api/v1/reports/list} response; the plugin never mutates it locally — status changes go
 * through the backend and are reflected on the next fetch.
 */
public final class Report {
    private final int id;
    private final String reporterName;
    private final String targetName;
    private final String targetUuid;
    private final String reason;
    private final ReportStatus status;
    private final String handlerName;
    private final String cancelReason;
    private final String serverName;
    private final boolean crossServer;
    private final boolean local;
    private final long claimedAtMs;
    private final List<Double> checks;

    public Report(int id, String reporterName, String targetName, String targetUuid, String reason,
            ReportStatus status, String handlerName, String cancelReason, String serverName,
            boolean crossServer, boolean local, List<Double> checks) {
        this(id, reporterName, targetName, targetUuid, reason, status, handlerName, cancelReason,
                serverName, crossServer, local, 0L, checks);
    }

    public Report(int id, String reporterName, String targetName, String targetUuid, String reason,
            ReportStatus status, String handlerName, String cancelReason, String serverName,
            boolean crossServer, boolean local, long claimedAtMs, List<Double> checks) {
        this.id = id;
        this.reporterName = reporterName;
        this.targetName = targetName;
        this.targetUuid = targetUuid;
        this.reason = reason;
        this.status = status;
        this.handlerName = handlerName;
        this.cancelReason = cancelReason;
        this.serverName = serverName;
        this.crossServer = crossServer;
        this.local = local;
        this.claimedAtMs = claimedAtMs;
        this.checks = checks != null ? checks : Collections.emptyList();
    }

    /** Decodes one report object from the backend list response. Returns {@code null} if malformed. */
    public static Report fromJson(JsonObject json) {
        if (json == null) {
            return null;
        }
        int id = json.has("id") && json.get("id").isJsonPrimitive() ? json.get("id").getAsInt() : -1;
        if (id <= 0) {
            return null;
        }
        long claimedAt = json.has("claimedAt") && json.get("claimedAt").isJsonPrimitive()
                ? json.get("claimedAt").getAsLong() : 0L;
        if (claimedAt == 0L && json.has("claimed_at") && json.get("claimed_at").isJsonPrimitive()) {
            claimedAt = json.get("claimed_at").getAsLong();
        }
        return new Report(
                id,
                string(json, "reporterName", "Unknown"),
                string(json, "targetName", "Unknown"),
                string(json, "targetUuid", null),
                string(json, "reason", ""),
                ReportStatus.fromString(string(json, "status", "open")),
                string(json, "handlerName", null),
                string(json, "cancelReason", null),
                string(json, "serverName", null),
                bool(json, "crossServer"),
                bool(json, "local"),
                claimedAt,
                parseChecks(json.get("checks")));
    }

    private static List<Double> parseChecks(JsonElement element) {
        List<Double> checks = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                if (entry != null && entry.isJsonPrimitive()) {
                    try {
                        checks.add(entry.getAsDouble());
                    } catch (NumberFormatException ignored) {
                        // Skip non-numeric entries defensively.
                    }
                }
            }
        }
        return checks;
    }

    private static String string(JsonObject json, String key, String fallback) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            return json.get(key).getAsString();
        }
        return fallback;
    }

    private static boolean bool(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return false;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    public int getId() {
        return id;
    }

    public String getReporterName() {
        return reporterName;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getTargetUuid() {
        return targetUuid;
    }

    public String getReason() {
        return reason;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isCrossServer() {
        return crossServer;
    }

    /** Whether the report originated on this server (teleport actions are only allowed if true). */
    public boolean isLocal() {
        return local;
    }

    public long getClaimedAtMs() {
        return claimedAtMs;
    }

    public List<Double> getChecks() {
        return checks;
    }
}
