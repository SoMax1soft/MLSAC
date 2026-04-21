/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
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
 * This file contains code derived from:
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package wtf.mlsac.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import wtf.mlsac.util.AimProcessor;
import wtf.mlsac.util.BufferCalculator;

public class AIPlayerData {
    private final UUID playerId;
    private final AimProcessor aimProcessor;
    private final Deque<TickData> tickBuffer;
    private final Deque<Double> probabilityHistory;
    private final Deque<ModelProbabilityEntry> modelProbabilityHistory;
    private final Map<String, Double> lastProbabilitiesByModel;
    private final Map<String, Deque<Double>> probabilityHistoryByModel;
    private final int sequence;
    private int ticksSinceAttack;
    private int ticksStep;
    private volatile double buffer;
    private volatile double lastProbability;
    private volatile boolean pendingRequest;
    private volatile boolean isBedrock;
    private volatile int highProbabilityDetections;
    private final Deque<TickData> tickHistory = new ArrayDeque<>(5000);
    private static final int MAX_TICK_HISTORY = 5000;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public AIPlayerData(UUID playerId) {
        this(playerId, 40);
    }

    public AIPlayerData(UUID playerId, int sequence) {
        this.playerId = playerId;
        this.sequence = sequence;
        this.aimProcessor = new AimProcessor();
        this.tickBuffer = new ArrayDeque<>(sequence);
        this.probabilityHistory = new ArrayDeque<>(10);
        this.modelProbabilityHistory = new ArrayDeque<>(10);
        this.lastProbabilitiesByModel = new HashMap<>();
        this.probabilityHistoryByModel = new HashMap<>();
        this.ticksSinceAttack = sequence + 1;
        this.ticksStep = 0;
        this.buffer = 0.0;
        this.lastProbability = 0.0;
        this.pendingRequest = false;
        this.isBedrock = false;
        this.highProbabilityDetections = 0;
    }

    public TickData processTick(float yaw, float pitch) {
        TickData tickData = aimProcessor.process(yaw, pitch);
        lock.writeLock().lock();
        try {
            if (tickBuffer.size() >= sequence) {
                tickBuffer.pollFirst();
            }
            tickBuffer.addLast(tickData);
            if (tickHistory.size() >= MAX_TICK_HISTORY) {
                tickHistory.pollFirst();
            }
            tickHistory.addLast(tickData);
        } finally {
            lock.writeLock().unlock();
        }
        return tickData;
    }

    public void onAttack() {
        lock.writeLock().lock();
        try {
            this.ticksSinceAttack = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onTeleport() {
        lock.writeLock().lock();
        try {
            aimProcessor.reset();
            clearBuffer();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void incrementTicksSinceAttack() {
        lock.writeLock().lock();
        try {
            if (this.ticksSinceAttack <= sequence + 1) {
                this.ticksSinceAttack++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void incrementStepCounter() {
        lock.writeLock().lock();
        try {
            this.ticksStep++;
        } finally {
            lock.writeLock().unlock();
        }
    }


    public boolean shouldSendData(int step, int sequence) {
        lock.readLock().lock();
        try {
            return !pendingRequest && ticksStep >= step && tickBuffer.size() >= sequence;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setPendingRequest(boolean pending) {
        lock.writeLock().lock();
        try {
            this.pendingRequest = pending;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isPendingRequest() {
        lock.readLock().lock();
        try {
            return pendingRequest;
        } finally {
            lock.readLock().unlock();
        }
    }


    public void resetStepCounter() {
        lock.writeLock().lock();
        try {
            this.ticksStep = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TickData> getTickBuffer() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(tickBuffer);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clearBuffer() {
        lock.writeLock().lock();
        try {
            tickBuffer.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void fullReset() {
        lock.writeLock().lock();
        try {
            tickBuffer.clear();
            probabilityHistory.clear();
            modelProbabilityHistory.clear();
            lastProbabilitiesByModel.clear();
            probabilityHistoryByModel.clear();
            aimProcessor.reset();
            pendingRequest = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isInCombat() {
        lock.readLock().lock();
        try {
            return ticksSinceAttack <= sequence;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getBufferSize() {
        lock.readLock().lock();
        try {
            return tickBuffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getSequence() {
        return sequence;
    }

    public int getTicksSinceAttack() {
        lock.readLock().lock();
        try {
            return ticksSinceAttack;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateBuffer(double probability, double multiplier, double decreaseAmount, double threshold) {
        updateBuffer(probability, null, multiplier, decreaseAmount, threshold);
    }

    public void updateBuffer(double probability, String modelName, double multiplier, double decreaseAmount,
            double threshold) {
        lock.writeLock().lock();
        try {
            this.lastProbability = probability;
            if (probabilityHistory.size() >= 10) {
                probabilityHistory.pollFirst();
            }
            probabilityHistory.addLast(probability);
            String normalizedModel = normalizeModelName(modelName);
            if (modelProbabilityHistory.size() >= 10) {
                modelProbabilityHistory.pollFirst();
            }
            modelProbabilityHistory.addLast(new ModelProbabilityEntry(normalizedModel, probability));
            lastProbabilitiesByModel.put(normalizedModel, probability);
            Deque<Double> perModelHistory = probabilityHistoryByModel.computeIfAbsent(normalizedModel,
                    ignored -> new ArrayDeque<>(10));
            if (perModelHistory.size() >= 10) {
                perModelHistory.pollFirst();
            }
            perModelHistory.addLast(probability);
            if (probability > 0.8) {
                this.highProbabilityDetections++;
            }
            this.buffer = BufferCalculator.updateBuffer(buffer, probability, multiplier, decreaseAmount, threshold);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean shouldFlag(double flagThreshold) {
        lock.readLock().lock();
        try {
            return BufferCalculator.shouldFlag(buffer, flagThreshold);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void resetBuffer(double resetValue) {
        lock.writeLock().lock();
        try {
            this.buffer = BufferCalculator.resetBuffer(resetValue);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public double getBuffer() {
        lock.readLock().lock();
        try {
            return buffer;
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getLastProbability() {
        return lastProbability;
    }

    public List<Double> getProbabilityHistory() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(probabilityHistory);
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getAverageProbability() {
        lock.readLock().lock();
        try {
            if (probabilityHistory.isEmpty()) {
                return 0.0;
            }
            return probabilityHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getLastProbability(String modelName) {
        lock.readLock().lock();
        try {
            return lastProbabilitiesByModel.getOrDefault(normalizeModelName(modelName), 0.0D);
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getAverageProbability(String modelName) {
        lock.readLock().lock();
        try {
            Deque<Double> history = probabilityHistoryByModel.get(normalizeModelName(modelName));
            if (history == null || history.isEmpty()) {
                return 0.0D;
            }
            return history.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ModelProbabilityEntry> getModelProbabilityHistory() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(modelProbabilityHistory);
        } finally {
            lock.readLock().unlock();
        }
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return "unknown";
        }
        return modelName.trim().toLowerCase(Locale.ROOT);
    }

    public AimProcessor getAimProcessor() {
        return aimProcessor;
    }

    public boolean isBedrock() {
        return isBedrock;
    }

    public void setBedrock(boolean bedrock) {
        this.isBedrock = bedrock;
    }

    public List<TickData> getTickHistory() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(tickHistory);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getHighProbabilityDetections() {
        return highProbabilityDetections;
    }

    public static final class ModelProbabilityEntry {
        private final String modelName;
        private final double probability;

        public ModelProbabilityEntry(String modelName, double probability) {
            this.modelName = modelName;
            this.probability = probability;
        }

        public String getModelName() {
            return modelName;
        }

        public double getProbability() {
            return probability;
        }
    }
}
