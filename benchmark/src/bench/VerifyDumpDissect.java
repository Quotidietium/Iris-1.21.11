package bench;

import com.volmit.iris.util.io.CountingDataInputStream;
import com.volmit.iris.util.mantle.TectonicPlate;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Round-21 dump verifier: replays the dump-on-error plate captures (already
 * decompressed raw plate streams — the exact bytes the reader saw when it
 * panicked) through the production TectonicPlate reader. Deterministic: if
 * hasError() fires offline on a dump, the file content itself was internally
 * inconsistent at capture time (a write-side defect, later overwritten); if
 * every dump parses clean, the live failure was channel-state dependent.
 */
public class VerifyDumpDissect {
    public static void main(String[] args) throws Exception {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        com.volmit.iris.Iris.compat = new com.volmit.iris.engine.object.IrisCompat();
        File dir = new File(args.length > 0 ? args[0] : "build/smoke/plugins/Iris/dump");
        File[] dumps = dir.listFiles((d, n) -> n.endsWith(".bin"));
        if (dumps == null || dumps.length == 0) throw new IllegalStateException("no dumps in " + dir);
        int bad = 0;
        for (File f : dumps) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(f), 1 << 16))) {
                CountingDataInputStream cin = CountingDataInputStream.wrap(in);
                new TectonicPlate(256, cin, f.getName().startsWith("pv."));
                long consumed = cin.count();
                boolean err = TectonicPlate.hasError();
                System.out.printf("%-34s %10d/%-10d bytes %s%n", f.getName(), consumed, f.length(),
                        err ? "ERROR-REPRODUCED" : "clean");
                if (err) bad++;
            } catch (Throwable e) {
                System.out.printf("%-34s threw %s: %s (HARD)%n", f.getName(),
                        e.getClass().getSimpleName(), e.getMessage());
                bad++;
            }
        }
        System.out.println(bad == 0 ? "VerifyDumpDissect: all dumps clean (channel-state dependent)"
                : "VerifyDumpDissect: " + bad + "/" + dumps.length + " dumps deterministic-bad");
    }
}
