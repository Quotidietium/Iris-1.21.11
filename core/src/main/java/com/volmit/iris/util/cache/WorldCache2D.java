package com.volmit.iris.util.cache;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.function.Function2;

public class WorldCache2D<T> {
    private final ConcurrentLinkedHashMap<Long, ChunkCache2D<T>> chunks;
    private final Function2<Integer, Integer, T> resolver;

    // Single-slot memo for the boxed chunk key: generation rasters the same
    // chunk for hundreds of consecutive gets. One volatile reference keeps the
    // (value, box) pair consistently published; a miss just allocates a new box.
    private volatile Long lastKeyBoxed = null;

    public WorldCache2D(Function2<Integer, Integer, T> resolver, int size) {
        this.resolver = resolver;
        chunks = new ConcurrentLinkedHashMap.Builder<Long, ChunkCache2D<T>>()
                .initialCapacity(size)
                .maximumWeightedCapacity(size)
                .concurrencyLevel(Math.max(32, Runtime.getRuntime().availableProcessors() * 4))
                .build();
    }

    public T get(int x, int z) {
        long k = Cache.key(x >> 4, z >> 4);
        Long boxed = lastKeyBoxed;
        if (boxed == null || boxed != k) {
            boxed = k;
            lastKeyBoxed = boxed;
        }
        ChunkCache2D<T> chunk = chunks.computeIfAbsent(boxed, $ -> new ChunkCache2D<>());
        return chunk.get(x, z, resolver);
    }

    public long getSize() {
        return chunks.size() * 256L;
    }

    public long getMaxSize() {
        return chunks.capacity() * 256L;
    }
}
