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

package com.volmit.iris.engine.modifier;

import com.volmit.iris.engine.actuator.IrisDecorantActuator;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.engine.framework.Engine;
import com.volmit.iris.engine.framework.EngineAssignedModifier;
import com.volmit.iris.engine.object.*;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.collection.KMap;
import com.volmit.iris.util.context.ChunkContext;
import com.volmit.iris.util.data.B;
import com.volmit.iris.util.documentation.ChunkCoordinates;
import com.volmit.iris.util.function.Consumer4I;
import com.volmit.iris.util.hunk.Hunk;
import com.volmit.iris.util.mantle.Mantle;
import com.volmit.iris.util.mantle.MantleChunk;
import com.volmit.iris.util.math.M;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.matter.Matter;
import com.volmit.iris.util.matter.MatterCavern;
import com.volmit.iris.util.matter.MatterSlice;
import com.volmit.iris.util.matter.slices.MarkerMatter;
import com.volmit.iris.util.scheduling.PrecisionStopwatch;
import lombok.Data;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

public class IrisCarveModifier extends EngineAssignedModifier<BlockData> {
    private final RNG rng;
    private final BlockData AIR = Material.CAVE_AIR.createBlockData();
    private final BlockData LAVA = Material.LAVA.createBlockData();
    private final IrisDecorantActuator decorant;

    public IrisCarveModifier(Engine engine) {
        super(engine, "Carve");
        rng = new RNG(getEngine().getSeedManager().getCarve());
        decorant = new IrisDecorantActuator(engine);
    }

    @Override
    @ChunkCoordinates
    public void onModify(int x, int z, Hunk<BlockData> output, boolean multicore, ChunkContext context) {
        PrecisionStopwatch p = PrecisionStopwatch.start();
        Mantle mantle = getEngine().getMantle().getMantle();
        MantleChunk mc = mantle.getChunk(x, z).use();
        KMap<IrisPosition, MatterCavern> walls = new KMap<>();
        // Per-column cave Ys accumulate into a fixed 256-slot array (rx*16+rz),
        // avoiding a boxed-Long KMap lookup per block. firstTouch records the
        // columns in first-encounter order so the KMap below gets its entries
        // inserted in exactly the order the old computeIfAbsent chain would
        // have — identical key set + insertion order means an identical hash
        // table, so zone processing order (and the M.r draws inside it) is
        // bit-for-bit the same as before.
        int[][] columnYs = new int[256][];
        int[] columnCount = new int[256];
        int[] firstTouch = new int[256];
        final int[] touched = new int[1];
        // Loop-invariant bounds (world config cannot change mid-chunk).
        final int worldTop = getEngine().getWorld().maxHeight() - getEngine().getWorld().minHeight();
        // Neighbor probes below hit the same y-section for runs of cells (and
        // MantleChunk.iterate walks one section at a time); memoizing the
        // (section -> MatterCavern slice) pair turns each probe's section read
        // + slice map lookup into a compare. Per-onModify state: onModify is
        // single-threaded per chunk.
        final CavernMemo memo = new CavernMemo();
        Consumer4I<MatterCavern> iterator = (xx, yy, zz, c) -> {
            if (c == null) {
                return;
            }

            if (yy >= worldTop || yy <= 0) { // Yes, skip bedrock
                return;
            }

            int rx = xx & 15;
            int rz = zz & 15;

            BlockData current = output.get(rx, yy, rz);

            if (B.isFluid(current)) {
                return;
            }

            int ci = (rx << 4) | rz;
            int[] ys = columnYs[ci];
            if (ys == null) {
                ys = new int[8];
                columnYs[ci] = ys;
                firstTouch[touched[0]++] = ci;
            } else if (columnCount[ci] == ys.length) {
                int[] grown = new int[ys.length << 1];
                System.arraycopy(ys, 0, grown, 0, ys.length);
                columnYs[ci] = grown;
                ys = grown;
            }
            ys[columnCount[ci]++] = yy;

            //todo: Fix chunk decoration not working on chunk's border

            if (rz < 15 && cavernAt(mc, memo, xx, yy, zz + 1) == null) {
                walls.put(new IrisPosition(rx, yy, rz + 1), c);
            }

            if (rx < 15 && cavernAt(mc, memo, xx + 1, yy, zz) == null) {
                walls.put(new IrisPosition(rx + 1, yy, rz), c);
            }

            if (rz > 0 && cavernAt(mc, memo, xx, yy, zz - 1) == null) {
                walls.put(new IrisPosition(rx, yy, rz - 1), c);
            }

            if (rx > 0 && cavernAt(mc, memo, xx - 1, yy, zz) == null) {
                walls.put(new IrisPosition(rx - 1, yy, rz), c);
            }

            if (current.getMaterial().isAir()) {
                return;
            }

            if (c.isWater()) {
                output.set(rx, yy, rz, context.getFluid().get(rx, rz));
            } else if (c.isLava()) {
                output.set(rx, yy, rz, LAVA);
            } else {
                if (getEngine().getDimension().getCaveLavaHeight() > yy) {
                    output.set(rx, yy, rz, LAVA);
                } else {
                    output.set(rx, yy, rz, AIR);
                }
            }
        };

        mc.iterateInts(MatterCavern.class, iterator);

        KMap<Long, int[]> positions = new KMap<>();
        for (int i = 0; i < touched[0]; i++) {
            int ci = firstTouch[i];
            positions.put(Cache.key(ci >> 4, ci & 15),
                    java.util.Arrays.copyOf(columnYs[ci], columnCount[ci]));
        }

        walls.forEach((i, v) -> {
            IrisBiome biome = v.getCustomBiome().isEmpty()
                    ? getEngine().getCaveBiome(i.getX() + (x << 4), i.getZ() + (z << 4))
                    : getEngine().getData().getBiomeLoader().load(v.getCustomBiome());

            if (biome != null) {
                biome.setInferredType(InferredType.CAVE);
                BlockData d = biome.getWall().get(rng, i.getX() + (x << 4), i.getY(), i.getZ() + (z << 4), getData());

                if (d != null && B.isSolid(output.get(i.getX(), i.getY(), i.getZ())) && i.getY() <= context.getHeight().get(i.getX(), i.getZ())) {
                    output.set(i.getX(), i.getY(), i.getZ(), d);
                }
            }
        });

        final int engineHeight = getEngine().getHeight();
        // One mutable zone per onModify: zones are processed strictly
        // sequentially, so a reset beats a fresh CaveZone per run.
        final CaveZone zone = new CaveZone();
        positions.forEach((k, v) -> {
            if (v.length == 0) {
                return;
            }

            int rx = Cache.keyX(k);
            int rz = Cache.keyZ(k);
            java.util.Arrays.sort(v);
            zone.setCeiling(-1);
            zone.setFloor(v[0]);
            int buf = v[0] - 1;

            for (int i : v) {
                if (i < 0 || i > engineHeight) {
                    continue;
                }

                if (i == buf + 1) {
                    buf = i;
                    zone.ceiling = buf;
                } else if (zone.isValid(getEngine())) {
                    processZone(output, mc, mantle, zone, rx, rz, rx + (x << 4), rz + (z << 4));
                    zone.setCeiling(-1);
                    zone.setFloor(i);
                    buf = i;
                }
            }

            if (zone.isValid(getEngine())) {
                processZone(output, mc, mantle, zone, rx, rz, rx + (x << 4), rz + (z << 4));
            }
        });

        getEngine().getMetrics().getDeposit().put(p.getMilliseconds());
        mc.release();
    }

    /** Per-onModify memo for the neighbor cavern probes (see onModify). */
    private static final class CavernMemo {
        int section = -1;
        MatterSlice<MatterCavern> slice;
    }

    private static MatterCavern cavernAt(MantleChunk mc, CavernMemo memo, int x, int y, int z) {
        int section = y >> 4;
        if (memo.section != section) {
            Matter matter = mc.get(section);
            memo.slice = matter == null ? null : matter.getSlice(MatterCavern.class);
            memo.section = section;
        }
        MatterSlice<MatterCavern> slice = memo.slice;
        return slice == null ? null : slice.get(x & 15, y & 15, z & 15);
    }

    private void processZone(Hunk<BlockData> output, MantleChunk mc, Mantle mantle, CaveZone zone, int rx, int rz, int xx, int zz) {
        boolean decFloor = B.isSolid(output.getClosest(rx, zone.floor - 1, rz));
        boolean decCeiling = B.isSolid(output.getClosest(rx, zone.ceiling + 1, rz));
        int center = (zone.floor + zone.ceiling) / 2;
        int thickness = zone.airThickness();
        String customBiome = "";

        if (B.isDecorant(output.getClosest(rx, zone.ceiling + 1, rz))) {
            output.set(rx, zone.ceiling + 1, rz, AIR);
        }

        if (B.isDecorant(output.get(rx, zone.ceiling, rz))) {
            output.set(rx, zone.ceiling, rz, AIR);
        }

        if (M.r(1D / 16D)) {
            mantle.set(xx, zone.ceiling, zz, MarkerMatter.CAVE_CEILING);
        }

        if (M.r(1D / 16D)) {
            mantle.set(xx, zone.floor, zz, MarkerMatter.CAVE_FLOOR);
        }

        for (int i = zone.floor; i <= zone.ceiling; i++) {
            MatterCavern cavernData = (MatterCavern) mc.getOrCreate(i >> 4).slice(MatterCavern.class)
                    .get(rx, i & 15, rz);

            if (cavernData != null && !cavernData.getCustomBiome().isEmpty()) {
                customBiome = cavernData.getCustomBiome();
                break;
            }
        }

        IrisBiome biome = customBiome.isEmpty()
                ? getEngine().getCaveBiome(xx, zz)
                : getEngine().getData().getBiomeLoader().load(customBiome);

        if (biome == null) {
            return;
        }

        biome.setInferredType(InferredType.CAVE);

        KList<BlockData> blocks = biome.generateLayers(getDimension(), xx, zz, rng, 3, zone.floor, getData(), getComplex());

        for (int i = 0; i < zone.floor - 1; i++) {
            if (!blocks.hasIndex(i)) {
                break;
            }
            int y = zone.floor - i - 1;

            BlockData b = blocks.get(i);
            BlockData down = output.get(rx, y, rz);

            if (!B.isSolid(down)) {
                continue;
            }

            if (B.isOre(down)) {
                output.set(rx, y, rz, B.toDeepSlateOre(down, b));
                continue;
            }

            output.set(rx, y, rz, b);
        }

        blocks = biome.generateCeilingLayers(getDimension(), xx, zz, rng, 3, zone.ceiling, getData(), getComplex());

        if (zone.ceiling + 1 < mantle.getWorldHeight()) {
            for (int i = 0; i < zone.ceiling + 1; i++) {
                if (!blocks.hasIndex(i)) {
                    break;
                }

                BlockData b = blocks.get(i);
                BlockData up = output.get(rx, zone.ceiling + i + 1, rz);

                if (!B.isSolid(up)) {
                    continue;
                }

                if (B.isOre(up)) {
                    output.set(rx, zone.ceiling + i + 1, rz, B.toDeepSlateOre(up, b));
                    continue;
                }

                output.set(rx, zone.ceiling + i + 1, rz, b);
            }
        }

        for (IrisDecorator i : biome.getDecorators()) {
            if (i.getPartOf().equals(IrisDecorationPart.NONE) && B.isSolid(output.get(rx, zone.getFloor() - 1, rz))) {
                decorant.getSurfaceDecorator().decorate(rx, rz, xx, xx, xx, zz, zz, zz, output, biome, zone.getFloor() - 1, zone.airThickness());
            } else if (i.getPartOf().equals(IrisDecorationPart.CEILING) && B.isSolid(output.get(rx, zone.getCeiling() + 1, rz))) {
                decorant.getCeilingDecorator().decorate(rx, rz, xx, xx, xx, zz, zz, zz, output, biome, zone.getCeiling(), zone.airThickness());
            }
        }
    }

    @Data
    public static class CaveZone {
        private int ceiling = -1;
        private int floor = -1;

        public int airThickness() {
            return (ceiling - floor) - 1;
        }

        public boolean isValid(Engine engine) {
            return floor < ceiling && ceiling - floor >= 1 && floor >= 0 && ceiling <= engine.getHeight() && airThickness() > 0;
        }

        public String toString() {
            return floor + "-" + ceiling;
        }
    }
}
