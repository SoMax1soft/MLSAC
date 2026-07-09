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
 */

package wtf.mlsac.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.alert.AlertManager;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.ColorUtil;
import wtf.mlsac.util.SecurityUtil;
import java.io.BufferedReader;
import java.util.Locale;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpAIClient implements IAIClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    // Circuit breaker: this many connection-level failures in a row (timeouts, refused
    // connections) mark the backend unreachable and suspend all outgoing requests.
    private static final int NETWORK_FAILURE_THRESHOLD = 3;
    private static final long RECONNECT_INITIAL_DELAY_MS = 10_000;
    private static final long RECONNECT_MAX_DELAY_MS = 300_000;
    private static final int MAX_IN_FLIGHT_PREDICTS = 8;
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    private static final long REPORT_STATS_INTERVAL_MS = 30000;
    private static final long INTERSERVER_EVENT_POLL_INTERVAL_MS = 3000;
    // Kept short: a healthy backend accepts a TCP connection near-instantly, and while the
    // backend is down every queued request blocks a worker thread for this long.
    private static final int CONNECT_TIMEOUT_SECONDS = 4;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final long PERIODIC_CHECK_INTERVAL_MS = 60000;
    private static final long STASIS_CHECK_INTERVAL_MS = 300000; // 5 minutes
    private static final int DUPLICATE_NAME_WARNING_LIMIT = 3;
    private static final long DUPLICATE_NAME_WARNING_INTERVAL_MS = 30000;
    private static final int INTERSERVER_EVENT_CACHE_LIMIT = 512;

    private final Main plugin;
    private final String serverAddress;
    private final String apiKey;
    private final Logger logger;
    private final boolean debug;
    private final PayloadFactory payloads;
    private final boolean eventReportingEnabled;
    private final double apiAlertEventThreshold;
    private final ExecutorService httpExecutor;
    private final OkHttpClient httpClient;
    private final ManagedTask heartbeat = new ManagedTask();
    private final ManagedTask reportStats = new ManagedTask();
    private final ManagedTask interserverEvent = new ManagedTask();
    private final ManagedTask periodicCheck = new ManagedTask();
    private final ManagedTask reconnect = new ManagedTask();
    private final ManagedTask stasisCheck = new ManagedTask();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean interserverPollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean inStasisMode = new AtomicBoolean(false);
    private volatile boolean autoReconnectEnabled = true;
    private volatile String sessionId = null;
    private volatile boolean limitExceeded = false;
    private static final long SERVER_ERROR_SILENCE_MS = 60000;
    private final TimedErrorState serverError = new TimedErrorState(SERVER_ERROR_SILENCE_MS);
    private final WarningThrottle duplicateNameWarning =
            new WarningThrottle(DUPLICATE_NAME_WARNING_LIMIT, DUPLICATE_NAME_WARNING_INTERVAL_MS);
    private final RecentEventCache seenInterserverEvents = new RecentEventCache(INTERSERVER_EVENT_CACHE_LIMIT);
    private final AtomicInteger consecutiveNetworkFailures = new AtomicInteger();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicInteger inFlightPredicts = new AtomicInteger();

    public HttpAIClient(Main plugin, String serverAddress, String apiKey,
                        IntSupplier onlinePlayersSupplier, boolean debug) {
        this(plugin, serverAddress, apiKey, onlinePlayersSupplier, debug,
                "default", "default", false, true, 0.75);
    }

    public HttpAIClient(Main plugin, String serverAddress, String apiKey,
                        IntSupplier onlinePlayersSupplier, boolean debug,
                        String serverName, String serverFamily, boolean interServerEnabled,
                        boolean eventReportingEnabled, double apiAlertEventThreshold) {
        this.plugin = plugin;
        this.serverAddress = serverAddress;
        this.apiKey = apiKey;
        this.logger = plugin.getLogger();
        this.debug = debug;
        if (serverAddress != null && serverAddress.toLowerCase(Locale.ROOT).startsWith("http://")
                && !serverAddress.contains("localhost") && !serverAddress.contains("127.0.0.1")) {
            logger.warning("[HTTP] AI backend uses plaintext http:// to a remote host - predictions and"
                    + " punish reports can be read or modified in transit. Use https:// for remote backends.");
        }
        this.payloads = new PayloadFactory(plugin, serverName, serverFamily,
                interServerEnabled, onlinePlayersSupplier);
        this.eventReportingEnabled = eventReportingEnabled;
        this.apiAlertEventThreshold = apiAlertEventThreshold;
        int workers = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        this.httpExecutor = Executors.newFixedThreadPool(workers, r -> {
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

    private RequestBody jsonBody(JsonObject json) {
        return RequestBody.create(JSON, json.toString());
    }

    private void handleApiWarnings(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return;
        }
        try {
            JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
            if (!root.has("warnings") || !root.get("warnings").isJsonObject()) {
                return;
            }
            JsonObject warnings = root.getAsJsonObject("warnings");
            if (!warnings.has("duplicateServerName") || !warnings.get("duplicateServerName").isJsonObject()) {
                return;
            }
            JsonObject duplicate = warnings.getAsJsonObject("duplicateServerName");
            if (!duplicate.has("active") || !duplicate.get("active").getAsBoolean()) {
                duplicateNameWarning.reset();
                return;
            }
            String message = duplicate.has("message")
                    ? duplicate.get("message").getAsString()
                    : "MLSAC server-name is duplicated on another active server. Change server-identity.name in config.yml.";
            warnDuplicateServerName(message);
        } catch (Exception ignored) {
        }
    }

    private void handleInterserverEvents(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return;
        }

        try {
            JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                return;
            }

            JsonObject data = root.getAsJsonObject("data");
            if (!data.has("interserverEvents") || !data.get("interserverEvents").isJsonArray()) {
                return;
            }

            JsonArray events = data.getAsJsonArray("interserverEvents");
            for (JsonElement element : events) {
                if (element != null && element.isJsonObject()) {
                    handleInterserverEvent(element.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            if (debug) {
                logger.warning("[HTTP] Failed to parse inter-server events: " + e.getMessage());
            }
        }
    }

    private void handleInterserverEvent(JsonObject event) {
        String eventId = JsonSupport.getString(event, "id", "");
        if (!seenInterserverEvents.markIfNew(eventId)) {
            return;
        }

        AlertManager alertManager = plugin.getAlertManager();
        if (alertManager == null) {
            return;
        }

        // Inter-server payloads come from outside this server; strip control characters and cap
        // lengths before they reach admin chat/console.
        String type = SecurityUtil.sanitizeChatText(JsonSupport.getString(event, "type", "alert"), 24);
        String sourceServerName = SecurityUtil.sanitizeChatText(JsonSupport.getString(event, "serverName", "unknown"), 32);
        String playerName = SecurityUtil.sanitizeChatText(JsonSupport.getString(event, "playerName", "Unknown"), 32);
        String model = SecurityUtil.sanitizeChatText(JsonSupport.getString(event, "model", "unknown"), 32);
        String action = SecurityUtil.sanitizeChatText(JsonSupport.getString(event, "action", type), 48);
        double probability = JsonSupport.getDouble(event, "probability", 0.0);
        double buffer = JsonSupport.getDouble(event, "buffer", 0.0);
        int violationLevel = (int) Math.round(JsonSupport.getDouble(event, "violationLevel", JsonSupport.getDouble(event, "vl", 0.0)));

        alertManager.sendInterServerEvent(type, sourceServerName, playerName, probability,
                buffer, violationLevel, model, action);
    }

    private void warnDuplicateServerName(String message) {
        if (!duplicateNameWarning.shouldFire(System.currentTimeMillis())) {
            return;
        }

        logger.warning("[MLSAC] " + message);
        SchedulerManager.getAdapter().runSync(() -> {
            String chatMessage = ColorUtil.colorize("&c[MLSAC] &f" + message);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(Permissions.ALERTS) || player.isOp()) {
                    player.sendMessage(chatMessage);
                }
            }
        });
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
                JsonObject initJson = payloads.base();
                initJson.addProperty("apiKey", apiKey);
                payloads.addOnline(initJson);
                RequestBody initBody = jsonBody(initJson);
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
                    handleApiWarnings(responseBody);
                    sessionId = extractSessionId(responseBody);
                    if (sessionId == null || sessionId.isEmpty()) {
                        sessionId = "http-session-" + System.currentTimeMillis();
                    }
                }

                connected.set(true);
                consecutiveNetworkFailures.set(0);
                reconnectAttempts.set(0);
                logger.info("[HTTP] Connected successfully. Session: " + sessionId);

                startHeartbeat();
                startReportStats();
                startInterserverEventPoll();
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
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
            String sid = JsonSupport.getString(root, "sessionId", null);
            if ((sid == null || sid.isEmpty()) && root.has("data") && root.get("data").isJsonObject()) {
                sid = JsonSupport.getString(root.getAsJsonObject("data"), "sessionId", null);
            }
            return sid != null && !sid.isEmpty() ? sid : null;
        } catch (Exception ignored) {
            return null;
        }
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
            logger.severe("[HTTP] Max retry attempts reached - continuing to retry in the background");
            // Without this the plugin would stay offline until a manual reload whenever the
            // backend happened to be down during startup: the periodic check and reconnect chain
            // were only ever started from a successful connect().
            startPeriodicCheck();
            scheduleReconnect();
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

        heartbeat.cancel();
        reportStats.cancel();
        interserverEvent.cancel();
        periodicCheck.cancel();
        reconnect.cancel();
        stasisCheck.cancel();

        connected.set(false);
        sessionId = null;
        limitExceeded = false;
        serverError.clear();
        inStasisMode.set(false);

        return CompletableFuture.runAsync(() -> {
            logger.info("[HTTP] Disconnected from server");
            httpClient.dispatcher().cancelAll();
            httpClient.connectionPool().evictAll();
            httpExecutor.shutdown();
            try {
                if (!httpExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    httpExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                httpExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }).thenApply(v -> null);
    }

    private void startHeartbeat() {
        heartbeat.reschedule(() -> SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get() || !autoReconnectEnabled) return;
            sendHeartbeat();
        }, 100, HEARTBEAT_INTERVAL_MS / 50));
    }

    private void sendHeartbeat() {
        CompletableFuture.runAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/heartbeat";
                JsonObject json = payloads.base();
                payloads.addSession(json, sessionId);
                payloads.addOnline(json);

                RequestBody body = jsonBody(json);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    recordNetworkSuccess();
                    ResponseBody respBody = response.body();
                    String responseBody = respBody != null ? respBody.string() : "";
                    handleApiWarnings(responseBody);
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
                recordNetworkFailure("heartbeat");
            }
        }, httpExecutor);
    }

    private void startReportStats() {
        reportStats.reschedule(() -> SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get() || !autoReconnectEnabled) return;
            sendReportStats();
        }, 100, REPORT_STATS_INTERVAL_MS / 50));
    }

    private void startInterserverEventPoll() {
        interserverEvent.reschedule(() -> SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get() || !autoReconnectEnabled) return;
            sendInterserverEventPoll();
        }, 60, INTERSERVER_EVENT_POLL_INTERVAL_MS / 50));
    }

    private void startPeriodicCheck() {
        periodicCheck.reschedule(() -> SchedulerManager.getAdapter().runAsyncRepeating(() -> {
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
                String url = serverAddress + "/api/v1/online";
                JsonObject json = payloads.base();
                payloads.addSession(json, sessionId);
                payloads.addOnline(json);

                RequestBody body = jsonBody(json);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    recordNetworkSuccess();
                    int code = response.code();
                    ResponseBody respBody = response.body();
                    String responseBody = respBody != null ? respBody.string() : "";
                    handleApiWarnings(responseBody);
                    if (code == 404 || code == 405) {
                        sendLegacyReportStats(json);
                        return;
                    }
                    if (response.isSuccessful()) {
                        handleInterserverEvents(responseBody);
                        limitExceeded = false;
                        serverError.clear();
                        exitStasisMode();
                    } else if (code == 429) {
                        handleRateLimitError();
                    } else if (code >= 500) {
                        logger.warning("[HTTP] ReportStats received server error " + code);
                        enterServerErrorState("ReportStats received HTTP " + code);
                    }
                }
            } catch (IOException e) {
                if (debug) logger.warning("[HTTP] ReportStats error: " + e.getMessage());
                recordNetworkFailure("reportstats");
            }
        }, httpExecutor);
    }

    private void sendInterserverEventPoll() {
        if (!isConnected() || sessionId == null || isServerInErrorState()) {
            return;
        }
        if (!interserverPollInFlight.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/events/poll";
                JsonObject json = payloads.base();
                payloads.addSession(json, sessionId);

                Request request = new Request.Builder()
                        .url(url)
                        .post(jsonBody(json))
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    recordNetworkSuccess();
                    int code = response.code();
                    ResponseBody respBody = response.body();
                    String responseBody = respBody != null ? respBody.string() : "";
                    handleApiWarnings(responseBody);
                    if (code == 404 || code == 405) {
                        return;
                    }
                    if (code == 401 || code == 403) {
                        connected.set(false);
                        return;
                    }
                    if (response.isSuccessful()) {
                        handleInterserverEvents(responseBody);
                    } else if (debug) {
                        logger.warning("[HTTP] Inter-server event poll failed: HTTP " + code);
                    }
                }
            } catch (IOException e) {
                if (debug) logger.warning("[HTTP] Inter-server event poll error: " + e.getMessage());
                recordNetworkFailure("interserver-poll");
            } finally {
                interserverPollInFlight.set(false);
            }
        }, httpExecutor);
    }

    private void sendLegacyReportStats(JsonObject json) throws IOException {
        String url = serverAddress + "/api/v1/reportstats";
        Request request = new Request.Builder()
                .url(url)
                .post(jsonBody(json))
                .header("X-API-Key", apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            ResponseBody respBody = response.body();
            String responseBody = respBody != null ? respBody.string() : "";
            handleApiWarnings(responseBody);
            if (response.isSuccessful()) {
                limitExceeded = false;
                serverError.clear();
                exitStasisMode();
            } else if (code == 429) {
                handleRateLimitError();
            } else if (code >= 500) {
                logger.warning("[HTTP] ReportStats received server error " + code);
                enterServerErrorState("ReportStats received HTTP " + code);
            }
        }
    }

    private void scheduleReconnect() {
        if (shuttingDown.get() || !autoReconnectEnabled) return;
        if (reconnect.isScheduled()) {
            logger.info("[HTTP] Reconnect already scheduled, skipping");
            return;
        }
        int attempt = reconnectAttempts.getAndIncrement();
        long delayMs = Math.min(RECONNECT_INITIAL_DELAY_MS << Math.min(attempt, 5), RECONNECT_MAX_DELAY_MS);
        // Jitter so a fleet of servers does not reconnect in lockstep after a backend outage.
        delayMs += ThreadLocalRandom.current().nextLong(delayMs / 5 + 1);
        logger.info("[HTTP] Scheduling reconnect in " + (delayMs / 1000) + "s (attempt " + (attempt + 1) + ")");
        long delayTicks = Math.max(1, delayMs / 50);
        reconnect.reschedule(() -> SchedulerManager.getAdapter().runAsyncDelayed(() -> {
            reconnect.clearReference();
            if (!shuttingDown.get() && autoReconnectEnabled && !connected.get()) {
                connect().thenAccept(success -> {
                    if (!success) {
                        scheduleReconnect();
                    } else {
                        logger.info("[HTTP] Reconnected successfully");
                    }
                });
            }
        }, delayTicks));
    }

    @Override
    public io.reactivex.rxjava3.core.Observable<AIResponse> predict(byte[] playerData, String playerUuid, String playerName) {
        if (!isConnected()) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("Not connected to HTTP server"));
        }
        if (inStasisMode.get()) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("API in stasis mode (rate limited)"));
        }
        if (limitExceeded) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("Request limit | Upgrade tariff"));
        }
        if (isServerInErrorState()) {
            return io.reactivex.rxjava3.core.Observable.error(
                    new IllegalStateException("Server error state active, Predict blocked"));
        }

        return io.reactivex.rxjava3.core.Observable.create(emitter -> {
            // Cap queued+running predicts. Windows overlap by 75%, so dropping one is harmless -
            // unlike letting a slow or dead backend build an unbounded executor queue of stale
            // requests that each block a worker thread for the full timeout.
            if (inFlightPredicts.incrementAndGet() > MAX_IN_FLIGHT_PREDICTS) {
                inFlightPredicts.decrementAndGet();
                if (debug) {
                    logger.warning("[HTTP] Dropping predict for " + playerName + ": "
                            + MAX_IN_FLIGHT_PREDICTS + " requests already in flight");
                }
                if (!emitter.isDisposed()) {
                    emitter.onComplete();
                }
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject payload = payloads.predict(playerData, playerUuid, playerName, sessionId);
                    executeStreamingPredict(payload, emitter);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                } catch (Exception e) {
                    if (e instanceof IOException || e.getCause() instanceof IOException) {
                        recordNetworkFailure("predict");
                    }
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("Server error") || msg.contains("503") || msg.contains("500"))) {
                        enterServerErrorState(msg);
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onError(new RuntimeException(e.getMessage()));
                    }
                } finally {
                    inFlightPredicts.decrementAndGet();
                }
            }, httpExecutor);
        });
    }

    /**
     * The only predict transport: NDJSON streaming from {@code /api/v1/predict-stream} (one line
     * per model, terminated by a {@code done} line). The legacy single-shot {@code /api/v1/predict}
     * fallback was removed on purpose - it doubled backend load whenever a stream completed without
     * predictions, and every supported backend serves the stream endpoint.
     */
    private void executeStreamingPredict(JsonObject json,
            io.reactivex.rxjava3.core.ObservableEmitter<AIResponse> emitter) throws IOException {
        String url = serverAddress + "/api/v1/predict-stream";
        Request request = new Request.Builder()
                .url(url)
                .post(jsonBody(json))
                .header("X-API-Key", apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            recordNetworkSuccess();
            int code = response.code();
            if (code == 404 || code == 405) {
                throw new RuntimeException("Backend does not support /api/v1/predict-stream (HTTP "
                        + code + ") - update the inference backend");
            }
            ResponseBody respBody = response.body();
            if (respBody == null) {
                handlePredictStatus(code, "");
                return;
            }
            if (!response.isSuccessful()) {
                String responseBody = respBody.string();
                handleApiWarnings(responseBody);
                handlePredictStatus(code, responseBody);
                return;
            }

            try (BufferedReader reader = new BufferedReader(respBody.charStream())) {
                String line;
                while (!emitter.isDisposed() && (line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    handleApiWarnings(line);
                    JsonObject object = new JsonParser().parse(line).getAsJsonObject();
                    String type = object.has("type") ? object.get("type").getAsString() : "prediction";
                    if ("done".equalsIgnoreCase(type)) {
                        break;
                    }
                    if ("error".equalsIgnoreCase(type)) {
                        if (debug) logger.warning("[HTTP] Streaming model error: " + line);
                        continue;
                    }
                    AIResponse aiResponse = JsonSupport.parsePredictResponse(line);
                    emitter.onNext(aiResponse);
                }
            }
        }
    }

    private void handlePredictStatus(int code, String responseBody) {
        if (code == 401 || code == 403) {
            logger.severe("[HTTP] Authentication failed! API key is invalid, expired, or corrupted. Please check your API key in config.yml");
            connected.set(false);
            throw new RuntimeException("API key is invalid or corrupted");
        }
        if (code == 429) {
            handleRateLimitError();
            throw new RuntimeException("Request limit");
        }
        if (code >= 500) {
            enterServerErrorState("Server error HTTP " + code + ": " + responseBody);
            throw new RuntimeException("Server error HTTP " + code + " - entering silent mode");
        }
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + responseBody);
        }
    }

    @Override
    public CompletableFuture<Boolean> reportAlert(String playerUuid, String playerName,
            String model, double probability, double buffer) {
        if (!eventReportingEnabled || probability < apiAlertEventThreshold) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject json = payloads.event("alert", playerUuid, playerName, model,
                probability, buffer, 0, "alert", "", sessionId);
        return sendEvent(json);
    }

    @Override
    public CompletableFuture<Boolean> reportPunish(String playerUuid, String playerName,
            String model, double probability, double buffer, int violationLevel,
            String action, String command) {
        if (!eventReportingEnabled) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject json = payloads.event("punish", playerUuid, playerName, model,
                probability, buffer, violationLevel, action, command, sessionId);
        return sendEvent(json);
    }

    private CompletableFuture<Boolean> sendEvent(JsonObject json) {
        if (!isConnected() || isServerInErrorState()) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/events";
                Request request = new Request.Builder()
                        .url(url)
                        .post(jsonBody(json))
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    ResponseBody respBody = response.body();
                    String responseBody = respBody != null ? respBody.string() : "";
                    handleApiWarnings(responseBody);
                    int code = response.code();
                    if (code == 404 || code == 405) {
                        return false;
                    }
                    if (code == 401 || code == 403) {
                        connected.set(false);
                        return false;
                    }
                    if (code >= 500) {
                        enterServerErrorState("Event report received HTTP " + code);
                        return false;
                    }
                    return response.isSuccessful();
                }
            } catch (Exception e) {
                if (debug) {
                    logger.warning("[HTTP] Event report failed: " + e.getMessage());
                }
                return false;
            }
        }, httpExecutor);
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
        return serverError.isActive();
    }

    private boolean isServerInErrorState() {
        if (!serverError.isActive()) return false;
        if (serverError.isExpired(System.currentTimeMillis())) {
            logger.info("[HTTP] Server error state expired, clearing");
            serverError.clear();
            return false;
        }
        return true;
    }

    private void enterServerErrorState(String reason) {
        serverError.enter(System.currentTimeMillis());
        logger.warning("[HTTP] Entering server error state: " + reason);
        scheduleReconnect();
    }

    /** Any completed HTTP round-trip (regardless of status code) proves the network path works. */
    private void recordNetworkSuccess() {
        consecutiveNetworkFailures.set(0);
    }

    /**
     * Counts connection-level failures (timeouts, refused connections). HTTP error statuses do NOT
     * go through here - they mean the backend is reachable and are handled per-endpoint. After
     * {@link #NETWORK_FAILURE_THRESHOLD} failures in a row the backend is treated as unreachable.
     */
    private void recordNetworkFailure(String context) {
        int failures = consecutiveNetworkFailures.incrementAndGet();
        if (debug) {
            logger.warning("[HTTP] Network failure #" + failures + " (" + context + ")");
        }
        if (failures >= NETWORK_FAILURE_THRESHOLD) {
            enterConnectionLostState(context);
        }
    }

    /**
     * Circuit breaker for a dead/unreachable backend. Marks the client disconnected (which makes
     * AICheck stop producing predicts instantly) and cancels the periodic senders, so an offline
     * API is not hammered with requests that each burn a full connect timeout. Recovery runs
     * through {@link #scheduleReconnect()} with exponential backoff; a successful connect restarts
     * the periodic tasks.
     */
    private void enterConnectionLostState(String context) {
        if (shuttingDown.get() || !connected.compareAndSet(true, false)) {
            return;
        }
        logger.warning("[HTTP] Backend unreachable (" + NETWORK_FAILURE_THRESHOLD
                + " consecutive network failures, last: " + context
                + ") - suspending requests until reconnect");
        heartbeat.cancel();
        reportStats.cancel();
        interserverEvent.cancel();
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

    public boolean isInStasisMode() {
        return inStasisMode.get();
    }

    private void handleRateLimitError() {
        limitExceeded = true;
        logger.warning("[HTTP] Request limit | Upgrade tariff - entering stasis mode");
        enterStasisMode();
    }

    private void enterStasisMode() {
        if (inStasisMode.compareAndSet(false, true)) {
            logger.warning("[HTTP] Entering stasis mode - stopping all requests");

            heartbeat.cancel();
            reportStats.cancel();
            interserverEvent.cancel();
            periodicCheck.cancel();

            // Start stasis check task (every 5 minutes)
            startStasisCheck();
        }
    }

    private void exitStasisMode() {
        if (inStasisMode.compareAndSet(true, false)) {
            logger.info("[HTTP] Exiting stasis mode - resuming normal operation");

            stasisCheck.cancel();

            // Restart normal tasks
            startHeartbeat();
            startReportStats();
            startInterserverEventPoll();
            startPeriodicCheck();
        }
    }

    private void startStasisCheck() {
        stasisCheck.reschedule(() -> SchedulerManager.getAdapter().runAsyncRepeating(() -> {
            if (shuttingDown.get()) return;
            checkApiAvailability();
        }, 100, STASIS_CHECK_INTERVAL_MS / 50));
    }

    private void checkApiAvailability() {
        if (debug) logger.info("[HTTP] Stasis check: testing API availability...");
        
        CompletableFuture.runAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/heartbeat";
                JsonObject json = payloads.base();
                payloads.addSession(json, sessionId);
                payloads.addOnline(json);

                RequestBody body = jsonBody(json);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-API-Key", apiKey)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    int code = response.code();
                    if (response.isSuccessful()) {
                        logger.info("[HTTP] API is available again - exiting stasis mode");
                        limitExceeded = false;
                        exitStasisMode();
                    } else if (code == 429) {
                        if (debug) logger.info("[HTTP] Still rate limited, remaining in stasis mode");
                    } else {
                        if (debug) logger.warning("[HTTP] Stasis check failed with code: " + code);
                    }
                }
            } catch (Exception e) {
                if (debug) logger.warning("[HTTP] Stasis check error: " + e.getMessage());
            }
        }, httpExecutor);
    }

    public CompletableFuture<Long> measureLatency() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = serverAddress + "/api/v1/heartbeat";
                JsonObject json = payloads.base();
                payloads.addSession(json, sessionId);
                payloads.addOnline(json);

                RequestBody body = jsonBody(json);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-API-Key", apiKey)
                        .build();

                long start = System.currentTimeMillis();
                try (Response response = httpClient.newCall(request).execute()) {
                    long end = System.currentTimeMillis();
                    if (response.isSuccessful()) {
                        return end - start;
                    }
                    return -1L;
                }
            } catch (Exception e) {
                return -1L;
            }
        }, httpExecutor);
    }
}
