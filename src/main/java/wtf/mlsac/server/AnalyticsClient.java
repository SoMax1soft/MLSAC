/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
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
 */

package wtf.mlsac.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class AnalyticsClient {
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final Logger logger;
    private final ExecutorService executor;
    private final Map<String, AnalyticsResult> cache = new ConcurrentHashMap<>();

    public AnalyticsClient(String baseUrl, Logger logger) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.logger = logger;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mlsac-analytics-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    public CompletableFuture<AnalyticsResult> checkPlayer(String playerName) {
        AnalyticsResult cached = cache.get(playerName.toLowerCase());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        String cacheKey = playerName.toLowerCase();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedName = java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8.name());
                String url = baseUrl + "/analytics/check/" + encodedName;
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        cache.put(cacheKey, AnalyticsResult.NOT_FOUND);
                        return AnalyticsResult.NOT_FOUND;
                    }

                    String body = response.body().string();
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                    boolean success = json.has("success") && json.get("success").getAsBoolean();
                    if (!success) {
                        cache.put(cacheKey, AnalyticsResult.NOT_FOUND);
                        return AnalyticsResult.NOT_FOUND;
                    }

                    JsonObject data = json.getAsJsonObject("data");
                    if (data == null) {
                        cache.put(cacheKey, AnalyticsResult.NOT_FOUND);
                        return AnalyticsResult.NOT_FOUND;
                    }

                    boolean isFound = data.has("isFound") && data.get("isFound").getAsBoolean();
                    int totalDetections = data.has("totalDetections") ? data.get("totalDetections").getAsInt() : 0;

                    AnalyticsResult result = new AnalyticsResult(isFound, totalDetections);
                    cache.put(cacheKey, result);
                    return result;
                }
            } catch (Exception e) {
                logger.warning("Failed to check analytics for " + playerName + ": " + e.getMessage());
                return AnalyticsResult.NOT_FOUND;
            }
        }, executor);
    }

    public void invalidateCache(String playerName) {
        cache.remove(playerName.toLowerCase());
    }

    public void clearCache() {
        cache.clear();
    }

    public void shutdown() {
        executor.shutdownNow();
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
    }

    public static class AnalyticsResult {
        public static final AnalyticsResult NOT_FOUND = new AnalyticsResult(false, 0);

        private final boolean found;
        private final int totalDetections;

        public AnalyticsResult(boolean found, int totalDetections) {
            this.found = found;
            this.totalDetections = totalDetections;
        }

        public boolean isFound() {
            return found;
        }

        public int getTotalDetections() {
            return totalDetections;
        }
    }
}
