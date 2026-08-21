package com.volmit.iris.util.cache;

import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.function.IntFunction2;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Size-bounded memoization of a per-block resolver, chunked into
 * {@link ChunkCache2D} pads. The chunk table is a striped open-addressing
 * hash over primitive long chunk keys: reads are lock-free (a single
 * acquire-load per probe step over immutable nodes), inserts serialize per
 * stripe, and a stripe that reaches its load limit is cleared wholesale.
 * This is behaviorally identical to the previous Caffeine-backed map because
 * the cache is a pure memoization layer — any eviction policy produces the
 * same values, only recompute cost differs — while removing the per-lookup
 * Long boxing, cache-node allocations and shared read buffers.
 */
public class WorldCache2D<T> {
    private static final int STRIPES = 32; // power of two
    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Object[].class);

    private final Stripe<T>[] stripes;
    private final IntFunction2<T> resolver;
    private final int maxSize;

    public WorldCache2D(IntFunction2<T> resolver, int size) {
        this.resolver = resolver;
        this.maxSize = size;
        int perStripe = Math.max(2, size / STRIPES);
        int cap = 16;
        while (cap - (cap >> 2) < perStripe) {
            cap <<= 1;
        }
        //noinspection unchecked
        this.stripes = new Stripe[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Stripe<>(cap);
        }
    }

    public T get(int x, int z) {
        long key = Cache.key(x >> 4, z >> 4);
        Stripe<T> stripe = stripes[stripeIndex(key)];
        ChunkCache2D<T> chunk = stripe.lookup(key);
        if (chunk == null) {
            chunk = stripe.insert(key);
        }
        return chunk.get(x, z, resolver);
    }

    public long getSize() {
        long total = 0;
        for (Stripe<T> stripe : stripes) {
            total += stripe.count;
        }
        return total * 256L;
    }

    public long getMaxSize() {
        return maxSize * 256L;
    }

    private static int stripeIndex(long key) {
        return (int) mix(key) & (STRIPES - 1);
    }

    /** splitmix64 finalizer: spreads packed chunk coords across stripes/slots. */
    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    /**
     * Immutable slot content: publishing the node with a release-store makes
     * both the key and the fully-built chunk visible to lock-free readers,
     * so a reader that matches the key can always trust the chunk (no
     * key/value pairing races across eviction + reinsert).
     */
    private static final class Node<T> {
        final long key;
        final ChunkCache2D<T> chunk;

        Node(long key) {
            this.key = key;
            this.chunk = new ChunkCache2D<>();
        }
    }

    private static final class Stripe<T> {
        final Object[] slots;
        final int mask;
        final int limit;
        int count; // guarded by this Stripe

        Stripe(int capacity) {
            slots = new Object[capacity];
            mask = capacity - 1;
            limit = capacity - (capacity >> 2);
        }

        @SuppressWarnings("unchecked")
        ChunkCache2D<T> lookup(long key) {
            Object[] slots = this.slots;
            int mask = this.mask;
            // Slot hash uses bits above the stripe index bits so every key in
            // a stripe doesn't collide into one probe chain.
            int i = (int) (mix(key) >>> 5) & mask;
            while (true) {
                Object o = SLOTS.getAcquire(slots, i);
                if (o == null) {
                    return null;
                }
                Node<T> n = (Node<T>) o;
                if (n.key == key) {
                    return n.chunk;
                }
                i = (i + 1) & mask;
            }
        }

        @SuppressWarnings("unchecked")
        synchronized ChunkCache2D<T> insert(long key) {
            int i = (int) (mix(key) >>> 5) & mask;
            while (slots[i] != null) {
                Node<T> n = (Node<T>) slots[i];
                if (n.key == key) {
                    return n.chunk;
                }
                i = (i + 1) & mask;
            }
            if (count >= limit) {
                java.util.Arrays.fill(slots, null);
                count = 0;
                i = (int) (mix(key) >>> 5) & mask;
            }
            Node<T> n = new Node<>(key);
            SLOTS.setRelease(slots, i, n);
            count++;
            return n.chunk;
        }
    }
}
