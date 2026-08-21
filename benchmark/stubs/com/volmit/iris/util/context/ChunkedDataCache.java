package com.volmit.iris.util.context;

import com.volmit.iris.util.stream.ProceduralStream;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin ChunkedDataCache).
 * Type shape only; never executed by the benchmark.
 */
public class ChunkedDataCache<T> {
    public ChunkedDataCache(ProceduralStream<T> stream, int x, int z, boolean cache) {
    }

    public ChunkedDataCache(ProceduralStream<T> stream, int x, int z) {
        this(stream, x, z, true);
    }

    public T get(int x, int z) {
        throw new UnsupportedOperationException("stub");
    }
}
