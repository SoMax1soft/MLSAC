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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wtf.mlsac.Main;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerAdapter;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpAIClientNetworkTest {
    private static final int MAX_IN_FLIGHT_PREDICTS = 8;
    private static final int FAILURE_THRESHOLD = 3;

    private enum BackendMode { NORMAL, DROP_CONNECTION }

    private enum Outcome { SUCCESS, ERROR, DROPPED }

    private final AtomicReference<BackendMode> predictMode = new AtomicReference<>(BackendMode.NORMAL);
    private final AtomicInteger predictRequestsServed = new AtomicInteger();
    private final AtomicInteger legacyEndpointHits = new AtomicInteger();
    private final AtomicLong predictDelayMs = new AtomicLong(0);

    private HttpServer backend;
    private ScheduledExecutorService testScheduler;
    private HttpAIClient client;

    @BeforeEach
    void setUp() throws Exception {
        predictMode.set(BackendMode.NORMAL);
        predictRequestsServed.set(0);
        legacyEndpointHits.set(0);
        predictDelayMs.set(0);

        testScheduler = Executors.newScheduledThreadPool(2);
        installScheduler(new TestSchedulerAdapter());

        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.setExecutor(Executors.newCachedThreadPool());
        backend.createContext("/api/v1/init", ex -> respondJson(ex, "{\"sessionId\":\"test-session\"}"));
        backend.createContext("/api/v1/heartbeat", ex -> respondJson(ex, "{\"success\":true}"));
        backend.createContext("/api/v1/online", ex -> respondJson(ex, "{\"success\":true}"));
        backend.createContext("/api/v1/events", ex -> respondJson(ex, "{\"success\":true}"));
        backend.createContext("/api/v1/predict-stream", this::handlePredict);
        backend.createContext("/api/v1/predict", ex -> {
            legacyEndpointHits.incrementAndGet();
            respondJson(ex, "{\"probability\":0.0,\"model\":\"legacy\",\"error\":\"\",\"success\":true}");
        });
        backend.start();

        client = new HttpAIClient(mockPlugin(), "http://127.0.0.1:" + backend.getAddress().getPort(),
                "test-key", () -> 3, false, "test-server", "test-family", false, false, 0.75);
        assertTrue(client.connect().get(10, TimeUnit.SECONDS), "initial connect must succeed");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            assertEquals(0, legacyEndpointHits.get(),
                    "the stream-only client must never call the legacy /api/v1/predict endpoint");
            client.disconnect().get(10, TimeUnit.SECONDS);
        } finally {
            backend.stop(0);
            testScheduler.shutdownNow();
            SchedulerManager.reset();
        }
    }

    // ── Scenario 1: backend is down for good ─────────────────────────────────────────────

    @Test
    void constantNetworkFailuresOpenBreakerAndStopTraffic() throws Exception {
        predictMode.set(BackendMode.DROP_CONNECTION);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertEquals(Outcome.ERROR, callPredict(), "predict #" + (i + 1) + " must fail on a dead backend");
        }

        assertFalse(client.isConnected(), "breaker must mark the client disconnected after "
                + FAILURE_THRESHOLD + " consecutive network failures");

        int servedBeforeBreaker = predictRequestsServed.get();
        for (int i = 0; i < 5; i++) {
            assertEquals(Outcome.ERROR, callPredict(), "predict after breaker opened must fail fast");
        }
        assertEquals(servedBeforeBreaker, predictRequestsServed.get(),
                "no request may reach the network once the breaker is open");
    }

    // ── Scenario 2: intermittent failures ────────────────────────────────────────────────

    @Test
    void intermittentFailuresDoNotOpenBreaker() throws Exception {
        for (int cycle = 0; cycle < 4; cycle++) {
            predictMode.set(BackendMode.DROP_CONNECTION);
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                assertEquals(Outcome.ERROR, callPredict());
            }
            predictMode.set(BackendMode.NORMAL);
            assertEquals(Outcome.SUCCESS, callPredict(), "healthy request in cycle " + cycle + " must succeed");
            assertTrue(client.isConnected(), "breaker must stay closed through intermittent failures");
        }
    }

    // ── Scenario 3: healthy backend, predict burst ───────────────────────────────────────

    @Test
    void predictBurstIsCappedWithoutErrors() throws Exception {
        predictDelayMs.set(500);
        int burst = 50;

        CountDownLatch done = new CountDownLatch(burst);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();

        for (int i = 0; i < burst; i++) {
            AtomicBoolean gotResponse = new AtomicBoolean(false);
            client.predict(new byte[] {1, 2, 3}, "00000000-0000-0000-0000-000000000000", "LoadTest")
                    .subscribe(response -> {
                        gotResponse.set(true);
                        successes.incrementAndGet();
                    }, error -> {
                        errors.incrementAndGet();
                        done.countDown();
                    }, () -> {
                        if (!gotResponse.get()) {
                            dropped.incrementAndGet();
                        }
                        done.countDown();
                    });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "all burst predicts must terminate");
        assertEquals(0, errors.get(), "a healthy backend under load must produce zero errors");
        assertEquals(burst, successes.get() + dropped.get(), "every predict must either respond or be dropped");
        assertTrue(successes.get() >= MAX_IN_FLIGHT_PREDICTS,
                "at least the in-flight window must be served, got " + successes.get());
        assertTrue(successes.get() <= MAX_IN_FLIGHT_PREDICTS * 2,
                "the burst must be capped near the in-flight limit, got " + successes.get());
        assertEquals(successes.get(), predictRequestsServed.get(),
                "dropped predicts must never reach the network");
        assertTrue(client.isConnected(), "load alone must never open the breaker");

        predictDelayMs.set(0);
        assertEquals(Outcome.SUCCESS, callPredict(), "client must recover to normal operation after the burst");
    }

    // ── Test backend ─────────────────────────────────────────────────────────────────────

    private void handlePredict(HttpExchange exchange) throws IOException {
        long delay = predictDelayMs.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (predictMode.get() == BackendMode.DROP_CONNECTION) {
            exchange.close();
            return;
        }
        predictRequestsServed.incrementAndGet();
        respondJson(exchange,
                "{\"type\":\"prediction\",\"probability\":0.42,\"model\":\"fast\",\"error\":\"\",\"success\":true}\n"
                        + "{\"type\":\"done\",\"success\":true,\"models\":1}\n");
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────

    private Outcome callPredict() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AIResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.predict(new byte[] {1, 2, 3}, "00000000-0000-0000-0000-000000000000", "TestPlayer")
                .subscribe(response::set, throwable -> {
                    error.set(throwable);
                    latch.countDown();
                }, latch::countDown);
        assertTrue(latch.await(15, TimeUnit.SECONDS), "predict must terminate");
        if (response.get() != null) {
            return Outcome.SUCCESS;
        }
        return error.get() != null ? Outcome.ERROR : Outcome.DROPPED;
    }

    private Main mockPlugin() {
        Main plugin = mock(Main.class);
        Server server = mock(Server.class);
        when(server.getIp()).thenReturn("127.0.0.1");
        when(server.getPort()).thenReturn(25565);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HttpAIClientNetworkTest"));
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("MLSAC", "test", "wtf.mlsac.Main"));
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    private static void installScheduler(SchedulerAdapter adapter) throws Exception {
        SchedulerManager.reset();
        setStatic("adapter", adapter);
        setStatic("serverType", ServerType.BUKKIT);
        setStatic("initialized", true);
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = SchedulerManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private final class TestSchedulerAdapter implements SchedulerAdapter {
        @Override
        public ScheduledTask runSync(Runnable task) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runSyncDelayed(Runnable task, long delayTicks) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runSyncRepeating(Runnable task, long delayTicks, long periodTicks) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runAsync(Runnable task) {
            return wrap(testScheduler.submit(task));
        }

        @Override
        public ScheduledTask runAsyncDelayed(Runnable task, long delayTicks) {
            return wrap(testScheduler.schedule(task, delayTicks * 50, TimeUnit.MILLISECONDS));
        }

        @Override
        public ScheduledTask runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runEntitySync(Entity entity, Runnable task) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runEntitySyncDelayed(Entity entity, Runnable task, long delayTicks) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runEntitySyncRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runRegionSync(Location location, Runnable task) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runRegionSyncDelayed(Location location, Runnable task, long delayTicks) {
            return NOOP_TASK;
        }

        @Override
        public ScheduledTask runRegionSyncRepeating(Location location, Runnable task, long delayTicks, long periodTicks) {
            return NOOP_TASK;
        }

        @Override
        public ServerType getServerType() {
            return ServerType.BUKKIT;
        }

        private ScheduledTask wrap(Future<?> future) {
            return new ScheduledTask() {
                @Override
                public void cancel() {
                    future.cancel(false);
                }

                @Override
                public boolean isCancelled() {
                    return future.isCancelled();
                }

                @Override
                public boolean isRunning() {
                    return !future.isDone();
                }
            };
        }
    }

    private static final ScheduledTask NOOP_TASK = new ScheduledTask() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    };
}
