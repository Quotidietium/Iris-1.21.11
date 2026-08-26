package bench;

import com.volmit.iris.engine.mantle.MantleWriter;
import com.volmit.iris.util.io.IO;
import com.volmit.iris.util.mantle.Mantle;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.matter.MatterCavern;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Round 26 memory-boundedness proof for the Mantle residency model.
 *
 * Simulates the adversarial pattern for plate residency: a hot pregen sweep
 * that touches every chunk of plate after plate far faster than the 30s
 * keepAlive can idle them out (every touch refreshes lastUse, so idle-based
 * eviction alone never fires mid-sweep). A driver mirroring IrisEngineSVC's
 * trim+unload cadence runs against the sweep.
 *
 * Checks:
 *  A. With the hard cap on (default): loaded plates at every checkpoint stay
 *     within limit + in-flight slack, no matter how much area was generated.
 *  B. After the sweep, trim(0,0)+unload must bring loaded plates to ZERO —
 *     any leaked MantleChunk use() pin (carve/custom modifier, writer
 *     constructor failure, ...) pins its whole plate forever, so this is an
 *     end-to-end pin audit, not just a residency check.
 *  C. Retained heap (after System.gc()) at the end returns to the pre-sweep
 *     plateau: no ratchet with generated area.
 *
 * Run with -Diris.mantle.hardcap=false to reproduce the pre-R26 behavior for
 * the A/B comparison (loaded plates grow with sweep width).
 */
public class VerifyMemoryBound {
    private static final int WORLD_HEIGHT = 256;

    public static void main(String[] args) throws Exception {
        // Real IrisSettings.get() touches the plugin data folder; pre-seed defaults
        // (same bootstrap as Benchmark.main).
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();

        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        int sweepWidthChunks = args.length > 1 ? Integer.parseInt(args[1]) : 1280; // 40 plates
        int sweepDepthChunks = args.length > 2 ? Integer.parseInt(args[2]) : 32;   // one plate row
        boolean hardcap = !"false".equals(System.getProperty("iris.mantle.hardcap", "true"));

        File dir = new File("benchmark/results/_membound");
        IO.delete(dir);
        dir.mkdirs();

        System.out.printf("hardcap=%s limit=%d sweep=%dx%d chunks (%d plates)%n",
                hardcap, limit, sweepWidthChunks, sweepDepthChunks,
                (sweepWidthChunks / 32 + 1) * (sweepDepthChunks / 32 + 1));

        Mantle mantle = new Mantle(dir, WORLD_HEIGHT);
        RNG rng = new RNG(777);

        long baseUsed = usedAfterGc();
        System.out.printf("baseline heap after gc: %.1f MB%n", baseUsed / 1048576.0);

        // Adversarial cadence: trim+unload every 64 chunks — far faster than
        // keepAlive, so idle eviction finds nothing on the active frontier.
        int maxLoaded = 0;
        int checkpoints = 0;
        List<String> table = new ArrayList<>();
        for (int cx = 0; cx < sweepWidthChunks; cx++) {
            for (int cz = 0; cz < sweepDepthChunks; cz++) {
                // Per-chunk matter write, shaped like the cave carve path:
                // one writer over a 5x5-chunk neighborhood, a few carved
                // cells per chunk (MatterCavern, no Bukkit dependency).
                try (MantleWriter w = new MantleWriter(null, mantle, cx, cz, 1, false)) {
                    MatterCavern cavern = new MatterCavern(true, "", (byte) 0);
                    w.setSphere((cx << 4) + 8, 64, (cz << 4) + 8, 3.0, true, cavern);
                }
            }

            if ((cx & 127) == 127) {
                mantle.trim(30_000, limit);
                int queued = mantle.getUnloadRegionCount();
                mantle.unloadTectonicPlate(limit);
                int loaded = mantle.getLoadedRegionCount();
                maxLoaded = Math.max(maxLoaded, loaded);
                checkpoints++;
                long used = usedAfterGc();
                table.add(String.format("  chunk x=%4d  platesTouched=%3d  queued=%2d  loaded=%2d  heap=%6.1f MB",
                        cx + 1, cx / 32 + 1, queued, loaded, used / 1048576.0));

                if (hardcap && loaded > limit + 2) {
                    table.forEach(System.out::println);
                    throw new IllegalStateException(
                            "HARD CAP VIOLATED: loaded=" + loaded + " > limit+2=" + (limit + 2)
                                    + " at chunk x=" + cx);
                }
            }
        }
        table.forEach(System.out::println);
        System.out.printf("sweep done: checkpoints=%d maxLoaded=%d (limit=%d, hardcap=%s)%n",
                checkpoints, maxLoaded, limit, hardcap);

        // B: settle — everything must be unloadable (pin audit). The 4s idle
        // floor inside trim (over-limit correction bottoms out at 4000ms) means
        // the audit must wait past it; after that, trim(0)+unload must drain
        // every plate — any survivor is either a leaked MantleChunk use() pin
        // (carve/custom modifier, writer constructor failure) or an unload
        // failure, both of which pin residency forever.
        Thread.sleep(4500);
        mantle.trim(0, Math.max(1, limit));
        mantle.unloadTectonicPlate(limit);
        mantle.trim(0, Math.max(1, limit));
        mantle.unloadTectonicPlate(limit);
        int settled = mantle.getLoadedRegionCount();
        if (settled != 0) {
            System.out.printf("DIAG: settled=%d toUnloadQueue=%d unloadQueued=%d%n",
                    settled, mantle.getUnloadRegionCount(), mantle.getUnloadRegionCount());
            try {
                var fld = Mantle.class.getDeclaredField("loadedRegions");
                fld.setAccessible(true);
                var regions = (java.util.Map<Long, ?>) fld.get(mantle);
                var luFld = Mantle.class.getDeclaredField("lastUse");
                luFld.setAccessible(true);
                var lastUse = (java.util.Map<Long, Long>) luFld.get(mantle);
                var tFld = com.volmit.iris.util.mantle.TectonicPlate.class.getDeclaredMethod("inUse");
                int pinned = 0, idle = 0;
                long now = System.currentTimeMillis();
                var chunksFld = com.volmit.iris.util.mantle.TectonicPlate.class.getDeclaredField("chunks");
                chunksFld.setAccessible(true);
                for (var e : regions.entrySet()) {
                    var plate = (com.volmit.iris.util.mantle.TectonicPlate) e.getValue();
                    boolean inUse = (boolean) tFld.invoke(plate);
                    if (inUse) {
                        pinned++;
                        var chunks = (java.util.concurrent.atomic.AtomicReferenceArray<?>) chunksFld.get(plate);
                        StringBuilder pins = new StringBuilder();
                        for (int i = 0; i < chunks.length(); i++) {
                            var ch = chunks.get(i);
                            if (ch != null && (boolean) com.volmit.iris.util.mantle.MantleChunk.class.getMethod("inUse").invoke(ch)) {
                                pins.append(' ').append(i & 31).append(',').append(i >> 5);
                            }
                        }
                        System.out.printf("  plate %s inUse chunks:%s%n", Long.toHexString(e.getKey()), pins);
                    } else idle++;
                    if (pinned + idle <= 5) {
                        long age = now - lastUse.getOrDefault(e.getKey(), 0L);
                        System.out.printf("  plate %s inUse=%s age=%dms closed=%s%n",
                                Long.toHexString(e.getKey()), inUse, age, plate.isClosed());
                    }
                }                System.out.printf("DIAG: %d pinned(inUse) / %d idle-not-unloaded%n", pinned, idle);
            } catch (ReflectiveOperationException rex) {
                rex.printStackTrace();
            }
            throw new IllegalStateException("PIN LEAK: " + settled
                    + " plate(s) failed to unload after settle — a leaked MantleChunk use() pin"
                    + " keeps its plate resident forever");
        }
        System.out.println("settle: loaded plates = 0 (no leaked pins)");

        // C: heap plateau — end state must return to the baseline band.
        long endUsed = usedAfterGc();
        long growth = endUsed - baseUsed;
        System.out.printf("end heap after gc: %.1f MB (baseline %.1f MB, growth %.1f MB)%n",
                endUsed / 1048576.0, baseUsed / 1048576.0, growth / 1048576.0);

        mantle.close();

        if (hardcap) {
            // Growth allowance: IOWorker's 128-channel LRU + runtime noise.
            long allowance = 64L * 1048576;
            if (growth > allowance) {
                throw new IllegalStateException("HEAP RATCHET: +" + (growth / 1048576.0)
                        + " MB retained after settle over " + sweepWidthChunks + "x" + sweepDepthChunks + " chunks");
            }
            System.out.println("VerifyMemoryBound: PASS (bounded residency, zero pins, heap plateau)");
        } else {
            System.out.println("VerifyMemoryBound: pre-R26 mode recorded (no assertions) — "
                    + "use this run for the A/B 'loaded grows with area' evidence");
        }
    }

    private static long usedAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
