package wtf.mlsac.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import wtf.mlsac.response.DetectionResponseManager;

public class CombatPenaltyListener implements Listener {
    private final DetectionResponseManager detectionResponseManager;

    public CombatPenaltyListener(DetectionResponseManager detectionResponseManager) {
        this.detectionResponseManager = detectionResponseManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }

        double multiplier = detectionResponseManager.getDamageMultiplier(attacker.getUniqueId());
        if (multiplier >= 0.9999D) {
            return;
        }

        event.setDamage(event.getDamage() * multiplier);
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
