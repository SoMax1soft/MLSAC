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

package wtf.mlsac.compat;

import org.bukkit.BanList;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reads the real punishment state from LiteBans, falling back to the vanilla ban list.
 *
 * <p>Why this exists: MLSAC's own {@code detections} table only records that a punishment command
 * was <em>issued</em>. It says nothing about whether the player is banned right now — the ban may
 * have expired, been lifted, or the command may never have landed. Any verdict built on
 * "we banned them once" is guesswork, so the authoritative state is read from the ban plugin.
 *
 * <p>Accessed by reflection rather than a compileOnly dependency: LiteBans is optional at runtime,
 * its API artifact is not on a repository this project already trusts, and every call here is
 * cheap. Signatures were taken from the shipped {@code litebans/api} classes, not guessed.
 */
public final class LiteBansCompat {
    private final Logger logger;
    private final boolean available;

    private Method databaseGet;
    private Method isPlayerBanned;
    private Method getBan;
    private Method getUsersByIP;
    private Method prepareStatement;

    public LiteBansCompat(Logger logger) {
        this.logger = logger;
        this.available = resolve();
        if (available) {
            logger.info("[LiteBans] Integration enabled");
        }
    }

    private boolean resolve() {
        // Verbose on purpose while the integration beds in: a silent "not available" is impossible
        // to tell apart from "the plugin is missing", and both look identical from the outside.
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("LiteBans");
            if (plugin == null) {
                logger.info("[LiteBans] Plugin not found by name 'LiteBans'. Installed plugins: "
                        + describeInstalledPlugins());
                return false;
            }
            if (!plugin.isEnabled()) {
                logger.warning("[LiteBans] Plugin found but not enabled yet");
                return false;
            }

            Class<?> database = Class.forName("litebans.api.Database");
            databaseGet = database.getMethod("get");
            isPlayerBanned = database.getMethod("isPlayerBanned", UUID.class, String.class);
            getBan = database.getMethod("getBan", UUID.class, String.class, String.class);
            getUsersByIP = database.getMethod("getUsersByIP", String.class);
            prepareStatement = database.getMethod("prepareStatement", String.class);
            return true;
        } catch (ClassNotFoundException e) {
            logger.warning("[LiteBans] API class litebans.api.Database is not visible: " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            logger.warning("[LiteBans] API method missing, version mismatch: " + e.getMessage());
            return false;
        } catch (NoClassDefFoundError e) {
            logger.warning("[LiteBans] API class failed to load: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warning("[LiteBans] Integration failed to initialise: " + e.getMessage());
            return false;
        }
    }

    private static String describeInstalledPlugins() {
        StringBuilder names = new StringBuilder();
        for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (names.length() > 0) names.append(", ");
            names.append(plugin.getName());
        }
        return names.toString();
    }

    public boolean isAvailable() {
        return available;
    }

    private Object database() throws Exception {
        return databaseGet.invoke(null);
    }

    /**
     * Whether the player is banned at this moment.
     *
     * <p>Falls back to the vanilla ban list when LiteBans is absent, so servers without a ban
     * plugin still report something truthful instead of nothing.
     */
    public boolean isBanned(UUID uuid, String name, String ip) {
        if (available) {
            try {
                return Boolean.TRUE.equals(isPlayerBanned.invoke(database(), uuid, ip));
            } catch (Exception e) {
                logger.warning("[LiteBans] isPlayerBanned failed: " + e.getMessage());
            }
        }
        try {
            return name != null && Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
        } catch (Exception e) {
            return false;
        }
    }

    /** The active ban, or {@code null} when the player is not banned. */
    public BanRecord getActiveBan(UUID uuid, String name, String ip) {
        if (available) {
            try {
                Object entry = getBan.invoke(database(), uuid, ip, null);
                if (entry != null) {
                    return BanRecord.fromEntry(entry);
                }
            } catch (Exception e) {
                logger.warning("[LiteBans] getBan failed: " + e.getMessage());
            }
            return null;
        }
        try {
            if (name == null) return null;
            org.bukkit.BanEntry vanilla = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
            if (vanilla == null) return null;
            Date expires = vanilla.getExpiration();
            return new BanRecord(vanilla.getReason(), vanilla.getSource(), null, null,
                    vanilla.getCreated() != null ? vanilla.getCreated().getTime() : 0L,
                    expires != null ? expires.getTime() : 0L,
                    expires == null, true, false);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Full punishment history, newest first, straight out of the LiteBans database.
     *
     * <p>{@code {bans}} is a LiteBans placeholder for the configured table name, which is why the
     * query goes through its own {@code prepareStatement} rather than a connection of our own.
     */
    public List<BanRecord> getHistory(UUID uuid, int limit) {
        List<BanRecord> history = new ArrayList<>();
        if (!available) return history;
        try (java.sql.PreparedStatement statement = (java.sql.PreparedStatement) prepareStatement.invoke(
                database(),
                "SELECT reason, banned_by_name, removed_by_name, removal_reason, time, until, active, ipban "
                        + "FROM {bans} WHERE uuid = ? ORDER BY time DESC LIMIT " + Math.max(1, Math.min(limit, 50)))) {
            statement.setString(1, uuid.toString());
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long until = rows.getLong("until");
                    history.add(new BanRecord(
                            rows.getString("reason"),
                            rows.getString("banned_by_name"),
                            rows.getString("removed_by_name"),
                            rows.getString("removal_reason"),
                            rows.getLong("time"),
                            until,
                            until <= 0,
                            rows.getBoolean("active"),
                            rows.getBoolean("ipban")));
                }
            }
        } catch (Exception e) {
            logger.warning("[LiteBans] History query failed: " + e.getMessage());
        }
        return history;
    }

    /**
     * Every ban that is active right now, with the player name resolved.
     *
     * <p>Pulled as one query instead of asking per player: the accounts worth knowing about are
     * mostly banned, therefore never online, and there is no other way to enumerate them. This is
     * what bootstraps the ban picture before any analysis has run.
     */
    public List<ActiveBan> getAllActiveBans(int limit) {
        List<ActiveBan> bans = new ArrayList<>();
        if (!available) return bans;
        try {
            Object db = database();
            java.lang.reflect.Method getPlayerName = db.getClass().getMethod("getPlayerName", UUID.class);
            try (java.sql.PreparedStatement statement = (java.sql.PreparedStatement) prepareStatement.invoke(
                    db,
                    "SELECT uuid, reason, banned_by_name, until, ipban FROM {bans} "
                            + "WHERE active = 1 AND uuid IS NOT NULL ORDER BY time DESC LIMIT "
                            + Math.max(1, Math.min(limit, 2000)))) {
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String rawUuid = rows.getString("uuid");
                        if (rawUuid == null || rawUuid.isEmpty()) continue;
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(rawUuid);
                        } catch (IllegalArgumentException e) {
                            continue; // console or a console-issued IP ban has no real uuid
                        }
                        String name = (String) getPlayerName.invoke(db, uuid);
                        if (name == null || name.isEmpty()) continue;

                        long until = rows.getLong("until");
                        bans.add(new ActiveBan(name, new BanRecord(
                                rows.getString("reason"),
                                rows.getString("banned_by_name"),
                                null, null, 0L, until, until <= 0, true,
                                rows.getBoolean("ipban"))));
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[LiteBans] Active ban list query failed: " + e.getMessage());
        }
        return bans;
    }

    /** A resolved name paired with its ban. */
    public static final class ActiveBan {
        public final String playerName;
        public final BanRecord ban;

        ActiveBan(String playerName, BanRecord ban) {
            this.playerName = playerName;
            this.ban = ban;
        }
    }

    /** Accounts LiteBans has seen on the same address. Empty when LiteBans is absent. */
    @SuppressWarnings("unchecked")
    public List<String> getAccountsOnIp(String ip) {
        if (!available || ip == null || ip.isEmpty()) return java.util.Collections.emptyList();
        try {
            Object result = getUsersByIP.invoke(database(), ip);
            if (result instanceof java.util.Collection) {
                List<String> names = new ArrayList<>();
                for (Object entry : (java.util.Collection<Object>) result) {
                    if (entry != null) names.add(String.valueOf(entry));
                }
                return names;
            }
        } catch (Exception e) {
            logger.warning("[LiteBans] getUsersByIP failed: " + e.getMessage());
        }
        return java.util.Collections.emptyList();
    }

    /** One punishment, flattened out of LiteBans' {@code Entry} so nothing else needs reflection. */
    public static final class BanRecord {
        public final String reason;
        public final String bannedBy;
        public final String removedBy;
        public final String removalReason;
        public final long startedAt;
        public final long expiresAt;
        public final boolean permanent;
        public final boolean active;
        public final boolean ipBan;

        BanRecord(String reason, String bannedBy, String removedBy, String removalReason,
                long startedAt, long expiresAt, boolean permanent, boolean active, boolean ipBan) {
            this.reason = reason;
            this.bannedBy = bannedBy;
            this.removedBy = removedBy;
            this.removalReason = removalReason;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
            this.permanent = permanent;
            this.active = active;
            this.ipBan = ipBan;
        }

        static BanRecord fromEntry(Object entry) throws Exception {
            Class<?> type = entry.getClass();
            return new BanRecord(
                    (String) invoke(type, entry, "getReason"),
                    (String) invoke(type, entry, "getExecutorName"),
                    (String) invoke(type, entry, "getRemovedByName"),
                    (String) invoke(type, entry, "getRemovalReason"),
                    (Long) invoke(type, entry, "getDateStart"),
                    (Long) invoke(type, entry, "getDateEnd"),
                    Boolean.TRUE.equals(invoke(type, entry, "isPermanent")),
                    Boolean.TRUE.equals(invoke(type, entry, "isActive")),
                    Boolean.TRUE.equals(invoke(type, entry, "isIpban")));
        }

        private static Object invoke(Class<?> type, Object target, String name) throws Exception {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        }
    }
}
