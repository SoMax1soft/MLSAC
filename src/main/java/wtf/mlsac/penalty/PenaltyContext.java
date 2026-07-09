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
public class PenaltyContext {
    private final String playerName;
    private final int violationLevel;
    private final double probability;
    private final double buffer;
    public PenaltyContext(String playerName, int violationLevel, double probability, double buffer) {
        this.playerName = playerName != null ? playerName : "";
        this.violationLevel = violationLevel;
        this.probability = probability;
        this.buffer = buffer;
    }
    public String getPlayerName() {
        return playerName;
    }
    public int getViolationLevel() {
        return violationLevel;
    }
    public double getProbability() {
        return probability;
    }
    public double getBuffer() {
        return buffer;
    }
    public static Builder builder() {
        return new Builder();
    }
    public static class Builder {
        private String playerName = "";
        private int violationLevel = 0;
        private double probability = 0.0;
        private double buffer = 0.0;
        public Builder playerName(String name) {
            this.playerName = name;
            return this;
        }
        public Builder violationLevel(int vl) {
            this.violationLevel = vl;
            return this;
        }
        public Builder probability(double prob) {
            this.probability = prob;
            return this;
        }
        public Builder buffer(double buf) {
            this.buffer = buf;
            return this;
        }
        public PenaltyContext build() {
            return new PenaltyContext(playerName, violationLevel, probability, buffer);
        }
    }
}