package bench;

import com.volmit.iris.util.data.Varint;
import com.volmit.iris.util.hunk.bits.DataContainer;
import com.volmit.iris.util.hunk.bits.Writable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round-21 concurrent repro for the slice size mismatch: writer threads hammer
 * DataContainer.set() (random values around palette bit boundaries) while a
 * serializer loops writeDos; every serialized form is checked against the
 * reader's size contract (varlong count == dataLength(bits(paletteSize+1))).
 */
public class VerifyContainerRace2 {
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
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        DataContainer<String> c = new DataContainer<>(STRINGS, LEN);
        Random r = new Random(4242);
        for (int i = 0; i < LEN; i++) {
            c.set(i, "init" + (i % 20));
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong ops = new AtomicLong();
        AtomicLong bad = new AtomicLong();
        Thread[] writers = new Thread[3];
        for (int t = 0; t < writers.length; t++) {
            final int seed = t;
            writers[t] = new Thread(() -> {
                Random rr = new Random(9000 + seed);
                String[] pool = new String[40];
                for (int i = 0; i < pool.length; i++) pool[i] = "t" + seed + "v" + i;
                while (!stop.get()) {
                    c.set(rr.nextInt(LEN), pool[rr.nextInt(pool.length)]);
                    ops.incrementAndGet();
                }
            });
            writers[t].start();
        }
        Thread serializer = new Thread(() -> {
            while (!stop.get()) {
                try {
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
                                report(bad, bytes, palSize, bits, "EOF at varlong " + li + "/" + longs);
                                return;
                            }
                        } while ((b & 0x80) != 0);
                    }
                    int leftover = 0;
                    while (din.read() >= 0) leftover++;
                    if (leftover > 0) {
                        report(bad, bytes, palSize, bits, "leftover=" + leftover + "B after " + longs + " varlongs");
                    }
                } catch (Throwable e) {
                    bad.incrementAndGet();
                    System.out.println("EXC " + e);
                }
            }
        });
        serializer.start();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread w : writers) w.join();
        serializer.join();
        System.out.println("ops=" + ops.get() + " bad=" + bad.get());
        System.out.println(bad.get() == 0 ? "VerifyContainerRace2: PASS (no divergence under concurrency)"
                : "VerifyContainerRace2: FAIL - reproduced");
    }

    private static synchronized void report(AtomicLong bad, byte[] bytes, int palSize, int bits, String msg) {
        bad.incrementAndGet();
        System.out.println("BAD pal=" + palSize + " bits=" + bits + " " + msg + " total=" + bytes.length);
    }

    private static int bits(int size) {
        if (8 >= size) return 3;
        for (int i = 0; i < 32; i++) {
            if ((1 << i) >= size) return i;
        }
        return 31;
    }
}
