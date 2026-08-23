package bench;

import com.volmit.iris.util.context.ChunkedDataCache;
import com.volmit.iris.util.parallel.MultiBurst;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;

/**
 * BENCHMARK-ONLY: faithful Java replica of the OLD (pre-round-14) Kotlin
 * ChunkContext/ChunkedDataCache fill orchestration, driving the REAL
 * kotlinx-coroutines machinery on the REAL MultiBurst dispatcher:
 * one runBlocking event loop, one launch per stream, and one launch PER CELL
 * (6 x 256 = 1536 child coroutines per chunk). Cell work goes through the
 * production ChunkedDataCache.fillCell, so this measures the orchestration
 * delta against the row-task fill with identical per-cell sampling.
 *
 * The only simplification: the Kotlin original wrapped each stream's cell
 * fan-out in a supervisorScope (failure isolation of children); that wrapper
 * allocates once per stream and is not on the timing path of successful fills.
 */
final class OldContextFill {
    private OldContextFill() {
    }

    static void fill(ChunkedDataCache<?>[] caches) throws InterruptedException {
        CoroutineDispatcher dispatcher = MultiBurst.burst.getDispatcher();

        BuildersKt.runBlocking((CoroutineContext) EmptyCoroutineContext.INSTANCE,
                (Function2<CoroutineScope, Continuation<? super Unit>, Unit>) (scope, cont) -> {
                    for (ChunkedDataCache<?> cache : caches) {
                        final ChunkedDataCache<?> c = cache;
                        BuildersKt.launch(scope, dispatcher, CoroutineStart.DEFAULT,
                                (Function2<CoroutineScope, Continuation<? super Unit>, Unit>) (s1, k1) -> {
                                    for (int j = 0; j < 16; j++) {
                                        for (int i = 0; i < 16; i++) {
                                            final int fi = i;
                                            final int fj = j;
                                            BuildersKt.launch(s1, dispatcher, CoroutineStart.DEFAULT,
                                                    (Function2<CoroutineScope, Continuation<? super Unit>, Unit>) (s2, k2) -> {
                                                        c.fillCell(fi, fj);
                                                        return Unit.INSTANCE;
                                                    });
                                        }
                                    }
                                    return Unit.INSTANCE;
                                });
                    }
                    return Unit.INSTANCE;
                });
    }
}
