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

package wtf.mlsac.vision;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtf.mlsac.Main;
import wtf.mlsac.Permissions;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.compat.LiteBansCompat;
import wtf.mlsac.server.IAIClient;
import wtf.mlsac.util.ColorUtil;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the server's copy of the MLS VISION watch list fresh and tells staff when it grows.
 *
 * <p>Polled once a minute rather than pushed: the verdict only changes when the hourly analysis
 * runs, so a minute of lag costs nothing, and polling means no inbound connection to the game
 * server. Nothing is written to disk — the list lives in memory and is rebuilt on every restart,
 * same as everything else MLS VISION touches.
 */
public final class VisionWatchList {
    private static final long POLL_INTERVAL_TICKS = 1200L; // 60s
    private static final long FIRST_POLL_DELAY_TICKS = 200L;

    private final Main plugin;
    private final LiteBansCompat liteBans;
    private volatile ScheduledTask pollTask;
    private volatile List<FlaggedPlayer> flagged = Collections.emptyList();
    private final Set<String> announced = new HashSet<>();
    private volatile boolean primed;
    private volatile long lastBanSweepAt;

    public VisionWatchList(Main plugin) {
        this.plugin = plugin;
        this.liteBans = new LiteBansCompat(plugin.getLogger());
        plugin.getLogger().info("[VISION] Ban source: " + (liteBans.isAvailable() ? "LiteBans" : "vanilla ban list"));
    }

    public LiteBansCompat getLiteBans() {
        return liteBans;
    }

    public void start() {
        if (pollTask != null) return;
        pollTask = SchedulerManager.getAdapter()
                .runAsyncRepeating(this::poll, FIRST_POLL_DELAY_TICKS, POLL_INTERVAL_TICKS);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    /** The full watch list, newest verdict first. */
    public List<FlaggedPlayer> getFlagged() {
        return flagged;
    }

    /** Only the flagged players actually on the server right now — what the menu shows. */
    public List<FlaggedPlayer> getOnlineFlagged() {
        List<FlaggedPlayer> online = new java.util.ArrayList<>();
        for (FlaggedPlayer player : flagged) {
            Player target = Bukkit.getPlayerExact(player.playerName);
            if (target != null && target.isOnline()) {
                online.add(player);
            }
        }
        return online;
    }

    private void poll() {
        if (plugin.getAiClientProvider() == null) return;
        IAIClient client = plugin.getAiClientProvider().get();
        if (client == null || !client.isConnected()) return;

        client.fetchFlaggedPlayers().thenAccept(list -> {
            if (list == null) return;
            flagged = list;
            sweepActiveBans();
            reportBanState(list);
            SchedulerManager.getAdapter().runSync(() -> announceNew(list));
        });
    }

    /**
     * Sends back what the ban plugin actually says about each flagged player.
     *
     * <p>The backend only knows that MLSAC once ran a punishment command, which is not the same as
     * the player being banned now — the ban may have expired or been lifted, and a verdict built on
     * the old assumption reads "banned by the server" about someone who is playing right now. This
     * is the authoritative answer, and it is gathered here because the flagged list is exactly the
     * set of players the model judges, most of whom are never online to be checked any other way.
     */
    private void reportBanState(List<FlaggedPlayer> list) {
        if (plugin.getVisionEventSender() == null) return;

        for (FlaggedPlayer player : list) {
            try {
                java.util.UUID uuid = resolveUuid(player.playerName);
                if (uuid == null) continue;

                LiteBansCompat.BanRecord ban = liteBans.getActiveBan(uuid, player.playerName, null);
                boolean banned = ban != null || liteBans.isBanned(uuid, player.playerName, null);

                plugin.getVisionEventSender().queue(VisionEvent.banState(
                        player.playerName,
                        banned,
                        ban != null ? ban.reason : null,
                        ban != null ? ban.bannedBy : null,
                        ban != null ? ban.removedBy : null,
                        ban != null && ban.permanent,
                        primaryGroup(player.playerName)));
            } catch (Exception e) {
                if (plugin.getPluginConfig() != null && plugin.getPluginConfig().isDebug()) {
                    plugin.getLogger().warning("[VISION] Ban state failed for " + player.playerName
                            + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Pushes the server's whole active ban list, every ten minutes.
     *
     * <p>Checking only the flagged players is not enough on its own: before any analysis has run
     * that list is empty, and the accounts that matter most are banned and therefore never online
     * to be noticed any other way. One query to LiteBans covers all of them at once.
     */
    private void sweepActiveBans() {
        long now = System.currentTimeMillis();
        if (now - lastBanSweepAt < 10 * 60 * 1000L) return;
        if (!liteBans.isAvailable()) {
            plugin.getLogger().info("[VISION] Ban sweep skipped: LiteBans not available");
            lastBanSweepAt = now;
            return;
        }
        if (plugin.getVisionEventSender() == null) {
            plugin.getLogger().info("[VISION] Ban sweep skipped: sender not started");
            return;
        }
        lastBanSweepAt = now;

        List<LiteBansCompat.ActiveBan> bans = liteBans.getAllActiveBans(2000);
        plugin.getLogger().info("[VISION] Ban sweep: LiteBans returned " + bans.size() + " active ban(s)");
        for (LiteBansCompat.ActiveBan entry : bans) {
            plugin.getVisionEventSender().queue(VisionEvent.banState(
                    entry.playerName, true, entry.ban.reason, entry.ban.bannedBy, null,
                    entry.ban.permanent, null));
        }
        plugin.getLogger().info("[VISION] Queued " + bans.size() + " ban_state event(s) for upload");
    }

    /** Local user cache only — never a Mojang lookup, this runs off the main thread in a loop. */
    private java.util.UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline != null && offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    /**
     * The player's rank, when a permissions plugin can tell us. Staff rank explains a lot of
     * otherwise odd behaviour, so it is worth passing on.
     *
     * <p>Vault is reached by reflection: it is optional at runtime and not a compile dependency.
     */
    private String primaryGroup(String name) {
        try {
            Player online = Bukkit.getPlayerExact(name);
            if (online == null) return null;
            Class<?> permission = Class.forName("net.milkbowl.vault.permission.Permission");
            Object registration = Bukkit.getServicesManager().getRegistration(permission);
            if (registration == null) return null;
            Object provider = registration.getClass().getMethod("getProvider").invoke(registration);
            if (provider == null) return null;
            return (String) provider.getClass()
                    .getMethod("getPrimaryGroup", org.bukkit.entity.Player.class)
                    .invoke(provider, online);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Alerts staff about players added since the last poll.
     *
     * <p>The first poll after start only records what is already there. Announcing it would spam
     * every admin with the whole backlog on every server restart.
     */
    private void announceNew(List<FlaggedPlayer> list) {
        if (!primed) {
            for (FlaggedPlayer player : list) {
                announced.add(player.playerName.toLowerCase(java.util.Locale.ROOT));
            }
            primed = true;
            return;
        }

        for (FlaggedPlayer player : list) {
            String key = player.playerName.toLowerCase(java.util.Locale.ROOT);
            if (!announced.add(key)) continue;

            String message = plugin.getMessagesConfig().getReportPrefix()
                    + plugin.getMessagesConfig().getMessage("vision-alert",
                            "{PLAYER}", player.playerName, "{VERDICT}", player.verdict);
            String colorized = ColorUtil.colorize(message);

            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(Permissions.ADMIN) || staff.hasPermission(Permissions.ALERTS)) {
                    staff.sendMessage(colorized);
                }
            }
            plugin.getLogger().info("[VISION] " + player.playerName + " — " + player.verdict);
        }
    }
}
