package bench;

import com.volmit.iris.util.parallel.BurstExecutor;
import com.volmit.iris.util.parallel.MultiBurst;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round 27 regression proof for BurstExecutor.complete() exception semantics:
 *
 *  A. Barrier: with one failing task among many, complete() returns/throws
 *     only after ALL tasks ran (the shared-hunk hand-on contract from R12).
 *  B. Propagation: the first failure (submission order) is rethrown,
 *     unwrapped from ExecutionException — matching the single-core path,
 *     which has always thrown from queue().
 *  C. Idempotent retry: a second complete() after a failure is a no-op
 *     (futures cleared), never a re-throw of the stale failure — the pattern
 *     Mantle.unloadTectonicPlate's catch path relies on.
 *  D. Single-core: unchanged — inline execution, exception straight out of
 *     queue(), complete() a no-op.
 */
public class VerifyBurstException {
    public static void main(String[] args) throws Exception {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();

        // A + B: barrier + first-failure propagation, multicore.
        {
            AtomicInteger ran = new AtomicInteger();
            IllegalStateException boom = new IllegalStateException("first");
            IllegalStateException second = new IllegalStateException("second-never-thrown");
            BurstExecutor e = MultiBurst.burst.burst(8);
            e.setMulticore(true);
            e.queue(() -> ran.incrementAndGet());
            e.queue(() -> {
                throw boom;
            });
            e.queue(() -> {
                // must still run even though an earlier task failed (barrier)
                ran.incrementAndGet();
                throw second;
            });
            for (int i = 0; i < 5; i++) e.queue(ran::incrementAndGet);

            Throwable t = null;
            try {
                e.complete();
            } catch (Throwable ex) {
                t = ex;
            }
            // NOTE: ForkJoinTask.get() reconstructs unchecked exceptions via
            // reflection (its exception-table compression) and rethrows them
            // DIRECTLY — identity is lost and the reconstruction may carry the
            // original as its cause with the wrapped message. Prove class
            // equality plus the original message somewhere on the chain; the
            // inline single-core path below does preserve identity.
            boolean classOk = t != null && t.getClass() == boom.getClass();
            boolean msgOk = false;
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (boom.getMessage().equals(c.getMessage())) {
                    msgOk = true;
                    break;
                }
            }
            check(classOk && msgOk, "first failure propagated (class+message-on-chain), got " + t);
            check(ran.get() == 7, "all 7 tasks ran before complete() finished (barrier), ran=" + ran.get());

            // C: retry is a no-op.
            e.complete();
            System.out.println("A+B+C multicore: PASS (barrier joined all 7, first failure propagated, retry no-op)");
        }

        // C for the interrupted flavor is covered by clearing semantics above.

        // D: single-core unchanged.
        {
            RuntimeException boom = new RuntimeException("inline");
            BurstExecutor e = MultiBurst.burst.burst(1);
            e.setMulticore(false);
            boolean[] after = {false};
            Throwable t = null;
            try {
                e.queue(() -> after[0] = true); // runs inline
                e.queue(() -> {
                    throw boom;
                }); // throws inline, straight out of queue()
            } catch (Throwable ex) {
                t = ex;
            }
            check(t == boom, "single-core throws from queue() by reference");
            check(after[0], "inline task before the failure ran");
            e.complete(); // no-op, must not throw
            System.out.println("D single-core: PASS (inline throw from queue(), complete() no-op)");
        }

        System.out.println("VerifyBurstException: PASS");
    }

    private static void check(boolean c, String msg) {
        if (!c) throw new IllegalStateException("FAIL: " + msg);
    }
}
