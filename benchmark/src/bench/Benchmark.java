package bench;

import com.volmit.iris.core.loader.IrisData;
import com.volmit.iris.engine.actuator.TerrainColumn;
import com.volmit.iris.engine.object.IrisBiome;
import com.volmit.iris.engine.object.IrisCompat;
import com.volmit.iris.engine.object.IrisDimension;
import com.volmit.iris.engine.object.IrisOreGenerator;
import com.volmit.iris.engine.object.IRare;
import com.volmit.iris.engine.object.IrisRegion;
import com.volmit.iris.engine.object.IrisInterpolator;
import com.volmit.iris.engine.object.IrisRange;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.cache.WorldCache2D;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.data.B;
import com.volmit.iris.util.interpolation.InterpolationMethod;
import com.volmit.iris.util.interpolation.InterpolationMethod3D;
import com.volmit.iris.util.interpolation.IrisInterpolation;
import com.volmit.iris.util.hunk.Hunk;
import com.volmit.iris.util.hunk.bits.DataContainer;
import com.volmit.iris.util.hunk.bits.Writable;
import com.volmit.iris.util.io.CountingDataInputStream;
import com.volmit.iris.util.mantle.MantleChunk;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.matter.IrisMatter;
import com.volmit.iris.util.matter.Matter;
import com.volmit.iris.util.matter.MatterSlice;
import com.volmit.iris.util.noise.CNG;
import com.volmit.iris.util.noise.NoiseType;
import com.volmit.iris.util.parallel.HyperLock;
import com.volmit.iris.util.stream.ProceduralStream;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone performance benchmark for Iris' pure hot paths (noise, streams,
 * interpolation, rarity selection, 2D caches). Compiles against the REAL
 * production sources in core/src/main/java (see build.sh); only the plugin
 * bootstrap classes (Iris, IrisSettings) are shadowed by no-op stubs.
 *
 * Every measured iteration also folds its outputs into a 64-bit digest so that
 * any run doubles as a regression check: the digest column must stay identical
 * across optimization rounds (bit-exact terrain guarantee).
 */
public final class Benchmark {
    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Benchmark() {
    }

    /** Simple rarity-bearing token mimicking IrisBiome for selection paths. */
    static final class FakeRare implements IRare {
        final int id;
        final int rarity;

        FakeRare(int id, int rarity) {
            this.id = id;
            this.rarity = rarity;
        }

        @Override
        public int getRarity() {
            return rarity;
        }
    }

    interface Scenario {
        String name();

        /** Operations per measured iteration (default 1M; parallel scenarios use less). */
        default int ops() {
            return OPS;
        }

        /** Runs {@code n} operations, folding each output into the digest. */
        double run(int n, long seed, Digest dg);
    }

    /** Functional body of a scenario (kept separate so lambdas can target it). */
    interface Op {
        double run(int n, long seed, Digest dg);
    }

    static final class Digest {
        long h = 0xcbf29ce484222325L;
        int count;

        void add(double v) {
            long bits = Double.doubleToLongBits(v);
            h ^= bits;
            h *= 0x100000001b3L;
            count++;
        }

        void add(long v) {
            h ^= v;
            h *= 0x100000001b3L;
            count++;
        }

        String hex() {
            return Long.toUnsignedString(h, 16);
        }
    }

    public static void main(String[] args) throws Exception {
        // Real IrisSettings.get() touches the plugin data folder; pre-seed defaults.
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        // BlockData compat filter chain (B.get("minecraft:...") routes through it;
        // its first action is a B.getOrNull cache probe, so the proxy factory
        // underneath answers immediately).
        com.volmit.iris.Iris.compat = new IrisCompat();
        String out = args.length > 0 ? args[0] : "benchmark/results/latest.csv";
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int iters = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        List<Scenario> scenarios = buildScenarios();

        try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
            w.println("scenario,iteration,seed,ops,ns_per_op,bytes_per_op,digest,samples");
            String filter = System.getProperty("bench.filter", "");
            for (Scenario s : scenarios) {
                if (!filter.isEmpty() && !s.name().contains(filter)) {
                    continue;
                }
                for (int i = 0; i < warmups; i++) {
                    s.run(Math.min(200_000, s.ops()), 424242L + i, new Digest());
                }
                for (int it = 0; it < iters; it++) {
                    long seed = 900_000L + it;
                    Digest dg = new Digest();
                    int n = s.ops();
                    long t0 = System.nanoTime();
                    long a0 = TMX.getThreadAllocatedBytes(Thread.currentThread().getId());
                    double blackhole = s.run(n, seed, dg);
                    long a1 = TMX.getThreadAllocatedBytes(Thread.currentThread().getId());
                    long t1 = System.nanoTime();
                    if (blackhole == 1.3371337e300) {
                        System.out.println("[bh]");
                    }
                    double nsPerOp = (t1 - t0) / (double) n;
                    double bytesPerOp = (a1 - a0) / (double) n;
                    w.printf("%s,%d,%d,%d,%.3f,%.1f,%s,%d%n",
                            s.name(), it, seed, n, nsPerOp, bytesPerOp, dg.hex(), dg.count);
                    w.flush();
                    System.out.printf("%-28s it=%d %10.1f ns/op %12.1f B/op  digest=%s%n",
                            s.name(), it, nsPerOp, bytesPerOp, dg.hex());
                }
            }
        }
        PARALLEL_POOL.shutdownNow();
        System.out.println("written: " + out);
    }

    private static final int OPS = 1_000_000;

    private static List<Scenario> buildScenarios() {
        List<Scenario> out = new ArrayList<>();

        // ---- CNG raw noise (terrain noise kernels) ----
        CNG signature = CNG.signature(new RNG(1234567));
        CNG fractured = CNG.signatureDouble(new RNG(7654321), NoiseType.SIMPLEX);
        CNG perlinSig = CNG.signaturePerlin(new RNG(555));

        out.add(sc("cng-noise2d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double x = r.nextInt(200_000) - 100_000, z = r.nextInt(200_000) - 100_000;
                double v = signature.noise(x, z);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));
        out.add(sc("cng-noise3d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double x = r.nextInt(200_000) - 100_000, y = r.nextInt(256), z = r.nextInt(200_000) - 100_000;
                double v = signature.noise(x, y, z);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));
        out.add(sc("cng-fractured2d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double v = fractured.noise(r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));
        out.add(sc("cng-perlin2d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double v = perlinSig.noise(r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));

        // ---- CNG fit (layer/ore/height integer selection) ----
        out.add(sc("cng-fit-int2d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                int v = signature.fit(-100, 100, r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));
        out.add(sc("cng-fitdouble2d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double v = signature.fitDouble(-50, 250, r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));

        // ---- Biome-style selection: zoom -> forceDouble -> IRare.pick ----
        KList<FakeRare> biomes = new KList<>();
        int[] rarities = {1, 1, 2, 3, 1, 5, 8, 2, 13, 1, 4, 7};
        for (int i = 0; i < rarities.length; i++) {
            biomes.add(new FakeRare(i, rarities[i]));
        }
        ProceduralStream<Double> landNoise = signature.stream().zoom(0.25D).forceDouble();
        ProceduralStream<FakeRare> pickStream = IRare.stream(landNoise, (List<FakeRare>) (List<?>) biomes, false);
        ProceduralStream<FakeRare> pickStreamLegacy = IRare.stream(landNoise, (List<FakeRare>) (List<?>) biomes, true);

        out.add(sc("irare-pick", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                FakeRare v = pickStream.get(r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v.id);
                bh += v.id;
            }
            return bh;
        }));
        out.add(sc("irare-pick-legacy", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                FakeRare v = pickStreamLegacy.get(r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v.id);
                bh += v.id;
            }
            return bh;
        }));

        // ---- IrisComplex.implode pattern: copy children + rebuild rarity map per sample ----
        out.add(sc("implode-fitRarity", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                KList<FakeRare> chx = biomes.copy();
                chx.add(biomes.get(0));
                FakeRare v = signature.fitRarity(chx, r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v.id);
                bh += v.id;
            }
            return bh;
        }));

        // ---- IrisComplex.implode AFTER round-1 caching: prebuilt rarity map, per-sample selection only ----
        KList<FakeRare> cachedMap;
        {
            KList<FakeRare> chx = biomes.copy();
            chx.add(biomes.get(0));
            cachedMap = CNG.buildRarityMap(chx);
        }
        out.add(sc("implode-fitRarity-cached", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                FakeRare v = signature.fitRarityMapped(cachedMap, r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000);
                dg.add(v.id);
                bh += v.id;
            }
            return bh;
        }));

        // ---- Height interpolation (heightStream path) ----
        IrisInterpolator bilinearStarcast = new IrisInterpolator().setFunction(InterpolationMethod.BILINEAR_STARCAST_6);
        IrisInterpolator bilinear = new IrisInterpolator().setFunction(InterpolationMethod.BILINEAR);
        IrisInterpolator hermite = new IrisInterpolator().setFunction(InterpolationMethod.HERMITE);
        out.add(interpScenario("interp-bilinear-starcast6", bilinearStarcast, signature));
        out.add(interpScenario("interp-bilinear", bilinear, signature));
        out.add(interpScenario("interp-hermite", hermite, signature));

        // ---- 3D interpolation (cave carving path) ----
        com.volmit.iris.util.function.NoiseProvider3 np3 = (x, y, z) -> signature.noise(x, y, z);
        out.add(sc("interp3d-trilinear", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double v = IrisInterpolation.getNoise3D(InterpolationMethod3D.TRILINEAR,
                        r.nextInt(100_000) - 50_000, r.nextInt(256), r.nextInt(100_000) - 50_000, 7, np3);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));
        out.add(sc("interp3d-trilinear-starcast6", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double v = IrisInterpolation.getNoise3D(InterpolationMethod3D.TRILINEAR_TRISTARCAST_6,
                        r.nextInt(100_000) - 50_000, r.nextInt(256), r.nextInt(100_000) - 50_000, 7, np3);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));

        // ---- WorldCache2D (cache2D stream backing) ----
        WorldCache2D<Integer> cache = new WorldCache2D<>((x, z) -> signature.fit(-100, 100, x, z), 1024);
        out.add(sc("worldcache2d", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            // Chunk-window raster scan: mixed hit/miss pattern like chunk generation
            for (int i = 0; i < n; i++) {
                if ((i & 1023) == 0) {
                    r.setSeed(seed + (i >> 10));
                }
                int cx = r.nextInt(1000) - 500, cz = r.nextInt(1000) - 500;
                int baseX = cx << 4, baseZ = cz << 4;
                for (int a = 0; a < 16; a++) {
                    for (int b = 0; b < 16; b += 4) {
                        Integer v = cache.get(baseX + a, baseZ + b);
                        dg.add(v);
                        bh += v;
                    }
                }
            }
            return bh;
        }));

        // Hot hit-path: one fixed chunk rastered every op (post-warmup every
        // get is a cache hit) — isolates raw lookup cost from resolver noise.
        WorldCache2D<Integer> hotCache = new WorldCache2D<>((x, z) -> signature.fit(-100, 100, x, z), 1024);
        out.add(sc("worldcache2d-hit", (n, seed, dg) -> {
            Random r = new Random(seed);
            int cx = r.nextInt(1000) - 500, cz = r.nextInt(1000) - 500;
            int baseX = cx << 4, baseZ = cz << 4;
            double bh = 0;
            for (int i = 0; i < n; i++) {
                for (int a = 0; a < 16; a++) {
                    for (int b = 0; b < 16; b += 4) {
                        Integer v = hotCache.get(baseX + a, baseZ + b);
                        dg.add(v);
                        bh += v;
                    }
                }
            }
            return bh;
        }));

        // ---- Per-column RNG (decorator pattern) ----
        out.add(sc("rng-column", (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                int x = r.nextInt(200_000) - 100_000, z = r.nextInt(200_000) - 100_000;
                RNG rngc = new RNG(Cache.key(x, z));
                int v = rngc.i(4);
                double v2 = rngc.d();
                dg.add(v);
                dg.add(v2);
                bh += v + v2;
            }
            return bh;
        }));

        // ---- Terrain column fill (IrisTerrainNormalActuator per-column loop) ----
        // Real IrisBiome/IrisRegion/IrisDimension objects with real ore lists.
        // Ore palettes stay empty (the offline-safe common case): generateOres
        // runs its null fast path, exercising the exact per-block dispatch the
        // actuator pays; rng is never consumed by this configuration.
        // terrain-col-legacy = verbatim pre-round-5 loop (per-block re-reads,
        // unconditional 3-level ore checks); terrain-col-fill = the hoisted
        // TerrainColumn.fill production path. Digests must match bit-exactly.
        {
            CNG heightNoise = CNG.signature(new RNG(4242));
            IrisBiome tBiome = new IrisBiome();
            IrisRegion tRegion = new IrisRegion();
            IrisDimension tDimension = new IrisDimension();
            tDimension.setFluidHeight(48);
            {
                // Typical pack: only the dimension configures (buried) ores;
                // biome/region ore lists stay empty and no ore wants surface.
                KList<IrisOreGenerator> ores = new KList<>();
                ores.add(new IrisOreGenerator());
                ores.add(new IrisOreGenerator().setRange(new IrisRange(5, 60)));
                ores.add(new IrisOreGenerator().setRange(new IrisRange(10, 40)));
                tDimension.setOres(ores);
            }
            RNG tRng = new RNG(777);
            Double[] tHeights = new Double[256];
            for (int i = 0; i < 256; i++) {
                tHeights[i] = (double) heightNoise.fit(8, 96, (i >> 4) * 3, (i & 15) * 7);
            }
            Ctx2D<Double> heightCtx = new Ctx2D<>(tHeights);
            Ctx2D<org.bukkit.block.data.BlockData> rockCtx = new Ctx2D<>(new org.bukkit.block.data.BlockData[256]);
            Ctx2D<org.bukkit.block.data.BlockData> fluidCtx = new Ctx2D<>(new org.bukkit.block.data.BlockData[256]);
            RecordingHunk tHunk = new RecordingHunk();

            out.add(sc("terrain-col-legacy", (n, seed, dg) -> {
                Random r = new Random(seed);
                double bh = 0;
                tHunk.dg = dg;
                int lastBedrock = -1;
                for (int op = 0; op < n; op++) {
                    int xf = 7, zf = op & 15;
                    int x = r.nextInt(100_000) - 50_000, z = r.nextInt(100_000) - 50_000;
                    int realX = xf + x, realZ = zf + z;
                    IrisBiome biome = tBiome;
                    IrisRegion region = tRegion;
                    IrisData data = null;
                    int he = (int) Math.round(Math.min(tHunk.getHeight(), (Double) heightCtx.get(xf, zf)));
                    int hf = Math.round(Math.max(Math.min(tHunk.getHeight(), tDimension.getFluidHeight()), he));
                    if (hf < 0) {
                        continue;
                    }

                    KList<org.bukkit.block.data.BlockData> blocks = null;
                    KList<org.bukkit.block.data.BlockData> fblocks = null;
                    int depth, fdepth;
                    for (int i = hf; i >= 0; i--) {
                        if (i >= tHunk.getHeight()) {
                            continue;
                        }

                        if (i == 0) {
                            if (tDimension.isBedrock()) {
                                tHunk.set(xf, i, zf, null);
                                lastBedrock = i;
                                continue;
                            }
                        }

                        org.bukkit.block.data.BlockData ore = biome.generateOres(realX, i, realZ, tRng, data, true);
                        ore = ore == null ? region.generateOres(realX, i, realZ, tRng, data, true) : ore;
                        ore = ore == null ? tDimension.generateOres(realX, i, realZ, tRng, data, true) : ore;
                        if (ore != null) {
                            tHunk.set(xf, i, zf, ore);
                            continue;
                        }

                        if (i > he && i <= hf) {
                            fdepth = hf - i;

                            if (fblocks == null) {
                                fblocks = biome.generateSeaLayers(realX, realZ, tRng, hf - he, data);
                            }

                            if (fblocks.hasIndex(fdepth)) {
                                tHunk.set(xf, i, zf, fblocks.get(fdepth));
                                continue;
                            }

                            tHunk.set(xf, i, zf, fluidCtx.get(xf, zf));
                            continue;
                        }

                        if (i <= he) {
                            depth = he - i;
                            if (blocks == null) {
                                blocks = biome.generateLayers(tDimension, realX, realZ, tRng, he, he, data, null);
                            }

                            if (blocks.hasIndex(depth)) {
                                tHunk.set(xf, i, zf, blocks.get(depth));
                                continue;
                            }

                            ore = biome.generateOres(realX, i, realZ, tRng, data, false);
                            ore = ore == null ? region.generateOres(realX, i, realZ, tRng, data, false) : ore;
                            ore = ore == null ? tDimension.generateOres(realX, i, realZ, tRng, data, false) : ore;

                            if (ore != null) {
                                tHunk.set(xf, i, zf, ore);
                            } else {
                                tHunk.set(xf, i, zf, rockCtx.get(xf, zf));
                            }
                        }
                    }
                }
                dg.add(lastBedrock);
                bh += lastBedrock;
                return bh;
            }));

            out.add(sc("terrain-col-fill", (n, seed, dg) -> {
                Random r = new Random(seed);
                double bh = 0;
                tHunk.dg = dg;
                int lastBedrock = -1;
                for (int op = 0; op < n; op++) {
                    int xf = 7, zf = op & 15;
                    int x = r.nextInt(100_000) - 50_000, z = r.nextInt(100_000) - 50_000;
                    int realX = xf + x, realZ = zf + z;
                    IrisBiome biome = tBiome;
                    IrisRegion region = tRegion;
                    IrisData data = null;
                    int he = (int) Math.round(Math.min(tHunk.getHeight(), (Double) heightCtx.get(xf, zf)));
                    int hf = Math.round(Math.max(Math.min(tHunk.getHeight(), tDimension.getFluidHeight()), he));
                    if (hf < 0) {
                        continue;
                    }

                    int bedrockAt = TerrainColumn.fill(xf, zf, realX, realZ, he, hf, tHunk.getHeight(), tHunk,
                            biome, region, tDimension, data, null, tRng,
                            rockCtx.get(xf, zf), fluidCtx.get(xf, zf), null, tDimension.isBedrock());
                    if (bedrockAt >= 0) {
                        lastBedrock = bedrockAt;
                    }
                }
                dg.add(lastBedrock);
                bh += lastBedrock;
                return bh;
            }));
        }

        // ---- Parallel scenarios: shared engine objects across 8 threads ----
        WorldCache2D<Integer> sharedCache = new WorldCache2D<>((x, z) -> signature.fit(-100, 100, x, z), 1024);
        out.add(parallelScenario("par-cng-noise2d", 8, (nn, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < nn; i++) {
                double v = signature.noise(r.nextInt(200_000) - 100_000, r.nextInt(200_000) - 100_000);
                dg.add(v);
                bh += v;
            }
            return bh;
        }));
        out.add(parallelScenario("par-worldcache2d", 8, (nn, seed, dg) -> {
            Random r = new Random(seed);
            int region = (int) (seed % 64);
            double bh = 0;
            for (int i = 0; i < nn; i++) {
                if ((i & 1023) == 0) {
                    r.setSeed(seed + (i >> 10));
                }
                int cx = (region * 7919 + r.nextInt(200)) - 100, cz = r.nextInt(200) - 100;
                int baseX = cx << 4, baseZ = cz << 4;
                for (int a = 0; a < 16; a += 2) {
                    for (int b = 0; b < 16; b += 2) {
                        Integer v = sharedCache.get(baseX + a, baseZ + b);
                        dg.add(v);
                        bh += v;
                    }
                }
            }
            return bh;
        }));

        // Production-like raster pattern: one chunk window is rastered for a
        // stretch of ops before moving on (models parallel chunk generation
        // sharing cache2D streams with spatial locality).
        out.add(parallelScenario("par-worldcache2d-raster", 8, (nn, seed, dg) -> {
            Random r = new Random(seed);
            int region = (int) (seed % 64);
            double bh = 0;
            int cx = 0, cz = 0;
            for (int i = 0; i < nn; i++) {
                if ((i & 1023) == 0) {
                    r.setSeed(seed + (i >> 10));
                    cx = (region * 7919 + r.nextInt(200)) - 100;
                    cz = r.nextInt(200) - 100;
                }
                int baseX = cx << 4, baseZ = cz << 4;
                for (int a = 0; a < 16; a += 2) {
                    for (int b = 0; b < 16; b += 2) {
                        Integer v = sharedCache.get(baseX + a, baseZ + b);
                        dg.add(v);
                        bh += v;
                    }
                }
            }
            return bh;
        }));

        // ---- Matter/Mantle storage layer (offline via Bukkit proxy stub) ----
        // Round 7 surface: palette-backed per-section storage (DataContainer),
        // the MantleChunk block-write chain, Matter serialize/deserialize
        // roundtrip, and HyperLock. All digests are deterministic so every
        // iteration doubles as a correctness proof (roundtrip content identity,
        // exact mutual-exclusion counters).
        {
            final BlockData[] protos;
            {
                Material[] mats = {Material.STONE, Material.DIRT, Material.GRASS_BLOCK,
                        Material.SAND, Material.OAK_LOG, Material.GRAVEL,
                        Material.COBBLESTONE, Material.ANDESITE};
                protos = new BlockData[mats.length];
                for (int i = 0; i < mats.length; i++) {
                    protos[i] = mats[i].createBlockData();
                }
            }

            // Palette node adapter mirroring BlockMatter.writeNode/readNode.
            final Writable<BlockData> bdWritable = new Writable<>() {
                @Override
                public BlockData readNodeData(DataInputStream din) throws java.io.IOException {
                    return B.get(din.readUTF());
                }

                @Override
                public void writeNodeData(DataOutputStream dos, BlockData t) throws java.io.IOException {
                    dos.writeUTF(t.getAsString());
                }
            };

            DataContainer<BlockData> dc = new DataContainer<>(bdWritable, 4096);
            out.add(sc("datacontainer-set", (n, seed, dg) -> {
                Random r = new Random(seed);
                double bh = 0;
                for (int i = 0; i < n; i++) {
                    BlockData v = protos[r.nextInt(protos.length)];
                    dc.set(r.nextInt(4096), v);
                    dg.add(v.getMaterial().ordinal());
                    bh += v.getMaterial().ordinal();
                }
                return bh;
            }));

            DataContainer<BlockData> dcGet = new DataContainer<>(bdWritable, 4096);
            for (int i = 0; i < 4096; i++) {
                dcGet.set(i, protos[i % protos.length]);
            }
            out.add(sc("datacontainer-get", (n, seed, dg) -> {
                Random r = new Random(seed);
                double bh = 0;
                for (int i = 0; i < n; i++) {
                    BlockData v = dcGet.get(r.nextInt(4096));
                    int o = v == null ? -1 : v.getMaterial().ordinal();
                    dg.add(o);
                    bh += o;
                }
                return bh;
            }));

            // Inner block-write chain used by Mantle.set / MantleWriter.setData:
            // section array CAS-read + slice map lookup + palette hunk set.
            MantleChunk mchunk = new MantleChunk(16, 0, 0);
            out.add(sc("mantlechunk-set", (n, seed, dg) -> {
                Random r = new Random(seed);
                double bh = 0;
                for (int i = 0; i < n; i++) {
                    int x = r.nextInt(16), y = r.nextInt(256), z = r.nextInt(16);
                    BlockData v = protos[r.nextInt(protos.length)];
                    mchunk.getOrCreate(y >> 4)
                            .slice(BlockData.class)
                            .set(x, y & 15, z, v);
                    dg.add(v.getMaterial().ordinal());
                    bh += v.getMaterial().ordinal();
                }
                return bh;
            }));

            // Full Matter serialize -> deserialize roundtrip of one 16^3 section
            // (block slice ~2/3 filled + int slice), digesting the read-back
            // content: proves format identity every iteration.
            out.add(new Scenario() {
                @Override
                public String name() {
                    return "matter-roundtrip";
                }

                @Override
                public int ops() {
                    return 1_000;
                }

                @Override
                public double run(int n, long seed, Digest dg) {
                    double bh = 0;
                    for (int i = 0; i < n; i++) {
                        try {
                            IrisMatter m = new IrisMatter(16, 16, 16);
                            MatterSlice<BlockData> bs = m.slice(BlockData.class);
                            MatterSlice<Integer> is = m.slice(Integer.class);
                            Random r = new Random(seed);
                            for (int j = 0; j < 4096; j++) {
                                if (r.nextInt(3) > 0) {
                                    bs.setRaw(r.nextInt(16), r.nextInt(16), r.nextInt(16),
                                            protos[r.nextInt(protos.length)]);
                                }
                                is.setRaw(r.nextInt(16), r.nextInt(16), r.nextInt(16), r.nextInt(100));
                            }
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
                            m.writeDos(new DataOutputStream(bytes));
                            Matter m2 = Matter.readDin(CountingDataInputStream.wrap(
                                    new ByteArrayInputStream(bytes.toByteArray())));
                            dg.add(bytes.size());
                            bh += bytes.size();
                            MatterSlice<BlockData> bs2 = m2.slice(BlockData.class);
                            MatterSlice<Integer> is2 = m2.slice(Integer.class);
                            for (int x = 0; x < 16; x++) {
                                for (int y = 0; y < 16; y++) {
                                    for (int z = 0; z < 16; z++) {
                                        BlockData v = bs2.get(x, y, z);
                                        dg.add(v == null ? -1 : v.getMaterial().ordinal());
                                        Integer iv = is2.get(x, y, z);
                                        dg.add(iv == null ? -1 : iv);
                                    }
                                }
                            }
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return bh;
                }
            });

            // ---- IrisObject.place: full object placement write loop ----
            // Real IrisObject (trunk + canopy, proxy BlockData), real default
            // IrisObjectPlacement (Y-axis spin active), recording placer folds
            // every set() (coords + material) into the digest.
            {
                final BlockData log = Material.OAK_LOG.createBlockData();
                final BlockData leaves = Material.OAK_LEAVES.createBlockData();
                final com.volmit.iris.engine.object.IrisObject tree =
                        new com.volmit.iris.engine.object.IrisObject(9, 13, 9);
                for (int t = 0; t < 7; t++) {
                    tree.setUnsigned(4, t, 4, log);
                }
                for (int bx = 0; bx < 9; bx++) {
                    for (int by = 5; by < 12; by++) {
                        for (int bz = 0; bz < 9; bz++) {
                            double dx = (bx - 4) / 4.0, dy = (by - 9) / 3.2, dz = (bz - 4) / 4.0;
                            if (dx * dx + dy * dy + dz * dz <= 1.0 && (bx + by + bz) % 3 != 0) {
                                tree.setUnsigned(bx, by, bz, leaves);
                            }
                        }
                    }
                }
                final com.volmit.iris.engine.object.IrisObjectPlacement placement =
                        new com.volmit.iris.engine.object.IrisObjectPlacement();
                final RecordingPlacer placer = new RecordingPlacer();

                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "object-place";
                    }

                    @Override
                    public int ops() {
                        return 100_000;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        Random r = new Random(seed);
                        placer.dg = dg;
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            int x = r.nextInt(1 << 20), z = r.nextInt(1 << 20);
                            int ret = tree.place(x, 64, z, placer, placement,
                                    new RNG(seed + i), (com.volmit.iris.util.math.BlockPosition p, BlockData d) -> {
                                    }, null, null);
                            dg.add(ret);
                            bh += ret;
                        }
                        return bh;
                    }
                });
                final com.volmit.iris.engine.object.IrisObjectPlacement stiltPlacement =
                        new com.volmit.iris.engine.object.IrisObjectPlacement()
                                .setMode(com.volmit.iris.engine.object.ObjectPlaceMode.STILT);
                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "object-place-stilt";
                    }

                    @Override
                    public int ops() {
                        return 100_000;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        Random r = new Random(seed);
                        placer.dg = dg;
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            int x = r.nextInt(1 << 20), z = r.nextInt(1 << 20);
                            int ret = tree.place(x, 64, z, placer, stiltPlacement,
                                    new RNG(seed + i), (com.volmit.iris.util.math.BlockPosition p, BlockData d) -> {
                                    }, null, null);
                            dg.add(ret);
                            bh += ret;
                        }
                        return bh;
                    }
                });
            }

            // ---- Decorator path: per-column selection + surface placement ----
            // Real IrisSurfaceDecorator against a JDK-proxy Engine (SeedManager,
            // IrisData on a scratch folder and the dimension POJO are all REAL;
            // only the Engine shell is a proxy). The biome carries 6 decorators
            // spanning all 5 decoration parts like a pack-scale list, so the
            // selection loop pays the same partOf scan as production.
            // decorator-select: real getRNG + getDecorator per column, digesting
            // the picked decorator index. decorator-decorate: full decorate()
            // incl. palette pick, stacking loop and hunk writes folded into the
            // digest via Hunk.listen.
            {
                final com.volmit.iris.engine.framework.Engine benchEngine;
                {
                    java.util.Map<String, Object> hard = new java.util.HashMap<>();
                    hard.put("getCacheID", 123456);
                    hard.put("getSeedManager",
                            new com.volmit.iris.engine.framework.SeedManager(1234567L));
                    hard.put("getData", com.volmit.iris.core.loader.IrisData.get(
                            new java.io.File("benchmark/results/_decodata")));
                    hard.put("getDimension", new com.volmit.iris.engine.object.IrisDimension()
                            .setName("bench-dim").setFluidHeight(62));
                    java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                        Object v = hard.get(method.getName());
                        if (v != null) {
                            return v;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == long.class) return 0L;
                        if (rt == int.class) return 0;
                        if (rt == double.class) return 0D;
                        if (rt == float.class) return 0F;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        return null;
                    };
                    benchEngine = (com.volmit.iris.engine.framework.Engine)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.framework.Engine.class}, h);
                }

                final com.volmit.iris.engine.object.IrisBiome biome =
                        new com.volmit.iris.engine.object.IrisBiome()
                                .setName("bench-biome")
                                .setInferredType(com.volmit.iris.engine.object.InferredType.LAND);
                {
                    com.volmit.iris.util.collection.KList<com.volmit.iris.engine.object.IrisDecorator> ds =
                            new com.volmit.iris.util.collection.KList<>();
                    // Surface candidates (partOf NONE): flat grass + stacking flowers
                    ds.add(new com.volmit.iris.engine.object.IrisDecorator()
                            .setChance(0.5));
                    com.volmit.iris.engine.object.IrisDecorator flowers =
                            new com.volmit.iris.engine.object.IrisDecorator()
                                    .setChance(0.35)
                                    .setStackMin(1).setStackMax(3).setTopThreshold(0.9)
                                    .setTopPalette(new com.volmit.iris.util.collection.KList<com.volmit.iris.engine.object.IrisBlockData>()
                                            .qadd(new com.volmit.iris.engine.object.IrisBlockData("oxeye_daisy")))
                                    .setVariance(com.volmit.iris.engine.object.NoiseStyle.STATIC.style());
                    flowers.add("poppy");
                    flowers.add("dandelion");
                    ds.add(flowers);
                    // Non-surface parts: always scanned, never picked by the surface decorator
                    com.volmit.iris.engine.object.IrisDecorator cane =
                            new com.volmit.iris.engine.object.IrisDecorator()
                                    .setChance(0.5)
                                    .setPartOf(com.volmit.iris.engine.object.IrisDecorationPart.SHORE_LINE);
                    cane.add("sugar_cane");
                    ds.add(cane);
                    com.volmit.iris.engine.object.IrisDecorator lily =
                            new com.volmit.iris.engine.object.IrisDecorator()
                                    .setChance(0.5)
                                    .setPartOf(com.volmit.iris.engine.object.IrisDecorationPart.SEA_SURFACE);
                    lily.add("lily_pad");
                    ds.add(lily);
                    com.volmit.iris.engine.object.IrisDecorator seagrass =
                            new com.volmit.iris.engine.object.IrisDecorator()
                                    .setChance(0.5)
                                    .setPartOf(com.volmit.iris.engine.object.IrisDecorationPart.SEA_FLOOR);
                    seagrass.add("seagrass");
                    ds.add(seagrass);
                    com.volmit.iris.engine.object.IrisDecorator lichen =
                            new com.volmit.iris.engine.object.IrisDecorator()
                                    .setChance(0.5)
                                    .setPartOf(com.volmit.iris.engine.object.IrisDecorationPart.CEILING);
                    lichen.add("glow_lichen");
                    ds.add(lichen);
                    biome.setDecorators(ds);
                }

                class BenchSurfaceDecorator extends com.volmit.iris.engine.decorator.IrisSurfaceDecorator {
                    BenchSurfaceDecorator(com.volmit.iris.engine.framework.Engine e) {
                        super(e);
                    }

                    int select(int x, int z, com.volmit.iris.engine.object.IrisBiome b) {
                        return indexOf(b, getDecorator(getRNG(x, z), b, x, z));
                    }

                    void decorateColumn(int i, int j, int realX, int realZ,
                                        Hunk<BlockData> hunk,
                                        com.volmit.iris.engine.object.IrisBiome b,
                                        int height, int max) {
                        decorate(i, j, realX, realX + 1, realX - 1,
                                realZ, realZ + 1, realZ - 1, hunk, b, height, max);
                    }

                    private int indexOf(com.volmit.iris.engine.object.IrisBiome b,
                                        com.volmit.iris.engine.object.IrisDecorator d) {
                        return d == null ? -1 : b.getDecorators().indexOf(d);
                    }
                }
                final BenchSurfaceDecorator surface = new BenchSurfaceDecorator(benchEngine);

                out.add(sc("decorator-select", (n, seed, dg) -> {
                    Random r = new Random(seed);
                    double bh = 0;
                    for (int i = 0; i < n; i++) {
                        int x = r.nextInt(1 << 20), z = r.nextInt(1 << 20);
                        int pick = surface.select(x, z, biome);
                        dg.add(pick);
                        bh += pick;
                    }
                    return bh;
                }));

                final Hunk<BlockData> hunkBase = Hunk.newArrayHunk(16, 96, 16);
                final Digest[] writeDigest = new Digest[1];
                final Hunk<BlockData> hunk = hunkBase.listen((x, y, z, t) -> {
                    Digest d = writeDigest[0];
                    d.add(x);
                    d.add(y);
                    d.add(z);
                    d.add(t == null ? -1 : t.getMaterial().ordinal());
                });
                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "decorator-decorate";
                    }

                    @Override
                    public int ops() {
                        return 100_000;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        writeDigest[0] = dg;
                        Random r = new Random(seed);
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            int baseX = r.nextInt(1 << 18), baseZ = r.nextInt(1 << 18);
                            int k = i & 255;
                            int xi = k & 15, zi = k >> 4;
                            int realX = baseX + xi, realZ = baseZ + zi;
                            int height = 60 + ((realX * 31 + realZ * 17) >> 3 & 15);
                            surface.decorateColumn(xi, zi, realX, realZ, hunk, biome,
                                    height, 96 - height);
                            bh += height;
                        }
                        writeDigest[0] = null;
                        return bh;
                    }
                });
            }

            // ---- Deposit placement write path (IrisDepositModifier.generate) ----
            // Real modifier against the Engine proxy (getHeight -> 256), real
            // MantleChunk, pre-filled height grid and a 16x256x16 hunk whose
            // sub-64 layers are DEEPSLATE so the deepslate ore-conversion table
            // is exercised on ~half of all placed blocks. The hunk is reset
            // between iterations (through the base, non-listening hunk) so
            // fresh conversions keep firing; every generate() write folds into
            // the digest via Hunk.listen.
            {
                final com.volmit.iris.engine.framework.Engine depositEngine;
                {
                    java.util.Map<String, Object> hard = new java.util.HashMap<>();
                    hard.put("getCacheID", 654321);
                    hard.put("getSeedManager",
                            new com.volmit.iris.engine.framework.SeedManager(7654321L));
                    hard.put("getData", com.volmit.iris.core.loader.IrisData.get(
                            new java.io.File("benchmark/results/_decodata")));
                    hard.put("getHeight", 256);
                    hard.put("getMinHeight", 0);
                    java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                        Object v = hard.get(method.getName());
                        if (v != null) {
                            return v;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == long.class) return 0L;
                        if (rt == int.class) return 0;
                        if (rt == double.class) return 0D;
                        if (rt == float.class) return 0F;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        return null;
                    };
                    depositEngine = (com.volmit.iris.engine.framework.Engine)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.framework.Engine.class}, h);
                }

                final com.volmit.iris.engine.modifier.IrisDepositModifier modifier =
                        new com.volmit.iris.engine.modifier.IrisDepositModifier(depositEngine);
                final com.volmit.iris.engine.object.IrisDepositGenerator gen =
                        new com.volmit.iris.engine.object.IrisDepositGenerator()
                                .setMinHeight(1).setMaxHeight(80)
                                .setMinSize(8).setMaxSize(40)
                                .setMinPerChunk(2).setMaxPerChunk(4)
                                .setSpawnChance(1.0).setPerClumpSpawnChance(1.0)
                                .setPalette(new KList<com.volmit.iris.engine.object.IrisBlockData>()
                                        .qadd(new com.volmit.iris.engine.object.IrisBlockData("diamond_ore"))
                                        .qadd(new com.volmit.iris.engine.object.IrisBlockData("redstone_ore"))
                                        .qadd(new com.volmit.iris.engine.object.IrisBlockData("lapis_ore")));

                final Double[] heightGrid = new Double[256];
                for (int i = 0; i < 256; i++) {
                    heightGrid[i] = (double) (100 + ((i * 7 + i / 16) % 11));
                }
                final com.volmit.iris.util.context.ChunkContext ctx =
                        new com.volmit.iris.util.context.ChunkContext(0, 0, null)
                                .height(new com.volmit.iris.util.context.ChunkedDataCache<Double>(null, 0, 0)
                                        .prefill(heightGrid));

                final MantleChunk depChunk = new MantleChunk(16, 0, 0);
                final BlockData deepslate = Material.DEEPSLATE.createBlockData();
                final BlockData stone = Material.STONE.createBlockData();
                final Hunk<BlockData> hunkBase = Hunk.newArrayHunk(16, 256, 16);
                final Digest[] writeDigest = new Digest[1];
                final Hunk<BlockData> hunk = hunkBase.listen((x, y, z, t) -> {
                    Digest d = writeDigest[0];
                    d.add(x);
                    d.add(y);
                    d.add(z);
                    d.add(t == null ? -1 : t.getMaterial().ordinal());
                });

                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "deposit-place";
                    }

                    @Override
                    public int ops() {
                        return 2_000;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        writeDigest[0] = dg;
                        Random r = new Random(seed);
                        // Reset: sub-64 layers deepslate, above stone, so each
                        // iteration converts fresh blocks.
                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 256; y++) {
                                BlockData fill = y < 64 ? deepslate : stone;
                                for (int z = 0; z < 16; z++) {
                                    hunkBase.set(x, y, z, fill);
                                }
                            }
                        }
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            RNG rng = new RNG(seed + i);
                            modifier.generate(gen, depChunk, hunk, rng,
                                    r.nextInt(1 << 12), r.nextInt(1 << 12), false, null, ctx);
                            bh += i;
                        }
                        writeDigest[0] = null;
                        return bh;
                    }
                });
            }

            // ---- Cave carve write path (IrisCave.generate -> worm -> MantleWriter.setNoiseMasked) ----
            // Real IrisCave against a real Mantle + MantleWriter (Engine and
            // EngineMantle are JDK proxies returning those real objects). The
            // worm walks 3 per-axis CNG streams, then every point is ballooned
            // over a (2*ceil(girth)+1)^3 lattice, noise-masked and written as
            // MatterCavern cells — the dominant underground carve cost. Digest =
            // the full MatterCavern slice state per op (position + cavern flag +
            // customBiome hash + liquid): between ops each chunk in the writer
            // bounds is iterated (folded) and its cavern slice deleted, so every
            // op's entire carve output lands in the digest while the mantle
            // stays constant-size.
            {
                final com.volmit.iris.engine.framework.Engine caveEngine;
                final com.volmit.iris.engine.mantle.EngineMantle caveEngineMantle;
                final com.volmit.iris.util.mantle.Mantle caveMantle;
                {
                    java.util.Map<String, Object> hard = new java.util.HashMap<>();
                    hard.put("getCacheID", 975313);
                    hard.put("getSeedManager",
                            new com.volmit.iris.engine.framework.SeedManager(13579113L));
                    hard.put("getData", com.volmit.iris.core.loader.IrisData.get(
                            new java.io.File("benchmark/results/_decodata")));
                    hard.put("getDimension", new com.volmit.iris.engine.object.IrisDimension()
                            .setName("bench-dim").setFluidHeight(62));
                    hard.put("getHeight", 256);
                    hard.put("getMinHeight", 0);
                    java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                        Object v = hard.get(method.getName());
                        if (v != null) {
                            return v;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == long.class) return 0L;
                        if (rt == int.class) return 0;
                        if (rt == double.class) return 0D;
                        if (rt == float.class) return 0F;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        return null;
                    };
                    caveEngine = (com.volmit.iris.engine.framework.Engine)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.framework.Engine.class}, h);
                    caveMantle = new com.volmit.iris.util.mantle.Mantle(
                            new java.io.File("benchmark/results/_cavemantle"), 256);
                    java.util.Map<String, Object> em = new java.util.HashMap<>();
                    em.put("getMantle", caveMantle);
                    caveEngineMantle = (com.volmit.iris.engine.mantle.EngineMantle)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.mantle.EngineMantle.class},
                                    (proxy, method, args) -> {
                                        Object v = em.get(method.getName());
                                        if (v != null) {
                                            return v;
                                        }
                                        Class<?> rt = method.getReturnType();
                                        if (rt == boolean.class) return false;
                                        if (rt == long.class) return 0L;
                                        if (rt == int.class) return 0;
                                        if (rt == double.class) return 0D;
                                        if (rt == float.class) return 0F;
                                        if (rt == short.class) return (short) 0;
                                        if (rt == byte.class) return (byte) 0;
                                        if (rt == char.class) return (char) 0;
                                        return null;
                                    });
                }

                final com.volmit.iris.engine.object.IrisCave cave =
                        new com.volmit.iris.engine.object.IrisCave()
                                .setWorm(new com.volmit.iris.engine.object.IrisWorm()
                                        .setMaxDistance(96).setMaxIterations(128));
                // Writer bounds: radius 3 -> chunks [-6, 6] centered on (0, 0);
                // worm heads stay inside isWithin bounds and balloons extend at
                // most girth+0.5 blocks past them, so every setData is in-bounds.
                final int WR = 6;

                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "cave-carve";
                    }

                    @Override
                    public int ops() {
                        return 120;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        Random r = new Random(seed);
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            reset(dg);
                            com.volmit.iris.engine.mantle.MantleWriter w =
                                    caveMantle.write(caveEngineMantle, 0, 0, 3, false);
                            RNG rng = new RNG(seed * 131L + i);
                            int x = r.nextInt(40) - 20;
                            int z = r.nextInt(40) - 20;
                            int y = 24 + r.nextInt(40);
                            cave.generate(w, rng, caveEngine, x, y, z);
                            w.close();
                            bh += x + y + z;
                        }
                        reset(dg);
                        return bh;
                    }

                    /** Fold + clear every chunk's cavern cells inside the writer bounds. */
                    private void reset(Digest dg) {
                        for (int cx = -WR; cx <= WR; cx++) {
                            for (int cz = -WR; cz <= WR; cz++) {
                                com.volmit.iris.util.mantle.MantleChunk c = caveMantle.getChunk(cx, cz);
                                c.iterate(com.volmit.iris.util.matter.MatterCavern.class,
                                        (x, y, z, v) -> {
                                            dg.add(x);
                                            dg.add(y);
                                            dg.add(z);
                                            dg.add(v.isCavern() ? 1 : 0);
                                            dg.add(v.getCustomBiome().hashCode());
                                            dg.add(v.getLiquid());
                                        });
                                c.deleteSlices(com.volmit.iris.util.matter.MatterCavern.class);
                            }
                        }
                    }
                });
            }

            // ---- Cave carve read path (IrisCarveModifier.onModify) ----
            // Real modifier over a real Mantle chunk pre-filled with MatterCavern
            // cells (a water pocket sphere + a dry tunnel, so the iterator pays
            // the fluid-skip, water-fill, cave-air and multi-zone assembly
            // branches). The 16x256x16 hunk is stone with air pockets and a
            // decorant block at each zone cap so processZone's decorant-clearing
            // writes fire; every hunk write folds into the digest via
            // Hunk.listen. M.r()-gated mantle markers are deliberately NOT
            // digested (Math.random() is unseedable); they do not touch the hunk.
            {
                final com.volmit.iris.engine.framework.Engine carveEngine;
                final com.volmit.iris.util.mantle.Mantle carveMantle;
                {
                    carveMantle = new com.volmit.iris.util.mantle.Mantle(
                            new java.io.File("benchmark/results/_carvemantle"), 256);
                    java.util.Map<String, Object> em = new java.util.HashMap<>();
                    em.put("getMantle", carveMantle);
                    final com.volmit.iris.engine.mantle.EngineMantle carveEngineMantle =
                            (com.volmit.iris.engine.mantle.EngineMantle)
                                    java.lang.reflect.Proxy.newProxyInstance(
                                            Benchmark.class.getClassLoader(),
                                            new Class[]{com.volmit.iris.engine.mantle.EngineMantle.class},
                                            (proxy, method, args) -> {
                                                Object v = em.get(method.getName());
                                                if (v != null) {
                                                    return v;
                                                }
                                                Class<?> rt = method.getReturnType();
                                                if (rt == boolean.class) return false;
                                                if (rt == long.class) return 0L;
                                                if (rt == int.class) return 0;
                                                if (rt == double.class) return 0D;
                                                if (rt == float.class) return 0F;
                                                if (rt == short.class) return (short) 0;
                                                if (rt == byte.class) return (byte) 0;
                                                if (rt == char.class) return (char) 0;
                                                return null;
                                            });
                    java.util.Map<String, Object> hard = new java.util.HashMap<>();
                    hard.put("getCacheID", 486217);
                    hard.put("getSeedManager",
                            new com.volmit.iris.engine.framework.SeedManager(246810L));
                    hard.put("getData", com.volmit.iris.core.loader.IrisData.get(
                            new java.io.File("benchmark/results/_decodata")));
                    hard.put("getDimension", new com.volmit.iris.engine.object.IrisDimension()
                            .setName("bench-dim").setFluidHeight(62).setCaveLavaHeight(-8));
                    hard.put("getHeight", 256);
                    hard.put("getMinHeight", 0);
                    hard.put("getWorld", com.volmit.iris.engine.object.IrisWorld.builder()
                            .minHeight(0).maxHeight(256).build());
                    hard.put("getMetrics", new com.volmit.iris.engine.framework.EngineMetrics(10));
                    hard.put("getMantle", carveEngineMantle);
                    java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                        Object v = hard.get(method.getName());
                        if (v != null) {
                            return v;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == long.class) return 0L;
                        if (rt == int.class) return 0;
                        if (rt == double.class) return 0D;
                        if (rt == float.class) return 0F;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        return null;
                    };
                    carveEngine = (com.volmit.iris.engine.framework.Engine)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.framework.Engine.class}, h);
                }

                // Prefill cavern cells: water sphere (y 34..46) + dry tunnel (y 60..66)
                for (int dx = -6; dx <= 6; dx++) {
                    for (int dy = -6; dy <= 6; dy++) {
                        for (int dz = -6; dz <= 6; dz++) {
                            if (dx * dx + dy * dy + dz * dz > 36) {
                                continue;
                            }
                            int x = 8 + dx, y = 40 + dy, z = 8 + dz;
                            if (x < 0 || x > 15 || z < 0 || z > 15 || y < 1 || y > 250) {
                                continue;
                            }
                            carveMantle.set(x, y, z, com.volmit.iris.util.matter.slices.CavernMatter.get(
                                    "", y < 38 ? 1 : 0));
                        }
                    }
                }
                for (int x = 7; x <= 9; x++) {
                    for (int y = 60; y <= 66; y++) {
                        for (int z = 7; z <= 9; z++) {
                            carveMantle.set(x, y, z,
                                    com.volmit.iris.util.matter.slices.CavernMatter.get("", 0));
                        }
                    }
                }

                final com.volmit.iris.engine.modifier.IrisCarveModifier carveModifier =
                        new com.volmit.iris.engine.modifier.IrisCarveModifier(carveEngine);

                final BlockData stone = Material.STONE.createBlockData();
                final BlockData shortGrass = Material.GRASS.createBlockData();
                final BlockData water = Material.WATER.createBlockData();
                final BlockData caveAir = Material.CAVE_AIR.createBlockData();
                final Double[] carveHeights = new Double[256];
                final BlockData[] carveFluid = new BlockData[256];
                for (int i = 0; i < 256; i++) {
                    carveHeights[i] = 100.0;
                    carveFluid[i] = water;
                }
                final com.volmit.iris.util.context.ChunkContext carveCtx =
                        new com.volmit.iris.util.context.ChunkContext(0, 0, null)
                                .height(new com.volmit.iris.util.context.ChunkedDataCache<Double>(null, 0, 0)
                                        .prefill(carveHeights))
                                .fluid(new com.volmit.iris.util.context.ChunkedDataCache<BlockData>(null, 0, 0)
                                        .prefill(carveFluid));

                final Hunk<BlockData> carveBase = Hunk.newArrayHunk(16, 80, 16);
                final Digest[] carveDigest = new Digest[1];
                final Hunk<BlockData> carveHunk = carveBase.listen((x, y, z, t) -> {
                    Digest d = carveDigest[0];
                    d.add(x);
                    d.add(y);
                    d.add(z);
                    d.add(t == null ? -1 : t.getMaterial().ordinal());
                });

                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "carve-modify";
                    }

                    @Override
                    public int ops() {
                        return 4_000;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        carveDigest[0] = dg;
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            // Reset: solid stone everywhere, then sculpt the sphere
                            // cells into four hunk bands so every iterator branch
                            // fires: y 34-35 cave air (isAir early return),
                            // y 36-37 stone at water-cavern cells (fluid fill),
                            // y 38-40 water (isFluid skip), y 41-46 stone at dry
                            // cavern cells (cave-air write). The tunnel cells stay
                            // stone (dry cavern write path). Decorant blocks sit
                            // above each zone ceiling for processZone's clearing.
                            for (int x = 0; x < 16; x++) {
                                for (int y = 0; y < 80; y++) {
                                    for (int z = 0; z < 16; z++) {
                                        carveBase.set(x, y, z, stone);
                                    }
                                }
                            }
                            for (int dy = -6; dy <= 6; dy++) {
                                for (int dx = -6; dx <= 6; dx++) {
                                    for (int dz = -6; dz <= 6; dz++) {
                                        if (dx * dx + dy * dy + dz * dz > 36) {
                                            continue;
                                        }
                                        int x = 8 + dx, y = 40 + dy, z = 8 + dz;
                                        if (x < 0 || x > 15 || z < 0 || z > 15 || y < 1) {
                                            continue;
                                        }
                                        if (y <= 35 || (y >= 38 && y <= 40)) {
                                            carveBase.set(x, y, z, y <= 35 ? caveAir : water);
                                        }
                                    }
                                }
                            }
                            carveBase.set(8, 47, 8, shortGrass);
                            carveBase.set(8, 67, 8, shortGrass);
                            carveModifier.onModify(0, 0, carveHunk, false, carveCtx);
                            bh += i;
                        }
                        carveDigest[0] = null;
                        return bh;
                    }
                });
            }

            // ---- Perfection pass (IrisPerfectionModifier.onModify) ----
            // Real modifier (single-core deterministic queue) over a hunk with
            // valid poppies on grass, floating poppies, and a poppy-on-poppy
            // stack so the decorator-support fixup runs removals, cascades and
            // a confirming second while-pass. Digest = AIR-clear write sequence
            // via Hunk.listen.
            {
                final com.volmit.iris.engine.framework.Engine perfectionEngine;
                {
                    java.util.Map<String, Object> hard = new java.util.HashMap<>();
                    hard.put("getCacheID", 159357);
                    hard.put("getMetrics", new com.volmit.iris.engine.framework.EngineMetrics(10));
                    hard.put("burst", com.volmit.iris.util.parallel.MultiBurst.burst);
                    java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                        Object v = hard.get(method.getName());
                        if (v != null) {
                            return v;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == long.class) return 0L;
                        if (rt == int.class) return 0;
                        if (rt == double.class) return 0D;
                        if (rt == float.class) return 0F;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        return null;
                    };
                    perfectionEngine = (com.volmit.iris.engine.framework.Engine)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.framework.Engine.class}, h);
                }

                final com.volmit.iris.engine.modifier.IrisPerfectionModifier perfectionModifier =
                        new com.volmit.iris.engine.modifier.IrisPerfectionModifier(perfectionEngine);

                final BlockData stone = Material.STONE.createBlockData();
                final BlockData grass = Material.GRASS_BLOCK.createBlockData();
                final BlockData poppy = Material.POPPY.createBlockData();
                final Hunk<BlockData> perfectionBase = Hunk.newArrayHunk(16, 80, 16);
                final Digest[] perfectionDigest = new Digest[1];
                final Hunk<BlockData> perfectionHunk = perfectionBase.listen((x, y, z, t) -> {
                    Digest d = perfectionDigest[0];
                    d.add(x);
                    d.add(y);
                    d.add(z);
                    d.add(t == null ? -1 : t.getMaterial().ordinal());
                });

                out.add(new Scenario() {
                    @Override
                    public String name() {
                        return "perfection-modify";
                    }

                    @Override
                    public int ops() {
                        return 3_000;
                    }

                    @Override
                    public double run(int n, long seed, Digest dg) {
                        perfectionDigest[0] = dg;
                        double bh = 0;
                        for (int i = 0; i < n; i++) {
                            // Reset: terrain columns (stone + grass cap), then
                            // valid/floating/stacked decorant patterns.
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    int h = 20 + ((x * 7 + z * 13) % 30);
                                    for (int y = 0; y < h; y++) {
                                        perfectionBase.set(x, y, z, y == h - 1 ? grass : stone);
                                    }
                                    if ((x + z) % 4 == 0) {
                                        perfectionBase.set(x, h, z, poppy);
                                    }
                                    if ((x * z) % 5 == 0) {
                                        perfectionBase.set(x, h + 6, z, poppy);
                                    }
                                    if ((x + z) % 7 == 0) {
                                        perfectionBase.set(x, h + 1, z, poppy);
                                    }
                                }
                            }
                            perfectionModifier.onModify(0, 0, perfectionHunk, false,
                                    new com.volmit.iris.util.context.ChunkContext(0, 0, null));
                            bh += i;
                        }
                        perfectionDigest[0] = null;
                        return bh;
                    }
                });
            }

            // ---- Biome height sampling (IrisBiome.getHeight -> generator links) ----
            // Two real generator links whose keys miss the offline loader (the
            // default-IrisGenerator fallback is the production null-fallback
            // path). Per op: one full biome height sample = 2 link getHeight
            // calls, each resolving its cached generator (the hottest stream
            // accessor in the engine).
            {
                final com.volmit.iris.engine.framework.Engine heightEngine;
                {
                    java.util.Map<String, Object> hard = new java.util.HashMap<>();
                    hard.put("getHeight", 384);
                    hard.put("getData", com.volmit.iris.core.loader.IrisData.get(
                            new java.io.File("benchmark/results/_decodata")));
                    java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                        Object v = hard.get(method.getName());
                        if (v != null) {
                            return v;
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == long.class) return 0L;
                        if (rt == int.class) return 0;
                        if (rt == double.class) return 0D;
                        if (rt == float.class) return 0F;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == char.class) return (char) 0;
                        return null;
                    };
                    heightEngine = (com.volmit.iris.engine.framework.Engine)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    Benchmark.class.getClassLoader(),
                                    new Class[]{com.volmit.iris.engine.framework.Engine.class}, h);
                }

                final com.volmit.iris.engine.object.IrisBiome hBiome =
                        new com.volmit.iris.engine.object.IrisBiome();
                hBiome.setGenerators(new KList<>(
                        new com.volmit.iris.engine.object.IrisBiomeGeneratorLink()
                                .setGenerator("bench-gen-a").setMin(-4).setMax(28),
                        new com.volmit.iris.engine.object.IrisBiomeGeneratorLink()
                                .setGenerator("bench-gen-b").setMin(2).setMax(40)));

                out.add(sc("biome-height", (n, seed, dg) -> {
                    Random r = new Random(seed);
                    double bh = 0;
                    for (int i = 0; i < n; i++) {
                        double x = r.nextInt(200_000) - 100_000;
                        double z = r.nextInt(200_000) - 100_000;
                        double v = hBiome.getHeight(heightEngine, x, z, seed);
                        dg.add(v);
                        bh += v;
                    }
                    return bh;
                }));
            }

            // ---- Layer fill (IrisBiome.generateLayers) ----
            // Three palette layers (2-3 weighted blocks each, thickness 2-8,
            // STATIC layer height like typical surface configs). Per op: one
            // full column layer generation with a per-column RNG, exactly as
            // TerrainColumn invokes it. Digest = per-block material ordinals.
            {
                final com.volmit.iris.core.loader.IrisData lData = com.volmit.iris.core.loader.IrisData.get(
                        new java.io.File("benchmark/results/_decodata"));
                final com.volmit.iris.engine.object.IrisDimension lDim =
                        new com.volmit.iris.engine.object.IrisDimension();

                final com.volmit.iris.engine.object.IrisBiome lBiome =
                        new com.volmit.iris.engine.object.IrisBiome();
                {
                    KList<com.volmit.iris.engine.object.IrisBiomePaletteLayer> ls = new KList<>();
                    ls.add(new com.volmit.iris.engine.object.IrisBiomePaletteLayer()
                            .setMinHeight(2).setMaxHeight(4)
                            .setPalette(new KList<>(
                                    new com.volmit.iris.engine.object.IrisBlockData("grass_block"),
                                    new com.volmit.iris.engine.object.IrisBlockData("dirt"))));
                    ls.add(new com.volmit.iris.engine.object.IrisBiomePaletteLayer()
                            .setMinHeight(2).setMaxHeight(8)
                            .setPalette(new KList<>(
                                    new com.volmit.iris.engine.object.IrisBlockData("dirt"),
                                    new com.volmit.iris.engine.object.IrisBlockData("coarse_dirt"),
                                    new com.volmit.iris.engine.object.IrisBlockData("dirt"))));
                    ls.add(new com.volmit.iris.engine.object.IrisBiomePaletteLayer()
                            .setMinHeight(2).setMaxHeight(6)
                            .setPalette(new KList<>(
                                    new com.volmit.iris.engine.object.IrisBlockData("stone"),
                                    new com.volmit.iris.engine.object.IrisBlockData("stone"))));
                    lBiome.setLayers(ls);
                }

                out.add(sc("layers-gen", (n, seed, dg) -> {
                    Random r = new Random(seed);
                    double bh = 0;
                    for (int i = 0; i < n; i++) {
                        int x = r.nextInt(200_000) - 100_000;
                        int z = r.nextInt(200_000) - 100_000;
                        RNG rng = new RNG(((long) x << 32) ^ z);
                        KList<BlockData> blocks = lBiome.generateLayers(lDim, x, z, rng, 16, 100, lData, null);
                        for (BlockData b : blocks) {
                            dg.add(b.getMaterial().ordinal());
                            bh += b.getMaterial().ordinal();
                        }
                    }
                    return bh;
                }));
            }

            // HyperLock: hit-pattern lock/unlock through with(x, z, runnable)
            // (region keys repeat, like Mantle region locking in production).
            HyperLock hl = new HyperLock(1024);
            out.add(sc("hyperlock-hit", (n, seed, dg) -> {
                Random r = new Random(seed);
                long[] counter = {0};
                double bh = 0;
                for (int i = 0; i < n; i++) {
                    int x = r.nextInt(64) - 32, z = r.nextInt(64) - 32;
                    hl.with(x, z, () -> counter[0]++);
                }
                dg.add(counter[0]);
                bh += counter[0];
                return bh;
            }));

            // 8 workers all locking the same 64 keys. HyperLock's contract is
            // PER-KEY exclusion (different keys may proceed concurrently), so
            // each key guards its own cell: cell[k]++ under with(k, ...) is
            // exact only if same-key critical sections never overlap. The
            // coordinator folds each cell after join (worker RNG sequences are
            // seed-fixed, so every cell value is deterministic) — digest =
            // per-key exclusion proof. Additionally stripes collide (8 keys,
            // striped pool), exercising the collision path.
            HyperLock hlContended = new HyperLock(1024);
            out.add(new Scenario() {
                @Override
                public String name() {
                    return "par-hyperlock-contended";
                }

                @Override
                public int ops() {
                    return 150_000;
                }

                @Override
                public double run(int n, long seed, Digest dg) {
                    final long[] cells = new long[8];
                    java.util.concurrent.Future<Long>[] futures = new java.util.concurrent.Future[8];
                    try {
                        for (int t = 0; t < 8; t++) {
                            final long tSeed = seed + t;
                            futures[t] = PARALLEL_POOL.submit(() -> {
                                Random r = new Random(tSeed);
                                long local = 0;
                                for (int i = 0; i < n; i++) {
                                    int k = r.nextInt(8);
                                    hlContended.with(k, 0, () -> {
                                        long v = cells[k];
                                        v++;
                                        cells[k] = v;
                                    });
                                    local++;
                                }
                                return local;
                            });
                        }
                        double bh = 0;
                        for (int t = 0; t < 8; t++) {
                            dg.add(futures[t].get());
                        }
                        for (int k = 0; k < 8; k++) {
                            dg.add(cells[k]);
                            bh += cells[k];
                        }
                        return bh;
                    } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        return out;
    }

    private static final java.util.concurrent.ExecutorService PARALLEL_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(8);

    /**
     * Runs an operation body on {@code threads} workers simultaneously, each
     * performing {@code n} iterations with per-thread seeds ({@code seed+t}).
     * Per-thread digests are folded in index order so the merged digest is
     * deterministic regardless of scheduling. ns/op is wall-time based.
     */
    private static Scenario parallelScenario(String name, int threads, Op body) {
        Scenario s = sc(name, (n, seed, dg) -> {
            try {
                Digest[] parts = new Digest[threads];
                java.util.concurrent.Future<Double>[] futures = new java.util.concurrent.Future[threads];
                for (int t = 0; t < threads; t++) {
                    parts[t] = new Digest();
                    final int ft = t;
                    final Digest local = parts[t];
                    futures[t] = PARALLEL_POOL.submit(() -> body.run(n, seed + ft, local));
                }
                double bh = 0;
                for (int t = 0; t < threads; t++) {
                    bh += futures[t].get();
                    dg.h ^= parts[t].h; // fold in deterministic order
                    dg.h *= 0x100000001b3L;
                    dg.count += parts[t].count;
                }
                return bh;
            } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        return new Scenario() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int ops() {
                return 150_000; // each op fans out to `threads` workers
            }

            @Override
            public double run(int n, long seed, Digest dg) {
                return s.run(n, seed, dg);
            }
        };
    }

    private static Scenario interpScenario(String name, IrisInterpolator interp, CNG cng) {
        return sc(name, (n, seed, dg) -> {
            Random r = new Random(seed);
            double bh = 0;
            for (int i = 0; i < n; i++) {
                double v = interp.interpolate(r.nextInt(100_000) - 50_000, r.nextInt(100_000) - 50_000,
                        (x, z) -> cng.noise(x, z));
                dg.add(v);
                bh += v;
            }
            return bh;
        });
    }

    /** Minimal array-backed stand-in for ChunkedDataCache.get (same array-read shape). */
    static final class Ctx2D<T> {
        private final T[] data;

        Ctx2D(T[] data) {
            this.data = data;
        }

        T get(int x, int z) {
            return data[(z * 16) + x];
        }
    }

    /** Hunk that folds every set() into the benchmark digest (y + nullness). */
    static final class RecordingHunk implements Hunk<org.bukkit.block.data.BlockData> {
        Digest dg;

        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getDepth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public void setRaw(int x, int y, int z, org.bukkit.block.data.BlockData t) {
            dg.add(y);
            dg.add(t == null ? 0L : 1L);
        }

        @Override
        public org.bukkit.block.data.BlockData getRaw(int x, int y, int z) {
            return null;
        }
    }

    /** Minimal array-backed IObjectPlacer that folds every set() into the digest. */
    static final class RecordingPlacer implements com.volmit.iris.engine.object.IObjectPlacer {
        private static final int SX = 64, SY = 128, SZ = 64;
        private final BlockData[][][] world = new BlockData[SX][SY][SZ];
        Digest dg;

        private int fx(int x) {
            return Math.floorMod(x, SX);
        }

        private int fy(int y) {
            return Math.floorMod(y, SY);
        }

        private int fz(int z) {
            return Math.floorMod(z, SZ);
        }

        @Override
        public int getHighest(int x, int z, IrisData data) {
            return 64;
        }

        @Override
        public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
            return 64;
        }

        @Override
        public void set(int x, int y, int z, BlockData d) {
            dg.add(x);
            dg.add(y);
            dg.add(z);
            dg.add(d == null ? -1 : d.getMaterial().ordinal());
            world[fx(x)][fy(y)][fz(z)] = d;
        }

        @Override
        public BlockData get(int x, int y, int z) {
            return world[fx(x)][fy(y)][fz(z)];
        }

        @Override
        public boolean isPreventingDecay() {
            return false;
        }

        @Override
        public boolean isCarved(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            BlockData b = get(x, y, z);
            return b != null && B.isSolid(b);
        }

        @Override
        public boolean isUnderwater(int x, int z) {
            return false;
        }

        @Override
        public int getFluidHeight() {
            return 0;
        }

        @Override
        public boolean isDebugSmartBore() {
            return false;
        }

        @Override
        public void setTile(int xx, int yy, int zz, com.volmit.iris.engine.object.TileData tile) {
        }

        @Override
        public <T> void setData(int xx, int yy, int zz, T data) {
        }

        @Override
        public <T> T getData(int xx, int yy, int zz, Class<T> t) {
            return null;
        }

        @Override
        public com.volmit.iris.engine.framework.Engine getEngine() {
            return null;
        }
    }

    private static Scenario sc(String name, Op s) {
        return new Scenario() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public double run(int n, long seed, Digest dg) {
                return s.run(n, seed, dg);
            }
        };
    }
}
