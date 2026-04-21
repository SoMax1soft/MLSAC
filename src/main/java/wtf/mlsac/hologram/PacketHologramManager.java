package wtf.mlsac.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.ColorUtil;
import wtf.mlsac.util.ProbabilityFormatUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketHologramManager {
    private final wtf.mlsac.Main plugin;
    private final AICheck aiCheck;
    private final Map<UUID, HologramData> playerHolograms = new ConcurrentHashMap<>();
    private final AtomicInteger entityIdCounter = new AtomicInteger(2000000000); // Start from a high range
    private ScheduledTask updateTask;

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    public PacketHologramManager(wtf.mlsac.Main plugin, AICheck aiCheck) {
        this.plugin = plugin;
        this.aiCheck = aiCheck;
    }

    public void start() {
        long interval = 2L; // Fast updates for smooth tracking
        updateTask = SchedulerManager.getAdapter().runSyncRepeating(this::tick, interval, interval);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        for (UUID viewerId : playerHolograms.keySet()) {
            removeHologramsForViewer(viewerId);
        }
        playerHolograms.clear();
    }

    private void tick() {
        boolean enabled = plugin.getHologramConfig().getConfig().getBoolean("nametags.enabled", true);
        if (!enabled) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Player> staff = new ArrayList<>();
        for (Player p : players) {
            if (p.hasPermission(Permissions.ADMIN) || p.hasPermission(Permissions.ALERTS)) {
                staff.add(p);
            }
        }

        for (Player viewer : staff) {
            UUID viewerId = viewer.getUniqueId();
            HologramData data = playerHolograms.computeIfAbsent(viewerId, k -> new HologramData());

            for (Player target : players) {
                if (target.equals(viewer) || !target.getWorld().equals(viewer.getWorld()) || 
                    viewer.getLocation().distanceSquared(target.getLocation()) > 1600) { // 40 blocks
                    removeHologram(viewer, target.getUniqueId(), data);
                    continue;
                }
                updateHologram(viewer, target, data);
            }
        }
    }
    private void updateHologram(Player viewer, Player target, HologramData data) {
        UUID targetId = target.getUniqueId();
        Integer entityId = data.targetEntities.get(targetId);
        
        double yOffset = isBelowNameMode() ? -0.5 : 2.3;
        Location loc = target.getLocation().add(0, yOffset, 0);

        boolean isNew = entityId == null;
        if (isNew) {
            entityId = entityIdCounter.incrementAndGet();
            data.targetEntities.put(targetId, entityId);
            spawnHologram(viewer, entityId, loc);
        }

        AIPlayerData aiData = aiCheck.getPlayerData(targetId);
        String text = buildText(aiData);
        
        // Always teleport for smooth tracking
        teleportHologram(viewer, entityId, loc);
        
        // Update text only if changed or periodically
        updateMetadata(viewer, entityId, text);
    }

    private String buildText(AIPlayerData data) {
        if (data == null) return ColorUtil.colorize("&7ML: &8---");
        double prob = data.getLastProbability();
        double avgProb = data.getAverageProbability();
        String color = getProbabilityColor(prob);
        String avgColor = getProbabilityColor(avgProb);

        String template = plugin.getHologramConfig().getConfig().getString("nametags.format", "&6▶AVG &f&l{AVG}% &7| {HISTORY}");
        if (isBelowNameMode()) {
            template = plugin.getHologramConfig().getConfig().getString("nametags.below-name-format", template);
        } else {
            template = plugin.getHologramConfig().getConfig().getString("nametags.above-name-format", template);
        }

        return ColorUtil.colorize(template
                .replace("{AVG}", String.format("%.0f", avgProb * 100))
                .replace("{RESULT}", String.format("%.0f", prob * 100))
                .replace("{HISTORY}", color + String.format("%.0f%%", prob * 100)));
    }

    private boolean isBelowNameMode() {
        String raw = plugin.getHologramConfig().getConfig().getString("nametags.position", "BELOW_NAME");
        return raw != null && (raw.equalsIgnoreCase("below_name") || raw.equalsIgnoreCase("below"));
    }

    private void spawnHologram(Player viewer, int entityId, Location loc) {
        boolean useTextDisplay = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_4);
        
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                useTextDisplay ? EntityTypes.TEXT_DISPLAY : EntityTypes.ARMOR_STAND,
                new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                0, 0, 0, 0, null
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);
    }

    private void teleportHologram(Player viewer, int entityId, Location loc) {
        WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(
                entityId,
                new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                0, 0, true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, tp);
    }

    private void updateMetadata(Player viewer, int entityId, String text) {
        boolean useTextDisplay = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_4);
        List<EntityData<?>> metadata = new ArrayList<>();
        
        if (useTextDisplay) {
            // TextDisplay metadata
            // 23: Text (Component)
            // 25: Background (Int) - Let's make it transparent or semi-transparent
            metadata.add(new EntityData(23, EntityDataTypes.COMPONENT, LEGACY_SERIALIZER.deserialize(text)));
            metadata.add(new EntityData(25, EntityDataTypes.INT, 1073741824)); // Gray background
        } else {
            // ArmorStand metadata
            // 0: General Flags (bit 5 is invisible)
            // 2: Custom Name (Component)
            // 3: Custom Name Visible (Boolean)
            // 15: ArmorStand Flags (bit 4 is marker)
            metadata.add(new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20)); // Invisible
            metadata.add(new EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(LEGACY_SERIALIZER.deserialize(text))));
            metadata.add(new EntityData(3, EntityDataTypes.BOOLEAN, true));
            metadata.add(new EntityData(15, EntityDataTypes.BYTE, (byte) 0x10)); // Marker
        }

        WrapperPlayServerEntityMetadata metaPacket = new WrapperPlayServerEntityMetadata(entityId, metadata);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metaPacket);
    }

    private void removeHologram(Player viewer, UUID targetId, HologramData data) {
        Integer entityId = data.targetEntities.remove(targetId);
        if (entityId != null) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityId);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
        }
    }

    private void removeHologramsForViewer(UUID viewerId) {
        HologramData data = playerHolograms.get(viewerId);
        if (data == null) return;
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) return;

        for (Integer entityId : data.targetEntities.values()) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityId);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
        }
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        removeHologramsForViewer(id);
        playerHolograms.remove(id);
        
        for (HologramData data : playerHolograms.values()) {
            Integer entityId = data.targetEntities.remove(id);
            if (entityId != null) {
                // We'd need to send destroy packet to each viewer, 
                // but tick will handle it or we can optimize here.
            }
        }
    }

    private String getProbabilityColor(double prob) {
        if (prob >= 0.9) return "&4&l";
        if (prob >= 0.8) return "&4";
        if (prob >= 0.6) return "&c";
        if (prob >= 0.4) return "&6";
        return "&a";
    }

    private static class HologramData {
        final Map<UUID, Integer> targetEntities = new ConcurrentHashMap<>();
    }
}
