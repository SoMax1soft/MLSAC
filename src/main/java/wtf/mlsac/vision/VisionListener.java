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

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import wtf.mlsac.Main;
import wtf.mlsac.checks.AICheck;
import wtf.mlsac.compat.EconomyPluginsCompat;
import wtf.mlsac.compat.WorldGuardCompat;
import wtf.mlsac.penalty.engine.AnimationManager;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.util.SecurityUtil;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects MLS VISION signals from plain Bukkit events and queues them on
 * {@link VisionEventSender}. Nothing here is persisted locally — every accepted signal is handed
 * straight to the sender's in-memory queue.
 *
 * <p>Only two things are inferred, everything else is read directly off the event:
 * <ul>
 *   <li><b>Chest transfers</b> — who last deposited into a container is remembered
 *       ({@link #chestLastDepositor}) so a later withdrawal by someone else can be attributed.</li>
 *   <li><b>Item transfer vs. dead drop</b> — the same drop-then-pickup pattern, split purely by
 *       how long the item sat on the ground: a quick pickup by someone nearby reads as a direct
 *       handoff, a delayed one as a dead drop.</li>
 * </ul>
 *
 * <p>WorldGuard region membership is the one signal that is polled rather than event-driven, for
 * the reason given on {@link #startRegionScanning()}.
 */
public class VisionListener implements Listener {
    // Ender chests are deliberately absent: the same block backs a different inventory per player,
    // so a "withdrawal" there can never be from someone else's deposit.
    private static final Set<InventoryType> CONTAINER_TYPES = new HashSet<>(java.util.Arrays.asList(
            InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.HOPPER, InventoryType.DROPPER
    ));

    private static final long ITEM_TRANSFER_WINDOW_MS = 5_000L;
    private static final long DEAD_DROP_WINDOW_MS = 120_000L;
    private static final double PICKUP_MATCH_RADIUS = 6.0;
    private static final int MAX_TRACKED_DROPS = 300;
    private static final int MAX_TRACKED_CHESTS = 2000;
    private static final long TPA_WINDOW_MS = 60_000L;
    private static final int MAX_PENDING_TPA = 500;

    private static final Set<String> TPA_REQUEST_COMMANDS = new HashSet<>(java.util.Arrays.asList("tpa", "tpask", "call"));
    private static final Set<String> TPA_ACCEPT_COMMANDS = new HashSet<>(java.util.Arrays.asList("tpaccept", "tpyes"));
    private static final Set<String> MESSAGE_COMMANDS = new HashSet<>(java.util.Arrays.asList(
            "msg", "m", "tell", "w", "whisper"
    ));
    private static final Set<String> REGION_COMMANDS = new HashSet<>(java.util.Arrays.asList(
            "rg", "region", "regions", "worldguard"
    ));
    private static final Set<String> REGION_ADD_SUBCOMMANDS = new HashSet<>(java.util.Arrays.asList(
            "addmember", "addmem", "addowner"
    ));

    // Full roster sweep. Cheap enough to run often (it reads WorldGuard's in-memory region map),
    // but nothing changes minute to minute, so five minutes is plenty.
    private static final long REGION_SCAN_INTERVAL_TICKS = 6000L; // 5 min
    private static final long REGION_FIRST_SCAN_DELAY_TICKS = 600L; // 30s after enable

    private final Main plugin;
    private final VisionEventSender sender;
    private final EconomyPluginsCompat economyPlugins;

    private final Map<UUID, Map<Material, Integer>> openChestSnapshot = new ConcurrentHashMap<>();
    private final Map<UUID, Location> openChestLocation = new ConcurrentHashMap<>();
    private final Map<String, String> chestLastDepositor = new ConcurrentHashMap<>(); // locationKey -> playerName
    private final ArrayDeque<RecentDrop> recentDrops = new ArrayDeque<>();
    private final Map<String, PendingTpa> pendingTpa = new ConcurrentHashMap<>(); // targetName(lower) -> requester
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    // Last known roster per region key ("world|region" -> "role:player" entries), so a sweep only
    // reports what actually changed and removals can be detected at all.
    private final Map<String, Set<String>> lastRegionRoster = new ConcurrentHashMap<>();

    private volatile ScheduledTask regionScanTask;

    public VisionListener(Main plugin, VisionEventSender sender, EconomyPluginsCompat economyPlugins) {
        this.plugin = plugin;
        this.sender = sender;
        this.economyPlugins = economyPlugins;
    }

    /**
     * Starts the periodic sweep of every WorldGuard region's owner/member roster.
     *
     * <p>Reads the whole region list rather than reacting to players: membership is a property of
     * the region, not of anyone being online, so a roster change must be noticed even when nobody
     * involved has logged in for weeks. Each sweep reports only the difference against the previous
     * one, which is also the only way removals can be seen at all.
     */
    public void startRegionScanning() {
        if (regionScanTask != null) return;
        regionScanTask = SchedulerManager.getAdapter()
                .runSyncRepeating(this::scanRegionRosters, REGION_FIRST_SCAN_DELAY_TICKS, REGION_SCAN_INTERVAL_TICKS);
    }

    public void stopRegionScanning() {
        if (regionScanTask != null) {
            regionScanTask.cancel();
            regionScanTask = null;
        }
    }

    private WorldGuardCompat worldGuard() {
        AICheck aiCheck = plugin.getAiCheck();
        return aiCheck != null ? aiCheck.getWorldGuardCompat() : null;
    }

    private boolean isAnimating(Player player) {
        AnimationManager animationManager = plugin.getAnimationManager();
        return animationManager != null && animationManager.isAnimating(player);
    }

    private void scanRegionRosters() {
        WorldGuardCompat worldGuard = worldGuard();
        if (worldGuard == null || !worldGuard.isWorldGuardAvailable()) return;

        Set<String> seenRegions = new HashSet<>();
        for (WorldGuardCompat.RegionRoster roster : worldGuard.getAllRegionRosters()) {
            String key = roster.worldName + "|" + roster.regionId;
            seenRegions.add(key);

            Set<String> current = new HashSet<>();
            for (String owner : roster.owners) {
                current.add("owner:" + owner);
            }
            for (String member : roster.members) {
                // An owner is already the stronger claim; don't report the same person twice.
                if (!roster.owners.contains(member)) {
                    current.add("member:" + member);
                }
            }

            Set<String> previous = lastRegionRoster.get(key);
            if (current.equals(previous)) continue;

            for (String entry : current) {
                if (previous != null && previous.contains(entry)) continue;
                int colon = entry.indexOf(':');
                sender.queue(VisionEvent.regionRole(
                        entry.substring(colon + 1), roster.regionId, roster.worldName,
                        entry.startsWith("owner:")));
            }
            if (previous != null) {
                for (String entry : previous) {
                    if (current.contains(entry)) continue;
                    String name = entry.substring(entry.indexOf(':') + 1);
                    // Still present under the other role? That is a promotion, not a removal.
                    if (current.contains("owner:" + name) || current.contains("member:" + name)) continue;
                    sender.queue(VisionEvent.regionLeft(name, roster.regionId, roster.worldName));
                }
            }
            lastRegionRoster.put(key, current);
        }

        // A region that disappeared entirely takes its roster with it.
        for (Iterator<Map.Entry<String, Set<String>>> it = lastRegionRoster.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Set<String>> entry = it.next();
            if (seenRegions.contains(entry.getKey())) continue;
            int separator = entry.getKey().indexOf('|');
            String world = separator >= 0 ? entry.getKey().substring(0, separator) : "";
            String regionId = separator >= 0 ? entry.getKey().substring(separator + 1) : entry.getKey();
            for (String rosterEntry : entry.getValue()) {
                sender.queue(VisionEvent.regionLeft(rosterEntry.substring(rosterEntry.indexOf(':') + 1), regionId, world));
            }
            it.remove();
        }
    }

    // ── Chest transfers ─────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (isAnimating(player)) return;

        Inventory inventory = event.getInventory();
        if (!CONTAINER_TYPES.contains(inventory.getType())) return;
        Location location = resolveContainerLocation(inventory);
        if (location == null) return;

        openChestSnapshot.put(player.getUniqueId(), countMaterials(inventory));
        openChestLocation.put(player.getUniqueId(), location);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        UUID uuid = player.getUniqueId();

        Map<Material, Integer> before = openChestSnapshot.remove(uuid);
        Location location = openChestLocation.remove(uuid);
        if (before == null || location == null) return;
        if (isAnimating(player)) return;

        Map<Material, Integer> after = countMaterials(event.getInventory());
        int netChange = 0;
        for (Material material : after.keySet()) {
            netChange += after.getOrDefault(material, 0) - before.getOrDefault(material, 0);
        }
        for (Material material : before.keySet()) {
            if (!after.containsKey(material)) {
                netChange -= before.get(material);
            }
        }
        if (netChange == 0) return;

        String locationKey = locationKey(location);
        String playerName = player.getName();

        if (netChange > 0) {
            // Net deposit: remember who left something here for a later withdrawer.
            if (chestLastDepositor.size() >= MAX_TRACKED_CHESTS) {
                chestLastDepositor.clear();
            }
            chestLastDepositor.put(locationKey, playerName);
            return;
        }

        // Net withdrawal: attribute it to whoever last deposited here, if it wasn't this player.
        String depositor = chestLastDepositor.get(locationKey);
        if (depositor != null && !depositor.equalsIgnoreCase(playerName)) {
            sender.queue(VisionEvent.chestTransfer(depositor, playerName, location.getWorld() != null ? location.getWorld().getName() : null,
                    location.getX(), location.getY(), location.getZ()));
            chestLastDepositor.remove(locationKey);
        }
    }

    private static Map<Material, Integer> countMaterials(Inventory inventory) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private static Location resolveContainerLocation(Inventory inventory) {
        try {
            if (inventory.getLocation() != null) return inventory.getLocation();
            if (inventory.getHolder() instanceof org.bukkit.block.BlockState) {
                return ((org.bukkit.block.BlockState) inventory.getHolder()).getLocation();
            }
        } catch (Exception ignored) {
            // Some holders (virtual inventories, other plugins' custom GUIs) don't resolve to a
            // block location — those aren't real containers to us, just skip them.
        }
        return null;
    }

    private static String locationKey(Location location) {
        return (location.getWorld() != null ? location.getWorld().getName() : "?")
                + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    // ── Item drop / pickup (direct handoff or dead drop) ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (isAnimating(player)) return;

        pruneOldDrops();
        synchronized (recentDrops) {
            if (recentDrops.size() >= MAX_TRACKED_DROPS) {
                recentDrops.pollFirst();
            }
            recentDrops.addLast(new RecentDrop(
                    player.getName(), event.getItemDrop().getLocation(),
                    event.getItemDrop().getItemStack().getType(), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player picker = (Player) event.getEntity();
        if (isAnimating(picker)) return;

        Item itemEntity = event.getItem();
        Material material = itemEntity.getItemStack().getType();
        Location pickupLocation = itemEntity.getLocation();
        long now = System.currentTimeMillis();

        RecentDrop match = null;
        synchronized (recentDrops) {
            for (Iterator<RecentDrop> iterator = recentDrops.iterator(); iterator.hasNext(); ) {
                RecentDrop candidate = iterator.next();
                if (candidate.material != material) continue;
                if (candidate.playerName.equalsIgnoreCase(picker.getName())) continue;
                if (!sameWorld(candidate.location, pickupLocation)) continue;
                if (candidate.location.distanceSquared(pickupLocation) > PICKUP_MATCH_RADIUS * PICKUP_MATCH_RADIUS) continue;
                match = candidate;
                iterator.remove();
                break;
            }
        }
        if (match == null) return;

        long elapsed = now - match.droppedAtMillis;
        String world = pickupLocation.getWorld() != null ? pickupLocation.getWorld().getName() : null;
        VisionEvent visionEvent = elapsed <= ITEM_TRANSFER_WINDOW_MS
                ? VisionEvent.itemTransfer(match.playerName, picker.getName(), material.name(), world,
                        pickupLocation.getX(), pickupLocation.getY(), pickupLocation.getZ())
                : VisionEvent.deadDrop(match.playerName, picker.getName(), material.name(), world,
                        pickupLocation.getX(), pickupLocation.getY(), pickupLocation.getZ());
        sender.queue(visionEvent);
    }

    private static boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private void pruneOldDrops() {
        long cutoff = System.currentTimeMillis() - DEAD_DROP_WINDOW_MS;
        synchronized (recentDrops) {
            while (!recentDrops.isEmpty() && recentDrops.peekFirst().droppedAtMillis < cutoff) {
                recentDrops.pollFirst();
            }
        }
    }

    private static final class RecentDrop {
        final String playerName;
        final Location location;
        final Material material;
        final long droppedAtMillis;

        RecentDrop(String playerName, Location location, Material material, long droppedAtMillis) {
            this.playerName = playerName;
            this.location = location;
            this.material = material;
            this.droppedAtMillis = droppedAtMillis;
        }
    }

    // ── Commands: /tpa, /msg family, economy pay ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isAnimating(player)) return;

        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':'); // "/essentials:pay" resolves to the same command as "/pay"
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }

        if (TPA_REQUEST_COMMANDS.contains(label) && parts.length >= 2) {
            handleTpaRequest(player, parts[1]);
            return;
        }
        if (TPA_ACCEPT_COMMANDS.contains(label)) {
            handleTpaAccept(player);
            return;
        }
        if (MESSAGE_COMMANDS.contains(label) && parts.length >= 2) {
            handleMessageMeta(player, parts[1]);
            return;
        }
        if (REGION_COMMANDS.contains(label)) {
            handleRegionMembership(player, parts);
            return;
        }
        // Economy: "/pay <player> <amount>" (Vault-backed plugins) and PlayerPoints' donation
        // currency, which is commonly aliased "/p pay" alongside its own "/points pay".
        if (label.equals("pay") && parts.length >= 3) {
            handleEconomyTransfer(player, parts[1], parts[2]);
        } else if ((label.equals("points") || label.equals("p")) && parts.length >= 4
                && parts[1].equalsIgnoreCase("pay")) {
            handleEconomyTransfer(player, parts[2], parts[3]);
        }
    }

    private void handleTpaRequest(Player requester, String targetArg) {
        String targetKey = SecurityUtil.sanitizeChatText(targetArg, 16).toLowerCase(Locale.ROOT);
        if (targetKey.isEmpty()) return;
        if (pendingTpa.size() >= MAX_PENDING_TPA) {
            long cutoff = System.currentTimeMillis() - TPA_WINDOW_MS;
            pendingTpa.values().removeIf(pending -> pending.requestedAtMillis < cutoff);
            if (pendingTpa.size() >= MAX_PENDING_TPA) {
                pendingTpa.clear();
            }
        }
        pendingTpa.put(targetKey, new PendingTpa(requester.getName(), System.currentTimeMillis()));
    }

    private void handleTpaAccept(Player acceptor) {
        PendingTpa pending = pendingTpa.remove(acceptor.getName().toLowerCase(Locale.ROOT));
        if (pending == null) return;
        if (System.currentTimeMillis() - pending.requestedAtMillis > TPA_WINDOW_MS) return;
        if (pending.requesterName.equalsIgnoreCase(acceptor.getName())) return;
        sender.queue(VisionEvent.tpaRequest(pending.requesterName, acceptor.getName()));
    }

    private static final class PendingTpa {
        final String requesterName;
        final long requestedAtMillis;

        PendingTpa(String requesterName, long requestedAtMillis) {
            this.requesterName = requesterName;
            this.requestedAtMillis = requestedAtMillis;
        }
    }

    /**
     * Parses {@code /rg addmember|addowner <region> <player...>} to record who granted the access.
     *
     * <p>The roster sweep already establishes <em>that</em> someone is a member; this only adds
     * <em>who put them there</em>, which the roster cannot tell us. When no grant was ever
     * observed — because it happened before collection started, or through another plugin — the
     * membership still stands on its own and is simply shown without an author.
     */
    private void handleRegionMembership(Player actor, String[] parts) {
        if (parts.length < 4) return;
        String sub = parts[1].toLowerCase(Locale.ROOT);
        if (!REGION_ADD_SUBCOMMANDS.contains(sub)) {
            // Removals are left to the roster sweep, which sees the actual result. Trusting the
            // command text here would record a removal that WorldGuard may well have rejected.
            return;
        }

        // WorldGuard accepts its flags before the region name — "/rg addmember -w world base Bob" —
        // and every flag takes a value. Walking the arguments and skipping flag pairs is what keeps
        // "-w" out of the region field and the world name out of the player list.
        int index = 2;
        while (index < parts.length && parts[index].startsWith("-")) {
            index += 2;
        }
        if (index >= parts.length) return;

        String region = SecurityUtil.sanitizeChatText(parts[index], 64);
        if (region.isEmpty()) return;

        for (int i = index + 1; i < parts.length; i += 1) {
            if (parts[i].startsWith("-")) {
                i += 1; // trailing flag pair
                continue;
            }
            String target = SecurityUtil.sanitizeChatText(parts[i], 16);
            if (target.isEmpty() || target.equalsIgnoreCase(actor.getName())) continue;
            // Only the "who granted it" half comes from the command; that the player *is* a member
            // is established independently by the sweep, so a rejected command adds no membership.
            sender.queue(VisionEvent.regionGrant(actor.getName(), target, region));
        }
    }

    private void handleMessageMeta(Player messenger, String targetArg) {
        String target = SecurityUtil.sanitizeChatText(targetArg, 16);
        if (target.isEmpty() || target.equalsIgnoreCase(messenger.getName())) return;
        sender.queue(VisionEvent.messageMeta(messenger.getName(), target));
    }

    private void handleEconomyTransfer(Player payer, String targetArg, String amountArg) {
        if (!economyPlugins.hasAnyEconomyProvider()) return;
        String target = SecurityUtil.sanitizeChatText(targetArg, 16);
        if (target.isEmpty() || target.equalsIgnoreCase(payer.getName())) return;
        double amount;
        try {
            amount = Double.parseDouble(amountArg.replace(",", "."));
        } catch (NumberFormatException e) {
            return;
        }
        if (!(amount > 0) || !Double.isFinite(amount)) return;
        sender.queue(VisionEvent.economyTransfer(payer.getName(), target, amount));
    }

    // ── IP + playtime ───────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : null;
        if (ip == null) return;
        sender.queue(VisionEvent.ipSeen(event.getName(), ip));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sessionStart.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        openChestSnapshot.remove(player.getUniqueId());
        openChestLocation.remove(player.getUniqueId());

        Long start = sessionStart.remove(player.getUniqueId());
        if (start == null) return;

        long sessionSeconds = Math.max(0, (System.currentTimeMillis() - start) / 1000L);
        if (sessionSeconds < 5) return; // not worth a round trip for a near-instant reconnect

        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        sender.queue(VisionEvent.playerStat(player.getName(), sessionSeconds, ip));
    }
}
