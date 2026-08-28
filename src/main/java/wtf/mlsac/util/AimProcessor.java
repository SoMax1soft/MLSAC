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

package wtf.mlsac.util;

import wtf.mlsac.data.TickData;

public class AimProcessor {
    private static final int SIGNIFICANT_SAMPLES_THRESHOLD = 15;
    private static final float MAX_DELTA_FOR_GCD = 5.0f;
    private static final int TOTAL_SAMPLES_THRESHOLD = 80;
    private final RunningMode xRotMode;
    private final RunningMode yRotMode;
    private float lastXRot;
    private float lastYRot;
    private float lastYaw;
    private float lastPitch;
    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private float lastYawAccel;
    private float lastPitchAccel;
    private float currentYawAccel;
    private float currentPitchAccel;
    private double modeX;
    private double modeY;
    private boolean hasLastRotation;

    public AimProcessor() {
        this(TOTAL_SAMPLES_THRESHOLD);
    }

    public AimProcessor(int modeSize) {
        this.xRotMode = new RunningMode(modeSize);
        this.yRotMode = new RunningMode(modeSize);
        reset();
    }

    public void reset() {
        lastYaw = 0;
        lastPitch = 0;
        lastXRot = 0;
        lastYRot = 0;
        lastDeltaYaw = 0;
        lastDeltaPitch = 0;
        lastYawAccel = 0;
        lastPitchAccel = 0;
        currentYawAccel = 0;
        currentPitchAccel = 0;
        modeX = 0;
        modeY = 0;
        hasLastRotation = false;
        xRotMode.clear();
        yRotMode.clear();
    }

    public TickData process(float yaw, float pitch) {
        // Rotations come straight off the wire, so NaN/Infinity are reachable.
        // Storing one would poison lastYaw/lastPitch permanently and silently
        // disable the check for that player, so drop the tick instead.
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return new TickData(0, 0, 0, 0, 0, 0, 0, 0);
        }

        if (!hasLastRotation) {
            lastYaw = yaw;
            lastPitch = pitch;
            lastDeltaYaw = 0;
            lastDeltaPitch = 0;
            lastYawAccel = 0;
            lastPitchAccel = 0;
            currentYawAccel = 0;
            currentPitchAccel = 0;
            hasLastRotation = true;
            return new TickData(0, 0, 0, 0, 0, 0, 0, 0);
        }

        float deltaYaw = normalizeAngle(yaw - lastYaw);
        float deltaPitch = pitch - lastPitch;
        float deltaYawAbs = Math.abs(deltaYaw);
        float deltaPitchAbs = Math.abs(deltaPitch);

        lastYawAccel = currentYawAccel;
        lastPitchAccel = currentPitchAccel;

        currentYawAccel = deltaYaw - lastDeltaYaw;
        currentPitchAccel = deltaPitch - lastDeltaPitch;

        float jerkYaw = currentYawAccel - lastYawAccel;
        float jerkPitch = currentPitchAccel - lastPitchAccel;

        double divisorX = GcdMath.gcd(deltaYawAbs, lastXRot);
        if (deltaYawAbs > 0 && deltaYawAbs < MAX_DELTA_FOR_GCD && divisorX > GcdMath.MINIMUM_DIVISOR) {
            xRotMode.add(divisorX);
            lastXRot = deltaYawAbs;
        }
        double divisorY = GcdMath.gcd(deltaPitchAbs, lastYRot);
        if (deltaPitchAbs > 0 && deltaPitchAbs < MAX_DELTA_FOR_GCD && divisorY > GcdMath.MINIMUM_DIVISOR) {
            yRotMode.add(divisorY);
            lastYRot = deltaPitchAbs;
        }
        updateModes();

        float gcdErrorYaw = calculateGcdError(deltaYaw, modeX);
        float gcdErrorPitch = calculateGcdError(deltaPitch, modeY);

        lastYaw = yaw;
        lastPitch = pitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;

        return new TickData(deltaYaw, deltaPitch, currentYawAccel, currentPitchAccel,
                jerkYaw, jerkPitch, gcdErrorYaw, gcdErrorPitch);
    }

    private float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180)
            angle -= 360;
        else if (angle < -180)
            angle += 360;
        return angle;
    }

    private void updateModes() {
        if (xRotMode.size() > SIGNIFICANT_SAMPLES_THRESHOLD) {
            Pair<Double, Integer> modeResult = xRotMode.getMode();
            if (modeResult.first() != null && modeResult.second() > SIGNIFICANT_SAMPLES_THRESHOLD) {
                modeX = modeResult.first();
            }
        }
        if (yRotMode.size() > SIGNIFICANT_SAMPLES_THRESHOLD) {
            Pair<Double, Integer> modeResult = yRotMode.getMode();
            if (modeResult.first() != null && modeResult.second() > SIGNIFICANT_SAMPLES_THRESHOLD) {
                modeY = modeResult.first();
            }
        }
    }

    private float calculateGcdError(float delta, double mode) {
        if (mode == 0) {
            return 0;
        }
        double absDelta = Math.abs(delta);
        double remainder = absDelta % mode;
        double error = Math.min(remainder, mode - remainder);
        return (float) error;
    }

    public double getModeX() {
        return modeX;
    }

    public double getModeY() {
        return modeY;
    }

    public RunningMode getXRotMode() {
        return xRotMode;
    }

    public RunningMode getYRotMode() {
        return yRotMode;
    }

    public float getCurrentYawAccel() {
        return currentYawAccel;
    }

    public float getCurrentPitchAccel() {
        return currentPitchAccel;
    }

    public float getLastYawAccel() {
        return lastYawAccel;
    }

    public float getLastPitchAccel() {
        return lastPitchAccel;
    }

    public int getSensitivity() {
        if (modeY <= 0) {
            return -1;
        }
        double f = Math.cbrt(modeY / 1.2);
        double sensitivity = (f - 0.2) / 0.6;
        return (int) Math.round(sensitivity * 200);
    }
}