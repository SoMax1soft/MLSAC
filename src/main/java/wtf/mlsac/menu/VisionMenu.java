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
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.menu;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.inventory.meta.SkullMeta;
import wtf.mlsac.Main;
import wtf.mlsac.util.ColorUtil;
import wtf.mlsac.vision.FlaggedPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The /mlsac vision menu: everyone MLS VISION flagged who is online right now.
 *
 * <p>Online only, on purpose — this is the screen an admin opens to decide who to go look at, and
 * an offline suspect is not actionable. The full list, including offline players and the model's
 * reasoning, lives on the dashboard.
 */
public class VisionMenu implements Listener {
    private static final int SIZE = 54;

    private final Main plugin;
    private final Player admin;
    private final List<FlaggedPlayer> entries = new ArrayList<>();
    private Inventory inventory;

    public VisionMenu(Main plugin, Player admin) {
        this.plugin = plugin;
        this.admin = admin;
    }

    private String text(String path, String fallback) {
        FileConfiguration config = plugin.getMenuConfig().getConfig();
        return ColorUtil.colorize(config.getString("gui.vision." + path, fallback));
    }

    public void open() {
        entries.clear();
        entries.addAll(plugin.getVisionWatchList().getOnlineFlagged());

        inventory = Bukkit.createInventory(null, SIZE, text("title", "&8» &cMLS VISION"));
        render();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        admin.openInventory(inventory);
    }

    private void render() {
        inventory.clear();

        if (entries.isEmpty()) {
            ItemStack empty = new ItemStack(Material.PAPER);
            ItemMeta meta = empty.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(text("empty", "&7Никого из списка нет в сети"));
                empty.setItemMeta(meta);
            }
            inventory.setItem(22, empty);
            return;
        }

        int slot = 0;
        for (FlaggedPlayer entry : entries) {
            if (slot >= SIZE - 9) break;
            inventory.setItem(slot, buildHead(entry));
            slot += 1;
        }
    }

    private ItemStack buildHead(FlaggedPlayer entry) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        Player target = Bukkit.getPlayerExact(entry.playerName);
        if (target != null) {
            meta.setOwningPlayer(target);
        }

        String risk = entry.isConfirmed()
                ? text("risk-confirmed", "&cподтверждён")
                : text("risk-suspect", "&eподозрительный");
        meta.setDisplayName(ColorUtil.colorize((entry.isConfirmed() ? "&c" : "&e") + entry.playerName));

        List<String> lore = new ArrayList<>();
        lore.add(ColorUtil.colorize("&8▪ &7Статус &8» " + risk));
        lore.add(ColorUtil.colorize("&8▪ &7Доверие &8» &f" + entry.trustScore));
        lore.add(ColorUtil.colorize("&8▪ &7Наиграно &8» &f" + entry.playtimeHours + " ч."));
        if (!entry.involved.isEmpty()) {
            lore.add(ColorUtil.colorize("&8▪ &7Связан с &8» &f" + String.join(", ", entry.involved)));
        }
        lore.add("");
        for (String line : wrap(entry.verdict, 38)) {
            lore.add(ColorUtil.colorize("&7" + line));
        }
        lore.add("");
        lore.add(text("click-teleport", "&eНажми &8» &fтелепорт (GM3)"));
        meta.setLore(lore);

        head.setItemMeta(meta);
        return head;
    }

    /** Splits the verdict across lore lines so it does not run off the screen. */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= entries.size()) return;

        FlaggedPlayer entry = entries.get(slot);
        Player target = Bukkit.getPlayerExact(entry.playerName);
        if (target == null || !target.isOnline()) {
            admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig().getReportPrefix()
                    + plugin.getMessagesConfig().getMessage("report-watch-offline", "{PLAYER}", entry.playerName)));
            return;
        }

        admin.closeInventory();
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory() == inventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory() == inventory) {
            HandlerList.unregisterAll(this);
        }
    }
}
