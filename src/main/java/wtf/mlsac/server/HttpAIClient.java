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

import okhttp3.*;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ScheduledTask;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpAIClient implements IAIClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    private static final long REPORT_STATS_INTERVAL_MS = 30000;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final long PERIODIC_CHECK_INTERVAL_MS = 60000;

    private final JavaPlugin plugin;
    private final String serverAddress;
    private final String apiKey;
    private final Logger logger;
    private final IntSupplier onlinePlayersSupplier;
    private final boolean debug;
    private final Executor httpExecutor;
    private final OkHttpClient httpClient;
    private final AtomicReference<ScheduledTask> heartbeatTask = new AtomicReference<>();
    private final AtomicReference<ScheduledTask> reportStatsTask = new AtomicReference<>();
    private final AtomicReference<ScheduledTask> periodicCheckTask = new AtomicReference<>();
    private final AtomicReference<ScheduledTask> reconnectTask = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile boolean autoReconnectEnabled = true;
    private volatile String sessionId = null;
    private volatile boolean limitExceeded = false;
    private volatile boolean serverErrorState = false;
    private volatile long lastServerErrorTime = 0;
    private static final long SERVER_ERROR_SILENCE_MS = 60000;

    public HttpAIClient(JavaPlugin plugin, String serverAddress, String apiKey,
                        IntSupplier onlinePlayersSupplier, boolean debug) {
        this.plugin = plugin;
        this.serverAddress = serverAddress;
        this.apiKey = apiKey;
        this.logger = plugin.getLogger();
        this.onlinePlayersSupplier = onlinePlayersSupplier;
        this.debug = debug;
        this.httpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "http-ai-client-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    public Executor getExecutor() {
        return httpExecutor;
    }

    @Override
    public CompletableFuture<Boolean> connect() {
        if (shuttingDown.get()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("[HTTP] Connecting to " + serverAddress + "...");

                String initUrl = serverAddress + "/api/v1/init";
                RequestBody initBody = RequestBody.create(JSON, "{\"apiKey\":\"" + apiKey + "\"}");
                Request initRequest = new Request.Builder()
                        .url(initUrl)
                        .post(initBody)
                        .build();

                try (Response response = httpClient.newCall(initRequest).execute()) {
                    if (response.code() == 401 || response.code() == 403) {
                        logger.severe("[HTTP] Authentication failed! API key is invalid, expired, or corrupted. Please check your API key in config.yml");
                        connected.set(false);
                        return false;
                    }
                    if (!response.isSuccessful()) {
                        logger.warning("[HTTP] Init failed: HTTP " + response.code());
                        connected.set(false);
                        return false;
                    }

                    ResponseBody body = response.body();
                    String responseBody;
                    if (body != null) {
                        responseBody = body.string();
                    } else {
                        responseBody = "";
                    }
                    sessionId = extractSessionId(responseBody);
                    if (sessionId == null || sessionId.isEmpty()) {
                        sessionId = "http-session-" + System.currentTimeMillis();
                    }
                }

                connected.set(true);
                logger.info("[HTTP] Connected successfully. Session: " + sessionId);

                startHeartbeat();
                startReportStats();
                startPeriodicCheck();

                return true;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[HTTP] Connection failed: " + e.getMessage());
                connected.set(false);
                return false;
            }
        }, httpExecutor);
    }

    private String extractSessionId(String responseBody) {
        try {
            if (responseBody.contains("sessionId")) {
                int start = responseBody.indexOf("sessionId") + 12;
                int end = responseBody.indexOf("\"", start);
                if (end > start) {
                    return responseBody.substring(start, end);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public CompletableFuture<Boolean> connectWithRetry() {
        return connectWithRetry(0);
    }

    private CompletableFuture<Boolean> connectWithRetry(int attempt) {
        if (shuttingDown.get()) {
            return CompletableFuture.completedFuture(false);
        }
        if (attempt >= MAX_RETRY_ATTEMPTS) {
            logger.severe("[HTTP] Max retry attempts reached");
            return CompletableFuture.completedFuture(false);
        }
        return connect().thenCompose(success -> {
            if (success) {
                return CompletableFuture.completedFuture(true);
            }
            long backoffMs = INITIAL_BACKOFF_MS * (1L << attempt);
            logger.info("[HTTP] Retrying in " + backoffMs + "ms (attempt " + (attempt + 1) + "/" + MAX_RETRY_ATTEMPTS + ")");
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            SchedulerManager.getAdapter().runAsyncDelayed(() -> {
                connectWithRetry(attempt + 1).thenAccept(future::complete);
            }, backoffMs / 50);
            return future;
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        shuttingDown.set(true);
        autoReconnectEnabled = false;

        ScheduledTask hb = heartbeatTask.getAndSet(null);
        if (hb != null) hb.cancel();
        ScheduledTask rs = reportStatsTask.getAndSet(null);
        if (rs != null) rs.cancel();
        ScheduledTask pc = periodicCheckTask.getAndSet(null);
        if (pc != null) pc.cancel();
        ScheduledTask rt = reconnectTask.getAndSet(null);
        if (rt != null) rt.cancel();

        connected.set(false);
        sessionId = null;
        limitExceeded = false;
        serverErrorState = false;

        return CompletableFuture.runAsync(() -> {
            httpExecutor.execute(() -> {
                logger.info("[HTTP] Disconnected from server");
            });
            try {
                if (!((ExecutorService) httpExecutor).awaitTermination(3, TimeUnit.SECONDS)) {
                    ((ExecutorService) httpExecutor).shutdownNow();
                }
            } catch (InterruptedException e) {
                ((ExecutorService) httpExecutor).shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, httpExecutor).thenApply(v -> null);
    }

    private void startHeartbeat() {
        ScheduledTask existing = heartbeatTask.get();
        if (existing != null) existing.cancel();

        heartbeatTask.set(SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get() || !autoReconnectEnabled) return;
            sendHeartbeat();
        }, 100, HEARTBEAT_INTERVAL_MS / 50));
    }

    private void sendHeartbeat() {
        CompletableFuture.runAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/heartbeat";
                String json = sessionId != null
                    ? "{\"sessionId\":\"" + sessionId + "\",\"onlinePlayers\":" + onlinePlayersSupplier.getAsInt() + "}"
                    : "{\"onlinePlayers\":" + onlinePlayersSupplier.getAsInt() + "}";

                RequestBody body = RequestBody.create(JSON, json);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    int code = response.code();
                    if (!response.isSuccessful()) {
                        if (debug) logger.warning("[HTTP] Heartbeat failed: " + code);
                        if (code == 401 || code == 403) {
                            logger.severe("[HTTP] Authentication failed! API key is invalid, expired, or corrupted. Please check your API key in config.yml");
                            scheduleReconnect();
                        } else if (code >= 500) {
                            logger.warning("[HTTP] Heartbeat received server error " + code);
                            enterServerErrorState("Heartbeat received HTTP " + code);
                        }
                    }
                }
            } catch (IOException e) {
                if (debug) logger.warning("[HTTP] Heartbeat error: " + e.getMessage());
            }
        }, httpExecutor);
    }

    private void startReportStats() {
        ScheduledTask existing = reportStatsTask.getAndSet(null);
        if (existing != null) existing.cancel();

        reportStatsTask.set(SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get() || !autoReconnectEnabled) return;
            sendReportStats();
        }, 100, REPORT_STATS_INTERVAL_MS / 50));
    }

    private void startPeriodicCheck() {
        ScheduledTask existing = periodicCheckTask.get();
        if (existing != null) existing.cancel();

        periodicCheckTask.set(SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get() || !autoReconnectEnabled) return;
            performPeriodicCheck();
        }, 100, PERIODIC_CHECK_INTERVAL_MS / 50));
    }

    private void performPeriodicCheck() {
        if (debug) logger.info("[HTTP] Periodic check running...");
        if (isServerInErrorState()) {
            if (debug) logger.info("[HTTP] Still in server error state, attempting to reconnect...");
            scheduleReconnect();
            return;
        }
        if (limitExceeded) {
            if (debug) logger.info("[HTTP] Limit exceeded, will retry after timeout");
            return;
        }
        if (!connected.get()) {
            if (debug) logger.info("[HTTP] Not connected, attempting to reconnect...");
            scheduleReconnect();
        }
    }

    private void sendReportStats() {
        CompletableFuture.runAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/reportstats";
                int players = onlinePlayersSupplier.getAsInt();
                String json = sessionId != null
                    ? "{\"sessionId\":\"" + sessionId + "\",\"onlinePlayers\":" + players + "}"
                    : "{\"onlinePlayers\":" + players + "}";

                RequestBody body = RequestBody.create(JSON, json);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    int code = response.code();
                    if (response.isSuccessful()) {
                        limitExceeded = false;
                        serverErrorState = false;
                    } else if (code == 429) {
                        limitExceeded = true;
                        logger.warning("[HTTP] Online limit exceeded - Predict blocked");
                    } else if (code >= 500) {
                        logger.warning("[HTTP] ReportStats received server error " + code);
                        enterServerErrorState("ReportStats received HTTP " + code);
                    }
                }
            } catch (IOException e) {
                if (debug) logger.warning("[HTTP] ReportStats error: " + e.getMessage());
            }
        }, httpExecutor);
    }

    private void scheduleReconnect() {
        if (shuttingDown.get() || !autoReconnectEnabled) return;
        if (reconnectTask.get() != null) {
            logger.info("[HTTP] Reconnect already scheduled, skipping");
            return;
        }
        logger.info("[HTTP] Scheduling reconnect in 10 seconds...");
        ScheduledTask task = SchedulerManager.getAdapter().runAsyncDelayed(() -> {
            reconnectTask.set(null);
            if (!shuttingDown.get() && autoReconnectEnabled && !connected.get()) {
                connect().thenAccept(success -> {
                    if (!success) {
                        scheduleReconnect();
                    } else {
                        logger.info("[HTTP] Reconnected successfully");
                    }
                });
            }
        }, 200);
        reconnectTask.set(task);
    }

    @Override
    public io.reactivex.rxjava3.core.Observable<AIResponse> predict(byte[] playerData, String playerUuid, String playerName) {
        if (!isConnected()) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("Not connected to HTTP server"));
        }
        if (limitExceeded) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("Online limit exceeded, Predict blocked"));
        }
        if (isServerInErrorState()) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("Server error state active, Predict blocked"));
        }

        return io.reactivex.rxjava3.core.Observable.create(emitter -> {
            CompletableFuture.supplyAsync(() -> {
                try {
                    String url = serverAddress + "/api/v1/predict";
                    String dataBase64 = java.util.Base64.getEncoder().encodeToString(playerData);

                    String json = sessionId != null
                        ? "{\"sessionId\":\"" + sessionId + "\",\"playerData\":\"" + dataBase64 + "\",\"playerUuid\":\"" + playerUuid + "\",\"playerName\":\"" + playerName + "\"}"
                        : "{\"playerData\":\"" + dataBase64 + "\",\"playerUuid\":\"" + playerUuid + "\",\"playerName\":\"" + playerName + "\"}";

                    RequestBody body = RequestBody.create(JSON, json);
                    Request request = new Request.Builder()
                            .url(url)
                            .post(body)
                            .header("X-API-Key", apiKey)
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        ResponseBody respBody = response.body();
                        String responseBody = respBody != null ? respBody.string() : "";
                        int code = response.code();

                        if (code == 401 || code == 403) {
                            logger.severe("[HTTP] Authentication failed! API key is invalid, expired, or corrupted. Please check your API key in config.yml");
                            connected.set(false);
                            throw new RuntimeException("API key is invalid or corrupted");
                        }
                        if (code == 429) {
                            limitExceeded = true;
                            logger.warning("[HTTP] Online limit exceeded - Predict blocked");
                            throw new RuntimeException("Online limit exceeded");
                        }
                        if (code >= 500) {
                            enterServerErrorState("Server error HTTP " + code + ": " + responseBody);
                            throw new RuntimeException("Server error HTTP " + code + " - entering silent mode");
                        }
                        if (!response.isSuccessful()) {
                            throw new RuntimeException("HTTP " + code + ": " + responseBody);
                        }

                        return parsePredictResponse(responseBody);
                    }
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("Server error") || msg.contains("503") || msg.contains("500"))) {
                        enterServerErrorState(msg);
                    }
                    throw new RuntimeException(e.getMessage());
                }
            }, httpExecutor).whenComplete((res, err) -> {
                if (emitter.isDisposed()) return;
                if (err != null) {
                    emitter.onError(err);
                } else {
                    emitter.onNext(res);
                    emitter.onComplete();
                }
            });
        });
    }


    private AIResponse parsePredictResponse(String responseBody) {
        try {
            com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(responseBody).getAsJsonObject();
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

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public boolean isLimitExceeded() {
        return limitExceeded;
    }

    @Override
    public boolean isServerErrorState() {
        return serverErrorState;
    }

    private boolean isServerInErrorState() {
        if (!serverErrorState) return false;
        if (System.currentTimeMillis() - lastServerErrorTime > SERVER_ERROR_SILENCE_MS) {
            logger.info("[HTTP] Server error state expired, clearing");
            serverErrorState = false;
            return false;
        }
        return true;
    }

    private void enterServerErrorState(String reason) {
        serverErrorState = true;
        lastServerErrorTime = System.currentTimeMillis();
        logger.warning("[HTTP] Entering server error state: " + reason);
        scheduleReconnect();
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getServerAddress() {
        return serverAddress;
    }

    public void setAutoReconnectEnabled(boolean enabled) {
        this.autoReconnectEnabled = enabled;
    }

    public boolean isAutoReconnectEnabled() {
        return autoReconnectEnabled;
    }
}