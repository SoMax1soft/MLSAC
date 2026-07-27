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

package wtf.mlsac.report;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.data.AIPlayerData;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.server.IAIClient;
import wtf.mlsac.util.ColorUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Coordinates the player-report queue. The backend owns the queue (persisted per account); this
 * manager is a thin client that submits reports, fetches the active list for the GUI, applies
 * status transitions, and captures the cancellation reason from chat.
 */
public class ReportManager implements Listener {
    private static final long REPORT_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long REPORTS_CACHE_TTL_MS = 2500L;

    private final Main plugin;
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    // Admin UUID -> pending cancel info (reportId + targetName) awaiting reason in chat.
    private final ConcurrentHashMap<UUID, PendingCancel> pendingCancels = new ConcurrentHashMap<>();
    // Report ID -> timestamp (ms) when claimed
    private final ConcurrentHashMap<Integer, Long> claimTimestamps = new ConcurrentHashMap<>();

    private static final class PendingCancel {
        private final int reportId;
        private final String targetName;

        private PendingCancel(int reportId, String targetName) {
            this.reportId = reportId;
            this.targetName = targetName;
        }
    }
    // Short-lived queue cache shared by all admins on this server (see fetchReports).
    private volatile List<Report> cachedReports;
    private volatile long cachedAt;

    public ReportManager(Main plugin) {
        this.plugin = plugin;
    }

    private IAIClient client() {
        return plugin.getAiClientProvider() != null ? plugin.getAiClientProvider().get() : null;
    }

    private String prefix() {
        return ColorUtil.colorize(plugin.getMessagesConfig().getPrefix());
    }

    private String reportPrefix() {
        return ColorUtil.colorize(plugin.getMessagesConfig().getReportPrefix());
    }

    private String msg(String key, String... replacements) {
        return ColorUtil.colorize(plugin.getMessagesConfig().getMessage(key, replacements));
    }

    private void message(Player player, String key, String... replacements) {
        if (player != null && player.isOnline()) {
            player.sendMessage(reportPrefix() + msg(key, replacements));
        }
    }

    public boolean isCrossReportsEnabled() {
        return plugin.getPluginConfig().isCrossReportsEnabled();
    }

    // ── Submission (/report) ────────────────────────────────────────────────

    public void submitReport(Player reporter, String targetName, String reason) {
        if (reporter == null) {
            return;
        }
        if (reporter.getName().equalsIgnoreCase(targetName)) {
            message(reporter, "report-self");
            return;
        }

        long now = System.currentTimeMillis();
        Long readyAt = cooldowns.get(reporter.getUniqueId());
        if (readyAt != null && readyAt > now && !reporter.hasPermission(Permissions.ALERTS)) {
            long secondsLeft = (readyAt - now + 999) / 1000;
            message(reporter, "report-cooldown", "{SECONDS}", String.valueOf(secondsLeft));
            return;
        }

        IAIClient client = client();
        if (client == null || !client.isConnected()) {
            message(reporter, "report-failed");
            return;
        }

        // Capture a snapshot of the target's recent checks (only possible while they are online on
        // this server). Cross-server viewers rely on this stored snapshot.
        List<Double> checks = Collections.emptyList();
        String targetUuid = null;
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            targetUuid = target.getUniqueId().toString();
            AIPlayerData data = plugin.getAiCheck().getPlayerData(target.getUniqueId());
            if (data != null) {
                checks = data.getRecentChecks();
            }
        }

        cooldowns.put(reporter.getUniqueId(), now + REPORT_COOLDOWN_MS);
        UUID reporterId = reporter.getUniqueId();
        String reporterName = reporter.getName();
        String finalTargetName = targetName;

        client.submitReport(reporterId.toString(), reporterName, targetUuid, targetName, reason,
                checks, isCrossReportsEnabled())
                .thenAccept(success -> runSync(() -> {
                    Player online = Bukkit.getPlayer(reporterId);
                    if (Boolean.TRUE.equals(success)) {
                        invalidateReportsCache();
                        message(online, "report-submitted", "{PLAYER}", finalTargetName);
                        notifyStaff(reporterName, finalTargetName, reason);
                    } else {
                        cooldowns.remove(reporterId);
                        message(online, "report-failed");
                    }
                }));
    }

    private void notifyStaff(String reporterName, String targetName, String reason) {
        String line = reportPrefix() + msg("report-notify-staff",
                "{REPORTER}", reporterName, "{PLAYER}", targetName, "{REASON}", reason);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(Permissions.ALERTS) || staff.hasPermission(Permissions.ADMIN)) {
                staff.sendMessage(line);
            }
        }
    }

    public void notifyStaffCrossServer(String sourceServer, String reporterName, String targetName, String reason) {
        String currentServer = plugin.getPluginConfig().getServerIdentityName();
        if (sourceServer != null && sourceServer.equalsIgnoreCase(currentServer)) {
            return; // Already notified local staff on submission
        }
        String line = reportPrefix() + msg("report-notify-staff-cross",
                "{SERVER}", sourceServer != null ? sourceServer : "?",
                "{REPORTER}", reporterName != null ? reporterName : "Unknown",
                "{PLAYER}", targetName != null ? targetName : "Unknown",
                "{REASON}", reason != null ? reason : "");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(Permissions.ALERTS) || staff.hasPermission(Permissions.ADMIN)) {
                staff.sendMessage(line);
            }
        }
    }

    // ── Queue access (GUI) ──────────────────────────────────────────────────

    /**
     * Fetches the active reports, delivering the result to {@code callback} on the main thread. A
     * short-lived cache keeps rapid re-opens/page-flips instant: a fresh cache is served without any
     * network call, and a stale-but-present cache is shown immediately while a refresh runs.
     */
    public void fetchReports(Player admin, Consumer<List<Report>> callback) {
        IAIClient client = client();
        if (client == null) {
            callback.accept(Collections.emptyList());
            return;
        }

        long now = System.currentTimeMillis();
        List<Report> cached = cachedReports;
        if (cached != null && now - cachedAt < REPORTS_CACHE_TTL_MS) {
            callback.accept(new ArrayList<>(cached));
            return;
        }
        if (cached != null) {
            // Instant paint from the last snapshot; the fetch below refreshes it a moment later.
            callback.accept(new ArrayList<>(cached));
        }

        client.fetchReports(isCrossReportsEnabled()).thenAccept(reports -> {
            List<Report> safe = reports != null ? new ArrayList<>(reports) : new ArrayList<>();
            cachedReports = safe;
            cachedAt = System.currentTimeMillis();
            SchedulerManager.getAdapter().runEntitySync(admin, () -> {
                if (admin.isOnline()) {
                    callback.accept(new ArrayList<>(safe));
                }
            });
        }).exceptionally(ex -> {
            SchedulerManager.getAdapter().runEntitySync(admin, () -> {
                if (admin.isOnline() && cachedReports == null) {
                    callback.accept(Collections.emptyList());
                }
            });
            return null;
        });
    }

    /** Drops the cache so the next open reflects a just-applied change (claim/close/cancel/submit). */
    public void invalidateReportsCache() {
        cachedAt = 0L;
    }

    // ── Transitions (claim / close / cancel) ────────────────────────────────

    public boolean canClaimReport(Player admin, Report report) {
        if (report == null || admin == null) {
            return false;
        }
        if (report.getStatus() != ReportStatus.CLAIMED) {
            return true;
        }
        String handler = report.getHandlerName();
        if (handler == null || handler.isEmpty() || handler.equalsIgnoreCase(admin.getName())) {
            return true;
        }
        long claimTime = report.getClaimedAtMs();
        if (claimTime <= 0) {
            Long stored = claimTimestamps.get(report.getId());
            if (stored != null) {
                claimTime = stored;
            }
        }
        if (claimTime > 0) {
            long elapsed = System.currentTimeMillis() - claimTime;
            return elapsed >= 30 * 60 * 1000L; // 30 minutes
        }
        return false;
    }

    public void claimReport(Player admin, Report report, Runnable onDone) {
        claimTimestamps.put(report.getId(), System.currentTimeMillis());
        // A refused claim means another admin holds the report, so say that rather than the
        // generic failure — the backend rejects the takeover, this only reports it.
        transition(admin, report.getId(), ReportStatus.CLAIMED, null, "report-claimed",
                report.getTargetName(), onDone, "report-claimed-by-other",
                report.getHandlerName() != null ? report.getHandlerName() : "?");
    }

    /**
     * Starts watching the reported player: teleports the admin (spectator) to the target and keeps
     * them visible through invisibility. Only valid for local reports whose target is online here.
     */
    public void startWatch(Player admin, Report report) {
        if (!canClaimReport(admin, report)) {
            message(admin, "report-claimed-by-other", "{HANDLER}",
                    report.getHandlerName() != null ? report.getHandlerName() : "?");
            admin.closeInventory();
            return;
        }
        if (!wtf.mlsac.menu.SuspectsMenu.isReportLocal(report, plugin)) {
            String server = report.getServerName() != null && !report.getServerName().isEmpty()
                    ? report.getServerName() : "?";
            message(admin, "report-watch-cross-server", "{SERVER}", server);
            admin.closeInventory();
            return;
        }
        Player target = Bukkit.getPlayerExact(report.getTargetName());
        if (target == null || !target.isOnline()) {
            message(admin, "report-watch-offline", "{PLAYER}", report.getTargetName());
            admin.closeInventory();
            return;
        }
        admin.closeInventory();
        WatchService.startWatch(admin, target);
        message(admin, "report-watch-started", "{PLAYER}", target.getName());
        // Mark the report claimed in the background; the watch itself already started.
        IAIClient client = client();
        if (client != null && client.isConnected()) {
            client.updateReport(report.getId(), ReportStatus.CLAIMED, admin.getName(), null);
            invalidateReportsCache();
        }
    }

    public void closeReport(Player admin, Report report, Runnable onDone) {
        if (admin != null) {
            AdminReportModService.clear(admin.getUniqueId());
        }
        Player target = Bukkit.getPlayerExact(report.getTargetName());
        if (target != null) {
            AdminReportModService.clear(target.getUniqueId());
        }
        transition(admin, report.getId(), ReportStatus.CLOSED, null, "report-closed",
                report.getTargetName(), onDone);
    }

    private void transition(Player admin, int reportId, ReportStatus status, String cancelReason,
            String successKey, String targetName, Runnable onDone) {
        transition(admin, reportId, status, cancelReason, successKey, targetName, onDone,
                "report-action-failed", null);
    }

    private void transition(Player admin, int reportId, ReportStatus status, String cancelReason,
            String successKey, String targetName, Runnable onDone,
            String failureKey, String failureHandler) {
        IAIClient client = client();
        if (client == null || !client.isConnected()) {
            message(admin, "report-action-failed");
            return;
        }
        UUID adminId = admin.getUniqueId();
        client.updateReport(reportId, status, admin.getName(), cancelReason)
                .thenAccept(success -> runSync(() -> {
                    Player online = Bukkit.getPlayer(adminId);
                    if (Boolean.TRUE.equals(success)) {
                        invalidateReportsCache();
                        message(online, successKey, "{PLAYER}", targetName);
                        if (status == ReportStatus.CLOSED || status == ReportStatus.CANCELLED) {
                            notifyStaffClosed(admin.getName(), targetName, cancelReason);
                        }
                        if (onDone != null && online != null && online.isOnline()) {
                            onDone.run();
                        }
                    } else {
                        invalidateReportsCache();
                        if (failureHandler != null) {
                            message(online, failureKey, "{HANDLER}", failureHandler);
                        } else {
                            message(online, failureKey);
                        }
                    }
                }));
    }

    private void notifyStaffClosed(String adminName, String targetName, String reason) {
        String reasonText = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "-";
        String line = reportPrefix() + msg("report-notify-closed",
                "{ADMIN}", adminName != null ? adminName : "Staff",
                "{PLAYER}", targetName != null ? targetName : "Unknown",
                "{REASON}", reasonText);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(Permissions.ALERTS) || staff.hasPermission(Permissions.ADMIN)) {
                staff.sendMessage(line);
            }
        }
    }

    // ── Cancellation / Closing via chat prompt ───────────────────────────────

    public void beginCancelPrompt(Player admin, Report report) {
        pendingCancels.put(admin.getUniqueId(), new PendingCancel(report.getId(), report.getTargetName()));
        admin.closeInventory();
        message(admin, "report-cancel-prompt", "{PLAYER}", report.getTargetName());
    }

    public boolean hasPendingCancel(UUID adminId) {
        return pendingCancels.containsKey(adminId);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player admin = event.getPlayer();
        PendingCancel pending = pendingCancels.remove(admin.getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String reason = event.getMessage().trim();
        if (reason.isEmpty() || reason.equalsIgnoreCase("cancel") || reason.equalsIgnoreCase("отмена")) {
            message(admin, "report-cancel-aborted");
            return;
        }

        // Clean up any admin mod flags when closing/cancelling
        AdminReportModService.clear(admin.getUniqueId());
        Player target = Bukkit.getPlayerExact(pending.targetName);
        if (target != null) {
            AdminReportModService.clear(target.getUniqueId());
        }

        // Chat events run async; hop back onto the main thread before touching the API/messages.
        runSync(() -> transition(admin, pending.reportId, ReportStatus.CLOSED, reason, "report-closed",
                pending.targetName, null));
    }

    public void handlePlayerQuit(UUID playerId) {
        pendingCancels.remove(playerId);
        cooldowns.remove(playerId);
        AdminReportModService.clear(playerId);
    }

    public void cleanup() {
        pendingCancels.clear();
        cooldowns.clear();
    }

    private void runSync(Runnable runnable) {
        SchedulerManager.getAdapter().runSync(runnable);
    }
}
