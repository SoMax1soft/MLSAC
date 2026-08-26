/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import wtf.mlsac.config.Config;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerAdapter;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.util.BypassCache;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link AntiEspManager} through the situations the engine exists for, with the
 * server and the packet library emulated around it.
 *
 * <p>The occlusion pass is invoked by hand rather than by a timer, so every assertion describes one
 * definite tick rather than a race. Packets the engine sends are captured, so hiding is checked at
 * the wire and not only in the bookkeeping.
 *
 * <p>The reveal burst is deliberately absent here. Building a spawn packet initialises PacketEvents'
 * entity and item registries, which need the netty layer a real server provides — outside one, the
 * registries cannot load at all. Reveal is therefore covered by {@code TrackedPlayerStateTest} at
 * unit level and has to be confirmed on a live server.
 */
class AntiEspEngineEmulationTest {

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<PacketEvents> packetEvents;

    private World world;
    private final Map<String, Material> blocks = new HashMap<>();
    private final Map<UUID, Player> online = new java.util.LinkedHashMap<>();
    /** Every packet the engine sent, as "<viewerName>:<PacketTypeName>:<entityId>". */
    private final List<String> sent = new ArrayList<>();

    private final java.util.concurrent.atomic.AtomicInteger blockLookups =
            new java.util.concurrent.atomic.AtomicInteger();

    private Config config;
    private AntiEspManager manager;
    private Runnable occlusionPass;

    @BeforeEach
    void setUp() throws Exception {
        BypassCache.clear();
        bukkit = Mockito.mockStatic(Bukkit.class);
        packetEvents = Mockito.mockStatic(PacketEvents.class);

        world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        // One Block per material, reused. Creating a mock per lookup costs microseconds and would
        // make the timing test measure Mockito instead of the occlusion engine.
        Map<Material, Block> blockPrototypes = new HashMap<>();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Material material = blocks.getOrDefault(
                    call.getArgument(0) + "," + call.getArgument(1) + "," + call.getArgument(2), Material.AIR);
            blockLookups.incrementAndGet();
            return blockPrototypes.computeIfAbsent(material, key -> {
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(key);
                return block;
            });
        });

        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> new ArrayList<>(online.values()));
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class)))
                .thenAnswer(call -> online.get((UUID) call.getArgument(0)));
        bukkit.when(Bukkit::getViewDistance).thenReturn(10);
        bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));

        PlayerManager playerManager = mock(PlayerManager.class);
        Mockito.doAnswer(call -> {
            Player viewer = call.getArgument(0);
            PacketWrapper<?> packet = call.getArgument(1);
            sent.add(viewer.getName() + ":" + describe(packet));
            return null;
        }).when(playerManager).sendPacketSilently(any(), any(PacketWrapper.class));

        ServerManager serverManager = mock(ServerManager.class);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_19_4);

        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, Mockito.RETURNS_DEEP_STUBS);
        when(api.isInitialized()).thenReturn(true);
        // Real settings, not a stub: PacketEvents loads its entity and item registries through
        // settings.getResourceProvider(), and a mocked provider hands back an empty stream, which
        // surfaces as an unreadable mappings file the moment a packet is built.
        when(api.getSettings()).thenReturn(new com.github.retrooper.packetevents.settings.PacketEventsSettings());
        when(api.getPlayerManager()).thenReturn(playerManager);
        when(api.getServerManager()).thenReturn(serverManager);
        when(api.getEventManager()).thenReturn(mock(EventManager.class));
        packetEvents.when(PacketEvents::getAPI).thenReturn(api);

        config = mock(Config.class);
        when(config.isAntiEspEnabled()).thenReturn(true);
        when(config.getAntiEspMaxDistance()).thenReturn(48.0);
        when(config.getAntiEspProximityDistance()).thenReturn(3.0);
        when(config.getAntiEspHideDelayTicks()).thenReturn(0);
        when(config.getAntiEspUpdateIntervalTicks()).thenReturn(1);
        when(config.getAntiEspBudgetMicros()).thenReturn(1_000_000);
        when(config.getAntiEspCacheTicks()).thenReturn(0);
        when(config.getAntiEspMoveThreshold()).thenReturn(0.3);
        when(config.getAntiEspRayCount()).thenReturn(5);
        when(config.isAntiEspRestoreMetadata()).thenReturn(true);

        installScheduler();

        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AntiEspEngineEmulationTest"));
        Server pluginServer = mock(Server.class);
        when(pluginServer.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(pluginServer);

        manager = new AntiEspManager(plugin, config);
    }

    @AfterEach
    void tearDown() {
        SchedulerManager.reset();
        packetEvents.close();
        bukkit.close();
        BypassCache.clear();
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────

    /** Captures the repeating pass instead of running it on a timer, so ticks are explicit. */
    private void installScheduler() throws Exception {
        SchedulerAdapter adapter = mock(SchedulerAdapter.class);
        when(adapter.runSyncRepeating(any(), Mockito.anyLong(), Mockito.anyLong()))
                .thenAnswer(call -> {
                    occlusionPass = call.getArgument(0);
                    return mock(ScheduledTask.class);
                });
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

    private static String describe(PacketWrapper<?> packet) {
        String type = packet.getClass().getSimpleName().replace("WrapperPlayServer", "");
        try {
            return type + ":" + packet.getClass().getMethod("getEntityId").invoke(packet);
        } catch (NoSuchMethodException expected) {
            // Destroy carries a list of ids rather than one.
        } catch (Exception ignored) {
            return type + ":?";
        }
        try {
            int[] ids = (int[]) packet.getClass().getMethod("getEntityIds").invoke(packet);
            return type + ":" + (ids.length == 1 ? String.valueOf(ids[0]) : java.util.Arrays.toString(ids));
        } catch (Exception ignored) {
            return type + ":?";
        }
    }

    private Player player(String name, int entityId, double x, double y, double z) {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.getEntityId()).thenReturn(entityId);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.isSneaking()).thenReturn(false);
        when(player.hasPermission(Mockito.anyString())).thenReturn(false);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        move(player, x, y, z);
        online.put(uuid, player);
        return player;
    }

    private void move(Player player, double x, double y, double z) {
        Location feet = new Location(world, x, y, z, 90f, 0f);
        Location eye = new Location(world, x, y + 1.62, z, 90f, 0f);
        when(player.getLocation()).thenReturn(feet);
        when(player.getEyeLocation()).thenReturn(eye);
    }

    private void wallBetween() {
        for (int y = 63; y <= 67; y++) {
            for (int z = -2; z <= 2; z++) {
                blocks.put("5," + y + "," + z, Material.STONE);
            }
        }
    }

    /**
     * Emulates the server tracker handing {@code target} to {@code viewer}.
     *
     * <p>Uses the pre-1.20.2 spawn packet, matching the emulated server version. The 1.20.2+ path
     * builds a generic spawn-entity packet, and that one needs PacketEvents' entity registry, which
     * cannot be initialised outside a running server — the choice between the two is covered at
     * unit level in {@code TrackedPlayerStateTest}.
     */
    private void serverSpawns(Player viewer, Player target) {
        ViewerVisibility visibility = manager.viewerState(viewer.getUniqueId());
        assertNotNull(visibility, "viewer must be registered before the tracker spawns anything");
        manager.handleSpawnObserved(visibility, target.getEntityId(), PacketType.Play.Server.SPAWN_PLAYER);
    }

    private void serverDestroys(Player viewer, Player target) {
        manager.handleDestroyObserved(manager.viewerState(viewer.getUniqueId()),
                new int[]{target.getEntityId()});
    }

    private void tick() {
        assertNotNull(occlusionPass, "the engine did not schedule its pass");
        occlusionPass.run();
    }

    private long countSent(String viewerName, String packetType, int entityId) {
        return sent.stream().filter(entry -> entry.equals(viewerName + ":" + packetType + ":" + entityId)).count();
    }

    // ── scenarios ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Open ground: nobody is hidden and no packet is sent")
    void testOpenGroundChangesNothing() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);

        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()));
        assertTrue(sent.isEmpty(), "a clear line of sight must cost nothing: " + sent);
    }

    @Test
    @DisplayName("A wall hides the target and the client is told to drop the entity")
    void testWallHidesTarget() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();

        tick();

        assertTrue(manager.isEntityHiddenFrom(viewer, target.getEntityId()));
        assertEquals(1, countSent("Viewer", "DestroyEntities", 2), "sent: " + sent);
    }


    @Test
    @DisplayName("Hiding is not repeated while the target stays behind the wall")
    void testHideIsNotSpammed() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();

        for (int i = 0; i < 10; i++) {
            tick();
        }

        assertEquals(1, countSent("Viewer", "DestroyEntities", 2),
                "one destroy, not one per pass: " + sent);
    }

    @Test
    @DisplayName("The hide delay holds the player visible for its duration")
    void testHideDelay() {
        when(config.getAntiEspHideDelayTicks()).thenReturn(20); // one second
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();

        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()),
                "a player who just stepped behind cover must not blink out instantly");
        assertTrue(sent.isEmpty(), "sent: " + sent);
    }

    @Test
    @DisplayName("Close range wins over a wall")
    void testProximityKeepsTargetVisible() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 2.0, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        blocks.put("1,64,0", Material.STONE);
        blocks.put("1,65,0", Material.STONE);

        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()),
                "hiding someone standing next to you would be worse than the ESP");
    }

    @Test
    @DisplayName("Past max distance the engine leaves the player alone")
    void testBeyondMaxDistance() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 200, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();

        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()));
    }

    @Test
    @DisplayName("The tracker dropping the entity clears the hidden flag")
    void testServerDestroyResetsState() {
        // This is the bug that made players invisible for good: the flag stayed set, so the fresh
        // spawn the tracker sent on their way back was cancelled and nothing ever restored it.
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();
        tick();
        assertTrue(manager.isEntityHiddenFrom(viewer, target.getEntityId()));

        serverDestroys(viewer, target);

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()),
                "the client no longer holds the entity, so nothing is being hidden");

        // Walking back into range: the tracker's own spawn must go through untouched.
        blocks.clear();
        serverSpawns(viewer, target);
        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()));
    }


    @Test
    @DisplayName("Quitting clears the viewer and every reference to them")
    void testQuitCleansUpBothDirections() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        serverSpawns(target, viewer);
        wallBetween();
        tick();

        online.remove(target.getUniqueId());
        when(target.isOnline()).thenReturn(false);
        manager.onPlayerQuit(new PlayerQuitEvent(target, "quit"));

        assertNull(manager.viewerState(target.getUniqueId()), "the leaver's own state is gone");
        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()),
                "and nobody is left hiding a player who is not here");
    }

    @Test
    @DisplayName("The bypass permission takes a player out of the engine entirely")
    void testBypassPermission() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        when(target.hasPermission("mlsac.bypass")).thenReturn(true);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();

        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()),
                "a player with bypass is never hidden from anyone");
    }

    @Test
    @DisplayName("A bypassing viewer keeps seeing everyone")
    void testBypassingViewerSeesEverything() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        when(viewer.hasPermission("mlsac.antiesp.bypass")).thenReturn(true);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();

        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()));
    }

    @Test
    @DisplayName("Each viewer is decided on its own")
    void testViewersAreIndependent() {
        Player behindWall = player("BehindWall", 1, 0, 64, 0);
        Player inTheOpen = player("InTheOpen", 3, 15, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(behindWall, target);
        serverSpawns(inTheOpen, target);
        wallBetween();

        tick();

        assertTrue(manager.isEntityHiddenFrom(behindWall, target.getEntityId()),
                "the wall is between this viewer and the target");
        assertFalse(manager.isEntityHiddenFrom(inTheOpen, target.getEntityId()),
                "this one has a clear view and must not be affected");
    }

    @Test
    @DisplayName("Sound muting follows the hidden player's position")
    void testHiddenPlayerSoundsAreMuted() {
        when(config.isAntiEspHideSounds()).thenReturn(true);
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();
        tick();

        assertTrue(manager.isSoundFromHiddenPlayer(viewer, new Location(world, 20, 64, 0)),
                "a footstep where the hidden player stands must not be forwarded");
        assertFalse(manager.isSoundFromHiddenPlayer(viewer, new Location(world, 20, 64, 40)),
                "an unrelated sound must still get through");
    }

    @Test
    @DisplayName("Switching worlds forgets the pair instead of leaving it hidden")
    void testWorldChangeDropsState() {
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        serverSpawns(viewer, target);
        wallBetween();
        tick();
        assertTrue(manager.isEntityHiddenFrom(viewer, target.getEntityId()));

        World nether = mock(World.class);
        when(target.getWorld()).thenReturn(nether);
        tick();

        assertFalse(manager.isEntityHiddenFrom(viewer, target.getEntityId()),
                "the client dropped its entity list; a stale flag would swallow the next spawn");
    }


    @Test
    @DisplayName("Untracked pairs are never raytraced")
    void testUntrackedPairsAreSkipped() {
        // No spawn was observed, so the client does not have the entity and there is nothing to
        // hide. Doing the work anyway is what made the old engine scale as the square of the
        // player count.
        manager.start();
        Player viewer = player("Viewer", 1, 0, 64, 0);
        player("Target", 2, 20, 64, 0);
        manager.onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(viewer, ""));
        wallBetween();

        tick();

        assertTrue(sent.isEmpty(), "sent: " + sent);
        Mockito.verify(world, Mockito.never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("A crowded server does a bounded amount of raytracing per pass")
    void testCrowdedServerWorkIsBounded() {
        // 60 players in mutual view: 3540 ordered pairs, the shape that used to melt the tick.
        // Counting block lookups rather than milliseconds keeps this about the engine — wall time
        // here would mostly measure the mocking framework.
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            players.add(player("P" + i, 100 + i, (i % 10) * 4, 64, (i / 10) * 4));
        }
        manager.start();
        for (Player viewer : players) {
            for (Player target : players) {
                if (viewer != target) {
                    serverSpawns(viewer, target);
                }
            }
        }
        wallBetween();

        blockLookups.set(0);
        tick();
        int lookups = blockLookups.get();

        // Every pair is inside max_distance here, so this is the worst case the engine can face at
        // this player count. A quadratic full-ray sweep would run into the millions.
        assertTrue(lookups < 400_000,
                "one pass cost " + lookups + " block lookups, far above what the pair count justifies");
    }

    @Test
    @DisplayName("Standing still costs nothing: the cached verdict is reused")
    void testCacheSkipsRepeatedRaytracing() {
        when(config.getAntiEspCacheTicks()).thenReturn(10);
        Player viewer = player("Viewer", 1, 0, 64, 0);
        player("Target", 2, 20, 64, 0);
        manager.start();
        wallBetween();
        tick();

        blockLookups.set(0);
        tick();
        tick();

        assertEquals(0, blockLookups.get(),
                "nobody moved, so the previous verdict still holds and no ray should be cast");
    }

    @Test
    @DisplayName("Moving far enough invalidates the cached verdict")
    void testMovementForcesRecheck() {
        when(config.getAntiEspCacheTicks()).thenReturn(10);
        Player viewer = player("Viewer", 1, 0, 64, 0);
        Player target = player("Target", 2, 20, 64, 0);
        manager.start();
        wallBetween();
        tick();

        blockLookups.set(0);
        move(target, 20, 64, 6); // well past move_threshold
        tick();

        assertTrue(blockLookups.get() > 0, "a player who moved must be re-checked");
    }
}
