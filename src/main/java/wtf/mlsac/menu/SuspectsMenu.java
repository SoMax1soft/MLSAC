/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 */

package wtf.mlsac.menu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.Main;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.config.Config;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.server.AnalyticsClient;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SuspectsMenu implements Listener {
    private static final int ITEMS_PER_PAGE = 45;
    private static final Map<ClickType, String> CLICK_ACTION_KEYS = createClickActionKeys();

    private final JavaPlugin plugin;
    private final Player admin;
    private final Inventory inventory;
    private final AICheck aiCheck;
    private final AnalyticsClient analyticsClient;
    private final Config pluginConfig;
    private List<SuspectData> currentPageData = new ArrayList<>();
    private int page = 0;

    public SuspectsMenu(JavaPlugin plugin, Player admin) {
        this.plugin = plugin;
        this.admin = admin;
        Main main = (Main) plugin;
        this.aiCheck = main.getAiCheck();
        this.analyticsClient = main.getAnalyticsClient();
        this.pluginConfig = main.getPluginConfig();
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

        SchedulerManager.getAdapter().runAsync(() -> {
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
            List<SuspectData> pageData = suspectDataList.subList(start, end);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (SuspectData data : pageData) {
                if (analyticsClient != null) {
                    futures.add(analyticsClient.checkPlayer(data.name).thenAccept(result -> {
                        if (result.isFound()) {
                            data.analyticsDetections = result.getTotalDetections();
                            data.analyticsFound = true;
                        }
                    }));
                }
            }

            int totalPagesFinal = totalPages;
            int endFinal = end;
            int totalSuspectsFinal = suspectDataList.size();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> SchedulerManager
                    .getAdapter().runSync(() -> renderPage(pageData, totalPagesFinal, endFinal, totalSuspectsFinal)));
        });
    }

    private SuspectData mapSuspectData(Player player) {
        AIPlayerData data = aiCheck.getPlayerData(player.getUniqueId());
        if (data == null || data.getProbabilityHistory().isEmpty()) {
            return null;
        }
        return new SuspectData(player.getUniqueId(), player.getName(), data.getAverageProbability(),
                new ArrayList<>(data.getProbabilityHistory()));
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

        String detections = data.analyticsFound
                ? pluginConfig.getDetectionColor(data.analyticsDetections) + data.analyticsDetections
                : "&7N/A";

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
        return color + String.format("%.2f", value) + "&r";
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) {
            return;
        }
        event.setCancelled(true);

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

        executeConfiguredActions(event.getClick(), suspectData, target, config);
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

    private void executeConfiguredActions(ClickType clickType, SuspectData suspectData, Player target,
            FileConfiguration config) {
        String key = CLICK_ACTION_KEYS.get(clickType);
        if (key == null) {
            return;
        }

        List<String> actions = config.getStringList("gui.actions." + key);
        if (actions.isEmpty()) {
            return;
        }

        String detections = suspectData.analyticsFound ? String.valueOf(suspectData.analyticsDetections) : "N/A";
        for (String rawAction : actions) {
            executeAction(rawAction, suspectData, target, detections);
        }
    }

    private void executeAction(String rawAction, SuspectData suspectData, Player target, String detections) {
        if (rawAction == null || rawAction.trim().isEmpty()) {
            return;
        }

        String action = rawAction.trim();
        String lowerAction = action.toLowerCase(Locale.ROOT);

        if (lowerAction.equals("[close]") || lowerAction.equals("close")) {
            admin.closeInventory();
            return;
        }
        if (lowerAction.startsWith("[message]")) {
            admin.sendMessage(ColorUtil.colorize(applyClickPlaceholders(action.substring(9).trim(), suspectData, target,
                    detections)));
            return;
        }
        if (lowerAction.startsWith("[teleport]")) {
            admin.teleport(target);
            return;
        }
        if (lowerAction.startsWith("[gamemode]")) {
            String modeName = action.substring(10).trim();
            try {
                admin.setGameMode(GameMode.valueOf(modeName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid suspects menu gamemode: " + modeName);
            }
            return;
        }
        if (lowerAction.startsWith("[console]")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    applyClickPlaceholders(action.substring(9).trim(), suspectData, target, detections));
            return;
        }
        if (lowerAction.startsWith("[player]") || lowerAction.startsWith("[admin]")) {
            int startIndex = lowerAction.startsWith("[player]") ? 8 : 7;
            admin.performCommand(applyClickPlaceholders(action.substring(startIndex).trim(), suspectData, target,
                    detections));
            return;
        }

        admin.sendMessage(ColorUtil.colorize(((Main) plugin).getMessagesConfig()
                .getMessage("suspects-invalid-action", "{ACTION}", action)));
    }

    private String applyPlaceholders(String input, SuspectData data, String detections) {
        return input
                .replace("{PLAYER}", data.name)
                .replace("{AVG_PROB}", getColorInfo(data.avgProbability))
                .replace("{HISTORY_SIZE}", String.valueOf(data.history.size()))
                .replace("{DETECTIONS}", detections);
    }

    private String applyClickPlaceholders(String input, SuspectData data, Player target, String detections) {
        return input
                .replace("{PLAYER}", target.getName())
                .replace("{TARGET}", target.getName())
                .replace("{ADMIN}", admin.getName())
                .replace("{AVG_PROB}", String.format("%.2f", data.avgProbability))
                .replace("{DETECTIONS}", detections);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory() == inventory) {
            HandlerList.unregisterAll(this);
        }
    }

    private static Map<ClickType, String> createClickActionKeys() {
        Map<ClickType, String> keys = new EnumMap<>(ClickType.class);
        keys.put(ClickType.LEFT, "left-click");
        keys.put(ClickType.RIGHT, "right-click");
        keys.put(ClickType.SHIFT_LEFT, "shift-left-click");
        keys.put(ClickType.SHIFT_RIGHT, "shift-right-click");
        keys.put(ClickType.MIDDLE, "middle-click");
        return Collections.unmodifiableMap(keys);
    }

    private static final class SuspectData {
        private final UUID uuid;
        private final String name;
        private final double avgProbability;
        private final List<Double> history;
        private volatile int analyticsDetections;
        private volatile boolean analyticsFound;

        private SuspectData(UUID uuid, String name, double avgProbability, List<Double> history) {
            this.uuid = uuid;
            this.name = name;
            this.avgProbability = avgProbability;
            this.history = history;
        }
    }
}
