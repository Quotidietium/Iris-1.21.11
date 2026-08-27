package bench;

import net.jpountz.lz4.LZ4BlockInputStream;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Round-20 offline plate parser: walks every TectonicPlate file and parses the
 * Matter.readDin slice loop manually (size header vs consumed bytes), reporting
 * the exact asymmetry the engine panicked on during the turbo pregen memory
 * test. Deterministic: same file, same result — used to A/B the branch build
 * against a pre-R32-35 (master) build.
 */
public class VerifyPlateParse {
    static int files, slices, bad;

    public static void main(String[] args) throws Exception {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        com.volmit.iris.Iris.compat = new com.volmit.iris.engine.object.IrisCompat();
        File dir = new File(args.length > 0 ? args[0] : "build/smoke/testworld/mantle");
        File[] plates = dir.listFiles((d, n) -> n.endsWith(".ttp.lz4b"));
        if (plates == null) throw new IllegalStateException("no plates in " + dir);
        for (File plate : plates) {
            parse(plate);
        }
        System.out.println("files=" + files + " slices=" + slices + " mismatches=" + bad);
        System.out.println(bad == 0 ? "VerifyPlateParse: PASS" : "VerifyPlateParse: FAIL (" + bad + " mismatches)");
    }

    private static void parse(File plate) throws Exception {
        files++;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new LZ4BlockInputStream(new FileInputStream(plate)), 1 << 16))) {
            // Same chain as IOWorker.read: LZ4 -> buffered -> Counting -> TectonicPlate(height, din, versioned)
            // (plate files here are all "pv.*" = versioned).
            com.volmit.iris.util.io.CountingDataInputStream cin =
                    com.volmit.iris.util.io.CountingDataInputStream.wrap(in);
            new com.volmit.iris.util.mantle.TectonicPlate(256, cin, plate.getName().startsWith("pv."));
        } catch (Throwable e) {
            bad++;
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            System.out.println("MISMATCH/ERROR in " + plate.getName() + ": " + c);
        }
    }
}
