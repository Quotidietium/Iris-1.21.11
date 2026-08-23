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

package com.volmit.iris.engine.mantle;

import com.volmit.iris.core.IrisSettings;
import com.volmit.iris.core.nms.container.Pair;
import com.volmit.iris.engine.framework.Engine;
import com.volmit.iris.util.context.ChunkContext;
import com.volmit.iris.util.documentation.ChunkCoordinates;
import com.volmit.iris.util.mantle.Mantle;
import com.volmit.iris.util.mantle.MantleChunk;
import com.volmit.iris.util.mantle.flag.MantleFlag;
import com.volmit.iris.util.parallel.FutureJoiner;
import com.volmit.iris.util.parallel.MultiBurst;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Java port of the Kotlin original. The per-chunk component fan-out used to
 * launch one coroutine per (chunk x component) behind a runBlocking barrier
 * per component group; it now runs one plain executor task per mantle chunk
 * (components sequentially inside) with the same per-group barrier. Flag
 * semantics (double-checked raise, PLANNED skip, group ordering) and the
 * PLANNED sweep at the real radius are unchanged.
 */
public interface MatterGenerator {
    Engine getEngine();

    Mantle getMantle();

    int getRadius();

    int getRealRadius();

    List<Pair<List<MantleComponent>, Integer>> getComponents();

    @ChunkCoordinates
    default void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
        if (!getEngine().getDimension().isUseMantle()) {
            return;
        }
        boolean mc = multicore || IrisSettings.get().getGenerator().isUseMulticoreMantle();

        try (MantleWriter writer = getMantle().write(getEngine().getMantle(), x, z, getRadius(), mc)) {
            for (Pair<List<MantleComponent>, Integer> pair : getComponents()) {
                runGroup(writer, x, z, pair.getA(), pair.getB(), mc, context);
            }

            int radius = getRealRadius();
            for (int i = -radius; i <= radius; i++) {
                for (int j = -radius; j <= radius; j++) {
                    writer.acquireChunk(x + i, z + j).flag(MantleFlag.PLANNED, true);
                }
            }
        }
    }

    private void runGroup(MantleWriter writer, int x, int z, List<MantleComponent> components, int radius, boolean mc, ChunkContext context) {
        if (!mc) {
            for (int i = -radius; i <= radius; i++) {
                for (int j = -radius; j <= radius; j++) {
                    generateChunk(writer, x + i, z + j, components, context);
                }
            }
            return;
        }

        List<Future<?>> futures = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                final int cx = x + i;
                final int cz = z + j;
                futures.add(MultiBurst.burst.submit(() -> generateChunk(writer, cx, cz, components, context)));
            }
        }
        FutureJoiner.join(futures);
    }

    private void generateChunk(MantleWriter writer, int cx, int cz, List<MantleComponent> components, ChunkContext context) {
        MantleChunk chunk = writer.acquireChunk(cx, cz);
        if (chunk.isFlagged(MantleFlag.PLANNED)) {
            return;
        }

        for (MantleComponent c : components) {
            if (chunk.isFlagged(c.getFlag())) {
                continue;
            }

            chunk.raiseFlag(c.getFlag(), () -> c.generateLayer(writer, cx, cz, context));
        }
    }
}
