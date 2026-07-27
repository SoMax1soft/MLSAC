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
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import wtf.mlsac.Main;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Queues MLS VISION events in memory and periodically POSTs them to
 * {@code /api/v1/vision/events}.
 *
 * <p>Deliberately simple compared to {@link wtf.mlsac.server.HttpAIClient}: this is best-effort
 * telemetry, not the detection pipeline, so a failed batch is just dropped rather than retried —
 * the next flush picks up wherever new events land. The queue is capped and in-memory only; on
 * overflow the oldest events are dropped. Nothing is ever written to disk, matching the
 * requirement that the plugin hold no local copy of anything it collects.
 */
public final class VisionEventSender {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_QUEUE_SIZE = 2000;
    private static final int MAX_EVENTS_PER_FLUSH = 500;
    private static final int FLUSH_INTERVAL_TICKS = 200; // 10s
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int WRITE_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 10;

    private final Main plugin;
    private final Logger logger;
    private final boolean debug;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final ArrayDeque<VisionEvent> queue = new ArrayDeque<>();

    private volatile ScheduledTask flushTask;
    private volatile String serverAddress;
    private volatile String apiKey;

    public VisionEventSender(Main plugin, boolean debug) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.debug = debug;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mlsac-vision-sender");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start(String serverAddress, String apiKey) {
        this.serverAddress = serverAddress;
        this.apiKey = apiKey;
        if (flushTask != null) {
            flushTask.cancel();
        }
        flushTask = SchedulerManager.getAdapter()
                .runAsyncRepeating(this::flush, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    public void updateCredentials(String serverAddress, String apiKey) {
        this.serverAddress = serverAddress;
        this.apiKey = apiKey;
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        // Best-effort final flush so events from the last few seconds before shutdown aren't lost,
        // then release the executor. Bounded wait — never block server shutdown indefinitely.
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void queue(VisionEvent event) {
        if (event == null) return;
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE_SIZE) {
                queue.pollFirst(); // drop oldest, keep collecting the most recent activity
            }
            queue.addLast(event);
        }
    }

    private void flush() {
        String address = serverAddress;
        String key = apiKey;
        if (address == null || address.isEmpty() || key == null || key.isEmpty()) return;

        List<VisionEvent> batch = drain();
        if (batch.isEmpty()) return;

        executor.execute(() -> send(address, key, batch));
    }

    private List<VisionEvent> drain() {
        List<VisionEvent> batch = new ArrayList<>();
        synchronized (queue) {
            while (!queue.isEmpty() && batch.size() < MAX_EVENTS_PER_FLUSH) {
                batch.add(queue.pollFirst());
            }
        }
        return batch;
    }

    private void send(String address, String apiKey, List<VisionEvent> batch) {
        try {
            JsonArray events = new JsonArray();
            for (VisionEvent event : batch) {
                events.add(toJson(event));
            }
            JsonObject body = new JsonObject();
            body.add("events", events);

            Request request = new Request.Builder()
                    .url(address + "/api/v1/vision/events")
                    .post(RequestBody.create(JSON, body.toString()))
                    .header("X-API-Key", apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() && debug) {
                    logger.warning("[VISION] Flush failed: HTTP " + response.code() + " (" + batch.size() + " events dropped)");
                }
            }
        } catch (Exception e) {
            if (debug) {
                logger.warning("[VISION] Flush error: " + e.getMessage() + " (" + batch.size() + " events dropped)");
            }
        }
    }

    private JsonObject toJson(VisionEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("type", event.type);
        json.addProperty("playerA", event.playerA);
        if (event.playerB != null) json.addProperty("playerB", event.playerB);
        if (event.valueTier != null) json.addProperty("valueTier", event.valueTier);
        if (event.amount != null) json.addProperty("amount", event.amount);
        if (event.material != null) json.addProperty("material", event.material);
        if (event.x != null) json.addProperty("x", event.x);
        if (event.y != null) json.addProperty("y", event.y);
        if (event.z != null) json.addProperty("z", event.z);
        if (event.world != null) json.addProperty("world", event.world);
        if (event.ip != null) json.addProperty("ip", event.ip);
        if (event.region != null) json.addProperty("region", event.region);
        if ("ban_state".equals(event.type)) {
            json.addProperty("banned", event.banned);
            json.addProperty("permanent", event.permanent);
            if (event.banReason != null) json.addProperty("banReason", event.banReason);
            if (event.removedBy != null) json.addProperty("removedBy", event.removedBy);
        }
        return json;
    }
}
