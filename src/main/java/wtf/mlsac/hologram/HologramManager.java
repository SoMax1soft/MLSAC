package wtf.mlsac.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.ColorUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class HologramManager {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final int ENTITY_ID_START = 42000000;

    private final Main plugin;
    private final AICheck aiCheck;
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final AtomicInteger entityIdCounter = new AtomicInteger(ENTITY_ID_START);
    private ScheduledTask task;

    public HologramManager(Main plugin, AICheck aiCheck) {
        this.plugin = plugin;
        this.aiCheck = aiCheck;
    }

    public void start() {
        if (!plugin.getHologramConfig().getConfig().getBoolean("nametags.enabled", true)) {
            return;
        }
        int interval = plugin.getHologramConfig().getConfig().getInt("nametags.update-interval-ticks", 20);
        task = SchedulerManager.getAdapter().runSyncRepeating(this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID viewerId : new ArrayList<>(viewers.keySet())) {
            ViewerState state = viewers.remove(viewerId);
            if (state != null) {
                destroyViewerEntities(viewerId, state);
            }
        }
    }

    private void tick() {
        Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        if (online.length == 0) return;

        double viewDist = plugin.getHologramConfig().getConfig().getDouble("nametags.view-distance", 40.0);
        double viewDistSq = viewDist * viewDist;

        List<Player> staff = new ArrayList<>();
        for (Player p : online) {
            if (p.hasPermission(Permissions.ADMIN) || p.hasPermission(Permissions.ALERTS)) {
                staff.add(p);
            }
        }
        if (staff.isEmpty()) return;

        for (Player viewer : staff) {
            UUID viewerId = viewer.getUniqueId();
            ViewerState state = viewers.computeIfAbsent(viewerId, k -> new ViewerState(viewerId));

            Set<UUID> alive = new HashSet<>();
            Location viewerLoc = viewer.getLocation();
            String viewerWorld = viewerLoc.getWorld().getName();

            for (Player target : online) {
                if (target.equals(viewer)) continue;

                UUID targetId = target.getUniqueId();
                String targetWorld = target.getWorld().getName();

                if (!viewerWorld.equals(targetWorld) ||
                        viewerLoc.distanceSquared(target.getLocation()) > viewDistSq) {
                    removeTarget(viewer, targetId, state);
                    continue;
                }

                alive.add(targetId);
                updateTarget(viewer, target, state);
            }

            state.targets.keySet().removeIf(id -> !alive.contains(id));
        }
    }

    private void updateTarget(Player viewer, Player target, ViewerState state) {
        UUID targetId = target.getUniqueId();
        AIPlayerData aiData = aiCheck.getPlayerData(targetId);
        String newText = buildText(aiData);
        Location loc = target.getLocation().add(0, 2.3, 0);

        int entityId;
        EntityCache cache = state.targets.get(targetId);
        if (cache == null) {
            entityId = entityIdCounter.incrementAndGet();
            cache = new EntityCache(entityId);
            state.targets.put(targetId, cache);
            spawn(viewer, entityId, loc, newText);
        } else {
            entityId = cache.entityId;
            boolean textChanged = !newText.equals(cache.lastText);
            boolean moved = cache.lastLoc != null && cache.lastLoc.distanceSquared(loc) > 0.01;

            if (moved) {
                teleport(viewer, entityId, loc);
            }
            if (textChanged) {
                updateText(viewer, entityId, newText);
            }

            cache.lastText = newText;
            cache.lastLoc = loc;
        }
    }

    private void removeTarget(Player viewer, UUID targetId, ViewerState state) {
        EntityCache cache = state.targets.remove(targetId);
        if (cache != null) {
            destroyEntity(viewer, cache.entityId);
        }
    }

    private void spawn(Player viewer, int entityId, Location loc, String text) {
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.ARMOR_STAND,
                new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                0, 0, 0, 0, null
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);

        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20));
        meta.add(new EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(LEGACY_SERIALIZER.deserialize(text))));
        meta.add(new EntityData(3, EntityDataTypes.BOOLEAN, true));
        meta.add(new EntityData(15, EntityDataTypes.BYTE, (byte) 0x10));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, meta));
    }

    private void teleport(Player viewer, int entityId, Location loc) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerEntityTeleport(entityId, new Vector3d(loc.getX(), loc.getY(), loc.getZ()), 0, 0, true));
    }

    private void updateText(Player viewer, int entityId, String text) {
        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(LEGACY_SERIALIZER.deserialize(text))));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, meta));
    }

    private void destroyEntity(Player viewer, int entityId) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityId));
    }

    private void destroyViewerEntities(UUID viewerId, ViewerState state) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer != null && viewer.isOnline()) {
            for (EntityCache cache : state.targets.values()) {
                destroyEntity(viewer, cache.entityId);
            }
        }
    }

    private String buildText(AIPlayerData data) {
        if (data == null) {
            return ColorUtil.colorize("&7ML: &8---");
        }
        double avg = data.getAverageProbability();
        double last = data.getLastProbability();
        String color = getColor(last);

        String format = plugin.getHologramConfig().getConfig().getString("nametags.format", "&6AVG &f{AVG}% &7| {HIST}");
        return ColorUtil.colorize(format
                .replace("{AVG}", String.format("%.0f", avg * 100))
                .replace("{HIST}", color + String.format("%.0f%%", last * 100)));
    }

    private String getColor(double prob) {
        if (prob >= 0.9) return "&4&l";
        if (prob >= 0.8) return "&4";
        if (prob >= 0.6) return "&c";
        if (prob >= 0.4) return "&6";
        return "&a";
    }

    public void handleQuit(Player player) {
        ViewerState state = viewers.remove(player.getUniqueId());
        if (state != null) {
            state.targets.clear();
        }
    }

    private static class ViewerState {
        final UUID viewerId;
        final Map<UUID, EntityCache> targets = new ConcurrentHashMap<>();

        ViewerState(UUID viewerId) {
            this.viewerId = viewerId;
        }
    }

    private static class EntityCache {
        final int entityId;
        String lastText = "";
        Location lastLoc;

        EntityCache(int entityId) {
            this.entityId = entityId;
        }
    }
}