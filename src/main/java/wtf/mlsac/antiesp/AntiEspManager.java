/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import wtf.mlsac.config.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for Anti-ESP engine.
 * Manages periodic occlusion checking, visibility state maps,
 * and dispatching destroy/spawn packets when players transition behind/out of walls.
 */
public class AntiEspManager implements Listener {
    private final JavaPlugin plugin;
    private final Config config;
    private final Map<UUID, Set<Integer>> hiddenEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Long>> lastVisibleTimestamps = new ConcurrentHashMap<>();
    private AntiEspPacketListener packetListener;
    private int taskId = -1;

    public AntiEspManager(JavaPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (!config.isAntiEspEnabled()) {
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            this.packetListener = new AntiEspPacketListener(this);
            PacketEvents.getAPI().getEventManager().registerListener(packetListener);
            plugin.getLogger().info("[Anti-ESP] PacketEvents occlusion listener registered successfully.");
        } else {
            plugin.getLogger().warning("[Anti-ESP] PacketEvents API not initialized! Anti-ESP disabled.");
            return;
        }

        int interval = Math.max(1, config.getAntiEspUpdateIntervalTicks());
        this.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::updateOcclusionStates, interval, interval);
        plugin.getLogger().info("[Anti-ESP] Occlusion checker task started (interval: " + interval + " ticks).");
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        if (packetListener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (Exception ignored) {
            }
            packetListener = null;
        }

        // Reveal all hidden players on plugin disable/reload
        revealAllHiddenEntities();
        hiddenEntities.clear();
    }

    private void updateOcclusionStates() {
        if (!config.isAntiEspEnabled()) {
            return;
        }

        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.size() < 2) {
            return;
        }

        double maxDist = config.getAntiEspMaxDistance();
        double proximityDist = config.getAntiEspProximityDistance();
        long hideDelayMs = Math.max(0, config.getAntiEspHideDelayTicks() * 50L);
        long now = System.currentTimeMillis();

        for (Player viewer : online) {
            if (!viewer.isOnline()) {
                continue;
            }

            UUID viewerId = viewer.getUniqueId();
            Set<Integer> viewerHiddenSet = hiddenEntities.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
            Map<Integer, Long> targetTimestamps = lastVisibleTimestamps.computeIfAbsent(viewerId, k -> new ConcurrentHashMap<>());

            for (Player target : online) {
                if (viewer.equals(target) || !target.isOnline()) {
                    continue;
                }

                if (!viewer.getWorld().equals(target.getWorld())) {
                    continue;
                }

                int targetId = target.getEntityId();
                boolean currentlyHidden = viewerHiddenSet.contains(targetId);
                boolean visibleNow = OcclusionChecker.isVisible(viewer, target, maxDist, proximityDist);

                boolean verbose = config.isDebug() || config.isAntiEspVerboseDebug();

                if (visibleNow) {
                    targetTimestamps.put(targetId, now);
                    if (currentlyHidden) {
                        // Transition: Hidden -> Visible (IMMEDIATE)
                        viewerHiddenSet.remove(targetId);
                        if (verbose) {
                            plugin.getLogger().info("[Anti-ESP-Debug] Transition HIDDEN -> VISIBLE | Target: "
                                    + target.getName() + " (ID: " + targetId + ") revealed to Viewer: " + viewer.getName());
                        }
                        revealPlayerToViewer(viewer, target);
                    }
                } else {
                    long lastSeen = targetTimestamps.getOrDefault(targetId, 0L);
                    long timeSinceSeen = now - lastSeen;

                    // If hideDelayMs has elapsed since target was last visible, transition to HIDDEN
                    if (!currentlyHidden && timeSinceSeen >= hideDelayMs) {
                        // Transition: Visible -> Hidden
                        viewerHiddenSet.add(targetId);
                        if (verbose) {
                            plugin.getLogger().info("[Anti-ESP-Debug] Transition VISIBLE -> HIDDEN | Target: "
                                    + target.getName() + " (ID: " + targetId + ") hidden from Viewer: " + viewer.getName());
                        }
                        hidePlayerFromViewer(viewer, target);
                    }
                }
            }
        }
    }

    public boolean isEntityHiddenFrom(Player viewer, int entityId) {
        if (viewer == null) {
            return false;
        }
        Set<Integer> hiddenSet = hiddenEntities.get(viewer.getUniqueId());
        return hiddenSet != null && hiddenSet.contains(entityId);
    }

    public boolean isSoundFromHiddenPlayer(Player viewer, Location soundLoc) {
        if (viewer == null || soundLoc == null) {
            return false;
        }
        Set<Integer> hiddenSet = hiddenEntities.get(viewer.getUniqueId());
        if (hiddenSet == null || hiddenSet.isEmpty()) {
            return false;
        }

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (hiddenSet.contains(target.getEntityId()) && target.getWorld().equals(soundLoc.getWorld())) {
                if (target.getLocation().distanceSquared(soundLoc) <= 6.25) { // within 2.5 blocks of target
                    return true;
                }
            }
        }
        return false;
    }

    private void hidePlayerFromViewer(Player viewer, Player target) {
        if (viewer == null || !viewer.isOnline() || target == null) {
            return;
        }
        try {
            viewer.hidePlayer(plugin, target);
        } catch (Exception ignored) {
        }
    }

    private void revealPlayerToViewer(Player viewer, Player target) {
        if (viewer == null || !viewer.isOnline() || target == null || !target.isOnline()) {
            return;
        }
        try {
            viewer.showPlayer(plugin, target);
        } catch (Exception ignored) {
        }
    }

    private void revealAllHiddenEntities() {
        for (Map.Entry<UUID, Set<Integer>> entry : hiddenEntities.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }

            for (int entityId : entry.getValue()) {
                Player target = null;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getEntityId() == entityId) {
                        target = p;
                        break;
                    }
                }
                if (target != null) {
                    revealPlayerToViewer(viewer, target);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hiddenEntities.remove(uuid);

        int entityId = event.getPlayer().getEntityId();
        for (Set<Integer> set : hiddenEntities.values()) {
            set.remove(entityId);
        }
    }

    public Config getConfig() {
        return config;
    }
}
