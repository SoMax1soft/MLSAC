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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
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
    private static final GsonComponentSerializer GSON_SERIALIZER = GsonComponentSerializer.gson();
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
                        viewerLoc.distanceSquared(target.getLocation()) > viewDistSq ||
                        target.isDead()) {
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
            
            // Check if world changed - if so, we must re-spawn because client cleared entities
            boolean worldChanged = cache.lastLoc == null || !cache.lastLoc.getWorld().equals(loc.getWorld());
            boolean moved = worldChanged || cache.lastLoc.distanceSquared(loc) > 0.01;

            if (worldChanged) {
                spawn(viewer, entityId, loc, newText);
            } else if (moved) {
                teleport(viewer, entityId, loc);
            }
            
            if (textChanged && !worldChanged) {
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
                0, 0, 0, 0, Optional.of(new Vector3d(0, 0, 0))
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);

        sendMetadata(viewer, entityId, text);
    }

    private void sendMetadata(Player viewer, int entityId, String text) {
        try {
            List<EntityData<?>> meta = new ArrayList<>();
            // Index 0: Entity flags (invisible)
            meta.add(new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20));
            // Index 2: Custom name (text component)
            meta.add(new EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT,
                Optional.of(GSON_SERIALIZER.serialize(LEGACY_SERIALIZER.deserialize(text)))));
            // Index 3: Custom name visible
            meta.add(new EntityData(3, EntityDataTypes.BOOLEAN, true));
            // Index 15: Armor Stand flags (0x10 is Marker - removes hitbox and interaction)
            meta.add(new EntityData(15, EntityDataTypes.BYTE, (byte) 0x10));

            WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(entityId, meta);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send metadata for entity " + entityId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void teleport(Player viewer, int entityId, Location loc) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerEntityTeleport(entityId, new Vector3d(loc.getX(), loc.getY(), loc.getZ()), 0, 0, true));
    }

    private void updateText(Player viewer, int entityId, String text) {
        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(GSON_SERIALIZER.serialize(LEGACY_SERIALIZER.deserialize(text)))));
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
            return ColorUtil.colorize("&7AVG &8--- &7| &8---");
        }

        List<AIPlayerData.ModelProbabilityEntry> history = data.getModelProbabilityHistory();
        if (history.isEmpty()) {
            return ColorUtil.colorize("&7AVG &8--- &7| &8---");
        }

        double sum = 0;
        int count = 0;
        int historyLimit = plugin.getHologramConfig().getConfig().getInt("nametags.history-limit", 8);

        int startIdx = Math.max(0, history.size() - historyLimit);
        for (int i = startIdx; i < history.size(); i++) {
            sum += history.get(i).getProbability();
            count++;
        }

        double avg = count > 0 ? (sum / count) * 100 : 0;

        StringBuilder histBuilder = new StringBuilder();
        for (int i = startIdx; i < history.size(); i++) {
            if (i > startIdx) histBuilder.append(" ");

            AIPlayerData.ModelProbabilityEntry entry = history.get(i);
            String formattedEntry = formatModelResult(entry);
            histBuilder.append(formattedEntry);
        }

        String format = plugin.getHologramConfig().getConfig().getString("nametags.hologram", "&6AVG &f{AVG}% &7| {HIST}");
        String avgColor = getColorForProbability(avg / 100);

        return ColorUtil.colorize(format
            .replace("{AVG}", avgColor + String.format("%.0f", avg))
            .replace("{HIST}", histBuilder.toString()));
    }

    private String formatModelResult(AIPlayerData.ModelProbabilityEntry entry) {
        String modelName = entry.getModelName().toLowerCase();
        String formatKey;
        if (modelName.contains("fast")) {
            formatKey = "nametags.fast-format";
        } else if (modelName.contains("pro")) {
            formatKey = "nametags.pro-format";
        } else if (modelName.contains("ultra")) {
            formatKey = "nametags.ultra-format";
        } else {
            return "?";
        }

        String format = plugin.getHologramConfig().getConfig().getString(formatKey, "&f{RESULT}%");
        int prob = (int) (entry.getProbability() * 100);
        return format.replace("{RESULT}", String.valueOf(prob));
    }

    private String getColorForProbability(double probability) {
        double critical_bold = plugin.getHologramConfig().getConfig().getDouble("nametags.colors.thresholds.critical-bold", 0.9);
        double critical = plugin.getHologramConfig().getConfig().getDouble("nametags.colors.thresholds.critical", 0.8);
        double high = plugin.getHologramConfig().getConfig().getDouble("nametags.colors.thresholds.high", 0.6);
        double medium = plugin.getHologramConfig().getConfig().getDouble("nametags.colors.thresholds.medium", 0.5);

        if (probability >= critical_bold) {
            return plugin.getHologramConfig().getConfig().getString("nametags.colors.critical_bold", "&4");
        } else if (probability >= critical) {
            return plugin.getHologramConfig().getConfig().getString("nametags.colors.critical", "&c");
        } else if (probability >= high) {
            return plugin.getHologramConfig().getConfig().getString("nametags.colors.high", "&c");
        } else if (probability >= medium) {
            return plugin.getHologramConfig().getConfig().getString("nametags.colors.medium", "&f");
        }
        return plugin.getHologramConfig().getConfig().getString("nametags.colors.low", "&f");
    }

    public void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        ViewerState state = viewers.remove(playerId);
        if (state != null) {
            destroyViewerEntities(playerId, state);
            state.targets.clear();
        }

        for (ViewerState viewerState : viewers.values()) {
            removeTarget(Bukkit.getPlayer(viewerState.viewerId), playerId, viewerState);
        }
    }

    public void handleDeath(Player player) {
        UUID playerId = player.getUniqueId();
        for (ViewerState viewerState : viewers.values()) {
            removeTarget(Bukkit.getPlayer(viewerState.viewerId), playerId, viewerState);
        }
    }

    public void handleRespawn(Player player) {
        // Голограммы автоматически появятся в следующем тике
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