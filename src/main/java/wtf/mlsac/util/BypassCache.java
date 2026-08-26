/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.util;

import org.bukkit.entity.Player;

import wtf.mlsac.Permissions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTL cache for the {@code mlsac.bypass} permission lookups.
 *
 * <p>Bypass is consulted per player per tick — and, in the anti-ESP engine, per viewer/target pair —
 * so hitting the permission plugin every time would be its own performance problem. Results are
 * cached for a few seconds; {@link #invalidate(UUID)} covers rank changes that need to apply now.
 */
public final class BypassCache {

    private static final long TTL_MS = 5_000L;

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    private BypassCache() {
    }

    /** True when the player carries {@code mlsac.bypass} and is exempt from all checks. */
    public static boolean isExempt(Player player) {
        return player != null && lookup(player).global;
    }

    /** True when the player is exempt from anti-ESP specifically, or globally. */
    public static boolean isAntiEspExempt(Player player) {
        if (player == null) {
            return false;
        }
        Entry entry = lookup(player);
        return entry.global || entry.antiEsp;
    }

    public static void invalidate(UUID uuid) {
        if (uuid != null) {
            CACHE.remove(uuid);
        }
    }

    public static void clear() {
        CACHE.clear();
    }

    private static Entry lookup(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(uuid);
        if (cached != null && now - cached.stamp < TTL_MS) {
            return cached;
        }
        Entry fresh = new Entry(
                player.hasPermission(Permissions.BYPASS),
                player.hasPermission(Permissions.ANTI_ESP_BYPASS),
                now);
        CACHE.put(uuid, fresh);
        return fresh;
    }

    private static final class Entry {
        final boolean global;
        final boolean antiEsp;
        final long stamp;

        Entry(boolean global, boolean antiEsp, long stamp) {
            this.global = global;
            this.antiEsp = antiEsp;
            this.stamp = stamp;
        }
    }
}
