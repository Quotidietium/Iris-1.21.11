package com.volmit.iris.util.cache;

import com.volmit.iris.util.function.IntFunction2;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Per-chunk (16x16) memo pad for a {@link WorldCache2D}. Cells hold the
 * resolved value directly; first access claims the slot via CAS on a
 * COMPUTING sentinel so exactly one thread runs the resolver, others wait
 * (mirrors the previous per-cell Entry's synchronized double-checked lock,
 * without allocating an Entry object per cell). Null results are not
 * memoized, matching the previous behavior. {@code iris.cache.fast} skips
 * the waiting entirely (racy duplicate compute, identical value).
 */
public class ChunkCache2D<T> {
    private static final boolean FAST = Boolean.getBoolean("iris.cache.fast");
    private static final VarHandle AA = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final Object COMPUTING = new Object();

    private final Object[] cache = new Object[256];

    @SuppressWarnings("unchecked")
    public T get(int x, int z, IntFunction2<T> resolver) {
        int key = ((z & 15) << 4) + (x & 15);
        Object v = AA.getAcquire(cache, key);
        if (v != null && v != COMPUTING) {
            return (T) v;
        }
        if (v == null && AA.compareAndSet(cache, key, null, COMPUTING)) {
            T r;
            try {
                r = resolver.apply(x, z);
            } catch (Throwable t) {
                AA.setRelease(cache, key, null);
                throw t;
            }
            AA.setRelease(cache, key, r == null ? null : r);
            return r;
        }
        if (FAST) {
            T r = resolver.apply(x, z);
            if (r != null) {
                AA.setRelease(cache, key, r);
            }
            return r;
        }
        while ((v = AA.getAcquire(cache, key)) == COMPUTING) {
            Thread.onSpinWait();
        }
        return (T) v;
    }
}
