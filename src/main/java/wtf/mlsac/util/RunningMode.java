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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
public class RunningMode {
    private static final double THRESHOLD = 1e-3;
    private final Queue<Double> addList;
    private final Map<Double, Integer> popularityMap;
    private final int maxSize;
    public RunningMode(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("There's no mode to a size 0 or negative list!");
        }
        this.addList = new ArrayDeque<>(maxSize);
        this.popularityMap = new HashMap<>();
        this.maxSize = maxSize;
    }
    public int size() {
        return addList.size();
    }
    public int getMaxSize() {
        return maxSize;
    }
    public void add(double value) {
        pop();
        for (Map.Entry<Double, Integer> entry : popularityMap.entrySet()) {
            if (Math.abs(entry.getKey() - value) < THRESHOLD) {
                entry.setValue(entry.getValue() + 1);
                addList.add(entry.getKey());
                return;
            }
        }
        popularityMap.put(value, 1);
        addList.add(value);
    }
    private void pop() {
        if (addList.size() >= maxSize) {
            Double type = addList.poll();
            if (type != null) {
                Integer popularity = popularityMap.get(type);
                if (popularity != null) {
                    if (popularity == 1) {
                        popularityMap.remove(type);
                    } else {
                        popularityMap.put(type, popularity - 1);
                    }
                }
            }
        }
    }
    public Pair<Double, Integer> getMode() {
        int max = 0;
        Double mostPopular = null;
        for (Map.Entry<Double, Integer> entry : popularityMap.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                mostPopular = entry.getKey();
            }
        }
        return new Pair<>(mostPopular, max);
    }
    public void clear() {
        addList.clear();
        popularityMap.clear();
    }
}