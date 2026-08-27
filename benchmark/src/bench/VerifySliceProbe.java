package bench;

import com.volmit.iris.util.data.Varint;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Round-22 slice probe: for every failing BlockData slice in the r21b dumps,
 * decodes ALL varlongs inside the declared region (not just the reader's
 * computed count) and reports their value profile. Discriminator: extra
 * varlongs with values bounded by the palette mask = a genuinely larger
 * cell array (writer bits/generation skew); extra bytes that decode to
 * wild 64-bit patterns (or fail varlong framing) = foreign bytes interleaved
 * into the stream.
 */
public class VerifySliceProbe {
    private final DataInputStream in;
    private long count;
    private final String name;

    VerifySliceProbe(File f) throws IOException {
        name = f.getName();
        in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "benchmark/results/r21b");
        File[] dumps = dir.listFiles((d, n) -> n.endsWith(".bin"));
        if (dumps == null) throw new IllegalStateException("no dumps");
        for (File f : dumps) {
            try {
                new VerifySliceProbe(f).run();
            } catch (Throwable e) {
                System.out.println(f.getName() + " HARD-FAIL " + e);
            }
        }
    }

    private int u8() throws IOException {
        int r = in.read();
        if (r < 0) throw new EOFException("eof@" + count);
        count++;
        return r;
    }

    private int i32() throws IOException {
        int v = 0;
        for (int i = 0; i < 4; i++) v = (v << 8) | u8();
        return v;
    }

    private long i64() throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | u8();
        return v;
    }

    private int u16() throws IOException {
        return (u8() << 8) | u8();
    }

    private String utf() throws IOException {
        int len = u16();
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) u8();
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private long varintV() throws IOException {
        int value = 0, i = 0, b;
        while (((b = u8()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
        }
        return value | (b << i);
    }

    void run() throws IOException {
        i32(); i32(); varintV();
        int probed = 0;
        for (int ci = 0; ci < 1024 && probed < 12; ci++) {
            int csize = i32();
            if (csize == 0) continue;
            long chunkEnd = count + csize;
            u8(); u8();
            int s = u8();
            int fl = (int) varintV();
            for (int fb = 0; fb < ((fl + 7) >> 3); fb++) u8();
            for (int i = 0; i < s; i++) {
                int ssize = i32();
                if (ssize == 0) continue;
                long secEnd = count + ssize;
                int problems = probeSection(ci, i);
                probed += problems;
                while (count < secEnd) u8();
            }
            while (count < chunkEnd) u8();
        }
    }

    private int probeSection(int ci, int sec) throws IOException {
        i32(); i32(); i32();
        int sliceCount = u8();
        utf(); i64(); u16();
        int problems = 0;
        for (int si = 0; si < sliceCount; si++) {
            int declared = i32();
            if (declared == 0) continue;
            long sliceStart = count;
            long sliceEnd = sliceStart + declared;
            String cls = utf();
            boolean cavern = cls.endsWith("MatterCavern");
            if (!cls.endsWith("BlockData") && !cavern) {
                while (count < sliceEnd) u8();
                continue;
            }
            long contentStart = count;
            int len = (int) varintV();
            int palSize = (int) varintV();
            List<String> nodes = new ArrayList<>();
            for (int p = 0; p < palSize; p++) {
                if (cavern) {
                    u8();
                    int ul = u16();
                    StringBuilder sb = new StringBuilder();
                    for (int ub = 0; ub < ul; ub++) sb.append((char) u8());
                    u8();
                    nodes.add(sb.toString());
                } else {
                    int ul = u16();
                    StringBuilder sb = new StringBuilder();
                    for (int ub = 0; ub < ul; ub++) sb.append((char) u8());
                    nodes.add(sb.toString());
                }
            }
            long nodesEnd = count;
            int bits = bits(palSize + 1);
            int vpl = 64 / bits;
            int wantLongs = (len + vpl - 1) / vpl;
            long mask = (1L << bits) - 1;
            // Decode varlongs until the declared end.
            List<Long> vals = new ArrayList<>();
            try {
                while (count < sliceEnd) {
                    long v = 0;
                    int i = 0, b;
                    while (((b = u8()) & 0x80) != 0) {
                        v |= (long) (b & 0x7F) << i;
                        i += 7;
                        if (i > 63) throw new IOException("varlong too long");
                    }
                    v |= (long) b << i;
                    vals.add(v);
                }
            } catch (IOException e) {
                System.out.printf("%s chunk[%d] sec%d slice#%d: decode error at %d/%d: %s%n",
                        name, ci, sec, si, vals.size(), declared, e.getMessage());
                while (count < sliceEnd) u8();
                problems++;
                continue;
            }
            long extra = vals.size() - wantLongs;
            long shortfall = sliceEnd - count;
            if (shortfall != 0) {
                // Distribution of per-long SET-BITS to see whether extra longs look like packed cells.
                java.util.StringJoiner sj = new java.util.StringJoiner(",");
                for (int k = (int) Math.max(0, wantLongs); k < vals.size() && k < wantLongs + 6; k++) {
                    sj.add(Long.toUnsignedString(vals.get(k)));
                }
                System.out.printf("%s chunk[%d] sec%d slice#%d %s: pal=%d uniq=%d bits=%d wantLongs=%d actual=%d SHORTFALL=%d declared=%d tailSamples=[%s]%n",
                        name, ci, sec, si, cls.substring(cls.lastIndexOf('.') + 1), palSize, new java.util.HashSet<>(nodes).size(), bits, wantLongs, vals.size(),
                        shortfall, declared, sj.toString());
                problems++;
            }
            while (count < sliceEnd) u8();
        }
        return problems;
    }

    private static int bits(int size) {
        if (8 >= size) return 3;
        for (int i = 0; i < 32; i++) {
            if ((1 << i) >= size) return i;
        }
        return 31;
    }
}
