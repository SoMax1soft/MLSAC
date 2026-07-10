package wtf.mlsac.response;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import wtf.mlsac.Main;
import wtf.mlsac.config.Config;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DetectionResponseManager {
    private final Main plugin;
    private final Map<UUID, ActiveDamageReduction> damageReductions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> trollCooldowns = new ConcurrentHashMap<>();
    private final Map<String, TrollAction> trollActionHandlers = new HashMap<>();
    private Config config;

    public DetectionResponseManager(Main plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        trollActionHandlers.put("shuffle_inventory", (player, action) -> {
            shuffleInventory(player);
            return true;
        });
        trollActionHandlers.put("drop_weapon", this::dropWeapon);
        trollActionHandlers.put("launch", this::launch);
    }

    @FunctionalInterface
    private interface TrollAction {
        /** @return {@code true} if the action actually ran (so cooldown/message should apply) */
        boolean apply(Player player, Config.TrollActionConfig action);
    }

    public void setConfig(Config config) {
        this.config = config;
        this.damageReductions.clear();
        this.trollCooldowns.clear();
    }

    /**
     * Drives damage-reduction and troll triggers off the player's current violation buffer.
     * Each configured stage / action fires once per upward crossing of its absolute buffer threshold
     * — see {@link AIPlayerData#consumeBufferCrossing(String, double)} for the edge-detection contract.
     */
    public void onBufferUpdated(Player player, AIPlayerData data, double probability) {
        if (player == null || !player.isOnline() || data == null || !config.isAlertResponsesEnabled()) {
            return;
        }
        boolean damageReduction = config.isDamageReductionEnabled();
        boolean troll = config.isTrollEnabled();
        if (!damageReduction && !troll) {
            return;
        }

        long now = System.currentTimeMillis();
        double buffer = data.getBuffer();

        if (damageReduction) {
            for (Config.DamageReductionStage stage : config.getDamageReductionStages()) {
                String key = "dr-" + stage.getBufferThreshold();
                if (data.consumeBufferCrossing(key, stage.getBufferThreshold())) {
                    applyDamageReductionStage(player, stage, now);
                }
            }
        }

        if (troll) {
            for (Config.TrollActionConfig action : config.getTrollActions()) {
                String key = "troll-" + action.getType() + "-" + action.getBufferThreshold();
                if (!data.consumeBufferCrossing(key, action.getBufferThreshold())) {
                    continue;
                }
                if (!isCooldownReady(player.getUniqueId(), action, now)) {
                    continue;
                }
                if (executeTrollAction(player, action)) {
                    markCooldown(player.getUniqueId(), action, now);
                    sendTrollMessage(player, action, buffer, probability);
                }
            }
        }
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

    public void handlePlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        damageReductions.remove(playerId);
        trollCooldowns.remove(playerId);
    }

    private void applyDamageReductionStage(Player player, Config.DamageReductionStage stage, long now) {
        long expiresAt = now + (stage.getDurationSeconds() * 1000L);
        ActiveDamageReduction current = damageReductions.get(player.getUniqueId());
        if (current == null
                || stage.getReductionPercent() > current.reductionPercent
                || expiresAt > current.expiresAt) {
            damageReductions.put(player.getUniqueId(),
                    new ActiveDamageReduction(stage.getReductionPercent(), expiresAt));
            plugin.debug("[Responses] Damage reduction applied to " + player.getName()
                    + ": " + stage.getReductionPercent() + "% for " + stage.getDurationSeconds()
                    + "s at buffer >= " + stage.getBufferThreshold());
        }
    }

    private boolean executeTrollAction(Player player, Config.TrollActionConfig action) {
        TrollAction handler = trollActionHandlers.get(action.getType().toLowerCase(Locale.ROOT));
        if (handler == null) {
            plugin.getLogger().warning("Unknown troll action type: " + action.getType());
            return false;
        }
        return handler.apply(player, action);
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

    private boolean launch(Player player, Config.TrollActionConfig action) {
        // Shove the player backwards (negative look direction) and up, reusing the velocity knobs.
        Vector direction = player.getLocation().getDirection().normalize().multiply(-action.getHorizontalVelocity());
        direction.setY(action.getVerticalVelocity());
        player.setVelocity(direction);
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
        trollCooldowns.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(action.getType().toLowerCase(Locale.ROOT), now);
    }

    private void sendTrollMessage(Player player, Config.TrollActionConfig action, double buffer, double probability) {
        String template = action.getMessage();
        if (template == null || template.trim().isEmpty()) {
            return;
        }
        String bufferStr = String.format(Locale.ROOT, "%.1f", buffer);
        // {DETECTIONS} kept as alias for {BUFFER} so legacy message templates don't go dark.
        String message = ColorUtil.colorize(template
                .replace("{PLAYER}", player.getName())
                .replace("{BUFFER}", bufferStr)
                .replace("{DETECTIONS}", bufferStr)
                .replace("{PROBABILITY}", String.format(Locale.ROOT, "%.2f", probability)));
        if (plugin.getAlertManager() != null) {
            plugin.getAlertManager().sendMessageToPermittedPlayers(message, null);
        }
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
