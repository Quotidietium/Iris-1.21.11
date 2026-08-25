package bench;

import com.volmit.iris.engine.object.IrisPosition;
import com.volmit.iris.util.collection.KSet;
import com.volmit.iris.util.function.Function3;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.noise.CNG;
import com.volmit.iris.util.noise.NoiseType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A/B equivalence proof for the R23 compact cave-carve set algebra:
 * the legacy chain (cleanup -> getMasked/getBallooned -> removeIf) vs the
 * packed-long chain, on identical inputs. Also probes CNG.noise purity
 * (same value for repeated/ordered-differently calls).
 */
public class VerifyCaveSet {
    // ---- legacy chain, verbatim copy of pre-R23 MantleWriter internals ----

    private static Set<IrisPosition> cleanup(List<IrisPosition> vectors) {
        Set<IrisPosition> vset = new KSet<>();
        for (int i = 0; vectors.size() != 0 && i < vectors.size() - 1; i++) {
            IrisPosition pos1 = vectors.get(i);
            IrisPosition pos2 = vectors.get(i + 1);
            int x1 = pos1.getX(), y1 = pos1.getY(), z1 = pos1.getZ();
            int x2 = pos2.getX(), y2 = pos2.getY(), z2 = pos2.getZ();
            int tipx = x1, tipy = y1, tipz = z1;
            int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
            if (dx + dy + dz == 0) {
                vset.add(new IrisPosition(tipx, tipy, tipz));
                continue;
            }
            int dMax = Math.max(Math.max(dx, dy), dz);
            if (dMax == dx) {
                for (int s = 0; s <= dx; s++) {
                    vset.add(new IrisPosition(x1 + s * Integer.signum(x2 - x1),
                            (int) Math.round(y1 + s * ((double) dy) / dx * Integer.signum(y2 - y1)),
                            (int) Math.round(z1 + s * ((double) dz) / dx * Integer.signum(z2 - z1))));
                }
            } else if (dMax == dy) {
                for (int s = 0; s <= dy; s++) {
                    vset.add(new IrisPosition(
                            (int) Math.round(x1 + s * ((double) dx) / dy * Integer.signum(x2 - x1)),
                            y1 + s * Integer.signum(y2 - y1),
                            (int) Math.round(z1 + s * ((double) dz) / dy * Integer.signum(z2 - z1))));
                }
            } else {
                for (int s = 0; s <= dz; s++) {
                    vset.add(new IrisPosition(
                            (int) Math.round(x1 + s * ((double) dx) / dz * Integer.signum(x2 - x1)),
                            (int) Math.round(y1 + s * ((double) dy) / dz * Integer.signum(y2 - y1)),
                            z1 + s * Integer.signum(z2 - z1)));
                }
            }
        }
        return vset;
    }

    private static Set<IrisPosition> legacyMasked(List<IrisPosition> vectors, Set<IrisPosition> masks, double radius) {
        Set<IrisPosition> vset = cleanup(vectors);
        int ceil = (int) Math.ceil(radius);
        double r2 = Math.pow(radius, 2);
        final double[] sq = new double[(ceil << 1) + 1];
        for (int i = 0; i < sq.length; i++) sq[i] = Math.pow(i - ceil, 2);

        Set<IrisPosition> out = new KSet<>();
        for (IrisPosition v : vset) {
            int tipX = v.getX(), tipY = v.getY(), tipZ = v.getZ();
            for (int x = -ceil; x <= ceil; x++) {
                double xy = sq[x + ceil];
                for (int y = -ceil; y <= ceil; y++) {
                    double xz = xy + sq[y + ceil];
                    for (int z = -ceil; z <= ceil; z++) {
                        if (xz + sq[z + ceil] > r2 || !masks.contains(new IrisPosition(x, y, z)))
                            continue;
                        out.add(new IrisPosition(tipX + x, tipY + y, tipZ + z));
                    }
                }
            }
        }
        return out;
    }

    // ---- packed chain, copy of the R23 compact internals ----

    private static long pack(int x, int y, int z) {
        return ((x & 0x1FFFFFL) << 42) | ((y & 0x1FFFFFL) << 21) | (z & 0x1FFFFFL);
    }

    private static int upX(long p) { return ((int) (p >> 42)) << 11 >> 11; }

    private static int upY(long p) { return ((int) (p >> 21)) << 11 >> 11; }

    private static int upZ(long p) { return (int) p << 11 >> 11; }

    private static LongOpenHashSet compactCells(List<IrisPosition> vectors, Set<IrisPosition> masks, double radius) {
        Set<IrisPosition> tips = cleanup(vectors);
        int ceil = (int) Math.ceil(radius);
        double r2 = Math.pow(radius, 2);
        final double[] sq = new double[(ceil << 1) + 1];
        for (int i = 0; i < sq.length; i++) sq[i] = Math.pow(i - ceil, 2);

        LongOpenHashSet maskSet = new LongOpenHashSet(Math.max(1, masks.size()));
        for (IrisPosition m : masks) maskSet.add(pack(m.getX(), m.getY(), m.getZ()));

        LongOpenHashSet cells = new LongOpenHashSet(Math.max(16, tips.size() * (ceil + 1)));
        for (IrisPosition v : tips) {
            int tipX = v.getX(), tipY = v.getY(), tipZ = v.getZ();
            for (int x = -ceil; x <= ceil; x++) {
                double xy = sq[x + ceil];
                for (int y = -ceil; y <= ceil; y++) {
                    double xz = xy + sq[y + ceil];
                    for (int z = -ceil; z <= ceil; z++) {
                        if (xz + sq[z + ceil] > r2) continue;
                        if (!maskSet.contains(pack(x, y, z))) continue;
                        cells.add(pack(tipX + x, tipY + y, tipZ + z));
                    }
                }
            }
        }
        return cells;
    }

    public static void main(String[] a) {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        // 1) CNG purity probe
        RNG rng = new RNG(1234567L);
        CNG cng = new CNG(new RNG(1234567L), NoiseType.PERLIN, 0.5, 12);
        double n1 = cng.noise(3, 4, 5);
        double n2 = cng.noise(3, 4, 5);
        double o1 = cng.noise(7, 8, 9);
        double n3 = cng.noise(3, 4, 5);
        System.out.println("purity: repeat=" + (n1 == n2) + " interleaved=" + (n1 == n3) + " n1=" + n1 + " o1=" + o1);

        // 2) set-algebra equivalence across randomized shapes
        Random r = new Random(42);
        int totalDiffs = 0;
        for (int trial = 0; trial < 200; trial++) {
            List<IrisPosition> vectors = new ArrayList<>();
            int points = 2 + r.nextInt(12);
            for (int i = 0; i < points; i++)
                vectors.add(new IrisPosition(r.nextInt(60) - 30, 20 + r.nextInt(40), r.nextInt(60) - 30));
            KSet<IrisPosition> masks = new KSet<>();
            int ceil = 1 + r.nextInt(4);
            int mc = r.nextInt((2 * ceil + 1) * (2 * ceil + 1) * (2 * ceil + 1) + 1);
            for (int i = 0; i < mc; i++)
                masks.add(new IrisPosition(r.nextInt(2 * ceil + 1) - ceil, r.nextInt(2 * ceil + 1) - ceil, r.nextInt(2 * ceil + 1) - ceil));
            double radius = ceil - r.nextDouble();

            Set<IrisPosition> legacy = legacyMasked(vectors, masks, radius);
            LongOpenHashSet compact = compactCells(vectors, masks, radius);

            LongOpenHashSet legacyPacked = new LongOpenHashSet(legacy.size());
            for (IrisPosition p : legacy) legacyPacked.add(pack(p.getX(), p.getY(), p.getZ()));

            if (!legacyPacked.equals(compact)) {
                totalDiffs++;
                if (totalDiffs <= 3) {
                    LongOpenHashSet onlyLegacy = new LongOpenHashSet(legacyPacked);
                    onlyLegacy.removeAll(compact);
                    LongOpenHashSet onlyCompact = new LongOpenHashSet(compact);
                    onlyCompact.removeAll(legacyPacked);
                    System.out.println("trial " + trial + " DIFF legacy=" + legacy.size() + " compact=" + compact.size()
                            + " onlyLegacy=" + onlyLegacy.size() + " onlyCompact=" + onlyCompact.size());
                    it.unimi.dsi.fastutil.longs.LongIterator li = onlyLegacy.iterator();
                    for (int i = 0; i < 3 && li.hasNext(); i++) {
                        long p = li.nextLong();
                        System.out.println("  onlyLegacy: " + upX(p) + "," + upY(p) + "," + upZ(p));
                    }
                    li = onlyCompact.iterator();
                    for (int i = 0; i < 3 && li.hasNext(); i++) {
                        long p = li.nextLong();
                        System.out.println("  onlyCompact: " + upX(p) + "," + upY(p) + "," + upZ(p));
                    }
                }
            }
        }
        System.out.println(totalDiffs == 0 ? "SET ALGEBRA IDENTICAL (200 trials)" : totalDiffs + " DIFFERING TRIALS");

        // 3) pack roundtrip sweep over signed ranges
        long bad = 0;
        for (int x = -1000; x <= 1000; x += 7)
            for (int y = -1000; y <= 1000; y += 11)
                for (int z = -1000; z <= 1000; z += 13)
                    if (upX(pack(x, y, z)) != x || upY(pack(x, y, z)) != y || upZ(pack(x, y, z)) != z)
                        bad++;
        System.out.println(bad == 0 ? "PACK ROUNDTRIP OK" : "PACK ROUNDTRIP FAILURES: " + bad);
    }
}
