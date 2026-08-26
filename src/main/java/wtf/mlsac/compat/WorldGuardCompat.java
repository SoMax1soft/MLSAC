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


package wtf.mlsac.compat;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
public class WorldGuardCompat {
    private static final long REGION_CACHE_TTL_MS = 500L;
    private final Logger logger;
    private final boolean enabled;
    private final Map<String, Set<String>> disabledRegions;
    private final Map<UUID, RegionCheckCache> bypassCache = new ConcurrentHashMap<>();
    /**
     * Memo of uuid -> player name for region rosters.
     *
     * <p>Resolving an offline uuid is far from free: once the name is not in the profile or the
     * usercache, the server falls back to reading that player's data file off disk. A roster sweep
     * touches the same handful of uuids across hundreds of regions, so without this every sweep
     * turned into hundreds of disk reads. Names change rarely enough that a session-lifetime memo
     * is the right trade.
     */
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();
    /** Placeholder for a uuid this server has no name for, so it is not looked up again. */
    private static final String UNRESOLVED = "";
    private boolean worldGuardAvailable;
    public WorldGuardCompat(Logger logger, boolean enabled, List<String> disabledRegionsList) {
        this.logger = logger;
        this.enabled = enabled;
        this.disabledRegions = new HashMap<>();
        parseDisabledRegions(disabledRegionsList);
        checkWorldGuardAvailability();
    }
    private void parseDisabledRegions(List<String> regionsList) {
        for (String entry : regionsList) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                String worldName = parts[0].toLowerCase();
                String regionName = parts[1].toLowerCase();
                disabledRegions.computeIfAbsent(worldName, k -> new HashSet<>()).add(regionName);
            } else {
                disabledRegions.computeIfAbsent("*", k -> new HashSet<>()).add(entry.toLowerCase());
            }
        }
    }
    private void checkWorldGuardAvailability() {
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
                worldGuardAvailable = true;
                logger.info("[WorldGuard] Integration enabled");
            } else {
                worldGuardAvailable = false;
                if (enabled) {
                    logger.warning("[WorldGuard] Plugin not found, region checking disabled");
                }
            }
        } catch (ClassNotFoundException e) {
            worldGuardAvailable = false;
            if (enabled) {
                logger.warning("[WorldGuard] Not available, region checking disabled");
            }
        }
    }
    public boolean shouldBypassAICheck(Player player) {
        if (!enabled || !worldGuardAvailable) {
            return false;
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        RegionCheckCache cached = bypassCache.get(playerId);
        if (cached != null && cached.matches(world.getName(), location) && now - cached.checkedAt <= REGION_CACHE_TTL_MS) {
            return cached.bypass;
        }
        boolean bypass = shouldBypassAtLocation(location);
        bypassCache.put(playerId, new RegionCheckCache(world.getName(), location, now, bypass));
        return bypass;
    }
    public boolean shouldBypassAtLocation(Location location) {
        if (!enabled || !worldGuardAvailable) {
            return false;
        }
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        String worldName = world.getName().toLowerCase();
        Set<String> worldDisabled = disabledRegions.getOrDefault(worldName, Collections.emptySet());
        Set<String> globalDisabled = disabledRegions.getOrDefault("*", Collections.emptySet());
        if (worldDisabled.isEmpty() && globalDisabled.isEmpty()) {
            return false;
        }
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager regionManager = container.get(BukkitAdapter.adapt(world));
            if (regionManager == null) {
                return false;
            }
            ApplicableRegionSet regions = regionManager.getApplicableRegions(
                BukkitAdapter.asBlockVector(location)
            );
            ProtectedRegion highestPriorityRegion = null;
            int highestPriority = Integer.MIN_VALUE;
            for (ProtectedRegion region : regions) {
                if (region.getPriority() > highestPriority) {
                    highestPriority = region.getPriority();
                    highestPriorityRegion = region;
                }
            }
            if (highestPriorityRegion != null) {
                String regionId = highestPriorityRegion.getId().toLowerCase();
                return worldDisabled.contains(regionId) || globalDisabled.contains(regionId);
            }
        } catch (Exception e) {
            logger.warning("[WorldGuard] Error checking regions: " + e.getMessage());
        }
        return false;
    }
    public List<String> getRegionsAtPlayer(Player player) {
        List<String> result = new ArrayList<>();
        if (!worldGuardAvailable) {
            return result;
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return result;
        }
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager regionManager = container.get(BukkitAdapter.adapt(world));
            if (regionManager == null) {
                return result;
            }
            ApplicableRegionSet regions = regionManager.getApplicableRegions(
                BukkitAdapter.asBlockVector(location)
            );
            for (ProtectedRegion region : regions) {
                result.add(region.getId() + " (priority: " + region.getPriority() + ")");
            }
        } catch (Exception e) {
            logger.warning("[WorldGuard] Error getting regions: " + e.getMessage());
        }
        return result;
    }
    /**
     * The owner/member roster of every region in every loaded world.
     *
     * <p>This is what MLS VISION cares about. Standing inside a region says nothing — anyone can
     * walk into a base — but being on its owner or member list is a deliberate grant of trust by
     * whoever runs that region, which is exactly the relationship worth recording.
     *
     * <p>Deliberately not gated on {@link #enabled}: that flag decides whether regions suppress
     * anticheat checks, which is a different concern.
     *
     * <p>Must be called on the main thread — WorldGuard's region managers are not thread-safe.
     * Names are resolved from the server's local user cache only; entries that cannot be resolved
     * without a network lookup are skipped rather than blocking the tick.
     */
    public List<RegionRoster> getAllRegionRosters() {
        List<RegionRoster> rosters = new ArrayList<>();
        if (!worldGuardAvailable) {
            return rosters;
        }
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            for (World world : Bukkit.getWorlds()) {
                RegionManager regionManager = container.get(BukkitAdapter.adapt(world));
                if (regionManager == null) {
                    continue;
                }
                for (ProtectedRegion region : regionManager.getRegions().values()) {
                    // Cheap emptiness test first: an unclaimed region needs no name resolution at all.
                    if (isEmpty(region.getOwners()) && isEmpty(region.getMembers())) {
                        continue;
                    }
                    Set<String> owners = resolveDomain(region.getOwners());
                    Set<String> members = resolveDomain(region.getMembers());
                    if (owners.isEmpty() && members.isEmpty()) {
                        continue;
                    }
                    rosters.add(new RegionRoster(region.getId(), world.getName(), owners, members));
                }
            }
        } catch (Exception e) {
            logger.warning("[WorldGuard] Error reading region rosters: " + e.getMessage());
        }
        return rosters;
    }

    private static boolean isEmpty(DefaultDomain domain) {
        return domain == null || (domain.getPlayers().isEmpty() && domain.getUniqueIds().isEmpty());
    }

    private Set<String> resolveDomain(DefaultDomain domain) {
        Set<String> names = new LinkedHashSet<>();
        if (domain == null) {
            return names;
        }
        try {
            names.addAll(domain.getPlayers()); // entries stored as plain names, already resolved
            for (UUID uuid : domain.getUniqueIds()) {
                String name = resolveName(uuid);
                if (name != null) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            logger.warning("[WorldGuard] Error resolving region domain: " + e.getMessage());
        }
        return names;
    }

    /**
     * Resolves a uuid to a player name, at most once per uuid per server session.
     *
     * @return the name, or null if this server has never seen the uuid
     */
    private String resolveName(UUID uuid) {
        String cached = nameCache.get(uuid);
        if (cached != null) {
            return UNRESOLVED.equals(cached) ? null : cached;
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            nameCache.put(uuid, online.getName());
            return online.getName();
        }

        // Reads the local usercache and, failing that, the player's data file; null for a uuid this
        // server has never seen, which we skip rather than asking Mojang and stalling.
        String resolved = Bukkit.getOfflinePlayer(uuid).getName();
        nameCache.put(uuid, resolved == null || resolved.isEmpty() ? UNRESOLVED : resolved);
        return resolved == null || resolved.isEmpty() ? null : resolved;
    }

    /** Immutable snapshot of one region's owner and member lists, by player name. */
    public static final class RegionRoster {
        public final String regionId;
        public final String worldName;
        public final Set<String> owners;
        public final Set<String> members;

        RegionRoster(String regionId, String worldName, Set<String> owners, Set<String> members) {
            this.regionId = regionId;
            this.worldName = worldName;
            this.owners = owners;
            this.members = members;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
    public boolean isWorldGuardAvailable() {
        return worldGuardAvailable;
    }
    public Map<String, Set<String>> getDisabledRegions() {
        return Collections.unmodifiableMap(disabledRegions);
    }

    public void clearCache(UUID playerId) {
        bypassCache.remove(playerId);
    }

    private static final class RegionCheckCache {
        private final String worldName;
        private final int blockX;
        private final int blockY;
        private final int blockZ;
        private final long checkedAt;
        private final boolean bypass;

        private RegionCheckCache(String worldName, Location location, long checkedAt, boolean bypass) {
            this.worldName = worldName;
            this.blockX = location.getBlockX();
            this.blockY = location.getBlockY();
            this.blockZ = location.getBlockZ();
            this.checkedAt = checkedAt;
            this.bypass = bypass;
        }

        private boolean matches(String worldName, Location location) {
            return this.worldName.equals(worldName)
                    && this.blockX == location.getBlockX()
                    && this.blockY == location.getBlockY()
                    && this.blockZ == location.getBlockZ();
        }
    }
}
