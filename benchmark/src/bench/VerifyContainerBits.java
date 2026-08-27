package bench;

import com.volmit.iris.util.data.Varint;
import com.volmit.iris.util.hunk.bits.DataContainer;
import com.volmit.iris.util.hunk.bits.Writable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Round-21 unit repro hunt for the plate slice size mismatch: searches
 * single-threaded DataContainer scenario sequences whose SERIALIZED bytes
 * violate the size contract the production reader assumes — the varlong
 * array on the stream must be exactly dataLength(bits(paletteSize+1), length)
 * values long. Node type mimics StringMatter (writeUTF/readUTF).
 */
public class VerifyContainerBits {
    static final int LEN = 4096;

    static final Writable<String> STRINGS = new Writable<>() {
        @Override
        public void writeNodeData(DataOutputStream dos, String s) throws IOException {
            dos.writeUTF(s);
        }

        @Override
        public String readNodeData(DataInputStream din) throws IOException {
            return din.readUTF();
        }
    };

    public static void main(String[] args) throws Exception {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        com.volmit.iris.Iris.compat = new com.volmit.iris.engine.object.IrisCompat();
        long scenarios = 0, bad = 0;
        for (int fill : new int[]{1, 2, 7, 8, 9, 15, 16, 17, 31, 32, 33}) {
            for (int overwrite : new int[]{1, 2, 7, 8, 9, 15, 16, 17, 31, 32, 33}) {
                for (int writes = 1; writes <= 3; writes++) {
                    for (int mutateBetween = 0; mutateBetween <= 2; mutateBetween++) {
                        scenarios++;
                        String r = run(fill, overwrite, writes, mutateBetween);
                        if (r != null) {
                            bad++;
                            System.out.println("BAD fill=" + fill + " ow=" + overwrite
                                    + " writes=" + writes + " mutate=" + mutateBetween + ": " + r);
                            if (bad > 5) {
                                System.out.println("(stopping early)");
                                break;
                            }
                        }
                    }
                }
            }
        }
        System.out.println("scenarios=" + scenarios + " bad=" + bad);
        System.out.println(bad == 0 ? "VerifyContainerBits: single-thread PASS (no divergence)"
                : "VerifyContainerBits: FAIL - divergence reproduced");
    }

    /** Returns null when the slice bytes satisfy the reader's size contract. */
    private static String run(int fill, int overwrite, int writes, int mutateBetween) {
        DataContainer<String> c = new DataContainer<>(STRINGS, LEN);
        for (int i = 0; i < LEN; i++) {
            c.set(i, "v" + (i % fill));
        }
        for (int i = 0; i < LEN; i++) {
            c.set(i, "w" + (i % overwrite));
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
            for (int wr = 0; wr < writes; wr++) {
                if (wr > 0) {
                    for (int m = 0; m < mutateBetween; m++) {
                        c.set((m * 997) % LEN, "m" + wr + "_" + m);
                    }
                }
                bo.reset();
                c.writeDos(new DataOutputStream(bo));
                byte[] bytes = bo.toByteArray();
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(bytes));
                int len = Varint.readUnsignedVarInt(din);
                int palSize = Varint.readUnsignedVarInt(din);
                for (int p = 0; p < palSize; p++) {
                    din.readUTF();
                }
                int bits = bits(palSize + 1);
                int valuesPerLong = 64 / bits;
                int longs = (len + valuesPerLong - 1) / valuesPerLong;
                for (int li = 0; li < longs; li++) {
                    int b;
                    do {
                        b = din.read();
                        if (b < 0) {
                            return "EOF at varlong " + li + "/" + longs + " (pal=" + palSize + " bits=" + bits + ")";
                        }
                    } while ((b & 0x80) != 0);
                }
                int leftover = 0;
                while (din.read() >= 0) leftover++;
                if (leftover > 0) {
                    return "leftover=" + leftover + "B after " + longs + " varlongs (pal=" + palSize
                            + " bits=" + bits + " total=" + bytes.length + ")";
                }
                DataContainer<String> back = new DataContainer<>(
                        new DataInputStream(new ByteArrayInputStream(bytes)), STRINGS);
                ByteArrayOutputStream bo2 = new ByteArrayOutputStream(1 << 16);
                back.writeDos(new DataOutputStream(bo2));
                if (bo2.size() != bytes.length) {
                    return "roundtrip size drift " + bytes.length + " -> " + bo2.size();
                }
            }
        } catch (Throwable e) {
            return "threw " + e;
        }
        return null;
    }

    private static int bits(int size) {
        if (8 >= size) return 3;
        for (int i = 0; i < 32; i++) {
            if ((1 << i) >= size) return i;
        }
        return 31;
    }
}
