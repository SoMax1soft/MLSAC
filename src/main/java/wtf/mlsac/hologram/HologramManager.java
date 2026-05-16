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
import wtf.mlsac.scheduler.ServerType;
import wtf.mlsac.util.ColorUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class HologramManager {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final GsonComponentSerializer GSON_SERIALIZER = GsonComponentSerializer.gson();
    private static final int ENTITY_ID_START = 42000000;
    private static final int DEFAULT_UPDATE_INTERVAL_TICKS = 5;
    private static final int DEFAULT_MAX_TARGETS_PER_VIEWER = 16;
    private static final double DEFAULT_MOVE_THRESHOLD_SQUARED = 0.04D;

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
        if (SchedulerManager.getServerType() == ServerType.FOLIA) {
            plugin.getLogger().warning("[Holograms] Nametag holograms are disabled on Folia to avoid unsafe cross-region player access.");
            return;
        }
        int interval = Math.max(1, plugin.getHologramConfig().getConfig()
                .getInt("nametags.update-interval-ticks", DEFAULT_UPDATE_INTERVAL_TICKS));
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

        NametagSettings settings = readSettings();
        double viewDistSq = settings.viewDistance * settings.viewDistance;

        List<Player> staff = new ArrayList<>();
        Set<UUID> staffIds = new HashSet<>();
        for (Player p : online) {
            if (p.hasPermission(Permissions.ADMIN) || p.hasPermission(Permissions.ALERTS)) {
                staff.add(p);
                staffIds.add(p.getUniqueId());
            }
        }
        for (UUID viewerId : new ArrayList<>(viewers.keySet())) {
            if (!staffIds.contains(viewerId)) {
                ViewerState state = viewers.remove(viewerId);
                if (state != null) {
                    destroyViewerEntities(viewerId, state);
                }
            }
        }
        if (staff.isEmpty()) return;

        for (Player viewer : staff) {
            UUID viewerId = viewer.getUniqueId();
            ViewerState state = viewers.computeIfAbsent(viewerId, k -> new ViewerState(viewerId));

            Location viewerLoc = viewer.getLocation();
            String viewerWorld = viewerLoc.getWorld().getName();
            List<TargetCandidate> candidates = new ArrayList<>();

            for (Player target : online) {
                if (target.equals(viewer)) continue;

                UUID targetId = target.getUniqueId();
                String targetWorld = target.getWorld().getName();
                AIPlayerData aiData = aiCheck.getPlayerData(targetId);

                if (!viewerWorld.equals(targetWorld) ||
                        !shouldDisplay(aiData, settings) ||
                        target.isDead()) {
                    continue;
                }

                Location targetLoc = target.getLocation();
                double distanceSquared = viewerLoc.distanceSquared(targetLoc);
                if (distanceSquared > viewDistSq) {
                    continue;
                }
                candidates.add(new TargetCandidate(targetId, targetLoc.add(0, 2.3, 0), distanceSquared, aiData));
            }

            candidates.sort(Comparator.comparingDouble(candidate -> candidate.distanceSquared));
            Set<UUID> alive = new HashSet<>();
            int limit = Math.min(settings.maxTargetsPerViewer, candidates.size());
            for (int i = 0; i < limit; i++) {
                TargetCandidate candidate = candidates.get(i);
                UUID targetId = candidate.targetId;
                alive.add(targetId);
                updateTarget(viewer, candidate, state, settings);
            }

            for (UUID targetId : new ArrayList<>(state.targets.keySet())) {
                if (!alive.contains(targetId)) {
                    removeTarget(viewer, targetId, state);
                }
            }
        }
    }

    private void updateTarget(Player viewer, TargetCandidate candidate, ViewerState state, NametagSettings settings) {
        UUID targetId = candidate.targetId;
        String newText = buildText(candidate.aiData, settings);
        Location loc = candidate.location;

        int entityId;
        EntityCache cache = state.targets.get(targetId);
        if (cache == null) {
            entityId = entityIdCounter.incrementAndGet();
            cache = new EntityCache(entityId);
            state.targets.put(targetId, cache);
            spawn(viewer, entityId, loc, newText);
            cache.lastText = newText;
            cache.lastLoc = loc;
        } else {
            entityId = cache.entityId;
            boolean textChanged = !newText.equals(cache.lastText);
            
            // Check if world changed - if so, we must re-spawn because client cleared entities
            boolean worldChanged = cache.lastLoc == null || !cache.lastLoc.getWorld().equals(loc.getWorld());
            boolean moved = worldChanged || cache.lastLoc.distanceSquared(loc) > settings.moveThresholdSquared;

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
        if (cache != null && viewer != null && viewer.isOnline()) {
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
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
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

    private String buildText(AIPlayerData data, NametagSettings settings) {
        if (data == null) {
            return ColorUtil.colorize("&7AVG &8--- &7| &8---");
        }

        List<AIPlayerData.ModelProbabilityEntry> history = data.getModelProbabilityHistory();
        if (history.isEmpty()) {
            return ColorUtil.colorize("&7AVG &8--- &7| &8---");
        }

        double sum = 0;
        int count = 0;
        int historyLimit = settings.historyLimit;

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

        String avgColor = getColorForProbability(avg / 100, settings);

        return ColorUtil.colorize(settings.format
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

    private String getColorForProbability(double probability, NametagSettings settings) {
        double critical_bold = settings.criticalBoldThreshold;
        double critical = settings.criticalThreshold;
        double high = settings.highThreshold;
        double medium = settings.mediumThreshold;

        if (probability >= critical_bold) {
            return settings.criticalBoldColor;
        } else if (probability >= critical) {
            return settings.criticalColor;
        } else if (probability >= high) {
            return settings.highColor;
        } else if (probability >= medium) {
            return settings.mediumColor;
        }
        return settings.lowColor;
    }

    private boolean shouldDisplay(AIPlayerData data, NametagSettings settings) {
        return settings.showEmpty || (data != null && !data.getModelProbabilityHistory().isEmpty());
    }

    private NametagSettings readSettings() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getHologramConfig().getConfig();
        double viewDistance = config.getDouble("nametags.view-distance", 40.0);
        int maxTargets = Math.max(1, config.getInt("nametags.max-targets-per-viewer", DEFAULT_MAX_TARGETS_PER_VIEWER));
        int historyLimit = Math.max(1, config.getInt("nametags.history-limit", 8));
        boolean showEmpty = config.getBoolean("nametags.show-empty", false);
        double moveThresholdSquared = Math.max(0.0D,
                config.getDouble("nametags.movement-threshold-squared", DEFAULT_MOVE_THRESHOLD_SQUARED));
        return new NametagSettings(
                viewDistance,
                maxTargets,
                historyLimit,
                showEmpty,
                moveThresholdSquared,
                config.getString("nametags.hologram", "&6AVG &f{AVG}% &7| {HIST}"),
                config.getString("nametags.colors.low", "&f"),
                config.getString("nametags.colors.medium", "&f"),
                config.getString("nametags.colors.high", "&c"),
                config.getString("nametags.colors.critical", "&c"),
                config.getString("nametags.colors.critical_bold", "&4"),
                config.getDouble("nametags.colors.thresholds.medium", 0.5),
                config.getDouble("nametags.colors.thresholds.high", 0.6),
                config.getDouble("nametags.colors.thresholds.critical", 0.8),
                config.getDouble("nametags.colors.thresholds.critical-bold", 0.9));
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

    private static class TargetCandidate {
        final UUID targetId;
        final Location location;
        final double distanceSquared;
        final AIPlayerData aiData;

        TargetCandidate(UUID targetId, Location location, double distanceSquared, AIPlayerData aiData) {
            this.targetId = targetId;
            this.location = location;
            this.distanceSquared = distanceSquared;
            this.aiData = aiData;
        }
    }

    private static class NametagSettings {
        final double viewDistance;
        final int maxTargetsPerViewer;
        final int historyLimit;
        final boolean showEmpty;
        final double moveThresholdSquared;
        final String format;
        final String lowColor;
        final String mediumColor;
        final String highColor;
        final String criticalColor;
        final String criticalBoldColor;
        final double mediumThreshold;
        final double highThreshold;
        final double criticalThreshold;
        final double criticalBoldThreshold;

        NametagSettings(double viewDistance, int maxTargetsPerViewer, int historyLimit, boolean showEmpty,
                double moveThresholdSquared, String format, String lowColor, String mediumColor, String highColor,
                String criticalColor, String criticalBoldColor, double mediumThreshold, double highThreshold,
                double criticalThreshold, double criticalBoldThreshold) {
            this.viewDistance = viewDistance;
            this.maxTargetsPerViewer = maxTargetsPerViewer;
            this.historyLimit = historyLimit;
            this.showEmpty = showEmpty;
            this.moveThresholdSquared = moveThresholdSquared;
            this.format = format;
            this.lowColor = lowColor;
            this.mediumColor = mediumColor;
            this.highColor = highColor;
            this.criticalColor = criticalColor;
            this.criticalBoldColor = criticalBoldColor;
            this.mediumThreshold = mediumThreshold;
            this.highThreshold = highThreshold;
            this.criticalThreshold = criticalThreshold;
            this.criticalBoldThreshold = criticalBoldThreshold;
        }
    }
}
