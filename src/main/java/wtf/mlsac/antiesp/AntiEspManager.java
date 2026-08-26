/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import wtf.mlsac.antiesp.ViewerVisibility.TargetLink;
import wtf.mlsac.config.Config;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.util.BypassCache;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-ESP engine: decides which players a viewer's client is allowed to know about, and keeps the
 * client's entity list in sync with that decision.
 *
 * <h2>How hiding works</h2>
 * A player behind a wall is removed from the viewer's client with a destroy packet, and every
 * further packet about them is dropped by {@link AntiEspPacketListener}. Nothing is changed
 * server-side: the target is never untracked, never hidden through the Bukkit visibility API, and
 * every other plugin still sees a perfectly normal player. When the wall stops blocking the view,
 * the spawn burst is replayed from {@link TrackedPlayerState}, which mirrors the server's own
 * metadata, equipment and effect packets — so they come back with their name, skin layers, armour,
 * pose and potion invisibility intact instead of as a bare default entity.
 *
 * <h2>Cost control</h2>
 * Work is bounded on three axes. Only pairs the server actually tracks are considered, so the
 * engine never raytraces towards players the client could not see anyway. Results are cached and
 * only recomputed once an endpoint has moved or the entry has aged out. And each pass runs against
 * a wall-clock budget, resuming at the next viewer on the following pass, so a full server can
 * never turn occlusion checks into a tick spike.
 */
public class AntiEspManager implements Listener {

    /** Folia's per-region ownership check, absent on regular servers. */
    private static final Method OWNED_BY_CURRENT_REGION = resolveRegionCheck();

    private final JavaPlugin plugin;
    private final Config config;

    private final Map<UUID, ViewerVisibility> viewers = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> playersByEntityId = new ConcurrentHashMap<>();
    private final Map<Integer, TrackedPlayerState> trackedPlayers = new ConcurrentHashMap<>();

    private final ThreadLocal<double[]> sampleBuffer =
            ThreadLocal.withInitial(() -> new double[OcclusionChecker.MAX_SAMPLES * 3]);

    private final OcclusionChecker.ChunkAccessGuard chunkGuard = this::canReadChunk;

    private AntiEspPacketListener packetListener;
    private ScheduledTask globalTask;
    private final Map<UUID, ScheduledTask> viewerTasks = new ConcurrentHashMap<>();

    private volatile boolean running;
    private final boolean folia;

    private int tickCounter;
    private int rotationCursor;

    public AntiEspManager(JavaPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.folia = SchedulerManager.isInitialized() && SchedulerManager.getServerType() == ServerType.FOLIA;
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        if (!config.isAntiEspEnabled()) {
            return;
        }
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
            plugin.getLogger().warning("[Anti-ESP] PacketEvents API not initialized! Anti-ESP disabled.");
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        this.packetListener = new AntiEspPacketListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        this.running = true;
        bootstrapOnlinePlayers();

        int interval = Math.max(1, config.getAntiEspUpdateIntervalTicks());
        if (folia) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                startViewerTask(player, interval);
            }
        } else {
            this.globalTask = SchedulerManager.getAdapter()
                    .runSyncRepeating(this::runGlobalPass, interval, interval);
        }

        plugin.getLogger().info("[Anti-ESP] Occlusion engine started (interval: " + interval
                + " ticks, budget: " + config.getAntiEspBudgetMicros() + "us/pass, rays: "
                + config.getAntiEspRayCount() + ").");
    }

    public void stop() {
        this.running = false;

        if (globalTask != null) {
            globalTask.cancel();
            globalTask = null;
        }
        for (ScheduledTask task : viewerTasks.values()) {
            task.cancel();
        }
        viewerTasks.clear();

        if (packetListener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (Exception ignored) {
            }
            packetListener = null;
        }

        revealEverything();

        // A reload builds a fresh manager, so the old instance's handlers have to go with it.
        HandlerList.unregisterAll(this);

        viewers.clear();
        trackedPlayers.clear();
        playersByEntityId.clear();
    }

    /**
     * Seeds the pair table for players who were already online — after {@code /mlsac reload} their
     * spawn packets are long gone, so without this the engine would never hide anyone until each
     * player re-entered someone's tracking range.
     */
    private void bootstrapOnlinePlayers() {
        double trackingRange = Math.max(config.getAntiEspMaxDistance(), Bukkit.getViewDistance() * 16.0);
        double trackingRangeSq = trackingRange * trackingRange;

        for (Player player : Bukkit.getOnlinePlayers()) {
            playersByEntityId.put(player.getEntityId(), player.getUniqueId());
            viewers.computeIfAbsent(player.getUniqueId(), ViewerVisibility::new);
            trackedPlayers.computeIfAbsent(player.getEntityId(), id -> new TrackedPlayerState());
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            ViewerVisibility visibility = viewers.get(viewer.getUniqueId());
            if (visibility == null) {
                continue;
            }
            Location viewerLoc = viewer.getLocation();
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.equals(viewer) || !target.getWorld().equals(viewer.getWorld())) {
                    continue;
                }
                if (viewerLoc.distanceSquared(target.getLocation()) > trackingRangeSq) {
                    continue;
                }
                TargetLink link = visibility.link(target.getEntityId());
                link.serverTracked = true;
                link.clientHas = true;
            }
        }
    }

    private void startViewerTask(Player player, int interval) {
        if (viewerTasks.containsKey(player.getUniqueId())) {
            return;
        }
        try {
            ScheduledTask task = SchedulerManager.getAdapter().runEntitySyncRepeating(player,
                    () -> runViewerPass(player), interval, interval);
            viewerTasks.put(player.getUniqueId(), task);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- occlusion passes

    /** One pass over every viewer, resuming where the previous pass ran out of budget. */
    private void runGlobalPass() {
        if (!running || !config.isAntiEspEnabled()) {
            return;
        }
        tickCounter += Math.max(1, config.getAntiEspUpdateIntervalTicks());

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int size = online.size();
        if (size < 2) {
            return;
        }
        if (rotationCursor >= size) {
            rotationCursor = 0;
        }

        long deadline = System.nanoTime() + config.getAntiEspBudgetMicros() * 1_000L;
        long now = System.currentTimeMillis();

        for (int processed = 0; processed < size; processed++) {
            int index = (rotationCursor + processed) % size;
            updateViewer(online.get(index), now, tickCounter);

            if (processed + 1 < size && System.nanoTime() > deadline) {
                rotationCursor = (index + 1) % size;
                return;
            }
        }
        rotationCursor = 0;
    }

    /** Folia variant: each viewer is updated on the thread that owns their region. */
    private void runViewerPass(Player viewer) {
        if (!running || !config.isAntiEspEnabled() || !viewer.isOnline()) {
            return;
        }
        tickCounter += Math.max(1, config.getAntiEspUpdateIntervalTicks());
        updateViewer(viewer, System.currentTimeMillis(), tickCounter);
    }

    private void updateViewer(Player viewer, long now, int tick) {
        ViewerVisibility visibility = viewers.get(viewer.getUniqueId());
        if (visibility == null || !viewer.isOnline()) {
            return;
        }

        Map<Integer, TargetLink> links = visibility.getLinks();
        if (links.isEmpty()) {
            visibility.setHiddenCount(0);
            return;
        }

        visibility.setBypassing(BypassCache.isAntiEspExempt(viewer));

        World world = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();

        double yawRad = Math.toRadians(eye.getYaw());
        double pitchRad = Math.toRadians(eye.getPitch());
        double cosPitch = Math.cos(pitchRad);
        double lookX = -cosPitch * Math.sin(yawRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = cosPitch * Math.cos(yawRad);

        boolean verbose = config.isDebug() || config.isAntiEspVerboseDebug();
        int hiddenCount = 0;
        List<double[]> hiddenSpots = null;

        for (Map.Entry<Integer, TargetLink> entry : links.entrySet()) {
            int targetId = entry.getKey();
            TargetLink link = entry.getValue();

            if (!link.serverTracked) {
                continue;
            }

            Player target = getPlayerByEntityId(targetId);
            if (target == null || !target.isOnline() || !world.equals(target.getWorld())) {
                // The client already dropped this entity (quit, world change). Reset the pair
                // instead of leaving a stale "hidden" flag that would swallow a later respawn.
                // The entry itself stays: removing it here could race with a spawn packet being
                // handled on a netty thread and lose the link the client actually has.
                link.hidden = false;
                link.clientHas = false;
                link.serverTracked = false;
                continue;
            }

            boolean visible = visibility.isBypassing()
                    || BypassCache.isAntiEspExempt(target)
                    || isVisible(world, link, eyeX, eyeY, eyeZ, lookX, lookY, lookZ, target, tick);

            applyDecision(viewer, target, link, visible, now, tick, verbose);
            resyncEquipmentIfDue(viewer, target, link, tick);

            if (link.hidden) {
                hiddenCount++;
                if (config.isAntiEspHideSounds()) {
                    Location loc = target.getLocation();
                    if (hiddenSpots == null) {
                        hiddenSpots = new ArrayList<>(4);
                    }
                    hiddenSpots.add(new double[]{loc.getX(), loc.getY(), loc.getZ()});
                }
            }
        }

        visibility.setHiddenCount(hiddenCount);
        visibility.setHiddenPositions(flatten(hiddenSpots));
    }

    private static double[] flatten(List<double[]> spots) {
        if (spots == null || spots.isEmpty()) {
            return new double[0];
        }
        double[] out = new double[spots.size() * 3];
        int i = 0;
        for (double[] spot : spots) {
            out[i++] = spot[0];
            out[i++] = spot[1];
            out[i++] = spot[2];
        }
        return out;
    }

    private boolean isVisible(World world, TargetLink link, double eyeX, double eyeY, double eyeZ,
                              double lookX, double lookY, double lookZ, Player target, int tick) {
        Location targetLoc = target.getLocation();
        double tx = targetLoc.getX();
        double ty = targetLoc.getY();
        double tz = targetLoc.getZ();

        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        double maxDistance = config.getAntiEspMaxDistance();
        if (distSq > maxDistance * maxDistance) {
            // Beyond the engine's range nothing is hidden, so a player who walks out of it is
            // revealed instead of being frozen in whatever state they were last checked in.
            return true;
        }

        double proximity = config.getAntiEspProximityDistance();
        if (distSq <= proximity * proximity) {
            return true;
        }

        if (config.isAntiEspFovEnabled()) {
            double cosHalfFov = Math.cos(Math.toRadians(config.getAntiEspFovDegrees() / 2.0));
            if (!OcclusionChecker.isInFov(lookX, lookY, lookZ, dx, dy, dz, cosHalfFov)) {
                return false;
            }
        }

        if (tick - link.lastCheckTick < config.getAntiEspCacheTicks()
                && link.stillWithin(eyeX, eyeY, eyeZ, tx, ty, tz, config.getAntiEspMoveThreshold())) {
            return link.lastVisible;
        }

        double[] samples = sampleBuffer.get();
        int count = OcclusionChecker.buildSamples(tx, ty, tz, target.isSneaking(), lookX, lookZ,
                config.getAntiEspRayCount(), samples);
        int clearSample = OcclusionChecker.firstClearSample(world, eyeX, eyeY, eyeZ, samples, count,
                link.preferredSample, chunkGuard);

        boolean visible = clearSample >= 0;
        if (visible) {
            link.preferredSample = clearSample;
        }
        link.lastVisible = visible;
        link.lastCheckTick = tick;
        link.rememberPositions(eyeX, eyeY, eyeZ, tx, ty, tz);
        return visible;
    }

    /**
     * Repeats the gear once shortly after a reveal. See {@link TargetLink#equipmentResyncTick}.
     */
    private void resyncEquipmentIfDue(Player viewer, Player target, TargetLink link, int tick) {
        if (link.equipmentResyncTick == 0 || tick < link.equipmentResyncTick) {
            return;
        }
        link.equipmentResyncTick = 0;
        if (!link.hidden && link.clientHas) {
            sendEquipment(viewer, target, trackedPlayers.get(target.getEntityId()));
        }
    }

    private void applyDecision(Player viewer, Player target, TargetLink link, boolean visible,
                               long now, int tick, boolean verbose) {
        if (visible) {
            link.occludedSinceMs = 0L;
            if (!link.hidden) {
                return;
            }
            link.hidden = false;
            if (link.serverTracked && !link.clientHas && !sendReveal(viewer, target, link, tick)) {
                // The spawn burst could not be built. Stay hidden rather than leaving the client
                // without an entity it will never be sent again, and retry on the next pass.
                link.hidden = true;
                return;
            }
            if (verbose) {
                plugin.getLogger().info("[Anti-ESP-Debug] REVEAL " + target.getName() + " -> " + viewer.getName());
            }
            return;
        }

        if (link.hidden) {
            return;
        }
        if (link.occludedSinceMs == 0L) {
            link.occludedSinceMs = now;
        }
        long hideDelayMs = Math.max(0, config.getAntiEspHideDelayTicks()) * 50L;
        if (now - link.occludedSinceMs < hideDelayMs) {
            return;
        }

        link.hidden = true;
        link.equipmentResyncTick = 0;
        if (link.clientHas) {
            sendSilently(viewer, new WrapperPlayServerDestroyEntities(target.getEntityId()));
            link.clientHas = false;
        }
        if (verbose) {
            plugin.getLogger().info("[Anti-ESP-Debug] HIDE " + target.getName() + " -> " + viewer.getName());
        }
    }

    // ---------------------------------------------------------------- reveal

    /**
     * Replays the spawn burst the server would have sent, rebuilt from the mirrored entity state.
     *
     * <p>The packet used to spawn a player changed in 1.20.2 (dedicated spawn-player packet before,
     * generic spawn-entity after). Rather than deriving it, the type the server itself used for
     * this player is reused whenever it has been observed, with the server version only as a
     * fallback — that is what makes the reveal behave identically across versions.
     *
     * @return false if the burst could not be built, leaving the client without the entity
     */
    private boolean sendReveal(Player viewer, Player target, TargetLink link, int revealTick) {
        Location loc = target.getLocation();
        int entityId = target.getEntityId();
        TrackedPlayerState state = trackedPlayers.get(entityId);

        PacketWrapper<?> spawn = buildSpawn(state, entityId, target.getUniqueId(), loc);
        if (spawn == null) {
            return false;
        }
        sendSilently(viewer, spawn);

        if (state != null) {
            if (config.isAntiEspRestoreMetadata()) {
                List<EntityData<?>> metadata = state.snapshotMetadata();
                if (!metadata.isEmpty()) {
                    sendSilently(viewer, new WrapperPlayServerEntityMetadata(entityId, metadata));
                }
            }

            List<TrackedPlayerState.ActiveEffect> effects = state.snapshotEffects();
            if (effects != null) {
                for (TrackedPlayerState.ActiveEffect effect : effects) {
                    sendSilently(viewer, new WrapperPlayServerEntityEffect(entityId, effect.type,
                            effect.amplifier, effect.durationTicks, effect.flags));
                }
            }

            int vehicleId = state.getVehicleId();
            int[] passengers = state.getVehiclePassengers();
            if (vehicleId != -1 && passengers != null) {
                sendSilently(viewer, new WrapperPlayServerSetPassengers(vehicleId, passengers));
            }
        }

        sendEquipment(viewer, target, state);

        sendSilently(viewer, new WrapperPlayServerEntityHeadLook(entityId, loc.getYaw()));
        link.clientHas = true;
        link.equipmentResyncTick = revealTick + 1;
        return true;
    }

    /**
     * Sends the target's visible gear.
     *
     * <p>The mirrored packets the server itself produced are the primary source: they are already
     * in protocol form, so nothing has to be converted and nothing can be lost in translation.
     * Reading the live inventory is only a cold-start fallback, for a player nobody has had hidden
     * yet — that conversion is exactly what made armour show up late instead of on reveal.
     */
    private void sendEquipment(Player viewer, Player target, TrackedPlayerState state) {
        List<Equipment> equipment = state == null ? null
                : state.snapshotEquipment(PacketEvents.getAPI().getServerManager().getVersion());
        if (equipment == null || equipment.isEmpty()) {
            equipment = readEquipment(target);
        }
        if (!equipment.isEmpty()) {
            sendSilently(viewer, new WrapperPlayServerEntityEquipment(target.getEntityId(), equipment));
        }
    }

    /** Cold-start fallback for {@link #sendEquipment}, in ascending protocol slot order. */
    private List<Equipment> readEquipment(Player target) {
        List<Equipment> equipment = new ArrayList<>(6);
        PlayerInventory inventory = target.getInventory();

        addEquipment(equipment, target, EquipmentSlot.MAIN_HAND, inventory.getItemInMainHand());
        addEquipment(equipment, target, EquipmentSlot.OFF_HAND, inventory.getItemInOffHand());
        addEquipment(equipment, target, EquipmentSlot.BOOTS, inventory.getBoots());
        addEquipment(equipment, target, EquipmentSlot.LEGGINGS, inventory.getLeggings());
        addEquipment(equipment, target, EquipmentSlot.CHEST_PLATE, inventory.getChestplate());
        addEquipment(equipment, target, EquipmentSlot.HELMET, inventory.getHelmet());
        return equipment;
    }

    private void addEquipment(List<Equipment> out, Player target, EquipmentSlot slot, ItemStack item) {
        // Empty slots are skipped: a freshly spawned entity starts bare, so there is nothing to clear.
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        try {
            out.add(new Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(item)));
        } catch (Throwable e) {
            // Worth a warning rather than a debug line: a silent miss here is invisible armour.
            plugin.getLogger().warning("[Anti-ESP] Could not convert " + slot + " of " + target.getName()
                    + "; it will appear on their next equipment change. " + e.getMessage());
        }
    }

    private PacketWrapper<?> buildSpawn(TrackedPlayerState state, int entityId, UUID uuid, Location loc) {
        PacketTypeCommon observed = state != null ? state.getSpawnPacketType() : null;
        boolean legacySpawn = observed != null
                ? observed == PacketType.Play.Server.SPAWN_PLAYER
                : !PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_2);

        Vector3d position = new Vector3d(loc.getX(), loc.getY(), loc.getZ());
        try {
            if (legacySpawn) {
                return new WrapperPlayServerSpawnPlayer(entityId, uuid, position,
                        loc.getYaw(), loc.getPitch(), Collections.emptyList());
            }
            return new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), EntityTypes.PLAYER,
                    position, loc.getPitch(), loc.getYaw(), loc.getYaw(), 0,
                    Optional.of(new Vector3d(0, 0, 0)));
        } catch (Throwable e) {
            // Throwable, not Exception: building a packet touches PacketEvents' entity and item
            // registries, and a mapping that fails to load raises an Error. Letting that escape
            // would kill the occlusion pass for everyone instead of just this one reveal.
            if (config.isDebug()) {
                plugin.getLogger().warning("[Anti-ESP] Failed to build spawn packet: " + e.getMessage());
            }
            return null;
        }
    }

    /** Reveals every player the plugin is currently hiding — used on shutdown and reload. */
    private void revealEverything() {
        for (ViewerVisibility visibility : viewers.values()) {
            Player viewer = Bukkit.getPlayer(visibility.getViewerId());
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            for (Map.Entry<Integer, TargetLink> entry : visibility.getLinks().entrySet()) {
                TargetLink link = entry.getValue();
                if (!link.hidden) {
                    continue;
                }
                link.hidden = false;
                Player target = getPlayerByEntityId(entry.getKey());
                if (target != null && target.isOnline() && target.getWorld().equals(viewer.getWorld())
                        && link.serverTracked && !link.clientHas) {
                    sendReveal(viewer, target, link, tickCounter);
                }
            }
            visibility.setHiddenCount(0);
            visibility.setHiddenPositions(new double[0]);
        }
    }

    private void sendSilently(Player viewer, PacketWrapper<?> packet) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
        } catch (Exception e) {
            if (config.isDebug()) {
                plugin.getLogger().warning("[Anti-ESP] Failed to send packet: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- packet listener hooks

    ViewerVisibility viewerState(UUID viewerId) {
        return viewerId == null ? null : viewers.get(viewerId);
    }

    boolean isPlayerEntity(int entityId) {
        return playersByEntityId.containsKey(entityId);
    }

    TrackedPlayerState trackedState(int entityId) {
        if (!playersByEntityId.containsKey(entityId)) {
            return null;
        }
        return trackedPlayers.computeIfAbsent(entityId, id -> new TrackedPlayerState());
    }

    /**
     * The server tracker just spawned {@code entityId} for this viewer.
     *
     * @return true when the packet must be cancelled because the target is currently hidden
     */
    boolean handleSpawnObserved(ViewerVisibility visibility, int entityId, PacketTypeCommon spawnType) {
        TrackedPlayerState state = trackedState(entityId);
        if (state != null && spawnType != null) {
            state.setSpawnPacketType(spawnType);
        }
        TargetLink link = visibility.link(entityId);
        link.serverTracked = true;
        if (link.hidden) {
            link.clientHas = false;
            return true;
        }
        link.clientHas = true;
        return false;
    }

    /**
     * Applies a passenger update to the mirrored state, including the dismount case: a player who
     * was riding this vehicle and is no longer in the list has left it.
     */
    void handlePassengerUpdate(int vehicleId, int[] passengers) {
        for (Map.Entry<Integer, TrackedPlayerState> entry : trackedPlayers.entrySet()) {
            TrackedPlayerState state = entry.getValue();
            boolean riding = contains(passengers, entry.getKey());
            if (riding) {
                state.recordVehicle(vehicleId, passengers);
            } else if (state.getVehicleId() == vehicleId) {
                state.clearVehicle();
            }
        }
    }

    private static boolean contains(int[] values, int needle) {
        if (values == null) {
            return false;
        }
        for (int value : values) {
            if (value == needle) {
                return true;
            }
        }
        return false;
    }

    /** The server tracker dropped these entities for this viewer; the pair state goes with them. */
    void handleDestroyObserved(ViewerVisibility visibility, int[] entityIds) {
        if (entityIds == null) {
            return;
        }
        for (int entityId : entityIds) {
            TargetLink link = visibility.peek(entityId);
            if (link == null) {
                continue;
            }
            link.clientHas = false;
            link.serverTracked = false;
            link.hidden = false;
            link.occludedSinceMs = 0L;
        }
    }

    /**
     * True while the target is occluded but still shown — the window in which a glowing outline
     * would otherwise draw straight through the wall the player is standing behind.
     */
    boolean isOccludedButVisible(ViewerVisibility visibility, int entityId) {
        TargetLink link = visibility.peek(entityId);
        return link != null && !link.hidden && link.occludedSinceMs != 0L;
    }

    // ---------------------------------------------------------------- public API

    public boolean isEntityHiddenFrom(Player viewer, int entityId) {
        if (viewer == null) {
            return false;
        }
        ViewerVisibility visibility = viewers.get(viewer.getUniqueId());
        return visibility != null && visibility.isHidden(entityId);
    }

    public boolean isSoundFromHiddenPlayer(Player viewer, Location soundLoc) {
        if (viewer == null || soundLoc == null) {
            return false;
        }
        ViewerVisibility visibility = viewers.get(viewer.getUniqueId());
        return visibility != null && isSoundFromHiddenPlayer(visibility,
                soundLoc.getX(), soundLoc.getY(), soundLoc.getZ());
    }

    /**
     * Matches a sound against the last known positions of the players hidden from this viewer.
     * Runs on the netty thread for every positioned sound, so it walks a flat array instead of the
     * online player list.
     */
    boolean isSoundFromHiddenPlayer(ViewerVisibility visibility, double x, double y, double z) {
        double[] spots = visibility.getHiddenPositions();
        for (int i = 0; i + 2 < spots.length; i += 3) {
            double dx = spots[i] - x;
            double dy = spots[i + 1] - y;
            double dz = spots[i + 2] - z;
            if (dx * dx + dy * dy + dz * dz <= 6.25) { // within 2.5 blocks of a hidden player
                return true;
            }
        }
        return false;
    }

    public Config getConfig() {
        return config;
    }

    public boolean isRunning() {
        return running;
    }

    private Player getPlayerByEntityId(int entityId) {
        UUID uuid = playersByEntityId.get(entityId);
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    private boolean canReadChunk(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }
        if (!folia || OWNED_BY_CURRENT_REGION == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(OWNED_BY_CURRENT_REGION.invoke(Bukkit.getServer(), world, chunkX, chunkZ));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method resolveRegionCheck() {
        try {
            return Bukkit.getServer().getClass()
                    .getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!running) {
            return;
        }
        Player player = event.getPlayer();
        playersByEntityId.put(player.getEntityId(), player.getUniqueId());
        viewers.computeIfAbsent(player.getUniqueId(), ViewerVisibility::new);
        trackedPlayers.computeIfAbsent(player.getEntityId(), id -> new TrackedPlayerState());
        if (folia) {
            startViewerTask(player, Math.max(1, config.getAntiEspUpdateIntervalTicks()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        int entityId = player.getEntityId();

        viewers.remove(uuid);
        playersByEntityId.remove(entityId);
        trackedPlayers.remove(entityId);
        BypassCache.invalidate(uuid);

        ScheduledTask task = viewerTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }

        for (ViewerVisibility visibility : viewers.values()) {
            visibility.getLinks().remove(entityId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        forgetClientEntities(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        forgetClientEntities(event.getPlayer());
    }

    /**
     * Drops every pair involving this player. A world change or respawn makes the client throw away
     * its whole entity list, so any surviving "hidden" flag would silently swallow the fresh spawn
     * packets the server is about to send — which is how players used to come back invisible.
     */
    private void forgetClientEntities(Player player) {
        UUID uuid = player.getUniqueId();
        int entityId = player.getEntityId();

        ViewerVisibility visibility = viewers.get(uuid);
        if (visibility != null) {
            visibility.getLinks().clear();
            visibility.setHiddenCount(0);
            visibility.setHiddenPositions(new double[0]);
        }

        for (ViewerVisibility other : viewers.values()) {
            other.getLinks().remove(entityId);
        }
    }
}
