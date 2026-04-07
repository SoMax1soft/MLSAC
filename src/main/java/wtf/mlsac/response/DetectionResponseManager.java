package wtf.mlsac.response;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import wtf.mlsac.Main;
import wtf.mlsac.config.Config;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DetectionResponseManager {
    private final Main plugin;
    private final Map<UUID, Deque<Long>> detectionHistory = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveDamageReduction> damageReductions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> trollCooldowns = new ConcurrentHashMap<>();
    private Config config;

    public DetectionResponseManager(Main plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setConfig(Config config) {
        this.config = config;
        this.damageReductions.clear();
        this.trollCooldowns.clear();
        this.detectionHistory.clear();
    }

    public void registerDetection(Player player, double probability) {
        if (!config.isAlertResponsesEnabled() || player == null || !player.isOnline()) {
            return;
        }

        long now = System.currentTimeMillis();
        Deque<Long> timestamps = detectionHistory.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        timestamps.addLast(now);
        pruneOldDetections(timestamps, now);

        applyDamageReduction(player, timestamps, now);
        applyTrollActions(player, timestamps, now, probability);
    }

    public double getDamageMultiplier(UUID playerId) {
        ActiveDamageReduction reduction = damageReductions.get(playerId);
        long now = System.currentTimeMillis();
        if (reduction == null) {
            return 1.0;
        }
        if (reduction.expiresAt <= now) {
            damageReductions.remove(playerId);
            return 1.0;
        }
        return Math.max(0.0, 1.0 - (reduction.reductionPercent / 100.0));
    }

    public double getActiveReductionPercent(UUID playerId) {
        ActiveDamageReduction reduction = damageReductions.get(playerId);
        return reduction == null || reduction.expiresAt <= System.currentTimeMillis() ? 0.0 : reduction.reductionPercent;
    }

    public void handlePlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        detectionHistory.remove(playerId);
        damageReductions.remove(playerId);
        trollCooldowns.remove(playerId);
    }

    private void pruneOldDetections(Deque<Long> timestamps, long now) {
        int maxWindowSeconds = Math.max(config.getDamageReductionWindowSeconds(), config.getTrollWindowSeconds());
        long cutoff = now - (maxWindowSeconds * 1000L);
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.removeFirst();
        }
    }

    private void applyDamageReduction(Player player, Deque<Long> timestamps, long now) {
        int detections = countDetectionsWithin(timestamps, now, config.getDamageReductionWindowSeconds());
        Config.DamageReductionStage bestStage = null;
        for (Config.DamageReductionStage stage : config.getDamageReductionStages()) {
            if (detections >= stage.getDetections()) {
                bestStage = stage;
            }
        }
        if (bestStage == null) {
            return;
        }

        long expiresAt = now + (bestStage.getDurationSeconds() * 1000L);
        ActiveDamageReduction current = damageReductions.get(player.getUniqueId());
        if (current == null
                || bestStage.getReductionPercent() > current.reductionPercent
                || expiresAt > current.expiresAt) {
            damageReductions.put(player.getUniqueId(),
                    new ActiveDamageReduction(bestStage.getReductionPercent(), expiresAt));
            plugin.debug("[Responses] Damage reduction applied to " + player.getName()
                    + ": " + bestStage.getReductionPercent() + "% for " + bestStage.getDurationSeconds()
                    + "s after " + detections + " detections");
        }
    }

    private void applyTrollActions(Player player, Deque<Long> timestamps, long now, double probability) {
        int detections = countDetectionsWithin(timestamps, now, config.getTrollWindowSeconds());
        for (Config.TrollActionConfig action : config.getTrollActions()) {
            if (detections < action.getDetections()) {
                continue;
            }
            if (!isCooldownReady(player.getUniqueId(), action, now)) {
                continue;
            }
            if (executeTrollAction(player, action)) {
                markCooldown(player.getUniqueId(), action, now);
                sendTrollMessage(player, action, detections, probability);
            }
        }
    }

    private boolean executeTrollAction(Player player, Config.TrollActionConfig action) {
        String type = action.getType().toLowerCase(Locale.ROOT);
        switch (type) {
            case "shuffle_inventory":
                shuffleInventory(player);
                return true;
            case "drop_weapon":
                return dropWeapon(player, action);
            default:
                plugin.getLogger().warning("Unknown troll action type: " + action.getType());
                return false;
        }
    }

    private void shuffleInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        List<ItemStack> items = new ArrayList<>();
        Collections.addAll(items, contents);
        Collections.shuffle(items);
        player.getInventory().setContents(items.toArray(new ItemStack[0]));
        player.updateInventory();
    }

    private boolean dropWeapon(Player player, Config.TrollActionConfig action) {
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem == null || handItem.getType().isAir()) {
            return false;
        }
        if (action.isOnlySword() && !isSword(handItem.getType())) {
            return false;
        }

        ItemStack dropped = handItem.clone();
        player.getInventory().setItemInMainHand(null);
        Item item = player.getWorld().dropItem(player.getEyeLocation(), dropped);
        Vector direction = player.getLocation().getDirection().normalize().multiply(action.getHorizontalVelocity());
        direction.setY(action.getVerticalVelocity());
        item.setVelocity(direction);
        player.updateInventory();
        return true;
    }

    private boolean isSword(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD");
    }

    private boolean isCooldownReady(UUID playerId, Config.TrollActionConfig action, long now) {
        if (action.getCooldownSeconds() <= 0) {
            return true;
        }
        Map<String, Long> playerCooldowns = trollCooldowns.get(playerId);
        if (playerCooldowns == null) {
            return true;
        }
        Long lastUse = playerCooldowns.get(action.getType().toLowerCase(Locale.ROOT));
        return lastUse == null || (now - lastUse) >= action.getCooldownSeconds() * 1000L;
    }

    private void markCooldown(UUID playerId, Config.TrollActionConfig action, long now) {
        trollCooldowns.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .put(action.getType().toLowerCase(Locale.ROOT), now);
    }

    private void sendTrollMessage(Player player, Config.TrollActionConfig action, int detections, double probability) {
        String template = action.getMessage();
        if (template == null || template.trim().isEmpty()) {
            return;
        }
        String message = ColorUtil.colorize(template
                .replace("{PLAYER}", player.getName())
                .replace("{DETECTIONS}", String.valueOf(detections))
                .replace("{PROBABILITY}", String.format("%.2f", probability)));
        Bukkit.getOnlinePlayers().stream()
                .filter(online -> online.hasPermission(wtf.mlsac.Permissions.ALERTS)
                        || online.hasPermission(wtf.mlsac.Permissions.ADMIN))
                .forEach(online -> online.sendMessage(message));
    }

    private int countDetectionsWithin(Deque<Long> timestamps, long now, int windowSeconds) {
        long cutoff = now - (windowSeconds * 1000L);
        int count = 0;
        for (Long timestamp : timestamps) {
            if (timestamp >= cutoff) {
                count++;
            }
        }
        return count;
    }

    private static final class ActiveDamageReduction {
        private final double reductionPercent;
        private final long expiresAt;

        private ActiveDamageReduction(double reductionPercent, long expiresAt) {
            this.reductionPercent = reductionPercent;
            this.expiresAt = expiresAt;
        }
    }
}
