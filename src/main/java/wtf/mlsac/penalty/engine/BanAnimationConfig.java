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

package wtf.mlsac.penalty.engine;

import org.bukkit.Particle;
import org.bukkit.Sound;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven конфигурация для анимаций бана.
 * Поддерживает параллельные стейджи, кастомные цвета частиц и звуковые эффекты.
 */
public class BanAnimationConfig {
    /** Общая длительность анимации в тиках (20 тиков = 1 секунда) */
    public int totalTicks = 80;
    
    /** Замораживать ли игрока (Slowness, Jump Boost, Levitation) */
    public boolean freezePlayer = true;
    
    /** Бить ли молнией в конце анимации */
    public boolean strikeLightningAtEnd = true;
    
    /** Раздеть игрока (снять броню и выкинуть предметы) */
    public boolean stripPlayer = false;
    
    /** Интервал выкидывания предметов в тиках */
    public int itemDropIntervalTicks = 5;
    
    /** Кастомная левитация (если -1, используется стандартная) */
    public int customLevitationAmplifier = -1;
    
    /** Кастомное перемещение игрока */
    public MovementConfig movement = null;
    
    /** Список стейджей анимации (могут выполняться параллельно) */
    public List<StageConfig> stages = new ArrayList<>();
    
    /**
     * Конфигурация перемещения игрока в пространстве.
     */
    public static class MovementConfig {
        /** Начальный тик перемещения */
        public int startTick = 0;
        
        /** Конечный тик перемещения */
        public int endTick = 0;
        
        /** Смещение по X */
        public double offsetX = 0.0;
        
        /** Смещение по Y */
        public double offsetY = 0.0;
        
        /** Смещение по Z */
        public double offsetZ = 0.0;
        
        /** Плавное перемещение (интерполяция) */
        public boolean smooth = true;
    }

    /**
     * Конфигурация одного стейджа анимации.
     * Стейджи могут пересекаться по времени для создания сложных эффектов.
     */
    public static class StageConfig {
        /** Название стейджа (для отладки) */
        public String name = "Unnamed Stage";
        
        /** Начальный тик стейджа (включительно) */
        public int startTick = 0;
        
        /** Конечный тик стейджа (включительно) */
        public int endTick = 0;
        
        /** Список эффектов частиц в этом стейдже */
        public List<ParticleEffectConfig> particles = new ArrayList<>();
        
        /** Список звуковых эффектов в этом стейдже */
        public List<SoundEffectConfig> sounds = new ArrayList<>();
        
        /** Список тайтлов в этом стейдже */
        public List<TitleConfig> titles = new ArrayList<>();
        
        /** Ударить молнией на определенном тике стейджа */
        public List<Integer> lightningTicks = new ArrayList<>();
        
        /** След из частиц за игроком */
        public ParticleTrailConfig particleTrail = null;
    }

    /**
     * Конфигурация эффекта частиц с поддержкой цветов и трансформаций.
     */
    public static class ParticleEffectConfig {
        /** Тип частицы (Bukkit Particle enum) */
        public Particle particleType = Particle.FLAME;
        
        /** Форма эффекта: POINT, RISING_SPIRAL, SPHERE, EXPLOSION, HELIX, CIRCLE */
        public String shape = "POINT";
        
        /** Интервал между спавном частиц (в тиках) */
        public int intervalTicks = 1;
        
        /** Количество частиц за один спавн */
        public int count = 1;

        // === ТРАНСФОРМАЦИИ (линейная интерполяция от start к end) ===
        /** Начальный радиус эффекта */
        public double radiusStart = 1.0;
        
        /** Конечный радиус эффекта */
        public double radiusEnd = 1.0;
        
        /** Начальное смещение по высоте */
        public double heightOffsetStart = 0.0;
        
        /** Конечное смещение по высоте */
        public double heightOffsetEnd = 0.0;

        // === ЦВЕТ И РАЗМЕР ===
        /** Использовать ли кастомный цвет (для REDSTONE/DUST частиц) */
        public boolean isColored = false;
        
        /** Красный компонент цвета (0-255) */
        public int red = 255;
        
        /** Зеленый компонент цвета (0-255) */
        public int green = 255;
        
        /** Синий компонент цвета (0-255) */
        public int blue = 255;
        
        /** Размер частицы (для DUST частиц) */
        public float particleSize = 1.0f;
        
        /** Скорость частиц */
        public double speed = 0.0;
        
        /** Разброс частиц по X */
        public double offsetX = 0.0;
        
        /** Разброс частиц по Y */
        public double offsetY = 0.0;
        
        /** Разброс частиц по Z */
        public double offsetZ = 0.0;
    }

    /**
     * Конфигурация звукового эффекта с поддержкой зацикливания.
     */
    public static class SoundEffectConfig {
        /** Тип звука (Bukkit Sound enum) */
        public Sound soundType = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        
        /** Тик воспроизведения относительно начала стейджа */
        public int playAtTick = 0;
        
        /** Громкость звука */
        public float volume = 1.0f;
        
        /** Высота тона звука */
        public float pitch = 1.0f;
        
        /** Зацикливать ли звук */
        public boolean loop = false;
        
        /** Интервал зацикливания (в тиках) */
        public int loopIntervalTicks = 20;
    }
    
    /**
     * Конфигурация тайтла на экране игрока.
     */
    public static class TitleConfig {
        /** Тик показа тайтла относительно начала стейджа */
        public int showAtTick = 0;
        
        /** Заголовок (title) */
        public String title = "";
        
        /** Подзаголовок (subtitle) */
        public String subtitle = "";
        
        /** Время появления (в тиках) */
        public int fadeIn = 10;
        
        /** Время показа (в тиках) */
        public int stay = 40;
        
        /** Время исчезновения (в тиках) */
        public int fadeOut = 10;
    }
    
    /**
     * Конфигурация следа из частиц за игроком.
     */
    public static class ParticleTrailConfig {
        /** Тип частицы */
        public Particle particleType = Particle.FLAME;
        
        /** Интервал спавна частиц (в тиках) */
        public int intervalTicks = 2;
        
        /** Количество частиц за один спавн */
        public int count = 3;
        
        /** Использовать кастомный цвет */
        public boolean isColored = false;
        
        /** Красный компонент цвета (0-255) */
        public int red = 255;
        
        /** Зеленый компонент цвета (0-255) */
        public int green = 255;
        
        /** Синий компонент цвета (0-255) */
        public int blue = 255;
        
        /** Размер частицы */
        public float particleSize = 1.0f;
        
        /** Разброс частиц */
        public double offsetX = 0.2;
        public double offsetY = 0.2;
        public double offsetZ = 0.2;
    }
}
