/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.volmit.iris.engine.object;

import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.stream.ProceduralStream;
import com.volmit.iris.util.stream.interpolation.Interpolated;

import java.util.List;

public interface IRare {
    /**
     * Shared no-op interpolation helper for rarity selection streams (selection
     * results are discrete and must never be interpolated).
     */
    Interpolated<IRare> SELECTOR = new Interpolated<IRare>() {
        @Override
        public double toDouble(IRare t) {
            return 0;
        }

        @Override
        public IRare fromDouble(double d) {
            return null;
        }
    };

    static <T extends IRare> ProceduralStream<T> stream(ProceduralStream<Double> noise, List<T> possibilities, boolean legacyRarity) {
        RareTable<T> table = new RareTable<>(possibilities);
        @SuppressWarnings("unchecked") Interpolated<T> selector = (Interpolated<T>) (Interpolated<?>) SELECTOR;
        if (legacyRarity) {
            return ProceduralStream.of(
                    (x, z) -> table.pickLegacy(noise.get(x, z)),
                    (x, y, z) -> table.pickLegacy(noise.get(x, y, z)),
                    selector);
        }
        return ProceduralStream.of(
                (x, z) -> table.pick(noise.get(x, z)),
                (x, y, z) -> table.pick(noise.get(x, y, z)),
                selector);
    }

    /**
     * Precomputed selection table for one fixed possibility list. Mirrors the
     * exact arithmetic order of {@link #pick(List, double)} and
     * {@link #pickLegacy(List, double)} (the weights are the very same
     * divisions, computed once), so selections are bit-identical while avoiding
     * per-sample divisions and iterator overhead.
     */
    final class RareTable<T extends IRare> {
        private final T[] items;
        private final double[] reciprocals;
        private final double totalReciprocal;
        private final int[] rarities;
        private final int totalRarity;

        @SuppressWarnings("unchecked")
        RareTable(List<T> possibilities) {
            this.items = (T[]) possibilities.toArray(new IRare[0]);
            this.reciprocals = new double[items.length];
            this.rarities = new int[items.length];
            double totalR = 0;
            int totalW = 0;
            for (int i = 0; i < items.length; i++) {
                reciprocals[i] = 1d / items[i].getRarity();
                totalR += reciprocals[i];
                rarities[i] = items[i].getRarity();
                totalW += rarities[i];
            }
            this.totalReciprocal = totalR;
            this.totalRarity = totalW;
        }

        public T pick(double noiseValue) {
            if (items.length == 0) {
                return null;
            }

            if (items.length == 1) {
                return items[0];
            }

            double threshold = totalReciprocal * noiseValue;
            double buffer = 0;
            for (int i = 0; i < items.length; i++) {
                buffer += reciprocals[i];
                if (buffer >= threshold) {
                    return items[i];
                }
            }

            return items[items.length - 1];
        }

        public T pickLegacy(double noiseValue) {
            if (items.length == 0) {
                return null;
            }

            if (items.length == 1) {
                return items[0];
            }
            double threshold = totalRarity * (items.length - 1) * noiseValue;
            int buffer = 0;
            for (int i = 0; i < items.length; i++) {
                buffer += totalRarity - rarities[i];

                if (buffer >= threshold) {
                    return items[i];
                }
            }
            return items[items.length - 1];
        }
    }


    static <T extends IRare> T pickSlowly(List<T> possibilities, double noiseValue) {
        if (possibilities.isEmpty()) {
            return null;
        }

        if (possibilities.size() == 1) {
            return possibilities.get(0);
        }

        KList<T> rarityTypes = new KList<>();
        int totalRarity = 0;
        for (T i : possibilities) {
            totalRarity += IRare.get(i);
        }

        for (T i : possibilities) {
            rarityTypes.addMultiple(i, totalRarity / IRare.get(i));
        }

        return rarityTypes.get((int) (noiseValue * rarityTypes.last()));
    }

    static <T extends IRare> T pick(List<T> possibilities, double noiseValue) {
        if (possibilities.isEmpty()) {
            return null;
        }

        if (possibilities.size() == 1) {
            return possibilities.getFirst();
        }

        double total = 0;
        for (T i : possibilities) {
            total += 1d / i.getRarity();
        }

        double threshold = total * noiseValue;
        double buffer = 0;
        for (T i : possibilities) {
            buffer += 1d / i.getRarity();
            if (buffer >= threshold) {
                return i;
            }
        }

        return possibilities.getLast();
    }

    static <T extends IRare> T pickLegacy(List<T> possibilities, double noiseValue) {
        if (possibilities.isEmpty()) {
            return null;
        }

        if (possibilities.size() == 1) {
            return possibilities.get(0);
        }
        int totalWeight = 0; // This is he baseline
        int buffer = 0;
        for (T i : possibilities) { // Im adding all of the rarity together
            totalWeight += i.getRarity();
        }
        double threshold = totalWeight * (possibilities.size() - 1) * noiseValue;
        for (T i : possibilities) {
            buffer += totalWeight - i.getRarity();

            if (buffer >= threshold) {
                return i;
            }
        }
        return possibilities.get(possibilities.size() - 1);
    }


    static <T extends IRare> T pickOld(List<T> possibilities, double noiseValue) {
        if (possibilities.isEmpty()) {
            return null;
        }

        if (possibilities.size() == 1) {
            return possibilities.get(0);
        }

        double completeWeight = 0.0;
        double highestWeight = 0.0;

        for (T item : possibilities) {
            double weight = Math.max(item.getRarity(), 1);
            highestWeight = Math.max(highestWeight, weight);
            completeWeight += weight;
        }

        double r = noiseValue * completeWeight;
        double countWeight = 0.0;

        for (T item : possibilities) {
            double weight = Math.max(highestWeight - Math.max(item.getRarity(), 1), 1);
            countWeight += weight;
            if (countWeight >= r) {
                return item;
            }
        }

        return possibilities.get(possibilities.size() - 1);
    }

    static int get(Object v) {
        return v instanceof IRare ? Math.max(1, ((IRare) v).getRarity()) : 1;
    }

    int getRarity();
}
