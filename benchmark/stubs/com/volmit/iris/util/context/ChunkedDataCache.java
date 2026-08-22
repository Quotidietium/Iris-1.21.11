package com.volmit.iris.util.context;

import com.volmit.iris.util.stream.ProceduralStream;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin ChunkedDataCache).
 * Type shape only; production paths are never exercised. Bench scenarios can
 * pre-fill a 16x16 grid so get(x, z) answers deterministically.
 */
public class ChunkedDataCache<T> {
    private T[] grid;

    public ChunkedDataCache(ProceduralStream<T> stream, int x, int z, boolean cache) {
    }

    public ChunkedDataCache(ProceduralStream<T> stream, int x, int z) {
        this(stream, x, z, true);
    }

    public ChunkedDataCache<T> prefill(T[] grid16x16) {
        this.grid = grid16x16;
        return this;
    }

    public T get(int x, int z) {
        if (grid != null) {
            return grid[(z & 15) * 16 + (x & 15)];
        }
        throw new UnsupportedOperationException("stub");
    }
}
