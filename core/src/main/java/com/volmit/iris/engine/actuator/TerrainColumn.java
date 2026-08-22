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

package com.volmit.iris.engine.actuator;

import com.volmit.iris.core.loader.IrisData;
import com.volmit.iris.engine.IrisComplex;
import com.volmit.iris.engine.object.IrisBiome;
import com.volmit.iris.engine.object.IrisDimension;
import com.volmit.iris.engine.object.IrisRegion;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.hunk.Hunk;
import com.volmit.iris.util.math.RNG;
import org.bukkit.block.data.BlockData;

/**
 * The per-column fill loop of {@link IrisTerrainNormalActuator}'s terrain
 * sliver, extracted verbatim with per-column invariants hoisted out of the
 * y-loop (rock/fluid block, hunk height, IrisData handle and the six
 * "has any ore for this placement flag" prechecks). Static and free of any
 * Bukkit class initialization so the exact production loop can also be driven
 * by the offline benchmark harness.
 */
public final class TerrainColumn {
    private TerrainColumn() {
    }

    /**
     * @return the y at which bedrock was forced, or -1 if it wasn't
     */
    public static int fill(int xf, int zf, int realX, int realZ, int he, int hf, int height,
                           Hunk<BlockData> h, IrisBiome biome, IrisRegion region, IrisDimension dimension,
                           IrisData data, IrisComplex complex, RNG rng,
                           BlockData rock, BlockData fluid, BlockData bedrock, boolean generateBedrock) {
        KList<BlockData> blocks = null;
        KList<BlockData> fblocks = null;
        boolean biomeSurfaceOres = biome.hasOres(true);
        boolean regionSurfaceOres = region.hasOres(true);
        boolean dimensionSurfaceOres = dimension.hasOres(true);
        boolean biomeDeepOres = biome.hasOres(false);
        boolean regionDeepOres = region.hasOres(false);
        boolean dimensionDeepOres = dimension.hasOres(false);
        int bedrockAt = -1;

        for (int i = hf; i >= 0; i--) {
            if (i >= height) {
                continue;
            }

            if (i == 0) {
                if (generateBedrock) {
                    h.set(xf, i, zf, bedrock);
                    bedrockAt = i;
                    continue;
                }
            }

            BlockData ore = biomeSurfaceOres ? biome.generateOres(realX, i, realZ, rng, data, true) : null;
            ore = ore == null && regionSurfaceOres ? region.generateOres(realX, i, realZ, rng, data, true) : ore;
            ore = ore == null && dimensionSurfaceOres ? dimension.generateOres(realX, i, realZ, rng, data, true) : ore;
            if (ore != null) {
                h.set(xf, i, zf, ore);
                continue;
            }

            if (i > he && i <= hf) {
                int fdepth = hf - i;

                if (fblocks == null) {
                    fblocks = biome.generateSeaLayers(realX, realZ, rng, hf - he, data);
                }

                if (fblocks.hasIndex(fdepth)) {
                    h.set(xf, i, zf, fblocks.get(fdepth));
                    continue;
                }

                h.set(xf, i, zf, fluid);
                continue;
            }

            if (i <= he) {
                int depth = he - i;
                if (blocks == null) {
                    blocks = biome.generateLayers(dimension, realX, realZ, rng,
                            he,
                            he,
                            data,
                            complex);
                }

                if (blocks.hasIndex(depth)) {
                    h.set(xf, i, zf, blocks.get(depth));
                    continue;
                }

                ore = biomeDeepOres ? biome.generateOres(realX, i, realZ, rng, data, false) : null;
                ore = ore == null && regionDeepOres ? region.generateOres(realX, i, realZ, rng, data, false) : ore;
                ore = ore == null && dimensionDeepOres ? dimension.generateOres(realX, i, realZ, rng, data, false) : ore;

                if (ore != null) {
                    h.set(xf, i, zf, ore);
                } else {
                    h.set(xf, i, zf, rock);
                }
            }
        }

        return bedrockAt;
    }
}
