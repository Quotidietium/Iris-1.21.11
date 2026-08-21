package com.volmit.iris.util.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.function.Function2;

public class WorldCache2D<T> {
    private final com.github.benmanes.caffeine.cache.Cache<Long, ChunkCache2D<T>> chunks;
    private final Function2<Integer, Integer, T> resolver;
    private final int maxSize;

    // Single-slot memo for the boxed chunk key: generation rasters the same
    // chunk for hundreds of consecutive gets. One volatile reference keeps the
    // (value, box) pair consistently published; a miss just allocates a new box.
    private volatile Long lastKeyBoxed = null;

    public WorldCache2D(Function2<Integer, Integer, T> resolver, int size) {
        this.resolver = resolver;
        this.maxSize = size;
        // Caffeine replaces ConcurrentLinkedHashMap: the cache is a pure
        // memoization layer (any eviction policy yields identical values, only
        // recompute cost differs), and Caffeine's read path scales far better
        // under many generation threads.
        this.chunks = Caffeine.newBuilder()
                .initialCapacity(size)
                .maximumSize(size)
                .build();
    }

    public T get(int x, int z) {
        long k = Cache.key(x >> 4, z >> 4);
        Long boxed = lastKeyBoxed;
        if (boxed == null || boxed != k) {
            boxed = k;
            lastKeyBoxed = boxed;
        }
        ChunkCache2D<T> chunk = chunks.get(boxed, $ -> new ChunkCache2D<>());
        return chunk.get(x, z, resolver);
    }

    public long getSize() {
        return chunks.estimatedSize() * 256L;
    }

    public long getMaxSize() {
        return maxSize * 256L;
    }
}
