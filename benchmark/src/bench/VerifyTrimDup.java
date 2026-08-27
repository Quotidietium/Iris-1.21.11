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
 * Round-21 regression proof for the plate slice corruption: reproduces the
 * exact production mechanism at unit level - a palette KEY MUTATED IN PLACE
 * rehashes away from its CHM bucket, so adding an equal-valued key later
 * misses and appends a DUPLICATE id. trim() then dedups by value while
 * (pre-fix) sizing the repacked DataBits from the pre-dedup distinct count,
 * producing a container whose serialized varlong count disagrees with what
 * any reader derives from paletteSize - the "Matter slice read size mismatch"
 * plate corruption (round20/21).
 */
public class VerifyTrimDup {
    static final int LEN = 4096;

    /** Mutable-hash key: models a BlockData mutated after entering the palette. */
    static final class MutKey {
        final String v;
        int h;

        MutKey(String v, int h) {
            this.v = v;
            this.h = h;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MutKey && ((MutKey) o).v.equals(v);
        }

        @Override
        public int hashCode() {
            return h;
        }
    }

    static final Writable<MutKey> KEYS = new Writable<>() {
        @Override
        public void writeNodeData(DataOutputStream dos, MutKey t) throws IOException {
            dos.writeUTF(t.v);
        }

        @Override
        public MutKey readNodeData(DataInputStream din) throws IOException {
            return new MutKey(din.readUTF(), 0);
        }
    };

    public static void main(String[] args) throws Exception {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        com.volmit.iris.Iris.compat = new com.volmit.iris.engine.object.IrisCompat();

        DataContainer<MutKey> c = new DataContainer<>(KEYS, LEN);
        MutKey[] keys = new MutKey[8];
        for (int i = 0; i < 8; i++) {
            keys[i] = new MutKey("k" + i, 1000 + i);
        }
        for (int i = 0; i < LEN; i++) {
            c.set(i, keys[i % 8]);
        }
        // Mutate one palette key's hash in place: it rehashes away from its
        // CHM bucket (production: a shared BlockData mutated without clone).
        keys[0].h = 999999;
        // Adding an equal value now misses the orphaned entry -> duplicate id (9).
        MutKey dup = new MutKey("k0", 555555);
        final MutKey[] dupRef = new MutKey[]{dup};
        c.set(0, dup);
        // Make k7's id unused so trim takes the repack path.
        for (int i = 0; i < LEN; i++) {
            if (i % 8 == 7) {
                c.set(i, keys[1]);
            }
        }
        // Converge the duplicate pair's hashes BEFORE trim (production timeline:
        // transient mutation creates the duplicate, later state normalization
        // makes the two instances hash-equal again) - now trim's add() dedups.
        keys[0].h = 424242;
        dupRef[0].h = 424242;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
        c.writeDos(new DataOutputStream(bo));
        byte[] bytes = bo.toByteArray();

        DataInputStream din = new DataInputStream(new ByteArrayInputStream(bytes));
        int len = Varint.readUnsignedVarInt(din);
        int palSize = Varint.readUnsignedVarInt(din);
        for (int p = 0; p < palSize; p++) {
            din.readUTF();
        }
        int bits = bits(palSize + 1);
        int vpl = 64 / bits;
        int longs = (len + vpl - 1) / vpl;
        for (int li = 0; li < longs; li++) {
            int b;
            do {
                b = din.read();
                if (b < 0) {
                    fail("EOF at varlong " + li + "/" + longs + " (pal=" + palSize + " bits=" + bits + ")");
                }
            } while ((b & 0x80) != 0);
        }
        int leftover = 0;
        while (din.read() >= 0) leftover++;
        if (leftover > 0) {
            fail("leftover=" + leftover + "B after " + longs + " varlongs (pal=" + palSize
                    + " bits=" + bits + " total=" + bytes.length + ")");
        }
        System.out.println("paletteSize=" + palSize + " bits=" + bits + " longs=" + longs
                + " total=" + bytes.length + " - size contract holds");
        System.out.println("VerifyTrimDup: PASS");
    }

    private static void fail(String m) {
        System.out.println("VerifyTrimDup: FAIL - " + m);
        System.exit(1);
    }

    private static int bits(int size) {
        if (8 >= size) return 3;
        for (int i = 0; i < 32; i++) {
            if ((1 << i) >= size) return i;
        }
        return 31;
    }
}
