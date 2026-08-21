package bench;

import com.volmit.iris.util.function.NoiseProvider3;
import com.volmit.iris.util.interpolation.IrisInterpolation;
import com.volmit.iris.util.interpolation.InterpolationMethod3D;
import com.volmit.iris.util.interpolation.Starcast;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.noise.CNG;

/**
 * One-off equivalence proof for the round-2 3D adapter rewrite: compares
 * IrisInterpolation.getStarcast3D / getNoise3D(TRILINEAR_TRISTARCAST_*) against
 * a local re-implementation of the ORIGINAL lambda chains (the same expressions
 * as the pre-optimization code), over a large deterministic grid. Exits
 * non-zero on any bit difference.
 */
public final class Verify3D {
    public static void main(String[] args) {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        CNG cng = CNG.signature(new RNG(424242));
        NoiseProvider3 np = cng::noise;
        InterpolationMethod3D[] ttMethods = {
                InterpolationMethod3D.TRILINEAR_TRISTARCAST_3,
                InterpolationMethod3D.TRILINEAR_TRISTARCAST_6,
                InterpolationMethod3D.TRILINEAR_TRISTARCAST_9,
                InterpolationMethod3D.TRILINEAR_TRISTARCAST_12
        };

        long checked = 0;
        int failures = 0;
        java.util.Random r = new java.util.Random(1234567);

        for (int i = 0; i < 200_000; i++) {
            int x = r.nextInt(100_000) - 50_000;
            int y = r.nextInt(256);
            int z = r.nextInt(100_000) - 50_000;
            double rad = 3 + r.nextInt(12);
            int checkIdx = r.nextInt(4);
            double checks = (checkIdx + 1) * 3D;

            // getStarcast3D: original expression
            double orig = (Starcast.starcast(x, z, rad, checks, (xx, zz) -> np.noise(xx, y, zz))
                    + Starcast.starcast(x, y, rad, checks, (xx, yy) -> np.noise(xx, yy, z))
                    + Starcast.starcast(y, z, rad, checks, (yy, zz) -> np.noise(x, yy, zz))) / 3D;
            double now = IrisInterpolation.getStarcast3D(x, y, z, rad, checks, np);
            if (Double.doubleToLongBits(orig) != Double.doubleToLongBits(now)) {
                failures++;
                if (failures < 5) System.out.printf("getStarcast3D MISMATCH at (%d,%d,%d): %a vs %a%n", x, y, z, orig, now);
            }
            checked++;

            // TRILINEAR_TRISTARCAST: original composition (lambda + getStarcast3D chain)
            double origTT = (Starcast.starcast(x, z, rad, checks, (xx, zz) -> IrisInterpolation.getTrilinear((int) xx, (int) (double) y, (int) zz, rad, rad, rad, np))
                    + Starcast.starcast(x, y, rad, checks, (xx, yy) -> IrisInterpolation.getTrilinear((int) xx, (int) yy, (int) (double) z, rad, rad, rad, np))
                    + Starcast.starcast(y, z, rad, checks, (yy, zz) -> IrisInterpolation.getTrilinear((int) (double) x, (int) yy, (int) zz, rad, rad, rad, np))) / 3D;
            double nowTT = IrisInterpolation.getNoise3D(ttMethods[checkIdx], x, y, z, rad, np);
            if (Double.doubleToLongBits(origTT) != Double.doubleToLongBits(nowTT)) {
                failures++;
                if (failures < 5) System.out.printf("TRILINEAR_TRISTARCAST MISMATCH at (%d,%d,%d) checks=%s: %a vs %a%n", x, y, z, checks, origTT, nowTT);
            }
            checked++;

            double origTri = IrisInterpolation.getTrilinear(x, y, z, rad, rad, rad, np);
            double nowTri = IrisInterpolation.getNoise3D(InterpolationMethod3D.TRILINEAR, x, y, z, rad, np);
            if (Double.doubleToLongBits(origTri) != Double.doubleToLongBits(nowTri)) {
                failures++;
                if (failures < 5) System.out.printf("TRILINEAR MISMATCH at (%d,%d,%d): %a vs %a%n", x, y, z, origTri, nowTri);
            }
            checked++;
        }

        System.out.printf("checked=%d failures=%d%n", checked, failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("3D ADAPTERS BIT-IDENTICAL TO ORIGINAL LAMBDA CHAINS");
    }
}
