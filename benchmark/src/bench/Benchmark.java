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
            for (Scenario s : scenarios) {
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
