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
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.Main;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class SuspectsMenu implements Listener {
    private static final int ITEMS_PER_PAGE = 45;

    private final JavaPlugin plugin;
    private final Player admin;
    private final Inventory inventory;
    private final AICheck aiCheck;
    private final MenuActionDispatcher actionDispatcher;
    private List<SuspectData> currentPageData = new ArrayList<>();
    private int page = 0;

    public SuspectsMenu(JavaPlugin plugin, Player admin) {
        this.plugin = plugin;
        this.admin = admin;
        Main main = (Main) plugin;
        this.aiCheck = main.getAiCheck();
        this.actionDispatcher = new MenuActionDispatcher(main, admin);
        FileConfiguration config = main.getMenuConfig().getConfig();
        String title = config.getString("gui.title", "&cMLSAC &8> &7Suspects");
        this.inventory = Bukkit.createInventory(null, 54, ColorUtil.colorize(title));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open() {
        updateInventory();
        admin.openInventory(inventory);
    }

    private void updateInventory() {
        inventory.clear();

        ItemStack loading = new ItemStack(Material.SUNFLOWER);
        ItemMeta loadingMeta = loading.getItemMeta();
        if (loadingMeta != null) {
            loadingMeta.setDisplayName(ColorUtil.colorize("&eLoading suspects..."));
            loading.setItemMeta(loadingMeta);
        }
        inventory.setItem(22, loading);

        SchedulerManager.getAdapter().runEntitySync(admin, () -> {
            if (!admin.isOnline()) {
                return;
            }
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            List<SuspectData> suspectDataList = onlinePlayers.stream()
                    .map(this::mapSuspectData)
                    .filter(data -> data != null)
                    .sorted((first, second) -> Double.compare(second.avgProbability, first.avgProbability))
                    .collect(Collectors.toList());

            int totalPages = (int) Math.ceil((double) suspectDataList.size() / ITEMS_PER_PAGE);
            page = normalizePage(page, totalPages);

            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, suspectDataList.size());
            List<SuspectData> pageData = new ArrayList<>(suspectDataList.subList(start, end));
            renderPage(pageData, totalPages, end, suspectDataList.size());
        });
    }

    private SuspectData mapSuspectData(Player player) {
        AIPlayerData data = aiCheck.getPlayerData(player.getUniqueId());
        if (data == null) {
            return null;
        }
        List<Double> history = data.getProbabilityHistory();
        if (history.isEmpty()) {
            return null;
        }
        return new SuspectData(player.getUniqueId(), player.getName(), data.getAverageProbability(),
                new ArrayList<>(history));
    }

    private int normalizePage(int requestedPage, int totalPages) {
        if (totalPages <= 0) {
            return 0;
        }
        if (requestedPage < 0) {
            return 0;
        }
        return Math.min(requestedPage, totalPages - 1);
    }

    private void renderPage(List<SuspectData> pageData, int totalPages, int currentEnd, int totalSuspects) {
        inventory.clear();
        currentPageData = new ArrayList<>(pageData);
        FileConfiguration config = ((Main) plugin).getMenuConfig().getConfig();

        for (int slot = 0; slot < pageData.size(); slot++) {
            inventory.setItem(slot, createSuspectHead(pageData.get(slot), config));
        }

        if (page > 0) {
            Material previousMaterial = Material.valueOf(config.getString("gui.items.previous_page.material", "ARROW"));
            String previousName = config.getString("gui.items.previous_page.name", "&ePrevious Page (&f{PAGE}&e)");
            inventory.setItem(45, createButtonItem(previousMaterial, previousName.replace("{PAGE}", String.valueOf(page))));
        }

        Material infoMaterial = Material.valueOf(config.getString("gui.items.page_info.material", "PAPER"));
        String infoName = config.getString("gui.items.page_info.name", "&bPage &f{CURRENT} &7/ &f{TOTAL}");
        inventory.setItem(49, createButtonItem(infoMaterial, infoName
                .replace("{CURRENT}", String.valueOf(page + 1))
                .replace("{TOTAL}", String.valueOf(Math.max(1, totalPages)))));

        if (currentEnd < totalSuspects) {
            Material nextMaterial = Material.valueOf(config.getString("gui.items.next_page.material", "ARROW"));
            String nextName = config.getString("gui.items.next_page.name", "&eNext Page (&f{PAGE}&e)");
            inventory.setItem(53,
                    createButtonItem(nextMaterial, nextName.replace("{PAGE}", String.valueOf(page + 2))));
        }

        Material fillerMaterial = Material
                .valueOf(config.getString("gui.items.filler.material", "GRAY_STAINED_GLASS_PANE"));
        String fillerName = config.getString("gui.items.filler.name", " ");
        ItemStack filler = createButtonItem(fillerMaterial, fillerName);
        for (int slot = 45; slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private ItemStack createSuspectHead(SuspectData data, FileConfiguration config) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) {
            return head;
        }

        String nameFormat = config.getString("gui.items.suspect_head.name", "&c{PLAYER}");
        meta.setDisplayName(ColorUtil.colorize(nameFormat.replace("{PLAYER}", data.name)));

        List<String> loreFormat = config.getStringList("gui.items.suspect_head.lore");
        if (loreFormat.isEmpty()) {
            loreFormat = new ArrayList<>();
            loreFormat.add("&8&m------------------------");
            loreFormat.add("&7AVG Probability: {AVG_PROB}");
            loreFormat.add("&7DB Detections: {DETECTIONS}");
            loreFormat.add("&7History (Last {HISTORY_SIZE}):");
            loreFormat.add("{HISTORY}");
            loreFormat.add("&8&m------------------------");
            loreFormat.add("&eActions are configured in menu.yml");
        }

        StringBuilder historyBuilder = new StringBuilder();
        for (Double value : data.history) {
            historyBuilder.append(getColorInfo(value)).append(" ");
        }

        String detections = "&7N/A";

        List<String> lore = new ArrayList<>();
        for (String line : loreFormat) {
            lore.add(ColorUtil.colorize(applyPlaceholders(line, data, detections)
                    .replace("{HISTORY}", historyBuilder.toString().trim())));
        }

        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createButtonItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getColorInfo(double value) {
        ChatColor color = ChatColor.GREEN;
        if (value >= 0.9D) {
            color = ChatColor.DARK_RED;
        } else if (value >= 0.8D) {
            color = ChatColor.RED;
        } else if (value >= 0.6D) {
            color = ChatColor.GOLD;
        }
        return color + String.format(Locale.ROOT, "%.2f", value) + "&r";
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) {
            return;
        }
        event.setCancelled(true);

        // Act only on clicks inside the menu's top inventory. Clicks in the admin's own inventory
        // are cancelled above, but their getSlot() (0-35) overlaps suspect-head slots and would
        // otherwise trigger configured actions (teleport/ban/console) on an unintended suspect.
        if (event.getClickedInventory() != inventory) {
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        FileConfiguration config = ((Main) plugin).getMenuConfig().getConfig();
        if (handlePageButtons(event, item, config)) {
            return;
        }

        if (event.getSlot() < 0 || event.getSlot() >= currentPageData.size()) {
            return;
        }

        SuspectData suspectData = currentPageData.get(event.getSlot());
        Player target = Bukkit.getPlayer(suspectData.uuid);
        if (target == null || !target.isOnline()) {
            admin.sendMessage(ColorUtil.colorize(((Main) plugin).getMessagesConfig().getMessage("suspects-player-offline")));
            return;
        }

        MenuActionContext context = new MenuActionContext(target, suspectData.avgProbability, "N/A");
        actionDispatcher.runForClick(event.getClick(), context, config);
    }

    private boolean handlePageButtons(InventoryClickEvent event, ItemStack item, FileConfiguration config) {
        if (event.getSlot() == 45) {
            Material previousMaterial = Material.valueOf(config.getString("gui.items.previous_page.material", "ARROW"));
            if (item.getType() == previousMaterial && page > 0) {
                page--;
                updateInventory();
            }
            return true;
        }

        if (event.getSlot() == 53) {
            Material nextMaterial = Material.valueOf(config.getString("gui.items.next_page.material", "ARROW"));
            if (item.getType() == nextMaterial) {
                page++;
                updateInventory();
            }
            return true;
        }
        return false;
    }

    private String applyPlaceholders(String input, SuspectData data, String detections) {
        return input
                .replace("{PLAYER}", data.name)
                .replace("{AVG_PROB}", getColorInfo(data.avgProbability))
                .replace("{HISTORY_SIZE}", String.valueOf(data.history.size()))
                .replace("{DETECTIONS}", detections);
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

    private static final class SuspectData {
        private final UUID uuid;
        private final String name;
        private final double avgProbability;
        private final List<Double> history;

        private SuspectData(UUID uuid, String name, double avgProbability, List<Double> history) {
            this.uuid = uuid;
            this.name = name;
            this.avgProbability = avgProbability;
            this.history = history;
        }
    }
}
