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
import wtf.mlsac.compat.ParticleCompat;

/**
 * Пресеты анимаций для быстрого создания конфигураций
 */
public class AnimationPresets {

    /**
     * Классическая анимация бана с левитацией и сферой
     */
    public static BanAnimationConfig createClassicBanAnimation() {
        BanAnimationConfig config = new BanAnimationConfig();
        config.totalTicks = 80;
        config.freezePlayer = true;
        config.strikeLightningAtEnd = true;

        // Stage 1: Левитация с восходящей спиралью (тики 1-60)
        BanAnimationConfig.StageConfig stage1 = new BanAnimationConfig.StageConfig();
        stage1.name = "Levitation";
        stage1.startTick = 1;
        stage1.endTick = 60;

        BanAnimationConfig.ParticleEffectConfig spiral = new BanAnimationConfig.ParticleEffectConfig();
        spiral.particleType = ParticleCompat.getWitchParticle();
        spiral.shape = "RISING_SPIRAL";
        spiral.intervalTicks = 1;
        spiral.count = 2;
        spiral.radiusStart = 1.5;
        spiral.radiusEnd = 0.8;
        spiral.heightOffsetStart = -1.0;
        spiral.heightOffsetEnd = 2.0;
        spiral.speed = 0.0;
        stage1.particles.add(spiral);

        config.stages.add(stage1);

        // Stage 2: Сжимающаяся сфера (тики 20-75)
        BanAnimationConfig.StageConfig stage2 = new BanAnimationConfig.StageConfig();
        stage2.name = "Sphere";
        stage2.startTick = 20;
        stage2.endTick = 75;

        BanAnimationConfig.ParticleEffectConfig sphere = new BanAnimationConfig.ParticleEffectConfig();
        sphere.particleType = ParticleCompat.getWitchParticle();
        sphere.shape = "SPHERE";
        sphere.intervalTicks = 1;
        sphere.count = 20;
        sphere.radiusStart = 3.0;
        sphere.radiusEnd = 1.0;
        sphere.heightOffsetStart = 0.0;
        sphere.heightOffsetEnd = 0.0;
        sphere.speed = 0.0;
        stage2.particles.add(sphere);

        // Добавляем розовую пыль в сферу
        BanAnimationConfig.ParticleEffectConfig dust = new BanAnimationConfig.ParticleEffectConfig();
        dust.particleType = ParticleCompat.getDustParticle();
        dust.shape = "SPHERE";
        dust.intervalTicks = 3;
        dust.count = 10;
        dust.radiusStart = 3.0;
        dust.radiusEnd = 1.0;
        dust.heightOffsetStart = 0.0;
        dust.heightOffsetEnd = 0.0;
        dust.isColored = true;
        dust.red = 255;
        dust.green = 105;
        dust.blue = 180;
        dust.particleSize = 1.0f;
        dust.speed = 0.0;
        stage2.particles.add(dust);

        config.stages.add(stage2);

        // Stage 3: Взрыв (тик 80)
        BanAnimationConfig.StageConfig stage3 = new BanAnimationConfig.StageConfig();
        stage3.name = "Explosion";
        stage3.startTick = 80;
        stage3.endTick = 80;

        BanAnimationConfig.ParticleEffectConfig explosion = new BanAnimationConfig.ParticleEffectConfig();
        explosion.particleType = ParticleCompat.getWitchParticle();
        explosion.shape = "EXPLOSION";
        explosion.intervalTicks = 1;
        explosion.count = 50;
        explosion.radiusStart = 2.0;
        explosion.radiusEnd = 2.0;
        explosion.heightOffsetStart = 0.0;
        explosion.heightOffsetEnd = 0.0;
        explosion.speed = 0.0;
        stage3.particles.add(explosion);

        config.stages.add(stage3);

        return config;
    }

    /**
     * Драматичная анимация с огнем и дымом
     */
    public static BanAnimationConfig createFireBanAnimation() {
        BanAnimationConfig config = new BanAnimationConfig();
        config.totalTicks = 100;
        config.freezePlayer = true;
        config.strikeLightningAtEnd = true;

        // Stage 1: Огненная спираль
        BanAnimationConfig.StageConfig stage1 = new BanAnimationConfig.StageConfig();
        stage1.name = "Fire Spiral";
        stage1.startTick = 1;
        stage1.endTick = 60;

        BanAnimationConfig.ParticleEffectConfig fireSpiral = new BanAnimationConfig.ParticleEffectConfig();
        fireSpiral.particleType = Particle.FLAME;
        fireSpiral.shape = "RISING_SPIRAL";
        fireSpiral.intervalTicks = 1;
        fireSpiral.count = 3;
        fireSpiral.radiusStart = 2.0;
        fireSpiral.radiusEnd = 1.0;
        fireSpiral.heightOffsetStart = -1.0;
        fireSpiral.heightOffsetEnd = 3.0;
        fireSpiral.speed = 0.02;
        stage1.particles.add(fireSpiral);

        // Добавляем дым
        BanAnimationConfig.ParticleEffectConfig smoke = new BanAnimationConfig.ParticleEffectConfig();
        smoke.particleType = Particle.SMOKE_LARGE;
        smoke.shape = "POINT";
        smoke.intervalTicks = 2;
        smoke.count = 5;
        smoke.radiusStart = 0.0;
        smoke.radiusEnd = 0.0;
        smoke.heightOffsetStart = 0.0;
        smoke.heightOffsetEnd = 2.0;
        smoke.speed = 0.05;
        stage1.particles.add(smoke);

        config.stages.add(stage1);

        // Stage 2: Огненная сфера
        BanAnimationConfig.StageConfig stage2 = new BanAnimationConfig.StageConfig();
        stage2.name = "Fire Sphere";
        stage2.startTick = 40;
        stage2.endTick = 90;

        BanAnimationConfig.ParticleEffectConfig fireSphere = new BanAnimationConfig.ParticleEffectConfig();
        fireSphere.particleType = Particle.FLAME;
        fireSphere.shape = "SPHERE";
        fireSphere.intervalTicks = 1;
        fireSphere.count = 30;
        fireSphere.radiusStart = 3.5;
        fireSphere.radiusEnd = 0.5;
        fireSphere.heightOffsetStart = 1.0;
        fireSphere.heightOffsetEnd = 1.0;
        fireSphere.speed = 0.0;
        stage2.particles.add(fireSphere);

        config.stages.add(stage2);

        // Stage 3: Взрыв лавы
        BanAnimationConfig.StageConfig stage3 = new BanAnimationConfig.StageConfig();
        stage3.name = "Lava Explosion";
        stage3.startTick = 100;
        stage3.endTick = 100;

        BanAnimationConfig.ParticleEffectConfig lavaExplosion = new BanAnimationConfig.ParticleEffectConfig();
        lavaExplosion.particleType = Particle.LAVA;
        lavaExplosion.shape = "EXPLOSION";
        lavaExplosion.intervalTicks = 1;
        lavaExplosion.count = 100;
        lavaExplosion.radiusStart = 3.0;
        lavaExplosion.radiusEnd = 3.0;
        lavaExplosion.heightOffsetStart = 1.0;
        lavaExplosion.heightOffsetEnd = 1.0;
        lavaExplosion.speed = 0.1;
        stage3.particles.add(lavaExplosion);

        config.stages.add(stage3);

        return config;
    }

    /**
     * Мистическая анимация с порталом Края
     */
    public static BanAnimationConfig createEnderBanAnimation() {
        BanAnimationConfig config = new BanAnimationConfig();
        config.totalTicks = 90;
        config.freezePlayer = true;
        config.strikeLightningAtEnd = false;

        // Stage 1: Частицы портала
        BanAnimationConfig.StageConfig stage1 = new BanAnimationConfig.StageConfig();
        stage1.name = "Portal Opening";
        stage1.startTick = 1;
        stage1.endTick = 50;

        BanAnimationConfig.ParticleEffectConfig portal = new BanAnimationConfig.ParticleEffectConfig();
        portal.particleType = Particle.PORTAL;
        portal.shape = "SPHERE";
        portal.intervalTicks = 1;
        portal.count = 25;
        portal.radiusStart = 0.5;
        portal.radiusEnd = 2.5;
        portal.heightOffsetStart = 1.0;
        portal.heightOffsetEnd = 1.0;
        portal.speed = 1.0;
        stage1.particles.add(portal);

        config.stages.add(stage1);

        // Stage 2: Частицы Края
        BanAnimationConfig.StageConfig stage2 = new BanAnimationConfig.StageConfig();
        stage2.name = "Ender Vortex";
        stage2.startTick = 30;
        stage2.endTick = 80;

        BanAnimationConfig.ParticleEffectConfig enderEye = new BanAnimationConfig.ParticleEffectConfig();
        enderEye.particleType = ParticleCompat.getEndRodParticle();
        enderEye.shape = "RISING_SPIRAL";
        enderEye.intervalTicks = 1;
        enderEye.count = 2;
        enderEye.radiusStart = 2.0;
        enderEye.radiusEnd = 0.3;
        enderEye.heightOffsetStart = -0.5;
        enderEye.heightOffsetEnd = 2.5;
        enderEye.speed = 0.05;
        stage2.particles.add(enderEye);

        config.stages.add(stage2);

        // Stage 3: Телепортация
        BanAnimationConfig.StageConfig stage3 = new BanAnimationConfig.StageConfig();
        stage3.name = "Teleport";
        stage3.startTick = 90;
        stage3.endTick = 90;

        BanAnimationConfig.ParticleEffectConfig teleport = new BanAnimationConfig.ParticleEffectConfig();
        teleport.particleType = Particle.PORTAL;
        teleport.shape = "EXPLOSION";
        teleport.intervalTicks = 1;
        teleport.count = 200;
        teleport.radiusStart = 2.0;
        teleport.radiusEnd = 2.0;
        teleport.heightOffsetStart = 1.0;
        teleport.heightOffsetEnd = 1.0;
        teleport.speed = 2.0;
        stage3.particles.add(teleport);

        config.stages.add(stage3);

        return config;
    }

    /**
     * Радужная анимация с цветными частицами
     */
    public static BanAnimationConfig createRainbowBanAnimation() {
        BanAnimationConfig config = new BanAnimationConfig();
        config.totalTicks = 100;
        config.freezePlayer = true;
        config.strikeLightningAtEnd = true;

        // Stage 1: Красная спираль
        addColoredSpiralStage(config, "Red Spiral", 1, 30, 255, 0, 0);
        
        // Stage 2: Оранжевая спираль (параллельно)
        addColoredSpiralStage(config, "Orange Spiral", 20, 50, 255, 165, 0);
        
        // Stage 3: Желтая спираль (параллельно)
        addColoredSpiralStage(config, "Yellow Spiral", 40, 70, 255, 255, 0);
        
        // Stage 4: Зеленая спираль (параллельно)
        addColoredSpiralStage(config, "Green Spiral", 60, 90, 0, 255, 0);
        
        // Stage 5: Синяя сфера
        BanAnimationConfig.StageConfig blueSphere = new BanAnimationConfig.StageConfig();
        blueSphere.name = "Blue Sphere";
        blueSphere.startTick = 70;
        blueSphere.endTick = 100;

        BanAnimationConfig.ParticleEffectConfig sphere = new BanAnimationConfig.ParticleEffectConfig();
        sphere.particleType = Particle.REDSTONE;
        sphere.shape = "SPHERE";
        sphere.intervalTicks = 1;
        sphere.count = 25;
        sphere.radiusStart = 3.5;
        sphere.radiusEnd = 0.5;
        sphere.heightOffsetStart = 1.0;
        sphere.heightOffsetEnd = 1.0;
        sphere.isColored = true;
        sphere.red = 0;
        sphere.green = 0;
        sphere.blue = 255;
        sphere.particleSize = 1.5f;
        sphere.speed = 0.0;
        blueSphere.particles.add(sphere);

        config.stages.add(blueSphere);

        return config;
    }

    /**
     * Вспомогательный метод для создания цветной спирали
     */
    private static void addColoredSpiralStage(BanAnimationConfig config, String name, 
                                             int start, int end, int r, int g, int b) {
        BanAnimationConfig.StageConfig stage = new BanAnimationConfig.StageConfig();
        stage.name = name;
        stage.startTick = start;
        stage.endTick = end;

        BanAnimationConfig.ParticleEffectConfig spiral = new BanAnimationConfig.ParticleEffectConfig();
        spiral.particleType = Particle.REDSTONE;
        spiral.shape = "RISING_SPIRAL";
        spiral.intervalTicks = 1;
        spiral.count = 3;
        spiral.radiusStart = 2.0;
        spiral.radiusEnd = 1.0;
        spiral.heightOffsetStart = -0.5;
        spiral.heightOffsetEnd = 2.5;
        spiral.isColored = true;
        spiral.red = r;
        spiral.green = g;
        spiral.blue = b;
        spiral.particleSize = 1.2f;
        spiral.speed = 0.0;
        stage.particles.add(spiral);

        config.stages.add(stage);
    }

    /**
     * Драматичная анимация с ДНК-спиралью и звуками
     */
    public static BanAnimationConfig createDramaticBanAnimation() {
        BanAnimationConfig config = new BanAnimationConfig();
        config.totalTicks = 120;
        config.freezePlayer = true;
        config.strikeLightningAtEnd = true;

        // Stage 1: Нарастающий гул
        BanAnimationConfig.StageConfig stage1 = new BanAnimationConfig.StageConfig();
        stage1.name = "Build Up";
        stage1.startTick = 1;
        stage1.endTick = 40;

        BanAnimationConfig.ParticleEffectConfig helix = new BanAnimationConfig.ParticleEffectConfig();
        helix.particleType = ParticleCompat.getEndRodParticle();
        helix.shape = "HELIX";
        helix.intervalTicks = 1;
        helix.count = 2;
        helix.radiusStart = 0.5;
        helix.radiusEnd = 1.5;
        helix.heightOffsetStart = 0.0;
        helix.heightOffsetEnd = 2.0;
        helix.speed = 0.02;
        stage1.particles.add(helix);

        BanAnimationConfig.SoundEffectConfig ambientSound = new BanAnimationConfig.SoundEffectConfig();
        ambientSound.soundType = Sound.BLOCK_PORTAL_AMBIENT;
        ambientSound.playAtTick = 0;
        ambientSound.volume = 0.5f;
        ambientSound.pitch = 0.5f;
        ambientSound.loop = true;
        ambientSound.loopIntervalTicks = 10;
        stage1.sounds.add(ambientSound);

        config.stages.add(stage1);

        // Stage 2: Кульминация
        BanAnimationConfig.StageConfig stage2 = new BanAnimationConfig.StageConfig();
        stage2.name = "Climax";
        stage2.startTick = 40;
        stage2.endTick = 100;

        BanAnimationConfig.ParticleEffectConfig redSphere = new BanAnimationConfig.ParticleEffectConfig();
        redSphere.particleType = Particle.REDSTONE;
        redSphere.shape = "SPHERE";
        redSphere.intervalTicks = 1;
        redSphere.count = 30;
        redSphere.radiusStart = 3.0;
        redSphere.radiusEnd = 0.3;
        redSphere.heightOffsetStart = 1.0;
        redSphere.heightOffsetEnd = 1.0;
        redSphere.isColored = true;
        redSphere.red = 255;
        redSphere.green = 0;
        redSphere.blue = 0;
        redSphere.particleSize = 2.0f;
        redSphere.speed = 0.0;
        stage2.particles.add(redSphere);

        BanAnimationConfig.SoundEffectConfig warningSound = new BanAnimationConfig.SoundEffectConfig();
        warningSound.soundType = Sound.BLOCK_NOTE_BLOCK_PLING;
        warningSound.playAtTick = 0;
        warningSound.volume = 1.0f;
        warningSound.pitch = 2.0f;
        warningSound.loop = false;
        stage2.sounds.add(warningSound);

        config.stages.add(stage2);

        // Stage 3: Финальный взрыв
        BanAnimationConfig.StageConfig stage3 = new BanAnimationConfig.StageConfig();
        stage3.name = "Final Explosion";
        stage3.startTick = 100;
        stage3.endTick = 120;

        BanAnimationConfig.ParticleEffectConfig explosion = new BanAnimationConfig.ParticleEffectConfig();
        explosion.particleType = Particle.EXPLOSION_LARGE;
        explosion.shape = "EXPLOSION";
        explosion.intervalTicks = 5;
        explosion.count = 20;
        explosion.radiusStart = 3.0;
        explosion.radiusEnd = 3.0;
        explosion.heightOffsetStart = 1.0;
        explosion.heightOffsetEnd = 1.0;
        explosion.speed = 0.1;
        stage3.particles.add(explosion);

        BanAnimationConfig.SoundEffectConfig explosionSound = new BanAnimationConfig.SoundEffectConfig();
        explosionSound.soundType = Sound.ENTITY_GENERIC_EXPLODE;
        explosionSound.playAtTick = 0;
        explosionSound.volume = 2.0f;
        explosionSound.pitch = 0.8f;
        explosionSound.loop = false;
        stage3.sounds.add(explosionSound);

        config.stages.add(stage3);

        return config;
    }
}
