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

package wtf.mlsac.penalty.engine;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.penalty.AnimationPacketListener;
import wtf.mlsac.penalty.BanAnimationEngine;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Менеджер анимаций - управляет загрузкой и выполнением анимаций.
 * Поддерживает множественные анимации и горячую перезагрузку.
 */
public class AnimationManager {
    private final JavaPlugin plugin;
    private final AnimationConfigLoader loader;
    private final Map<String, BanAnimationEngine> engines = new HashMap<>();
    private String defaultAnimationName = "classic_ban";
    private AnimationPacketListener packetListener;

    public AnimationManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.loader = new AnimationConfigLoader(plugin);
    }

    /**
     * Инициализация менеджера - загрузка всех анимаций
     */
    public void initialize() {
        plugin.getLogger().info("Initializing Animation Manager...");

        // Создание папки animations если не существует
        File animationsFolder = new File(plugin.getDataFolder(), "animations");
        if (!animationsFolder.exists()) {
            animationsFolder.mkdirs();
            saveDefaultAnimations();
        }

        // Загрузка кастомных анимаций из файлов
        loadCustomAnimations();

        // Регистрация PacketEvents листенера для блокировки пакетов
        try {
            if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
                packetListener = new AnimationPacketListener(this);
                PacketEvents.getAPI().getEventManager().registerListener(packetListener);
                plugin.getLogger().info("Registered PacketEvents listener for animation protection");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register PacketEvents listener: " + e.getMessage());
        }

        plugin.getLogger().info("Loaded " + engines.size() + " ban animations");
    }

    /**
     * Сохранение примеров анимаций в файлы
     */
    private void saveDefaultAnimations() {
        plugin.getLogger().info("Creating example animation files...");
        
        // Список всех анимаций которые нужно скопировать из resources
        String[] animationFiles = {
            "classic_ban.yml",
            "rainbow_ban.yml", 
            "dramatic_ban.yml",
        };
        
        File animationsFolder = new File(plugin.getDataFolder(), "animations");
        
        for (String fileName : animationFiles) {
            File targetFile = new File(animationsFolder, fileName);
            
            // Пропускаем если файл уже существует
            if (targetFile.exists()) {
                continue;
            }
            
            try {
                // Копируем файл из resources/animations/
                java.io.InputStream resourceStream = plugin.getResource("animations/" + fileName);
                if (resourceStream != null) {
                    java.nio.file.Files.copy(
                        resourceStream,
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    plugin.getLogger().info("Created animation file: " + fileName);
                    resourceStream.close();
                } else {
                    plugin.getLogger().warning("Animation file not found in resources: " + fileName);
                }
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to create animation file: " + fileName, e);
            }
        }
        
        // Создание документации (из корня resources, не из animations/)
        saveGuideFile();
    }

    /**
     * Сохранение ANIMATION_GUIDE.txt из корня resources в папку animations
     */
    private void saveGuideFile() {
        File animationsFolder = new File(plugin.getDataFolder(), "animations");
        File targetFile = new File(animationsFolder, "ANIMATION_GUIDE.txt");
        
        if (targetFile.exists()) {
            return; // Файл уже существует
        }

        try {
            // Ищем файл в корне resources, а не в animations/
            java.io.InputStream resourceStream = plugin.getResource("ANIMATION_GUIDE.txt");
            if (resourceStream != null) {
                java.nio.file.Files.copy(
                    resourceStream, 
                    targetFile.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
                plugin.getLogger().info("Created ANIMATION_GUIDE.txt in animations folder");
            } else {
                plugin.getLogger().warning("ANIMATION_GUIDE.txt not found in plugin resources");
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to create ANIMATION_GUIDE.txt", e);
        }
    }


    /**
     * Загрузка кастомных анимаций из папки animations/
     */
    private void loadCustomAnimations() {
        File animationsFolder = new File(plugin.getDataFolder(), "animations");
        if (!animationsFolder.exists() || !animationsFolder.isDirectory()) {
            return;
        }

        File[] files = animationsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            String fileName = file.getName();
            String animationName = fileName.replace(".yml", "").toLowerCase();

            try {
                BanAnimationConfig config = loader.loadFromFile(fileName);
                if (config != null) {
                    BanAnimationEngine engine = new BanAnimationEngine(plugin, config);
                    engines.put(animationName, engine);
                    plugin.getLogger().info("Loaded custom animation: " + animationName + " from " + fileName);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load animation from " + fileName, e);
            }
        }
    }

    /**
     * Перезагрузка всех анимаций
     */
    public void reload() {
        plugin.getLogger().info("Reloading animations...");
        
        // Остановка всех текущих движков
        for (BanAnimationEngine engine : engines.values()) {
            engine.shutdown();
        }
        engines.clear();

        // Повторная инициализация
        initialize();
    }

    /**
     * Запуск анимации по имени
     * 
     * @param player игрок для анимации
     * @param animationName имя анимации
     * @param banCommand команда бана для выполнения после анимации
     * @return true если анимация запущена, false если не найдена
     */
    public boolean playAnimation(Player player, String animationName, String banCommand) {
        BanAnimationEngine engine = engines.get(animationName.toLowerCase());
        
        if (engine == null) {
            plugin.getLogger().warning("Animation not found: " + animationName + ", using default");
            engine = engines.get(defaultAnimationName);
        }

        if (engine == null) {
            plugin.getLogger().severe("Default animation not found! Cannot play animation.");
            return false;
        }

        engine.playAnimation(player, banCommand);
        return true;
    }

    /**
     * Запуск анимации по умолчанию
     */
    public boolean playDefaultAnimation(Player player, String banCommand) {
        return playAnimation(player, defaultAnimationName, banCommand);
    }

    /**
     * Проверка, анимируется ли игрок
     */
    public boolean isAnimating(Player player) {
        for (BanAnimationEngine engine : engines.values()) {
            if (engine.isAnimating(player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Получение списка доступных анимаций
     */
    public Set<String> getAvailableAnimations() {
        return engines.keySet();
    }

    /**
     * Проверка существования анимации
     */
    public boolean hasAnimation(String name) {
        return engines.containsKey(name.toLowerCase());
    }

    /**
     * Установка анимации по умолчанию
     */
    public void setDefaultAnimation(String name) {
        if (hasAnimation(name)) {
            this.defaultAnimationName = name.toLowerCase();
            plugin.getLogger().info("Default animation set to: " + name);
        } else {
            plugin.getLogger().warning("Cannot set default animation to " + name + " - not found");
        }
    }

    /**
     * Получение имени анимации по умолчанию
     */
    public String getDefaultAnimationName() {
        return defaultAnimationName;
    }

    /**
     * Остановка всех анимаций
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down Animation Manager...");
        
        // Отключаем PacketEvents листенер
        try {
            if (packetListener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
                plugin.getLogger().info("Unregistered PacketEvents listener");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error unregistering PacketEvents listener: " + e.getMessage());
        }
        
        for (BanAnimationEngine engine : engines.values()) {
            engine.shutdown();
        }
        engines.clear();
    }

    /**
     * Получение информации об анимации
     */
    public String getAnimationInfo(String name) {
        if (!hasAnimation(name)) {
            return "Animation not found: " + name;
        }

        // Здесь можно добавить детальную информацию о конфигурации
        return "Animation: " + name + " (loaded)";
    }
}
