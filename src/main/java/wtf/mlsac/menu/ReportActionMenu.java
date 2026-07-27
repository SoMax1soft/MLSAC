/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This file is based on GPLv3 licensed work and includes modifications.
 * Derived from:
 *   - Shard (© 2025 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 *   - Client-side project (GPLv3: https://github.com/MLSAC/client-side)
 *
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.menu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import wtf.mlsac.Main;
import wtf.mlsac.report.AdminReportModService;
import wtf.mlsac.report.Report;
import wtf.mlsac.report.WatchService;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Action menu for a claimed report.
 * Provides moderation controls:
 * 1. Teleport to player (GM3)
 * 2. Disable admin damage (0 damage output)
 * 3. Double damage on suspect (2x incoming damage)
 * 4. Player info icon (playtime & click to open checks)
 * 5. Close report button (prompts for reason in chat)
 */
public class ReportActionMenu implements Listener {
    private static final int TP_SLOT = 11;
    private static final int ZERO_DAMAGE_SLOT = 13;
    private static final int DOUBLE_DAMAGE_SLOT = 15;
    private static final int INFO_SLOT = 22;
    private static final int CLOSE_SLOT = 31;
    private static final int BACK_SLOT = 45;
    private static final int EXIT_SLOT = 53;

    private final Main plugin;
    private final Player admin;
    private final Report report;
    private final Inventory inventory;
    private boolean consumed;

    public ReportActionMenu(Main plugin, Player admin, Report report) {
        this.plugin = plugin;
        this.admin = admin;
        this.report = report;
        FileConfiguration config = plugin.getMenuConfig().getConfig();
        String handlerDisplay = report.getHandlerName() != null && !report.getHandlerName().isEmpty()
                ? report.getHandlerName() : "-";
        String title = config.getString("gui.report-actions.title", "&cMLSAC &8> &7Report: &f{PLAYER} &8(&7Handler: &f{HANDLER}&8)")
                .replace("{PLAYER}", report.getTargetName())
                .replace("{HANDLER}", handlerDisplay)
                .replace("{STATUS}", report.getStatus().name());
        this.inventory = Bukkit.createInventory(null, 54, ColorUtil.colorize(title));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open() {
        render();
        admin.openInventory(inventory);
    }

    private void render() {
        inventory.clear();
        FileConfiguration config = plugin.getMenuConfig().getConfig();

        // 1. Teleport button
        if (config.getBoolean("gui.report-actions.tp.enabled", true)) {
            inventory.setItem(TP_SLOT, button(
                    Material.valueOf(config.getString("gui.report-actions.tp.material", "COMPASS")),
                    config.getString("gui.report-actions.tp.name", "&aTeleport to player (GM3)"), null));
        }

        Player target = Bukkit.getPlayerExact(report.getTargetName());

        // 2. Zero damage toggle (disable player/suspect damage)
        if (config.getBoolean("gui.report-actions.zero-damage.enabled", true)) {
            boolean zeroDmgActive = target != null && AdminReportModService.isZeroDamage(target.getUniqueId());
            String zeroDmgState = zeroDmgActive ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED";
            List<String> zeroDmgLore = new ArrayList<>();
            for (String line : config.getStringList("gui.report-actions.zero-damage.lore")) {
                zeroDmgLore.add(ColorUtil.colorize(line));
            }
            inventory.setItem(ZERO_DAMAGE_SLOT, button(
                    Material.valueOf(config.getString("gui.report-actions.zero-damage.material", "SHIELD")),
                    config.getString("gui.report-actions.zero-damage.name", "&eDisable Player Damage: {STATE}")
                            .replace("{STATE}", zeroDmgState), zeroDmgLore));
        }

        // 3. Double damage toggle
        if (config.getBoolean("gui.report-actions.double-damage.enabled", true)) {
            boolean doubleDmgActive = target != null && AdminReportModService.isDoubleDamage(target.getUniqueId());
            String doubleDmgState = doubleDmgActive ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED";
            List<String> doubleDmgLore = new ArrayList<>();
            for (String line : config.getStringList("gui.report-actions.double-damage.lore")) {
                doubleDmgLore.add(ColorUtil.colorize(line));
            }
            inventory.setItem(DOUBLE_DAMAGE_SLOT, button(
                    Material.valueOf(config.getString("gui.report-actions.double-damage.material", "DIAMOND_SWORD")),
                    config.getString("gui.report-actions.double-damage.name", "&cDouble Damage to Suspect: {STATE}")
                            .replace("{STATE}", doubleDmgState), doubleDmgLore));
        }

        // 4. Local stats info head
        if (config.getBoolean("gui.report-actions.info.enabled", true)) {
            inventory.setItem(INFO_SLOT, createInfoHead(config, target));
        }

        // 5. Close report button
        if (config.getBoolean("gui.report-actions.close.enabled", true)) {
            inventory.setItem(CLOSE_SLOT, button(
                    Material.valueOf(config.getString("gui.report-actions.close.material", "LIME_DYE")),
                    config.getString("gui.report-actions.close.name", "&aClose report (enter reason in chat)"), null));
        }

        // Navigation
        inventory.setItem(BACK_SLOT, button(
                Material.valueOf(config.getString("gui.report-actions.back.material", "ARROW")),
                config.getString("gui.report-actions.back.name", "&eBack"), null));
        inventory.setItem(EXIT_SLOT, button(
                Material.valueOf(config.getString("gui.report-actions.exit.material", "BARRIER")),
                config.getString("gui.report-actions.exit.name", "&cExit"), null));

        Material fillerMaterial = Material.valueOf(config.getString("gui.items.filler.material",
                "GRAY_STAINED_GLASS_PANE"));
        ItemStack filler = button(fillerMaterial, config.getString("gui.items.filler.name", " "), null);
        for (int slot = 0; slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private ItemStack createInfoHead(FileConfiguration config, Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta) {
            SkullMeta skullMeta = (SkullMeta) meta;
            skullMeta.setDisplayName(ColorUtil.colorize(config.getString("gui.report-actions.info.name", "&c{PLAYER}")
                    .replace("{PLAYER}", report.getTargetName())));

            String playtimeStr = "Offline";
            if (target != null && target.isOnline()) {
                long playTimeTicks = getPlayTimeTicks(target);
                playtimeStr = formatPlayTime(playTimeTicks);
            }

            List<String> lore = new ArrayList<>();
            List<String> configuredLore = config.getStringList("gui.report-actions.info.lore");
            if (configuredLore.isEmpty()) {
                lore.add(ColorUtil.colorize("&7Playtime: &f" + playtimeStr));
                lore.add(ColorUtil.colorize("&7Status: &f" + (target != null && target.isOnline() ? "&aOnline" : "&cOffline")));
                lore.add(ColorUtil.colorize("&eClick to view player checks"));
            } else {
                for (String line : configuredLore) {
                    lore.add(ColorUtil.colorize(line
                            .replace("{PLAYTIME}", playtimeStr)
                            .replace("{STATUS}", target != null && target.isOnline() ? "&aOnline" : "&cOffline")));
                }
            }

            skullMeta.setLore(lore);
            head.setItemMeta(skullMeta);
        }
        return head;
    }

    private long getPlayTimeTicks(Player player) {
        try {
            return player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Throwable ignored) {
            try {
                // Fallback for older Spigot versions where it was named WALKING or PLAY_ONE_TICK
                return player.getStatistic(Statistic.valueOf("PLAY_ONE_TICK"));
            } catch (Throwable ignored2) {
                return 0L;
            }
        }
    }

    private String formatPlayTime(long ticks) {
        long seconds = ticks / 20L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || consumed) {
            return;
        }

        FileConfiguration config = plugin.getMenuConfig().getConfig();
        Player target = Bukkit.getPlayerExact(report.getTargetName());

        switch (event.getSlot()) {
            case TP_SLOT:
                if (!config.getBoolean("gui.report-actions.tp.enabled", true)) {
                    return;
                }
                if (!SuspectsMenu.isReportLocal(report, plugin)) {
                    String server = report.getServerName() != null && !report.getServerName().isEmpty()
                            ? report.getServerName() : "?";
                    admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                            .getMessage("report-watch-cross-server", "{SERVER}", server)));
                    return;
                }
                if (target == null || !target.isOnline()) {
                    admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                            .getMessage("report-watch-offline", "{PLAYER}", report.getTargetName())));
                    return;
                }
                admin.closeInventory();
                WatchService.startWatch(admin, target);
                admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                        .getMessage("report-watch-started", "{PLAYER}", target.getName())));
                break;

            case ZERO_DAMAGE_SLOT:
                if (!config.getBoolean("gui.report-actions.zero-damage.enabled", true)) {
                    return;
                }
                if (!SuspectsMenu.isReportLocal(report, plugin)) {
                    String server = report.getServerName() != null && !report.getServerName().isEmpty()
                            ? report.getServerName() : "?";
                    admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                            .getMessage("report-watch-cross-server", "{SERVER}", server)));
                    return;
                }
                if (target == null || !target.isOnline()) {
                    admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                            .getMessage("report-watch-offline", "{PLAYER}", report.getTargetName())));
                    return;
                }
                boolean newZeroState = AdminReportModService.toggleZeroDamage(target.getUniqueId());
                admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig().getReportPrefix()
                        + ColorUtil.colorize("&7Disable damage for &f" + target.getName() + "&7: "
                        + (newZeroState ? "&aENABLED" : "&cDISABLED"))));
                render();
                break;

            case DOUBLE_DAMAGE_SLOT:
                if (!config.getBoolean("gui.report-actions.double-damage.enabled", true)) {
                    return;
                }
                if (!SuspectsMenu.isReportLocal(report, plugin)) {
                    String server = report.getServerName() != null && !report.getServerName().isEmpty()
                            ? report.getServerName() : "?";
                    admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                            .getMessage("report-watch-cross-server", "{SERVER}", server)));
                    return;
                }
                if (target == null || !target.isOnline()) {
                    admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                            .getMessage("report-watch-offline", "{PLAYER}", report.getTargetName())));
                    return;
                }
                boolean newDoubleState = AdminReportModService.toggleDoubleDamage(target.getUniqueId());
                admin.sendMessage(ColorUtil.colorize("&cMLSAC &8> &7Double damage on " + target.getName() + ": "
                        + (newDoubleState ? "&aENABLED" : "&cDISABLED")));
                render();
                break;

            case INFO_SLOT:
                if (!config.getBoolean("gui.report-actions.info.enabled", true)) {
                    return;
                }
                UUID targetId = target != null ? target.getUniqueId() : null;
                if (targetId == null && report.getTargetUuid() != null) {
                    try {
                        targetId = UUID.fromString(report.getTargetUuid());
                    } catch (Exception ignored) {
                    }
                }
                if (targetId != null) {
                    consumed = true;
                    HandlerList.unregisterAll(this);
                    new PlayerChecksMenu(plugin, admin, targetId, report.getTargetName()).open();
                } else {
                    admin.sendMessage(ColorUtil.colorize("&cCannot view checks for unknown player UUID"));
                }
                break;

            case CLOSE_SLOT:
                if (!config.getBoolean("gui.report-actions.close.enabled", true)) {
                    return;
                }
                consumed = true;
                HandlerList.unregisterAll(this);
                plugin.getReportManager().beginCancelPrompt(admin, report);
                break;

            case BACK_SLOT:
                consumed = true;
                reopenReports();
                break;

            case EXIT_SLOT:
                admin.closeInventory();
                break;

            default:
                break;
        }
    }

    private void reopenReports() {
        HandlerList.unregisterAll(this);
        new SuspectsMenu(plugin, admin, SuspectsMenu.Mode.REPORTS).open();
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory() == inventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory() == inventory) {
            HandlerList.unregisterAll(this);
        }
    }
}
