/*
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

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Оптимизированная отправка частиц через PacketEvents.
 * Более производительно чем Bukkit API - отправляет пакеты напрямую.
 */
public class ParticlePacketCompat {

    /**
     * Отправка частиц конкретным игрокам через PacketEvents
     */
    public static void sendParticle(Collection<? extends Player> players, 
                                    org.bukkit.Particle bukkitParticle,
                                    Location location,
                                    int count,
                                    double offsetX, double offsetY, double offsetZ,
                                    double speed) {
        sendParticle(players, bukkitParticle, location, count, offsetX, offsetY, offsetZ, speed, null);
    }

    /**
     * Отправка частиц с данными (например, цвет для DUST)
     */
    public static void sendParticle(Collection<? extends Player> players,
                                    org.bukkit.Particle bukkitParticle,
                                    Location location,
                                    int count,
                                    double offsetX, double offsetY, double offsetZ,
                                    double speed,
                                    Object data) {
        try {
            // Конвертируем Bukkit частицу в PacketEvents частицу
            Particle<?> particle = convertBukkitParticle(bukkitParticle, data);
            if (particle == null) {
                return;
            }

            // Создаем пакет частицы
            WrapperPlayServerParticle packetParticle = new WrapperPlayServerParticle(
                particle,
                true, // longDistance - видно издалека
                new com.github.retrooper.packetevents.util.Vector3d(
                    location.getX(),
                    location.getY(),
                    location.getZ()
                ),
                new Vector3f((float) offsetX, (float) offsetY, (float) offsetZ),
                (float) speed,
                count
            );

            // Отправляем пакет всем указанным игрокам
            for (Player player : players) {
                if (player != null && player.isOnline()) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, packetParticle);
                }
            }
        } catch (Exception e) {
            // Fallback на Bukkit API если что-то пошло не так
            for (Player player : players) {
                if (player != null && player.isOnline()) {
                    if (data != null) {
                        player.spawnParticle(bukkitParticle, location, count, offsetX, offsetY, offsetZ, speed, data);
                    } else {
                        player.spawnParticle(bukkitParticle, location, count, offsetX, offsetY, offsetZ, speed);
                    }
                }
            }
        }
    }

    /**
     * Конвертация Bukkit частицы в PacketEvents частицу
     */
    private static Particle<?> convertBukkitParticle(org.bukkit.Particle bukkitParticle, Object data) {
        String particleName = bukkitParticle.name();

        // Обработка цветных частиц (DUST/REDSTONE)
        if ((particleName.equals("REDSTONE") || particleName.equals("DUST")) && data instanceof org.bukkit.Particle.DustOptions) {
            org.bukkit.Particle.DustOptions dustOptions = (org.bukkit.Particle.DustOptions) data;
            Color color = dustOptions.getColor();
            float size = dustOptions.getSize();

            return new Particle<>(
                ParticleTypes.DUST,
                new ParticleDustData(
                    color.getRed() / 255.0f,
                    color.getGreen() / 255.0f,
                    color.getBlue() / 255.0f,
                    size
                )
            );
        }

        // Маппинг стандартных частиц
        com.github.retrooper.packetevents.protocol.particle.type.ParticleType<?> particleType = mapParticleType(particleName);
        if (particleType != null) {
            return new Particle<>(particleType);
        }

        return null;
    }

    /**
     * Маппинг имен частиц Bukkit -> PacketEvents
     */
    private static com.github.retrooper.packetevents.protocol.particle.type.ParticleType<?> mapParticleType(String name) {
        switch (name.toUpperCase()) {
            case "FLAME":
                return ParticleTypes.FLAME;
            case "SMOKE":
            case "SMOKE_NORMAL":
                return ParticleTypes.SMOKE;
            case "LARGE_SMOKE":
                return ParticleTypes.LARGE_SMOKE;
            case "CLOUD":
                return ParticleTypes.CLOUD;
            case "SPELL_WITCH":
            case "WITCH":
                return ParticleTypes.WITCH;
            case "HEART":
                return ParticleTypes.HEART;
            case "EXPLOSION":
            case "EXPLOSION_NORMAL":
                return ParticleTypes.POOF;
            case "EXPLOSION_LARGE":
                return ParticleTypes.EXPLOSION;
            case "EXPLOSION_HUGE":
            case "EXPLOSION_EMITTER":
                return ParticleTypes.EXPLOSION_EMITTER;
            case "FIREWORK":
            case "FIREWORKS_SPARK":
                return ParticleTypes.FIREWORK;
            case "CRIT":
                return ParticleTypes.CRIT;
            case "ENCHANTED_HIT":
            case "CRIT_MAGIC":
                return ParticleTypes.ENCHANTED_HIT;
            case "PORTAL":
                return ParticleTypes.PORTAL;
            case "ENCHANT":
            case "ENCHANTMENT_TABLE":
                return ParticleTypes.ENCHANT;
            case "END_ROD":
                return ParticleTypes.END_ROD;
            case "DRAGON_BREATH":
                return ParticleTypes.DRAGON_BREATH;
            case "SOUL":
                return ParticleTypes.SOUL;
            case "SOUL_FIRE_FLAME":
                return ParticleTypes.SOUL_FIRE_FLAME;
            case "TOTEM_OF_UNDYING":
            case "TOTEM":
                return ParticleTypes.TOTEM_OF_UNDYING;
            default:
                return null;
        }
    }

    /**
     * Отправка частиц всем игрокам в радиусе (оптимизированная версия)
     */
    public static void sendParticleInRadius(Location location,
                                           org.bukkit.Particle bukkitParticle,
                                           int count,
                                           double offsetX, double offsetY, double offsetZ,
                                           double speed,
                                           double radius,
                                           Object data) {
        if (location.getWorld() == null) return;

        // Получаем игроков в радиусе
        Collection<Player> nearbyPlayers = location.getWorld().getNearbyPlayers(location, radius);
        
        if (!nearbyPlayers.isEmpty()) {
            sendParticle(nearbyPlayers, bukkitParticle, location, count, offsetX, offsetY, offsetZ, speed, data);
        }
    }
}
