package bench;

import com.volmit.iris.engine.object.IRare;
import com.volmit.iris.engine.object.IrisInterpolator;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.cache.WorldCache2D;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.interpolation.InterpolationMethod;
import com.volmit.iris.util.interpolation.InterpolationMethod3D;
import com.volmit.iris.util.interpolation.IrisInterpolation;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.noise.CNG;
import com.volmit.iris.util.noise.NoiseType;
import com.volmit.iris.util.stream.ProceduralStream;

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
        String out = args.length > 0 ? args[0] : "benchmark/results/latest.csv";
        int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int iters = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        List<Scenario> scenarios = buildScenarios();

        try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
            w.println("scenario,iteration,seed,ops,ns_per_op,bytes_per_op,digest,samples");
            for (Scenario s : scenarios) {
                for (int i = 0; i < warmups; i++) {
                    s.run(200_000, 424242L + i, new Digest());
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
