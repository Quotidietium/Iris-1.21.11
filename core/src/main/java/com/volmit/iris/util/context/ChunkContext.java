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

package com.volmit.iris.util.context;

import com.volmit.iris.engine.IrisComplex;
import com.volmit.iris.engine.object.IrisBiome;
import com.volmit.iris.engine.object.IrisRegion;
import com.volmit.iris.util.parallel.FutureJoiner;
import com.volmit.iris.util.parallel.MultiBurst;
import com.volmit.iris.util.stream.ProceduralStream;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Per-chunk prefilled snapshots of the six 2D streams every generation stage
 * consumes. Java port of the Kotlin original: the constructor used to fan the
 * fill out through coroutines (one launch per cell: 6 x 256 = 1536 dispatched
 * coroutines plus a runBlocking event loop per chunk); it now drives one
 * executor task per row (6 x 16 = 96 tasks). Every cell is still sampled
 * exactly once from the same stream, so the grids are identical.
 */
public class ChunkContext {
    private final int x;
    private final int z;
    private final ChunkedDataCache<Double> height;
    private final ChunkedDataCache<IrisBiome> biome;
    private final ChunkedDataCache<IrisBiome> cave;
    private final ChunkedDataCache<BlockData> rock;
    private final ChunkedDataCache<BlockData> fluid;
    private final ChunkedDataCache<IrisRegion> region;

    public ChunkContext(int x, int z, IrisComplex c) {
        this(x, z, c, true);
    }

    public ChunkContext(int x, int z, IrisComplex c, boolean cache) {
        this(x, z,
                c.getHeightStream(), c.getTrueBiomeStream(), c.getCaveBiomeStream(),
                c.getRockStream(), c.getFluidStream(), c.getRegionStream(), cache);
    }

    public ChunkContext(int x, int z,
                        ProceduralStream<Double> height, ProceduralStream<IrisBiome> biome,
                        ProceduralStream<IrisBiome> cave, ProceduralStream<BlockData> rock,
                        ProceduralStream<BlockData> fluid, ProceduralStream<IrisRegion> region,
                        boolean cache) {
        this.x = x;
        this.z = z;
        this.height = new ChunkedDataCache<>(height, x, z, cache);
        this.biome = new ChunkedDataCache<>(biome, x, z, cache);
        this.cave = new ChunkedDataCache<>(cave, x, z, cache);
        this.rock = new ChunkedDataCache<>(rock, x, z, cache);
        this.fluid = new ChunkedDataCache<>(fluid, x, z, cache);
        this.region = new ChunkedDataCache<>(region, x, z, cache);
        fillAll();
    }

    /**
     * Rows per fill task (4 = 24 tasks per chunk, 4 per stream). The height
     * stream dominates the work; splitting it across 4 tasks keeps its
     * critical path at 4 rows while cutting task/queue churn 4x. Measured
     * (round 17): 1.31x single-chunk, 1.26x under 8-way saturated pregen
     * shape; 8 rows/task collapses under saturation (0.63x - height
     * stragglers) and 1 row/task pays 4x the submission churn. Grids are
     * identical for every grouping (cells are pure functions of stream +
     * coordinates) - only scheduling changes. Override with
     * {@code iris.ctx.rows-per-task}.
     */
    private static final int ROWS_PER_TASK = Math.max(1, Math.min(16,
            Integer.getInteger("iris.ctx.rows-per-task", 4)));

    private void fillAll() {
        // Row-group tasks replace the old 1536 coroutine launches (one per
        // cell) and keep the cold-cache fan-out: a per-stream task would
        // serialize the heavy height stream on one thread (measured 160.7us
        // vs 95.9us on the ctx-fill scenario; 24 tasks of 4 rows measured
        // 87.4us single-chunk - round 17).
        ChunkedDataCache<?>[] caches = {height, biome, cave, rock, fluid, region};
        List<Future<?>> futures = null;
        for (ChunkedDataCache<?> c : caches) {
            if (!c.isCache()) continue;
            for (int j = 0; j < 16; j += ROWS_PER_TASK) {
                if (futures == null) futures = new ArrayList<>(96 / ROWS_PER_TASK + 1);
                final ChunkedDataCache<?> cc = c;
                final int from = j;
                final int to = Math.min(16, j + ROWS_PER_TASK);
                futures.add(MultiBurst.burst.submit(() -> {
                    for (int row = from; row < to; row++) {
                        cc.fillRow(row);
                    }
                }));
            }
        }
        if (futures == null) return;

        // Mirror the old runBlocking join: a failing sample fails the constructor.
        FutureJoiner.join(futures);
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public ChunkedDataCache<Double> getHeight() {
        return height;
    }

    public ChunkedDataCache<IrisBiome> getBiome() {
        return biome;
    }

    public ChunkedDataCache<IrisBiome> getCave() {
        return cave;
    }

    public ChunkedDataCache<BlockData> getRock() {
        return rock;
    }

    public ChunkedDataCache<BlockData> getFluid() {
        return fluid;
    }

    public ChunkedDataCache<IrisRegion> getRegion() {
        return region;
    }
}
