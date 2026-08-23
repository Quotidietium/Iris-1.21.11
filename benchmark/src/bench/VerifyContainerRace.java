package bench;

import com.volmit.iris.util.hunk.bits.DataContainer;
import com.volmit.iris.util.hunk.bits.Writable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency stress for the lock-free DataContainer.get seqlock.
 *
 * Two families of 4096 distinct markers each (8192 palette entries => many
 * bit-width swaps + trim remaps during the run). Writer threads flip the whole
 * grid between families; every reader must only ever observe family A values
 * in A-cells and family B values in B-cells. A torn (data, palette) pair maps
 * an id through the wrong palette and surfaces as a value belonging to another
 * cell — the assertion fails. Runs of this test must end with zero violations.
 */
public final class VerifyContainerRace {
    private static final int CELLS = 4096;
    private static final int WRITERS = 2;
    private static final int READERS = 6;
    private static final long RUN_MS = 6000;
    static final class Val {
        final String s;

        Val(String s) {
            this.s = s;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Val v && v.s.equals(s);
        }

        @Override
        public int hashCode() {
            return s.hashCode();
        }

        @Override
        public String toString() {
            return s;
        }
    }

    public static void main(String[] args) throws Exception {
        final Val[][] families = new Val[4][CELLS];
        for (int f = 0; f < families.length; f++) {
            for (int i = 0; i < CELLS; i++) {
                families[f][i] = new Val("f" + f + "-" + i);
            }
        }

        Writable<Val> w = new Writable<>() {
            @Override
            public void writeNodeData(java.io.DataOutputStream dos, Val t) throws java.io.IOException {
            }

            @Override
            public Val readNodeData(java.io.DataInputStream din) throws java.io.IOException {
                return null;
            }
        };

        final DataContainer<Val> c = new DataContainer<>(w, CELLS);
        for (int i = 0; i < CELLS; i++) {
            c.set(i, families[0][i]);
        }

        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong violations = new AtomicLong();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong writes = new AtomicLong();
        final CountDownLatch go = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < WRITERS; t++) {
            final int id = t;
            threads.add(new Thread(() -> {
                await(go);
                int round = id;
                while (!stop.get()) {
                    Val[] fam = families[round++ % 2];
                    for (int i = 0; i < CELLS; i++) {
                        c.set(i, fam[i]);
                    }
                    writes.incrementAndGet();
                }
            }, "writer-" + t));
        }
        // Serializes (writeDos -> trim remap) while readers are live: exercises
        // the trim-side structural swap fence, not just palette growth. Pauses
        // between rounds so the writers can keep flipping (more mid-read swaps).
        threads.add(new Thread(() -> {
            await(go);
            while (!stop.get()) {
                try {
                    c.writeDos(new java.io.DataOutputStream(
                            new java.io.ByteArrayOutputStream()));
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, "trimmer"));
        for (int t = 0; t < READERS; t++) {
            threads.add(new Thread(() -> {
                await(go);
                long n = 0;
                while (!stop.get()) {
                    for (int i = 0; i < CELLS; i++) {
                        Val v = c.get(i);
                        boolean legal = false;
                        for (Val[] fam : families) {
                            legal |= v == fam[i];
                        }
                        if (!legal) {
                            violations.incrementAndGet();
                            System.out.println("VIOLATION cell=" + i + " got=" + v);
                        }
                        n++;
                    }
                    reads.addAndGet(n);
                    n = 0;
                }
            }, "reader-" + t));
        }

        threads.forEach(Thread::start);
        go.countDown();
        Thread.sleep(RUN_MS);
        stop.set(true);
        for (Thread th : threads) {
            th.join();
        }

        System.out.printf("reads=%d writes=%d violations=%d%n",
                reads.get(), writes.get(), violations.get());
        // Writers stop mid-round, so a family mix across cells is legal; what
        // must hold is that every cell holds a value belonging to THAT cell.
        for (int i = 0; i < CELLS; i++) {
            Val v = c.get(i);
            boolean legal = false;
            for (Val[] fam : families) {
                legal |= v == fam[i];
            }
            if (!legal) {
                throw new AssertionError("post-run incoherent cell " + i + " = " + v);
            }
        }
        if (violations.get() != 0) {
            throw new AssertionError("TORN READS DETECTED: " + violations.get());
        }
        System.out.println("VerifyContainerRace OK");
    }

    private static void await(CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private VerifyContainerRace() {
    }
}
