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


package wtf.mlsac.util;
public class BufferCalculator {
    private static final double LOW_PROBABILITY_THRESHOLD = 0.1;
    public static double calculateBufferIncrease(double probability, double multiplier, double threshold) {
        if (probability <= threshold) {
            return 0.0;
        }
        return (probability - threshold) * multiplier;
    }
    public static double calculateBufferDecrease(double currentBuffer, double decreaseAmount) {
        return Math.max(0.0, currentBuffer - decreaseAmount);
    }
    public static double updateBuffer(double currentBuffer, double probability,
                                       double multiplier, double decreaseAmount, double threshold, double decreaseThreshold) {
        if (probability > threshold) {
            return currentBuffer + calculateBufferIncrease(probability, multiplier, threshold);
        } else if (probability < decreaseThreshold) {
            return calculateBufferDecrease(currentBuffer,
                    scaledDecrease(probability, decreaseAmount, decreaseThreshold));
        }
        return currentBuffer;
    }

    /**
     * Buffer removed when the probability is below the decay threshold. Scales linearly from 0
     * (at the threshold) up to {@code maxAmount} (at probability 0) - the more confident the model
     * is that the player is legit, the more violation buffer is cleared.
     */
    public static double scaledDecrease(double probability, double maxAmount, double decreaseThreshold) {
        if (decreaseThreshold <= 0.0) {
            return maxAmount;
        }
        double clampedProbability = Math.max(0.0, Math.min(decreaseThreshold, probability));
        return maxAmount * (decreaseThreshold - clampedProbability) / decreaseThreshold;
    }
    public static double updateBuffer(double currentBuffer, double probability, 
                                       double multiplier, double decreaseAmount, double threshold) {
        return updateBuffer(currentBuffer, probability, multiplier, decreaseAmount, threshold, LOW_PROBABILITY_THRESHOLD);
    }
    public static double updateBuffer(double currentBuffer, double probability, 
                                       double multiplier, double decreaseAmount) {
        return updateBuffer(currentBuffer, probability, multiplier, decreaseAmount, 0.5, LOW_PROBABILITY_THRESHOLD);
    }
    public static boolean shouldFlag(double buffer, double flagThreshold) {
        return buffer >= flagThreshold;
    }
    public static double resetBuffer(double resetValue) {
        return Math.max(0.0, resetValue);
    }
}