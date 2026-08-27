package bench;

import com.volmit.iris.util.data.Varint;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Round-21 deep dump dissector: walks the (already decompressed) dump-on-error
 * plate streams frame by frame and, for every matter slice, decodes the
 * DataContainer structure (varint length, varint paletteSize, palette nodes,
 * packed varlong array) to account for its TRUE byte length vs the declared
 * slice size - pinpointing which side of the size contract is wrong.
 */
public class VerifyDumpDissect2 {
    private final DataInputStream in;
    private long count;
    private final long fileLen;
    private final String name;

    VerifyDumpDissect2(File f) throws IOException {
        name = f.getName();
        fileLen = f.length();
        in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/smoke/plugins/Iris/dump");
        File[] dumps = dir.listFiles((d, n) -> n.endsWith(".bin"));
        if (dumps == null) throw new IllegalStateException("no dumps");
        for (File f : dumps) {
            try {
                new VerifyDumpDissect2(f).run();
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

    /** Varint whose byte length we account for exactly. */
    private long[] varintCounted() throws IOException {
        int value = 0;
        int i = 0;
        int b;
        int bytes = 0;
        while (((b = u8()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            bytes++;
        }
        bytes++;
        return new long[]{value | (b << i), bytes};
    }

    private void skipTo(long target) throws IOException {
        while (count < target) {
            if (u8() < 0) return;
        }
    }

    void run() throws IOException {
        System.out.println("== " + name + " (" + fileLen + "B)");
        i32();
        i32();
        varintCounted();
        int mismatches = 0;
        for (int ci = 0; ci < 1024; ci++) {
            int csize = i32();
            if (csize == 0) continue;
            long chunkEnd = count + csize;
            u8();
            u8();
            int s = u8();
            int fl = (int) varintCounted()[0];
            for (int fb = 0; fb < ((fl + 7) >> 3); fb++) u8();
            for (int i = 0; i < s; i++) {
                int ssize = i32();
                if (ssize == 0) continue;
                long secEnd = count + ssize;
                mismatches += dissectSection(ci, i, ssize);
                skipTo(secEnd);
            }
            skipTo(chunkEnd);
            if (count != chunkEnd) {
                System.out.printf("  chunk[%d] frame desync: ended %d != %d%n", ci, count, chunkEnd);
                return;
            }
        }
        System.out.printf("  end: consumed=%d file=%d mismatches=%d%n", count, fileLen, mismatches);
    }

    private int dissectSection(int ci, int sec, int ssize) throws IOException {
        int problems = 0;
        i32();
        i32();
        i32();
        int sliceCount = u8();
        utf();
        i64();
        u16();
        for (int si = 0; si < sliceCount; si++) {
            int declared = i32();
            if (declared == 0) continue;
            long sliceStart = count;
            long sliceEnd = sliceStart + declared;
            String cls = utf();
            // DataContainer accounting
            long contentStart = count;
            long[] lr = varintCounted();
            int len = (int) lr[0];
            long headerBytes = (contentStart - sliceStart);
            int palSize = (int) varintCounted()[0];
            long nodeBytes = 0;
            boolean cavern = cls.endsWith("MatterCavern");
            boolean block = cls.endsWith("BlockData");
            for (int p = 0; p < palSize; p++) {
                if (cavern) {
                    u8();
                    int ul = u16();
                    for (int ub = 0; ub < ul; ub++) u8();
                    u8();
                    nodeBytes += 1 + 2 + ul + 1;
                } else if (block || cls.equals("java.lang.String") || cls.endsWith("MatterMarker")) {
                    int ul = u16();
                    for (int ub = 0; ub < ul; ub++) u8();
                    nodeBytes += 2 + ul;
                } else {
                    u8();
                    nodeBytes += 1;
                }
            }
            int bits = bits(palSize + 1);
            int valuesPerLong = 64 / bits;
            int longs = (len + valuesPerLong - 1) / valuesPerLong;
            long dataBytes = 0;
            for (int li = 0; li < longs; li++) {
                int b;
                do {
                    b = u8();
                    dataBytes++;
                } while ((b & 0x80) != 0);
            }
            long accounted = headerBytes + (count - contentStart);
            if (count != sliceEnd || accounted != declared) {
                System.out.printf("  MISMATCH chunk[%d] sec%d slice#%d %s: declared=%d accounted=%d pos=%d wantEnd=%d (len=%d pal=%d bits=%d longs=%d nodeB=%d dataB=%d hdrB=%d)%n",
                        ci, sec, si, cls, declared, accounted, count, sliceEnd, len, palSize, bits, longs, nodeBytes, dataBytes, headerBytes);
                problems++;
            }
            skipTo(sliceEnd);
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
