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

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import wtf.mlsac.compat.ParticleCompat;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Загрузчик конфигураций анимаций из YAML файлов.
 * Поддерживает полную десериализацию всех параметров анимации.
 */
public class AnimationConfigLoader {
    private final JavaPlugin plugin;

    public AnimationConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Загружает конфигурацию анимации из YAML файла
     * 
     * @param fileName имя файла в папке animations/ (например, "classic_ban.yml")
     * @return загруженная конфигурация или null при ошибке
     */
    public BanAnimationConfig loadFromFile(String fileName) {
        File animationsFolder = new File(plugin.getDataFolder(), "animations");
        if (!animationsFolder.exists()) {
            animationsFolder.mkdirs();
        }

        File configFile = new File(animationsFolder, fileName);
        if (!configFile.exists()) {
            plugin.getLogger().warning("Animation config file not found: " + fileName);
            return null;
        }

        try {
            FileConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            return loadFromYaml(yaml);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load animation config: " + fileName, e);
            return null;
        }
    }

    /**
     * Загружает конфигурацию из секции основного config.yml
     * 
     * @param section секция конфигурации
     * @return загруженная конфигурация
     */
    public BanAnimationConfig loadFromConfigSection(ConfigurationSection section) {
        try {
            return loadFromYaml(section);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load animation from config section", e);
            return AnimationPresets.createClassicBanAnimation();
        }
    }

    /**
     * Внутренний метод загрузки из YAML
     */
    private BanAnimationConfig loadFromYaml(ConfigurationSection yaml) {
        BanAnimationConfig config = new BanAnimationConfig();

        // Основные параметры
        config.totalTicks = yaml.getInt("totalTicks", 80);
        config.freezePlayer = yaml.getBoolean("freezePlayer", true);
        config.strikeLightningAtEnd = yaml.getBoolean("strikeLightningAtEnd", true);
        
        // Новые параметры
        config.stripPlayer = yaml.getBoolean("stripPlayer", false);
        config.itemDropIntervalTicks = yaml.getInt("itemDropIntervalTicks", 5);
        config.customLevitationAmplifier = yaml.getInt("customLevitationAmplifier", -1);
        
        // Загрузка перемещения
        if (yaml.contains("movement")) {
            ConfigurationSection movementSection = yaml.getConfigurationSection("movement");
            if (movementSection != null) {
                config.movement = loadMovement(movementSection);
            }
        }

        // Загрузка стейджей
        if (yaml.contains("stages")) {
            List<?> stagesList = yaml.getList("stages");
            if (stagesList != null) {
                for (Object stageObj : stagesList) {
                    if (stageObj instanceof ConfigurationSection) {
                        ConfigurationSection stageSection = (ConfigurationSection) stageObj;
                        BanAnimationConfig.StageConfig stage = loadStage(stageSection);
                        if (stage != null) {
                            config.stages.add(stage);
                        }
                    } else if (stageObj instanceof Map) {
                        // Попытка обработать как Map (альтернативный формат)
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stageMap = (Map<String, Object>) stageObj;
                        ConfigurationSection stageSection = convertMapToSection(yaml, stageMap);
                        if (stageSection != null) {
                            BanAnimationConfig.StageConfig stage = loadStage(stageSection);
                            if (stage != null) {
                                config.stages.add(stage);
                            }
                        }
                    } else {
                        plugin.getLogger().warning("Unknown stage object type: " + stageObj.getClass().getName());
                    }
                }
            } else {
                plugin.getLogger().warning("Stages list is null!");
            }
        } else {
            plugin.getLogger().warning("No 'stages' section found in YAML!");
        }

        return config;
    }
    
    /**
     * Конвертирует Map в ConfigurationSection для обработки
     */
    private ConfigurationSection convertMapToSection(ConfigurationSection parent, Map<String, Object> map) {
        try {
            // Создаем временную секцию
            org.bukkit.configuration.MemoryConfiguration memConfig = new org.bukkit.configuration.MemoryConfiguration();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                memConfig.set(entry.getKey(), entry.getValue());
            }
            return memConfig;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to convert Map to ConfigurationSection", e);
            return null;
        }
    }
    
    /**
     * Загрузка конфигурации перемещения
     */
    private BanAnimationConfig.MovementConfig loadMovement(ConfigurationSection section) {
        BanAnimationConfig.MovementConfig movement = new BanAnimationConfig.MovementConfig();
        
        movement.startTick = section.getInt("startTick", 0);
        movement.endTick = section.getInt("endTick", 0);
        movement.offsetX = section.getDouble("offsetX", 0.0);
        movement.offsetY = section.getDouble("offsetY", 0.0);
        movement.offsetZ = section.getDouble("offsetZ", 0.0);
        movement.smooth = section.getBoolean("smooth", true);
        
        return movement;
    }

    /**
     * Загрузка одного стейджа
     */
    private BanAnimationConfig.StageConfig loadStage(ConfigurationSection section) {
        BanAnimationConfig.StageConfig stage = new BanAnimationConfig.StageConfig();

        stage.name = section.getString("name", "Unnamed Stage");
        stage.startTick = section.getInt("startTick", 0);
        stage.endTick = section.getInt("endTick", 0);

        // Загрузка частиц
        if (section.contains("particles")) {
            List<?> particlesList = section.getList("particles");
            if (particlesList != null) {
                for (Object particleObj : particlesList) {
                    if (particleObj instanceof ConfigurationSection) {
                        ConfigurationSection particleSection = (ConfigurationSection) particleObj;
                        BanAnimationConfig.ParticleEffectConfig particle = loadParticle(particleSection);
                        if (particle != null) {
                            stage.particles.add(particle);
                        }
                    } else if (particleObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> particleMap = (Map<String, Object>) particleObj;
                        ConfigurationSection particleSection = convertMapToSection(section, particleMap);
                        if (particleSection != null) {
                            BanAnimationConfig.ParticleEffectConfig particle = loadParticle(particleSection);
                            if (particle != null) {
                                stage.particles.add(particle);
                            }
                        }
                    } else {
                        plugin.getLogger().warning("Unknown particle object type: " + particleObj.getClass().getName());
                    }
                }
            } else {
                plugin.getLogger().warning("Particles list is null for stage: " + stage.name);
            }
        }

        // Загрузка звуков
        if (section.contains("sounds")) {
            List<?> soundsList = section.getList("sounds");
            if (soundsList != null) {
                for (Object soundObj : soundsList) {
                    if (soundObj instanceof ConfigurationSection) {
                        ConfigurationSection soundSection = (ConfigurationSection) soundObj;
                        BanAnimationConfig.SoundEffectConfig sound = loadSound(soundSection);
                        if (sound != null) {
                            stage.sounds.add(sound);
                        }
                    } else if (soundObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> soundMap = (Map<String, Object>) soundObj;
                        ConfigurationSection soundSection = convertMapToSection(section, soundMap);
                        if (soundSection != null) {
                            BanAnimationConfig.SoundEffectConfig sound = loadSound(soundSection);
                            if (sound != null) {
                                stage.sounds.add(sound);
                            }
                        }
                    }
                }
            }
        }
        
        // Загрузка тайтлов
        if (section.contains("titles")) {
            List<?> titlesList = section.getList("titles");
            if (titlesList != null) {
                for (Object titleObj : titlesList) {
                    if (titleObj instanceof ConfigurationSection) {
                        ConfigurationSection titleSection = (ConfigurationSection) titleObj;
                        BanAnimationConfig.TitleConfig title = loadTitle(titleSection);
                        if (title != null) {
                            stage.titles.add(title);
                        }
                    }
                }
            }
        }
        
        // Загрузка молний
        if (section.contains("lightningTicks")) {
            List<?> lightningList = section.getList("lightningTicks");
            if (lightningList != null) {
                for (Object tick : lightningList) {
                    if (tick instanceof Number) {
                        stage.lightningTicks.add(((Number) tick).intValue());
                    }
                }
            }
        }
        
        // Загрузка следа из частиц
        if (section.contains("particleTrail")) {
            ConfigurationSection trailSection = section.getConfigurationSection("particleTrail");
            if (trailSection != null) {
                stage.particleTrail = loadParticleTrail(trailSection);
            }
        }

        return stage;
    }
    
    /**
     * Загрузка конфигурации тайтла
     */
    private BanAnimationConfig.TitleConfig loadTitle(ConfigurationSection section) {
        BanAnimationConfig.TitleConfig title = new BanAnimationConfig.TitleConfig();
        
        title.showAtTick = section.getInt("showAtTick", 0);
        title.title = section.getString("title", "");
        title.subtitle = section.getString("subtitle", "");
        title.fadeIn = section.getInt("fadeIn", 10);
        title.stay = section.getInt("stay", 40);
        title.fadeOut = section.getInt("fadeOut", 10);
        
        return title;
    }
    
    /**
     * Загрузка конфигурации следа из частиц
     */
    private BanAnimationConfig.ParticleTrailConfig loadParticleTrail(ConfigurationSection section) {
        BanAnimationConfig.ParticleTrailConfig trail = new BanAnimationConfig.ParticleTrailConfig();
        
        // Тип частицы - используем ParticleCompat для совместимости версий
        String particleTypeName = section.getString("particleType", "FLAME");
        trail.particleType = ParticleCompat.getParticle(particleTypeName);
        
        if (trail.particleType == null) {
            plugin.getLogger().warning("Unknown particle type in trail: " + particleTypeName + ", using FLAME");
            trail.particleType = ParticleCompat.getParticle("FLAME");
            
            if (trail.particleType == null) {
                trail.particleType = Particle.FLAME;
            }
        }
        
        trail.intervalTicks = section.getInt("intervalTicks", 2);
        trail.count = section.getInt("count", 3);
        trail.isColored = section.getBoolean("isColored", false);
        trail.red = section.getInt("red", 255);
        trail.green = section.getInt("green", 255);
        trail.blue = section.getInt("blue", 255);
        trail.particleSize = (float) section.getDouble("particleSize", 1.0);
        trail.offsetX = section.getDouble("offsetX", 0.2);
        trail.offsetY = section.getDouble("offsetY", 0.2);
        trail.offsetZ = section.getDouble("offsetZ", 0.2);
        
        return trail;
    }

    /**
     * Загрузка конфигурации частицы
     */
    private BanAnimationConfig.ParticleEffectConfig loadParticle(ConfigurationSection section) {
        BanAnimationConfig.ParticleEffectConfig particle = new BanAnimationConfig.ParticleEffectConfig();

        // Тип частицы - используем ParticleCompat для совместимости версий
        String particleTypeName = section.getString("particleType", "FLAME");
        particle.particleType = ParticleCompat.getParticle(particleTypeName);
        
        if (particle.particleType == null) {
            plugin.getLogger().warning("Unknown particle type: " + particleTypeName + ", trying FLAME as fallback");
            particle.particleType = ParticleCompat.getParticle("FLAME");
            
            if (particle.particleType == null) {
                plugin.getLogger().severe("CRITICAL: Cannot resolve any particle type! Animation will not work.");
                particle.particleType = Particle.FLAME; // Last resort
            }
        }

        // Форма
        particle.shape = section.getString("shape", "POINT").toUpperCase();

        // Параметры спавна
        particle.intervalTicks = section.getInt("intervalTicks", 1);
        particle.count = section.getInt("count", 1);

        // Трансформации
        particle.radiusStart = section.getDouble("radiusStart", 1.0);
        particle.radiusEnd = section.getDouble("radiusEnd", 1.0);
        particle.heightOffsetStart = section.getDouble("heightOffsetStart", 0.0);
        particle.heightOffsetEnd = section.getDouble("heightOffsetEnd", 0.0);

        // Цвет
        particle.isColored = section.getBoolean("isColored", false);
        particle.red = section.getInt("red", 255);
        particle.green = section.getInt("green", 255);
        particle.blue = section.getInt("blue", 255);
        particle.particleSize = (float) section.getDouble("particleSize", 1.0);

        // Физика
        particle.speed = section.getDouble("speed", 0.0);
        particle.offsetX = section.getDouble("offsetX", 0.0);
        particle.offsetY = section.getDouble("offsetY", 0.0);
        particle.offsetZ = section.getDouble("offsetZ", 0.0);

        return particle;
    }

    /**
     * Загрузка конфигурации звука
     */
    private BanAnimationConfig.SoundEffectConfig loadSound(ConfigurationSection section) {
        BanAnimationConfig.SoundEffectConfig sound = new BanAnimationConfig.SoundEffectConfig();

        // Тип звука
        String soundTypeName = section.getString("soundType", "ENTITY_EXPERIENCE_ORB_PICKUP");
        try {
            sound.soundType = Sound.valueOf(soundTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown sound type: " + soundTypeName);
            sound.soundType = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        }

        // Параметры воспроизведения
        sound.playAtTick = section.getInt("playAtTick", 0);
        sound.volume = (float) section.getDouble("volume", 1.0);
        sound.pitch = (float) section.getDouble("pitch", 1.0);
        sound.loop = section.getBoolean("loop", false);
        sound.loopIntervalTicks = section.getInt("loopIntervalTicks", 20);

        return sound;
    }

    /**
     * Сохраняет пример конфигурации в файл
     */
    public void saveExampleConfig(String fileName, BanAnimationConfig config) {
        File animationsFolder = new File(plugin.getDataFolder(), "animations");
        if (!animationsFolder.exists()) {
            animationsFolder.mkdirs();
        }

        File configFile = new File(animationsFolder, fileName);
        if (configFile.exists()) {
            plugin.getLogger().info("Animation config already exists: " + fileName);
            return;
        }

        try {
            FileConfiguration yaml = new YamlConfiguration();
            saveToYaml(yaml, config);
            yaml.save(configFile);
            plugin.getLogger().info("Saved example animation config: " + fileName);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save example config: " + fileName, e);
        }
    }

    /**
     * Сохранение конфигурации в YAML (для примеров)
     */
    private void saveToYaml(ConfigurationSection yaml, BanAnimationConfig config) {
        yaml.set("totalTicks", config.totalTicks);
        yaml.set("freezePlayer", config.freezePlayer);
        yaml.set("strikeLightningAtEnd", config.strikeLightningAtEnd);

        // Сохранение стейджей (упрощенная версия для примера)
        yaml.set("stages", null); // Очистка
        for (int i = 0; i < config.stages.size(); i++) {
            BanAnimationConfig.StageConfig stage = config.stages.get(i);
            String path = "stages." + i;
            yaml.set(path + ".name", stage.name);
            yaml.set(path + ".startTick", stage.startTick);
            yaml.set(path + ".endTick", stage.endTick);
            // Примечание: полное сохранение частиц и звуков требует более сложной логики
        }
    }
}
