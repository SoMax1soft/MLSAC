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
import org.bukkit.event.inventory.ClickType;
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
import wtf.mlsac.report.Report;
import wtf.mlsac.report.ReportStatus;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The staff overview GUI. It has two modes toggled from the bottom bar: <b>Reports</b> (the player
 * report queue, default) and <b>Checks</b> (online suspects sorted by detection probability). In
 * Checks mode left-click teleports to the player and right-click opens their detailed check view.
 */
public class SuspectsMenu implements Listener {
    /** Which view the menu is currently showing. */
    public enum Mode {
        REPORTS,
        CHECKS
    }

    private static final int ITEMS_PER_PAGE = 45;
    private static final int PREV_SLOT = 45;
    private static final int MODE_SLOT = 47;
    private static final int PAGE_INFO_SLOT = 49;
    private static final int REFRESH_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final Main plugin;
    private final Player admin;
    private final AICheck aiCheck;
    private Mode mode;
    private final Inventory inventory;

    private List<SuspectData> currentSuspects = new ArrayList<>();
    private List<Report> currentReports = new ArrayList<>();
    private int page = 0;
    // Incremented every render so a late async callback from a previous render/mode is ignored.
    private int renderToken = 0;

    public SuspectsMenu(JavaPlugin plugin, Player admin) {
        // Reports is the more useful landing tab, but it does not exist when the module is off.
        this((Main) plugin, admin,
                ((Main) plugin).getReportManager() != null ? Mode.REPORTS : Mode.CHECKS);
    }

    public SuspectsMenu(Main plugin, Player admin, Mode mode) {
        this.plugin = plugin;
        this.admin = admin;
        this.mode = plugin.getReportManager() == null ? Mode.CHECKS : mode;
        this.aiCheck = plugin.getAiCheck();
        FileConfiguration config = plugin.getMenuConfig().getConfig();
        String title = config.getString("gui.title", "&cMLSAC &8> &7Suspects");
        this.inventory = Bukkit.createInventory(null, 54, ColorUtil.colorize(title));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open() {
        updateInventory();
        admin.openInventory(inventory);
    }

    private FileConfiguration menu() {
        return plugin.getMenuConfig().getConfig();
    }

    private void updateInventory() {
        int token = ++renderToken;
        inventory.clear();
        setLoadingPlaceholder();

        if (mode == Mode.CHECKS) {
            SchedulerManager.getAdapter().runEntitySync(admin, () -> {
                if (!admin.isOnline() || token != renderToken) {
                    return;
                }
                List<SuspectData> suspects = Bukkit.getOnlinePlayers().stream()
                        .map(this::mapSuspectData)
                        .filter(data -> data != null)
                        .sorted((first, second) -> Double.compare(second.avgProbability, first.avgProbability))
                        .collect(Collectors.toList());
                currentSuspects = suspects;
                renderChecksPage();
            });
            return;
        }

        if (plugin.getReportManager() == null) {
            currentReports = new ArrayList<>();
            renderReportsPage();
            return;
        }
        // Reports mode: the queue lives on the backend, so fetch it asynchronously.
        plugin.getReportManager().fetchReports(admin, reports -> {
            if (!admin.isOnline() || token != renderToken) {
                return;
            }
            currentReports = reports;
            renderReportsPage();
        });
    }

    private void setLoadingPlaceholder() {
        ItemStack loading = new ItemStack(Material.SUNFLOWER);
        ItemMeta meta = loading.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(menu().getString("gui.loading", "&eLoading...")));
            loading.setItemMeta(meta);
        }
        inventory.setItem(22, loading);
    }

    // ── Checks mode ──────────────────────────────────────────────────────────

    private SuspectData mapSuspectData(Player player) {
        AIPlayerData data = aiCheck.getPlayerData(player.getUniqueId());
        if (data == null || !data.hasRecentChecks()) {
            return null;
        }
        // Only the average is needed to sort/list; the (up to 50) check history is fetched lazily for
        // the ~45 heads actually rendered, so a full server doesn't copy every player's window per open.
        return new SuspectData(player.getUniqueId(), player.getName(), data.getAverageProbability());
    }

    private void renderChecksPage() {
        inventory.clear();
        FileConfiguration config = menu();
        int totalPages = pageCount(currentSuspects.size());
        page = normalizePage(page, totalPages);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, currentSuspects.size());
        for (int slot = 0; slot < end - start; slot++) {
            inventory.setItem(slot, createSuspectHead(currentSuspects.get(start + slot), config));
        }
        renderBottomBar(config, totalPages, end < currentSuspects.size());
    }

    private ItemStack createSuspectHead(SuspectData data, FileConfiguration config) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) {
            return head;
        }
        meta.setDisplayName(ColorUtil.colorize(config.getString("gui.items.suspect_head.name", "&c{PLAYER}")
                .replace("{PLAYER}", data.name)));

        List<String> loreFormat = config.getStringList("gui.items.suspect_head.lore");
        if (loreFormat.isEmpty()) {
            loreFormat = defaultSuspectLore();
        }
        AIPlayerData live = aiCheck.getPlayerData(data.uuid);
        List<Double> history = live != null ? live.getRecentChecks() : java.util.Collections.emptyList();
        String historyText = CheckDisplay.history(lastN(history, 10));
        List<String> lore = new ArrayList<>();
        for (String line : loreFormat) {
            lore.add(ColorUtil.colorize(line
                    .replace("{PLAYER}", data.name)
                    .replace("{AVG_PROB}", CheckDisplay.format(data.avgProbability))
                    .replace("{DETECTIONS}", "&7N/A")
                    .replace("{HISTORY_SIZE}", String.valueOf(Math.min(history.size(), 10)))
                    .replace("{HISTORY}", historyText)));
        }
        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private List<String> defaultSuspectLore() {
        List<String> lore = new ArrayList<>();
        lore.add("&8&m------------------------");
        lore.add("&7AVG Probability: {AVG_PROB}");
        lore.add("&7History (Last {HISTORY_SIZE}):");
        lore.add("{HISTORY}");
        lore.add("&8&m------------------------");
        lore.add("&eLMB: Teleport to player");
        lore.add("&eRMB: View all checks");
        return lore;
    }

    // ── Reports mode ─────────────────────────────────────────────────────────

    private void renderReportsPage() {
        inventory.clear();
        FileConfiguration config = menu();
        int totalPages = pageCount(currentReports.size());
        page = normalizePage(page, totalPages);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, currentReports.size());
        for (int slot = 0; slot < end - start; slot++) {
            inventory.setItem(slot, createReportHead(config, currentReports.get(start + slot)));
        }

        if (currentReports.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BOOK);
            ItemMeta meta = empty.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ColorUtil.colorize(config.getString("gui.reports.empty.name",
                        "&7No active reports")));
                empty.setItemMeta(meta);
            }
            inventory.setItem(22, empty);
        }

        renderBottomBar(config, totalPages, end < currentReports.size());
    }

    private ItemStack createReportHead(FileConfiguration config, Report report) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta == null) {
            return head;
        }
        meta.setDisplayName(ColorUtil.colorize(config.getString("gui.report_head.name", "&c{PLAYER}")
                .replace("{PLAYER}", report.getTargetName())));
        meta.setLore(buildReportLore(config, plugin, report));
        head.setItemMeta(meta);
        return head;
    }

    /**
     * Builds the lore describing a report (reporter, reason, status, source server, and the colored
     * check history from the snapshot stored with the report). Shared with {@link ReportActionMenu};
     * kept snapshot-only so rendering the whole list stays off the per-player data path. The live
     * check window is shown in the per-report action menu grid instead.
     */
    public static boolean isReportLocal(Report report, Main plugin) {
        if (report == null) {
            return false;
        }
        Player target = Bukkit.getPlayerExact(report.getTargetName());
        if (target != null && target.isOnline()) {
            return true;
        }
        if (plugin != null && plugin.getPluginConfig() != null) {
            String currentServer = plugin.getPluginConfig().getServerIdentityName();
            String reportServer = report.getServerName();
            if (reportServer != null && !reportServer.isEmpty() && !reportServer.equalsIgnoreCase(currentServer)) {
                return false;
            }
            if ("default".equalsIgnoreCase(currentServer) && (target == null || !target.isOnline())) {
                return false;
            }
        }
        return report.isLocal();
    }

    public static List<String> buildReportLore(FileConfiguration config, Main plugin, Report report) {
        List<String> format = config.getStringList("gui.report_head.lore");
        if (format.isEmpty()) {
            format = defaultReportLore();
        }

        List<Double> checks = report.getChecks();
        String historyText = CheckDisplay.history(lastN(checks, 8));
        String serverLabel = report.getServerName() != null && !report.getServerName().isEmpty()
                ? report.getServerName()
                : "unknown";
        if (isReportLocal(report, plugin)) {
            serverLabel += " " + config.getString("gui.report_head.local-tag", "&8(this server)");
        }

        List<String> lore = new ArrayList<>();
        for (String line : format) {
            lore.add(ColorUtil.colorize(line
                    .replace("{PLAYER}", report.getTargetName())
                    .replace("{REPORTER}", report.getReporterName())
                    .replace("{REASON}", report.getReason())
                    .replace("{STATUS}", statusLabel(report.getStatus()))
                    .replace("{SERVER}", serverLabel)
                    .replace("{HANDLER}", report.getHandlerName() != null ? report.getHandlerName() : "-")
                    .replace("{CHECKS_SIZE}", String.valueOf(Math.min(checks.size(), 8)))
                    .replace("{CHECKS}", historyText)));
        }
        return lore;
    }

    private static List<String> defaultReportLore() {
        List<String> lore = new ArrayList<>();
        lore.add("&8&m------------------------");
        lore.add("&7Reporter: &f{REPORTER}");
        lore.add("&7Reason: &f{REASON}");
        lore.add("&7Status: {STATUS}");
        lore.add("&7Server: &f{SERVER}");
        lore.add("&7Last checks (&f{CHECKS_SIZE}&7):");
        lore.add("{CHECKS}");
        lore.add("&8&m------------------------");
        lore.add("&eClick to manage");
        return lore;
    }

    private static String statusLabel(ReportStatus status) {
        switch (status) {
            case CLAIMED:
                return ChatColor.YELLOW + "Claimed";
            case CLOSED:
                return ChatColor.GRAY + "Closed";
            case CANCELLED:
                return ChatColor.RED + "Cancelled";
            case OPEN:
            default:
                return ChatColor.GREEN + "Open";
        }
    }

    // ── Shared bottom bar ─────────────────────────────────────────────────────

    private void renderBottomBar(FileConfiguration config, int totalPages, boolean hasNext) {
        if (page > 0) {
            inventory.setItem(PREV_SLOT, button(
                    Material.valueOf(config.getString("gui.items.previous_page.material", "ARROW")),
                    config.getString("gui.items.previous_page.name", "&ePrevious Page (&f{PAGE}&e)")
                            .replace("{PAGE}", String.valueOf(page))));
        }
        if (hasNext) {
            inventory.setItem(NEXT_SLOT, button(
                    Material.valueOf(config.getString("gui.items.next_page.material", "ARROW")),
                    config.getString("gui.items.next_page.name", "&eNext Page (&f{PAGE}&e)")
                            .replace("{PAGE}", String.valueOf(page + 2))));
        }

        inventory.setItem(PAGE_INFO_SLOT, button(
                Material.valueOf(config.getString("gui.items.page_info.material", "PAPER")),
                config.getString("gui.items.page_info.name", "&bPage &f{CURRENT} &7/ &f{TOTAL}")
                        .replace("{CURRENT}", String.valueOf(page + 1))
                        .replace("{TOTAL}", String.valueOf(Math.max(1, totalPages)))));

        // Mode toggle: describes the mode you switch TO when clicked.
        boolean reports = mode == Mode.REPORTS;
        Material toggleMaterial = Material.valueOf(config.getString(
                reports ? "gui.mode.to-checks.material" : "gui.mode.to-reports.material",
                reports ? "COMPASS" : "WRITABLE_BOOK"));
        String toggleName = config.getString(
                reports ? "gui.mode.to-checks.name" : "gui.mode.to-reports.name",
                reports ? "&bMode: &fReports &7(click: Checks)" : "&bMode: &fChecks &7(click: Reports)");
        inventory.setItem(MODE_SLOT, button(toggleMaterial, toggleName));

        if (reports) {
            inventory.setItem(REFRESH_SLOT, button(
                    Material.valueOf(config.getString("gui.reports.refresh.material", "CLOCK")),
                    config.getString("gui.reports.refresh.name", "&aRefresh")));
        }

        Material fillerMaterial = Material.valueOf(config.getString("gui.items.filler.material",
                "GRAY_STAINED_GLASS_PANE"));
        ItemStack filler = button(fillerMaterial, config.getString("gui.items.filler.name", " "));
        for (int slot = 45; slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.colorize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── Clicks ────────────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory() != inventory) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        int slot = event.getSlot();
        if (handleControlClick(slot)) {
            return;
        }
        if (slot < 0 || slot >= ITEMS_PER_PAGE) {
            return;
        }

        int index = page * ITEMS_PER_PAGE + slot;
        if (mode == Mode.CHECKS) {
            handleSuspectClick(index, event.getClick());
        } else {
            handleReportClick(index);
        }
    }

    private void renderCurrentPage() {
        if (mode == Mode.CHECKS) {
            renderChecksPage();
        } else {
            renderReportsPage();
        }
    }

    private boolean handleControlClick(int slot) {
        switch (slot) {
            case PREV_SLOT:
                // Paginate from the already-fetched data — avoid a fresh backend round-trip per click.
                if (page > 0) {
                    page--;
                    renderCurrentPage();
                }
                return true;
            case NEXT_SLOT:
                page++;
                renderCurrentPage();
                return true;
            case MODE_SLOT:
                if (plugin.getReportManager() == null) {
                    return true; // Only one tab exists while the reports module is off.
                }
                mode = mode == Mode.REPORTS ? Mode.CHECKS : Mode.REPORTS;
                page = 0;
                updateInventory();
                return true;
            case REFRESH_SLOT:
                if (mode == Mode.REPORTS) {
                    updateInventory();
                    return true;
                }
                return false;
            case PAGE_INFO_SLOT:
                return true;
            default:
                return false;
        }
    }

    private void handleSuspectClick(int index, ClickType click) {
        if (index < 0 || index >= currentSuspects.size()) {
            return;
        }
        SuspectData suspect = currentSuspects.get(index);
        Player target = Bukkit.getPlayer(suspect.uuid);
        if (target == null || !target.isOnline()) {
            admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig()
                    .getMessage("suspects-player-offline")));
            return;
        }
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            HandlerList.unregisterAll(this);
            new PlayerChecksMenu(plugin, admin, suspect.uuid, suspect.name).open();
        } else {
            admin.closeInventory();
            admin.teleport(target);
        }
    }

    private void handleReportClick(int index) {
        if (index < 0 || index >= currentReports.size()) {
            return;
        }
        Report report = currentReports.get(index);

        if (!plugin.getReportManager().canClaimReport(admin, report)) {
            admin.sendMessage(ColorUtil.colorize(plugin.getMessagesConfig().getReportPrefix()
                    + plugin.getMessagesConfig().getMessage("report-claimed-by-other", "{HANDLER}",
                    report.getHandlerName() != null ? report.getHandlerName() : "Unknown")));
            return;
        }

        HandlerList.unregisterAll(this);
        // The action menu opens only once the backend has actually granted the claim. Opening it
        // straight away let a second admin walk into a report someone had just taken: the list they
        // clicked from is a couple of seconds stale, so it still showed the report as free.
        plugin.getReportManager().claimReport(admin, report,
                () -> new ReportActionMenu(plugin, admin, report).open());
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static int pageCount(int total) {
        return (int) Math.ceil((double) total / ITEMS_PER_PAGE);
    }

    private int normalizePage(int requestedPage, int totalPages) {
        if (totalPages <= 0 || requestedPage < 0) {
            return 0;
        }
        return Math.min(requestedPage, totalPages - 1);
    }

    private static List<Double> lastN(List<Double> values, int n) {
        if (values.size() <= n) {
            return values;
        }
        return values.subList(values.size() - n, values.size());
    }

    private static final class SuspectData {
        private final UUID uuid;
        private final String name;
        private final double avgProbability;

        private SuspectData(UUID uuid, String name, double avgProbability) {
            this.uuid = uuid;
            this.name = name;
            this.avgProbability = avgProbability;
        }
    }
}
