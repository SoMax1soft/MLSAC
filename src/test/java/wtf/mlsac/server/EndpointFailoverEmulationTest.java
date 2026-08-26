/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
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
import org.junit.jupiter.api.DisplayName;
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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end emulation of the reserve endpoint over real sockets: a backend that cannot be reached,
 * a backend that answers, and the client deciding between them.
 */
class EndpointFailoverEmulationTest {

    private HttpServer reserve;
    private ServerSocket deadPort;
    private String deadAddress;
    private String reserveAddress;
    private final List<String> reserveHits = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService testScheduler;
    private HttpAIClient client;

    @BeforeEach
    void setUp() throws Exception {
        testScheduler = Executors.newScheduledThreadPool(2);
        installScheduler(new TestSchedulerAdapter());

        // A port nobody listens on: connecting to it fails immediately and deterministically,
        // which is the "cannot reach the host" case without waiting out a real timeout.
        deadPort = new ServerSocket(0);
        deadAddress = "http://127.0.0.1:" + deadPort.getLocalPort();
        deadPort.close();

        reserve = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reserve.setExecutor(Executors.newCachedThreadPool());
        for (String path : new String[]{"/api/v1/init", "/api/v1/heartbeat", "/api/v1/online", "/api/v1/events"}) {
            reserve.createContext(path, exchange -> {
                reserveHits.add(exchange.getRequestURI().getPath());
                respond(exchange, 200, "{\"sessionId\":\"reserve-session\",\"success\":true}");
            });
        }
        reserve.start();
        reserveAddress = "http://127.0.0.1:" + reserve.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (client != null) {
                client.disconnect().get(10, TimeUnit.SECONDS);
            }
        } finally {
            reserve.stop(0);
            testScheduler.shutdownNow();
            SchedulerManager.reset();
        }
    }

    private HttpAIClient newClient(String primary, String reserveEndpoint) {
        return new HttpAIClient(mockPlugin(), primary, reserveEndpoint, "test-key",
                () -> 1, false, "test-server", "test-family", false, false, 0.75);
    }

    @Test
    @DisplayName("Unreachable primary falls over to the reserve and connects")
    void testFailoverOnUnreachablePrimary() throws Exception {
        client = newClient(deadAddress, reserveAddress);

        assertTrue(client.connect().get(20, TimeUnit.SECONDS), "the reserve must carry the connection");
        assertTrue(client.isConnected());
        assertEquals(reserveAddress, client.getServerAddress(), "traffic must now go to the reserve");
        assertTrue(reserveHits.contains("/api/v1/init"), "the reserve actually served the handshake");
    }

    @Test
    @DisplayName("With no reserve configured an unreachable primary simply fails")
    void testNoReserveMeansNoFailover() throws Exception {
        client = newClient(deadAddress, "");

        assertFalse(client.connect().get(20, TimeUnit.SECONDS));
        assertFalse(client.isConnected());
        assertTrue(reserveHits.isEmpty(), "nothing may reach the reserve when it is not configured");
    }

    @Test
    @DisplayName("A reachable primary is used and the reserve stays untouched")
    void testPrimaryWins() throws Exception {
        client = newClient(reserveAddress, deadAddress);

        assertTrue(client.connect().get(20, TimeUnit.SECONDS));
        assertEquals(reserveAddress, client.getServerAddress());
        assertEquals(1, reserveHits.stream().filter("/api/v1/init"::equals).count(),
                "exactly one handshake, no pointless second attempt");
    }

    @Test
    @DisplayName("A rejected API key does not fail over")
    void testAuthFailureDoesNotFailOver() throws Exception {
        HttpServer rejecting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rejecting.setExecutor(Executors.newCachedThreadPool());
        rejecting.createContext("/api/v1/init", exchange -> respond(exchange, 401, "{\"error\":\"bad key\"}"));
        rejecting.start();
        try {
            // The reserve would answer happily, and that is exactly the trap: a bad key is an
            // answer, so retrying it elsewhere only re-sends an invalid credential.
            client = newClient("http://127.0.0.1:" + rejecting.getAddress().getPort(), reserveAddress);

            assertFalse(client.connect().get(20, TimeUnit.SECONDS));
            assertTrue(reserveHits.isEmpty(), "an HTTP answer must not trigger failover");
        } finally {
            rejecting.stop(0);
        }
    }

    @Test
    @DisplayName("A 500 from the primary does not fail over either")
    void testServerErrorDoesNotFailOver() throws Exception {
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.setExecutor(Executors.newCachedThreadPool());
        broken.createContext("/api/v1/init", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
        broken.start();
        try {
            client = newClient("http://127.0.0.1:" + broken.getAddress().getPort(), reserveAddress);

            assertFalse(client.connect().get(20, TimeUnit.SECONDS));
            assertTrue(reserveHits.isEmpty(),
                    "hiding a broken backend behind the reserve would mask the real problem");
        } finally {
            broken.stop(0);
        }
    }

    @Test
    @DisplayName("Once the primary recovers, a reconnect goes back to it")
    void testReturnsToPrimaryAfterRecovery() throws Exception {
        // First connection: primary down, so the reserve takes over.
        client = newClient(deadAddress, reserveAddress);
        assertTrue(client.connect().get(20, TimeUnit.SECONDS));
        assertEquals(reserveAddress, client.getServerAddress());

        // The primary comes back on the very port that was refusing connections.
        HttpServer recovered = HttpServer.create(
                new InetSocketAddress("127.0.0.1", Integer.parseInt(deadAddress.substring(deadAddress.lastIndexOf(':') + 1))), 0);
        recovered.setExecutor(Executors.newCachedThreadPool());
        recovered.createContext("/api/v1/init", exchange ->
                respond(exchange, 200, "{\"sessionId\":\"primary-session\",\"success\":true}"));
        recovered.createContext("/api/v1/heartbeat", exchange -> respond(exchange, 200, "{\"success\":true}"));
        recovered.start();
        try {
            int reserveHitsBefore = reserveHits.size();

            assertTrue(client.connect().get(20, TimeUnit.SECONDS), "reconnect must succeed");

            assertEquals(deadAddress, client.getServerAddress(),
                    "the configured endpoint is tried first, so recovery brings the server back to it");
            assertEquals(reserveHitsBefore, reserveHits.size(),
                    "no need to touch the reserve once the primary answers");
        } finally {
            recovered.stop(0);
        }
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private Main mockPlugin() {
        Main plugin = mock(Main.class);
        Server server = mock(Server.class);
        when(server.getIp()).thenReturn("127.0.0.1");
        when(server.getPort()).thenReturn(25565);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EndpointFailoverEmulationTest"));
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

    private static void setStatic(String name, Object value) throws Exception {
        Field field = SchedulerManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Runs everything on a plain executor; the client only needs something that schedules. */
    private final class TestSchedulerAdapter implements SchedulerAdapter {
        @Override
        public ScheduledTask runSync(Runnable task) {
            return submit(task, 0L);
        }

        @Override
        public ScheduledTask runSyncDelayed(Runnable task, long delayTicks) {
            return submit(task, delayTicks);
        }

        @Override
        public ScheduledTask runSyncRepeating(Runnable task, long delayTicks, long periodTicks) {
            return repeating(task, delayTicks, periodTicks);
        }

        @Override
        public ScheduledTask runAsync(Runnable task) {
            return submit(task, 0L);
        }

        @Override
        public ScheduledTask runAsyncDelayed(Runnable task, long delayTicks) {
            return submit(task, delayTicks);
        }

        @Override
        public ScheduledTask runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
            return repeating(task, delayTicks, periodTicks);
        }

        @Override
        public ScheduledTask runEntitySync(Entity entity, Runnable task) {
            return submit(task, 0L);
        }

        @Override
        public ScheduledTask runEntitySyncDelayed(Entity entity, Runnable task, long delayTicks) {
            return submit(task, delayTicks);
        }

        @Override
        public ScheduledTask runEntitySyncRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks) {
            return repeating(task, delayTicks, periodTicks);
        }

        @Override
        public ScheduledTask runRegionSync(Location location, Runnable task) {
            return submit(task, 0L);
        }

        @Override
        public ScheduledTask runRegionSyncDelayed(Location location, Runnable task, long delayTicks) {
            return submit(task, delayTicks);
        }

        @Override
        public ScheduledTask runRegionSyncRepeating(Location location, Runnable task, long delayTicks, long periodTicks) {
            return repeating(task, delayTicks, periodTicks);
        }

        @Override
        public ServerType getServerType() {
            return ServerType.BUKKIT;
        }

        private ScheduledTask submit(Runnable task, long delayTicks) {
            return wrap(testScheduler.schedule(task, delayTicks * 50L, TimeUnit.MILLISECONDS));
        }

        private ScheduledTask repeating(Runnable task, long delayTicks, long periodTicks) {
            return wrap(testScheduler.scheduleAtFixedRate(task, delayTicks * 50L,
                    Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS));
        }

        private ScheduledTask wrap(java.util.concurrent.Future<?> future) {
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
}
