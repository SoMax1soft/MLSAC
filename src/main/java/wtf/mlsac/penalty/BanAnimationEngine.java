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

package wtf.mlsac.penalty;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import wtf.mlsac.compat.EffectCompat;
import wtf.mlsac.compat.ParticleCompat;
import wtf.mlsac.compat.ParticlePacketCompat;
import wtf.mlsac.scheduler.ScheduledTask;
import wtf.mlsac.scheduler.SchedulerManager;
import wtf.mlsac.penalty.engine.BanAnimationConfig;
import wtf.mlsac.penalty.engine.BanAnimationConfig.StageConfig;
import wtf.mlsac.penalty.engine.BanAnimationConfig.ParticleEffectConfig;
import wtf.mlsac.penalty.engine.BanAnimationConfig.SoundEffectConfig;
import wtf.mlsac.penalty.engine.BanAnimationConfig.TitleConfig;
import wtf.mlsac.penalty.engine.BanAnimationConfig.ParticleTrailConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BanAnimationEngine implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, String> pendingBans = new ConcurrentHashMap<>();
    private final Set<UUID> animatingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<ItemStack>> playerInventories = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> itemDropCounters = new ConcurrentHashMap<>();
    private final Map<UUID, Location> playerStartLocations = new ConcurrentHashMap<>();
    private final Map<UUID, List<Item>> droppedItems = new ConcurrentHashMap<>(); // Отслеживание выпавших предметов
    private final BanAnimationConfig config;

    public BanAnimationEngine(JavaPlugin plugin, BanAnimationConfig config) {
        this.plugin = plugin;
        this.config = config;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void playAnimation(Player player, String banCommand) {
        if (player == null)
            return;

        if (SchedulerManager.getServerType() == wtf.mlsac.scheduler.ServerType.FOLIA || !Bukkit.isPrimaryThread()) {
            SchedulerManager.getAdapter().runEntitySync(player, () -> startAnimation(player, banCommand));
            return;
        }
        startAnimation(player, banCommand);
    }

    private void startAnimation(Player player, String banCommand) {
        if (!player.isOnline()) {
            executeBanCommand(banCommand);
            return;
        }

        UUID playerId = player.getUniqueId();
        if (animatingPlayers.contains(playerId))
            return;

        plugin.getLogger().fine(() -> "Starting ban animation for " + player.getName()
                + " (ticks=" + config.totalTicks + ", stages=" + config.stages.size()
                + ", strip=" + config.stripPlayer + ", freeze=" + config.freezePlayer + ")");

        animatingPlayers.add(playerId);
        pendingBans.put(playerId, banCommand);
        playerStartLocations.put(playerId, player.getLocation().clone());

        player.closeInventory();
        forceInventoryResync(player);

        if (config.freezePlayer)
            freezePlayer(player);

        if (config.stripPlayer) {
            savePlayerInventory(player);
            stripPlayerArmor(player);
            clearPlayerInventory(player);
        }

        final int[] tick = { 0 };
        final ScheduledTask[] taskRef = new ScheduledTask[1];

        taskRef[0] = SchedulerManager.getAdapter().runEntitySyncRepeating(player, () -> {
            try {
                if (!player.isOnline()) {
                    taskRef[0].cancel();
                    handleQuit(playerId);
                    return;
                }

                tick[0]++;

                Location baseLoc = player.getLocation();

                // Выкидывание предметов с динамическим интервалом
                if (config.stripPlayer) {
                    dropNextItemDynamic(player, tick[0], config.totalTicks);
                }

                // Перемещение игрока
                if (config.movement != null && tick[0] >= config.movement.startTick
                        && tick[0] <= config.movement.endTick) {
                    movePlayer(player, tick[0]);
                }

                for (StageConfig stage : config.stages) {
                    if (tick[0] >= stage.startTick && tick[0] <= stage.endTick) {
                        processStage(player, baseLoc, tick[0], stage);
                    }
                }

                if (tick[0] >= config.totalTicks) {
                    if (config.strikeLightningAtEnd)
                        baseLoc.getWorld().strikeLightningEffect(baseLoc);
                    taskRef[0].cancel();
                    cleanup(playerId);
                    executeBanCommand(banCommand);
                }
            } catch (Throwable e) {
                plugin.getLogger().severe("ERROR IN ANIMATION ENGINE: " + e.getMessage());
                e.printStackTrace();
                taskRef[0].cancel();
                cleanup(playerId);
                executeBanCommand(banCommand);
            }
        }, 0L, 1L);
    }

    private void processStage(Player player, Location baseLoc, int currentTick, StageConfig stage) {
        int stageTick = currentTick - stage.startTick;
        int stageDuration = stage.endTick - stage.startTick;
        double progress = stageDuration <= 0 ? 1.0 : (double) stageTick / stageDuration;

        // Обработка звуков
        for (SoundEffectConfig s : stage.sounds) {
            if (s.loop && stageTick % s.loopIntervalTicks == 0) {
                baseLoc.getWorld().playSound(baseLoc, s.soundType, s.volume, s.pitch);
            } else if (!s.loop && stageTick == s.playAtTick) {
                baseLoc.getWorld().playSound(baseLoc, s.soundType, s.volume, s.pitch);
            }
        }

        // Обработка тайтлов
        for (TitleConfig t : stage.titles) {
            if (stageTick == t.showAtTick) {
                try {
                    // Попытка использовать новый API (1.11+)
                    player.sendTitle(t.title, t.subtitle, t.fadeIn, t.stay, t.fadeOut);
                } catch (NoSuchMethodError e) {
                    // Fallback для старых версий
                    plugin.getLogger().warning("sendTitle method not available, skipping title display");
                }
            }
        }

        // Обработка молний
        for (Integer lightningTick : stage.lightningTicks) {
            if (stageTick == lightningTick) {
                baseLoc.getWorld().strikeLightningEffect(baseLoc);
            }
        }

        // Обработка следа из частиц
        if (stage.particleTrail != null && stageTick % stage.particleTrail.intervalTicks == 0) {
            spawnParticleTrail(player, stage.particleTrail);
        }

        // Обработка частиц
        for (ParticleEffectConfig p : stage.particles) {
            if (stageTick % p.intervalTicks != 0)
                continue;

            // Линейная интерполяция (Lerp) радиуса и высоты
            double radius = lerp(p.radiusStart, p.radiusEnd, progress);
            double heightOffset = lerp(p.heightOffsetStart, p.heightOffsetEnd, progress);
            Location spawnLoc = baseLoc.clone().add(0, heightOffset, 0);

            // Подготовка данных для цветных частиц
            Object particleData = prepareParticleData(p);

            // Генерация частиц по форме
            switch (p.shape.toUpperCase()) {
                case "POINT":
                    spawnParticle(spawnLoc, p, particleData);
                    break;

                case "RISING_SPIRAL":
                    spawnRisingSpiral(spawnLoc, radius, currentTick, p, particleData);
                    break;

                case "SPHERE":
                    spawnSphere(spawnLoc, radius, currentTick, p, particleData);
                    break;

                case "EXPLOSION":
                    spawnExplosion(spawnLoc, radius, p, particleData);
                    break;

                case "HELIX":
                    spawnHelix(spawnLoc, radius, heightOffset, currentTick, p, particleData);
                    break;

                case "CIRCLE":
                    spawnCircle(spawnLoc, radius, currentTick, p, particleData);
                    break;
            }
        }
    }

    /**
     * Линейная интерполяция между двумя значениями
     */
    private double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    /**
     * Подготовка данных для цветных частиц (DustOptions)
     */
    private Object prepareParticleData(ParticleEffectConfig p) {
        if (p.isColored && (p.particleType == Particle.REDSTONE || p.particleType.name().equals("DUST"))) {
            return new Particle.DustOptions(
                    Color.fromRGB(
                            Math.max(0, Math.min(255, p.red)),
                            Math.max(0, Math.min(255, p.green)),
                            Math.max(0, Math.min(255, p.blue))),
                    p.particleSize);
        }
        return null;
    }

    /**
     * Спавн одиночной частицы (оптимизировано через PacketEvents)
     */
    private void spawnParticle(Location loc, ParticleEffectConfig p, Object data) {
        if (p.particleType == null) {
            plugin.getLogger().warning("Attempted to spawn null particle type!");
            return;
        }

        // Отправляем частицы через PacketEvents - более оптимально
        // Отправляем только игрокам в радиусе 64 блоков
        ParticlePacketCompat.sendParticleInRadius(
                loc,
                p.particleType,
                p.count,
                p.offsetX, p.offsetY, p.offsetZ,
                p.speed,
                64.0, // радиус видимости
                data);
    }

    /**
     * Восходящая спираль
     */
    private void spawnRisingSpiral(Location center, double radius, int tick, ParticleEffectConfig p, Object data) {
        double angle = Math.toRadians((tick * 20) % 360);
        Location loc = center.clone().add(
                Math.cos(angle) * radius,
                0,
                Math.sin(angle) * radius);
        spawnParticle(loc, p, data);
    }

    /**
     * Сфера (алгоритм Фибоначчи)
     */
    private void spawnSphere(Location center, double radius, int tick, ParticleEffectConfig p, Object data) {
        int particleCount = Math.max(1, p.count);
        for (int i = 0; i < particleCount; i++) {
            double phi = Math.acos(1 - 2.0 * i / particleCount);
            double theta = Math.PI * (1 + Math.sqrt(5)) * i + Math.toRadians(tick * 4);

            Location loc = center.clone().add(
                    radius * Math.sin(phi) * Math.cos(theta),
                    radius * Math.cos(phi),
                    radius * Math.sin(phi) * Math.sin(theta));

            // Для сферы спавним по 1 частице за раз
            ParticleEffectConfig singleParticle = new ParticleEffectConfig();
            singleParticle.particleType = p.particleType;
            singleParticle.count = 1;
            singleParticle.speed = p.speed;
            singleParticle.offsetX = p.offsetX;
            singleParticle.offsetY = p.offsetY;
            singleParticle.offsetZ = p.offsetZ;

            spawnParticle(loc, singleParticle, data);
        }
    }

    /**
     * Взрыв (случайные направления)
     */
    private void spawnExplosion(Location center, double radius, ParticleEffectConfig p, Object data) {
        for (int i = 0; i < p.count; i++) {
            Vector randomDir = new Vector(
                    Math.random() * 2 - 1,
                    Math.random() * 2 - 1,
                    Math.random() * 2 - 1).normalize().multiply(radius);

            spawnParticle(center.clone().add(randomDir), p, data);
        }
    }

    /**
     * Двойная спираль (ДНК-эффект)
     */
    private void spawnHelix(Location center, double radius, double height, int tick, ParticleEffectConfig p,
            Object data) {
        double angle = Math.toRadians((tick * 15) % 360);

        // Первая спираль
        Location loc1 = center.clone().add(
                Math.cos(angle) * radius,
                0,
                Math.sin(angle) * radius);
        spawnParticle(loc1, p, data);

        // Вторая спираль (противоположная)
        Location loc2 = center.clone().add(
                Math.cos(angle + Math.PI) * radius,
                0,
                Math.sin(angle + Math.PI) * radius);
        spawnParticle(loc2, p, data);
    }

    /**
     * Круг (горизонтальный)
     */
    private void spawnCircle(Location center, double radius, int tick, ParticleEffectConfig p, Object data) {
        int points = Math.max(8, p.count);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points + Math.toRadians(tick * 2);
            Location loc = center.clone().add(
                    Math.cos(angle) * radius,
                    0,
                    Math.sin(angle) * radius);

            ParticleEffectConfig singleParticle = new ParticleEffectConfig();
            singleParticle.particleType = p.particleType;
            singleParticle.count = 1;
            singleParticle.speed = p.speed;
            singleParticle.offsetX = p.offsetX;
            singleParticle.offsetY = p.offsetY;
            singleParticle.offsetZ = p.offsetZ;

            spawnParticle(loc, singleParticle, data);
        }
    }

    // --- Функции защиты игрока во время анимации ---
    private void freezePlayer(Player player) {
        PotionEffectType slowness = EffectCompat.getSlowness();
        PotionEffectType jumpBoost = EffectCompat.getJumpBoost();
        PotionEffectType levitation = EffectCompat.getLevitation();

        int levitationAmplifier = config.customLevitationAmplifier >= 0 ? config.customLevitationAmplifier : 1;

        if (slowness != null)
            EffectCompat.applyEffect(player, slowness, config.totalTicks + 40, 255, false, false);
        if (jumpBoost != null)
            EffectCompat.applyEffect(player, jumpBoost, config.totalTicks + 40, 128, false, false);
        if (levitation != null)
            EffectCompat.applyEffect(player, levitation, config.totalTicks, levitationAmplifier, false, false);

        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    /**
     * Снятие брони с игрока
     */
    private void stripPlayerArmor(Player player) {
        player.getInventory().setHelmet(null);
        player.getInventory().setChestplate(null);
        player.getInventory().setLeggings(null);
        player.getInventory().setBoots(null);
        player.updateInventory();
    }

    /**
     * Сохранение инвентаря игрока для постепенного выкидывания
     */
    private void savePlayerInventory(Player player) {
        List<ItemStack> items = new ArrayList<>();

        // Сначала добавляем броню (она должна выпасть первой)
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.getType() != Material.AIR) {
            items.add(helmet.clone());
        }
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType() != Material.AIR) {
            items.add(chestplate.clone());
        }
        ItemStack leggings = player.getInventory().getLeggings();
        if (leggings != null && leggings.getType() != Material.AIR) {
            items.add(leggings.clone());
        }
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            items.add(boots.clone());
        }

        // Вторая рука
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            items.add(offHand.clone());
        }

        // Предмет на курсоре
        ItemStack cursorItem = player.getItemOnCursor();
        if (cursorItem != null && cursorItem.getType() != Material.AIR) {
            items.add(cursorItem.clone());
        }

        // Потом добавляем основной инвентарь (слоты 0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }

        playerInventories.put(player.getUniqueId(), items);
        itemDropCounters.put(player.getUniqueId(), 0);
        droppedItems.put(player.getUniqueId(), new ArrayList<>());
    }

    /**
     * Очистка инвентаря игрока после сохранения
     */
    private void clearPlayerInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    /**
     * Динамическое выкидывание предметов - ускоряется к концу анимации
     * Гарантирует что все предметы будут выкинуты до конца анимации
     */
    private void dropNextItemDynamic(Player player, int currentTick, int totalTicks) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> items = playerInventories.get(uuid);
        if (items == null || items.isEmpty())
            return;

        Integer counter = itemDropCounters.get(uuid);
        if (counter == null || counter >= items.size())
            return;

        int remainingItems = items.size() - counter;
        int remainingTicks = totalTicks - currentTick;

        // Если предметов не осталось или времени нет - выходим
        if (remainingItems <= 0 || remainingTicks <= 0)
            return;

        // Вычисляем нужный интервал чтобы все предметы успели выпасть
        // Добавляем небольшой запас (80% времени) для надежности
        int optimalInterval = Math.max(1, (int) (remainingTicks * 0.8 / remainingItems));

        // Используем минимальный интервал из конфига или вычисленного
        int interval = Math.min(config.itemDropIntervalTicks, optimalInterval);

        // Если пора выкидывать предмет
        if (currentTick % interval == 0) {
            ItemStack item = items.get(counter);
            Location dropLoc = player.getLocation().add(0, 1, 0);

            // Выкидываем предмет (можно подобрать сразу после анимации)
            Item droppedItem = player.getWorld().dropItemNaturally(dropLoc, item);
            droppedItem.setPickupDelay(20); // Небольшая задержка 1 секунда

            // Сохраняем ссылку на выпавший предмет
            List<Item> dropped = droppedItems.get(uuid);
            if (dropped != null) {
                dropped.add(droppedItem);
            }

            // Звук подбора ресурсов
            player.getWorld().playSound(dropLoc, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);

            itemDropCounters.put(uuid, counter + 1);
        }
    }

    /**
     * Перемещение игрока в пространстве
     */
    private void movePlayer(Player player, int currentTick) {
        if (config.movement == null)
            return;

        Location startLoc = playerStartLocations.get(player.getUniqueId());
        if (startLoc == null)
            return;

        int movementDuration = config.movement.endTick - config.movement.startTick;
        int movementTick = currentTick - config.movement.startTick;

        if (config.movement.smooth && movementDuration > 0) {
            // Плавное перемещение с интерполяцией
            double progress = (double) movementTick / movementDuration;
            double x = startLoc.getX() + config.movement.offsetX * progress;
            double y = startLoc.getY() + config.movement.offsetY * progress;
            double z = startLoc.getZ() + config.movement.offsetZ * progress;

            Location newLoc = new Location(startLoc.getWorld(), x, y, z, startLoc.getYaw(), startLoc.getPitch());
            player.teleport(newLoc);
        } else if (movementTick == 0) {
            // Мгновенное перемещение в начале
            Location newLoc = startLoc.clone().add(config.movement.offsetX, config.movement.offsetY,
                    config.movement.offsetZ);
            player.teleport(newLoc);
        }
    }

    /**
     * След из частиц за игроком (оптимизировано через PacketEvents)
     */
    private void spawnParticleTrail(Player player, ParticleTrailConfig trail) {
        Location loc = player.getLocation();

        Object particleData = null;
        if (trail.isColored && (trail.particleType == Particle.REDSTONE || trail.particleType.name().equals("DUST"))) {
            particleData = new Particle.DustOptions(
                    Color.fromRGB(
                            Math.max(0, Math.min(255, trail.red)),
                            Math.max(0, Math.min(255, trail.green)),
                            Math.max(0, Math.min(255, trail.blue))),
                    trail.particleSize);
        }

        // Отправляем через PacketEvents
        ParticlePacketCompat.sendParticleInRadius(
                loc,
                trail.particleType,
                trail.count,
                trail.offsetX, trail.offsetY, trail.offsetZ,
                0.0,
                64.0,
                particleData);
    }

    private void unfreezePlayer(Player player) {
        EffectCompat.removeEffect(player, EffectCompat.getSlowness());
        EffectCompat.removeEffect(player, EffectCompat.getJumpBoost());
    }

    private void dropAllRemainingItems(UUID uuid) {
        List<ItemStack> items = playerInventories.get(uuid);
        Integer counter = itemDropCounters.get(uuid);
        Location loc = playerStartLocations.get(uuid);

        if (loc == null) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                loc = p.getLocation();
            }
        }

        if (items != null && counter != null && loc != null) {
            for (int i = counter; i < items.size(); i++) {
                ItemStack item = items.get(i);
                if (item != null && item.getType() != Material.AIR) {
                    loc.getWorld().dropItemNaturally(loc, item);
                }
            }
            itemDropCounters.put(uuid, items.size());
        }
    }

    private void cleanup(UUID uuid) {
        dropAllRemainingItems(uuid);

        animatingPlayers.remove(uuid);
        pendingBans.remove(uuid);
        playerInventories.remove(uuid);
        itemDropCounters.remove(uuid);
        playerStartLocations.remove(uuid);

        // НЕ удаляем выпавшие предметы - они остаются на земле
        droppedItems.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline())
            unfreezePlayer(player);
    }

    private void handleQuit(UUID playerId) {
        if (animatingPlayers.contains(playerId)) {
            String cmd = pendingBans.get(playerId);
            cleanup(playerId);
            if (cmd != null)
                executeBanCommand(cmd);
        }
    }

    private void executeBanCommand(String command) {
        if (SchedulerManager.getServerType() == wtf.mlsac.scheduler.ServerType.FOLIA || !Bukkit.isPrimaryThread()) {
            SchedulerManager.getAdapter().runSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public boolean isAnimating(Player player) {
        return player != null && animatingPlayers.contains(player.getUniqueId());
    }

    private void cancelInventoryMutation(Player player, Cancellable event) {
        event.setCancelled(true);
        forceInventoryResync(player);
    }

    private void forceInventoryResync(Player player) {
        if (player == null || !player.isOnline())
            return;
        player.updateInventory();
        SchedulerManager.getAdapter().runEntitySyncDelayed(player, () -> {
            if (player.isOnline())
                player.updateInventory();
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        handleQuit(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (isAnimating(e.getPlayer())) {
            Location from = e.getFrom(), to = e.getTo();
            if (to != null && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
                Location newTo = to.clone();
                newTo.setX(from.getX());
                newTo.setZ(from.getZ());
                e.setTo(newTo);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (isAnimating(e.getPlayer()))
            cancelInventoryMutation(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (isAnimating(e.getPlayer()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (isAnimating(e.getPlayer())) {
            cancelInventoryMutation(e.getPlayer(), e);
            e.getItemDrop().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player && isAnimating((Player) e.getWhoClicked()))
            cancelInventoryMutation((Player) e.getWhoClicked(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player && isAnimating((Player) e.getWhoClicked()))
            cancelInventoryMutation((Player) e.getWhoClicked(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent e) {
        if (isAnimating(e.getPlayer()))
            cancelInventoryMutation(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemHeld(PlayerItemHeldEvent e) {
        if (isAnimating(e.getPlayer()))
            cancelInventoryMutation(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player && isAnimating((Player) e.getPlayer()))
            cancelInventoryMutation((Player) e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        if (isAnimating(e.getPlayer()))
            cancelInventoryMutation(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isAnimating(e.getPlayer()))
            cancelInventoryMutation(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && isAnimating((Player) e.getDamager()))
            e.setCancelled(true);
        if (e.getEntity() instanceof Player && isAnimating((Player) e.getEntity()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttemptPickupItem(PlayerAttemptPickupItemEvent e) {
        if (isAnimating(e.getPlayer()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player && isAnimating((Player) e.getEntity()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (isAnimating(p)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            forceInventoryResync(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStandManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        if (isAnimating(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player && isAnimating((Player) e.getPlayer())) {
            // Принудительно закрываем инвентарь
            Player p = (Player) e.getPlayer();
            SchedulerManager.getAdapter().runEntitySyncDelayed(p, () -> {
                if (p.isOnline())
                    p.closeInventory();
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(org.bukkit.event.inventory.InventoryMoveItemEvent e) {
        // Проверяем все онлайн игроков которые анимируются
        for (UUID uuid : animatingPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                // Если событие связано с инвентарем анимирующегося игрока
                if (e.getSource().getHolder() instanceof Player
                        && ((Player) e.getSource().getHolder()).getUniqueId().equals(uuid)) {
                    e.setCancelled(true);
                    return;
                }
                if (e.getDestination().getHolder() instanceof Player
                        && ((Player) e.getDestination().getHolder()).getUniqueId().equals(uuid)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);

        // НЕ удаляем выпавшие предметы при выключении - они остаются на земле

        animatingPlayers.clear();
        pendingBans.clear();
        playerInventories.clear();
        itemDropCounters.clear();
        playerStartLocations.clear();
        droppedItems.clear();
    }
}
