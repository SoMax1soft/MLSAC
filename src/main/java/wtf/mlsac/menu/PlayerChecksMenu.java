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
import org.bukkit.Material;
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
import wtf.mlsac.Main;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A double-chest view of a single player's recent detection checks, rendered as colored stained
 * glass with the detection percentage on hover. Opened from the checks-mode suspects menu (RMB).
 */
public class PlayerChecksMenu implements Listener {
    private static final int MAX_CHECK_SLOTS = 45;
    private static final int BACK_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int EXIT_SLOT = 53;

    private final Main plugin;
    private final Player admin;
    private final UUID targetId;
    private final String targetName;
    private final Inventory inventory;

    public PlayerChecksMenu(Main plugin, Player admin, UUID targetId, String targetName) {
        this.plugin = plugin;
        this.admin = admin;
        this.targetId = targetId;
        this.targetName = targetName;
        FileConfiguration config = plugin.getMenuConfig().getConfig();
        String title = config.getString("gui.checks.title", "&cMLSAC &8> &7Checks: &f{PLAYER}")
                .replace("{PLAYER}", targetName);
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

        List<Double> checks = new ArrayList<>();
        AIPlayerData data = plugin.getAiCheck().getPlayerData(targetId);
        if (data != null) {
            checks = data.getRecentChecks();
        }

        // Newest checks first so the most recent detections sit at the top-left.
        int count = Math.min(checks.size(), MAX_CHECK_SLOTS);
        for (int i = 0; i < count; i++) {
            double value = checks.get(checks.size() - 1 - i);
            inventory.setItem(i, createCheckPane(config, value, i + 1));
        }

        if (count == 0) {
            inventory.setItem(22, createButton(Material.BARRIER,
                    config.getString("gui.checks.empty.name", "&7No checks recorded yet"), null));
        }

        inventory.setItem(BACK_SLOT, createButton(
                Material.valueOf(config.getString("gui.checks.back.material", "ARROW")),
                config.getString("gui.checks.back.name", "&eBack"), null));

        List<String> infoLore = new ArrayList<>();
        infoLore.add(ColorUtil.colorize(config.getString("gui.checks.info.lore",
                "&7Total checks: &f{COUNT}").replace("{COUNT}", String.valueOf(count))));
        inventory.setItem(INFO_SLOT, createButton(
                Material.valueOf(config.getString("gui.checks.info.material", "PAPER")),
                config.getString("gui.checks.info.name", "&bChecks: &f{PLAYER}").replace("{PLAYER}", targetName),
                infoLore));

        inventory.setItem(EXIT_SLOT, createButton(
                Material.valueOf(config.getString("gui.checks.exit.material", "BARRIER")),
                config.getString("gui.checks.exit.name", "&cExit"), null));

        Material fillerMaterial = Material.valueOf(config.getString("gui.items.filler.material",
                "GRAY_STAINED_GLASS_PANE"));
        ItemStack filler = createButton(fillerMaterial, config.getString("gui.items.filler.name", " "), null);
        for (int slot = MAX_CHECK_SLOTS; slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private ItemStack createCheckPane(FileConfiguration config, double value, int index) {
        ItemStack pane = new ItemStack(CheckDisplay.glass(value));
        ItemMeta meta = pane.getItemMeta();
        if (meta == null) {
            return pane;
        }
        meta.setDisplayName(ColorUtil.colorize(config.getString("gui.checks.pane.name", "&7Check &f#{INDEX}")
                .replace("{INDEX}", String.valueOf(index))));
        List<String> lore = new ArrayList<>();
        for (String line : config.getStringList("gui.checks.pane.lore")) {
            lore.add(ColorUtil.colorize(line.replace("{PERCENT}", CheckDisplay.percent(value))
                    .replace("{PROB}", CheckDisplay.format(value))));
        }
        if (lore.isEmpty()) {
            lore.add(ColorUtil.colorize("&7Detection: ") + CheckDisplay.percent(value));
        }
        meta.setLore(lore);
        pane.setItemMeta(meta);
        return pane;
    }

    private ItemStack createButton(Material material, String name, List<String> lore) {
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
        if (event.getClickedInventory() != inventory) {
            return;
        }
        int slot = event.getSlot();
        if (slot == BACK_SLOT) {
            HandlerList.unregisterAll(this);
            new SuspectsMenu(plugin, admin, SuspectsMenu.Mode.CHECKS).open();
        } else if (slot == EXIT_SLOT) {
            admin.closeInventory();
        }
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
