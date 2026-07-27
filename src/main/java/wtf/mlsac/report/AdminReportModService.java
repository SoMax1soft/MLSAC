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

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import wtf.mlsac.Main;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages admin moderation tools during report handling:
 * 1. Disabling damager attacks (0 damage).
 * 2. 2x extra damage applied against suspect.
 */
public class AdminReportModService implements Listener {
    private static final Map<UUID, Boolean> zeroDamagePlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> doubleDamageSuspects = new ConcurrentHashMap<>();

    private final Main plugin;

    public AdminReportModService(Main plugin) {
        this.plugin = plugin;
    }

    public static boolean isZeroDamage(UUID playerId) {
        return Boolean.TRUE.equals(zeroDamagePlayers.get(playerId));
    }

    public static void setZeroDamage(UUID playerId, boolean enable) {
        if (enable) {
            zeroDamagePlayers.put(playerId, true);
        } else {
            zeroDamagePlayers.remove(playerId);
        }
    }

    public static boolean toggleZeroDamage(UUID playerId) {
        boolean active = !isZeroDamage(playerId);
        setZeroDamage(playerId, active);
        return active;
    }

    public static boolean isDoubleDamage(UUID playerId) {
        return Boolean.TRUE.equals(doubleDamageSuspects.get(playerId));
    }

    public static void setDoubleDamage(UUID playerId, boolean enable) {
        if (enable) {
            doubleDamageSuspects.put(playerId, true);
        } else {
            doubleDamageSuspects.remove(playerId);
        }
    }

    public static boolean toggleDoubleDamage(UUID playerId) {
        boolean active = !isDoubleDamage(playerId);
        setDoubleDamage(playerId, active);
        return active;
    }

    public static void clear(UUID playerId) {
        zeroDamagePlayers.remove(playerId);
        doubleDamageSuspects.remove(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker != null && isZeroDamage(attacker.getUniqueId())) {
            event.setDamage(0.0D);
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            if (isDoubleDamage(victim.getUniqueId())) {
                event.setDamage(event.getDamage() * 2.0D);
            }
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Player) {
                return (Player) projectile.getShooter();
            }
        }
        return null;
    }
}
