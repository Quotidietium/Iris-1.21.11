package com.volmit.iris.util.context;

import org.bukkit.block.data.BlockData;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin ChunkContext).
 * Only the constructor signature and getter types are needed by the Java
 * stream closure; the pre-fill machinery is never exercised by the benchmark.
 * The complex parameter is typed Object so the stub does not drag in IrisComplex.
 */
public class ChunkContext {
    private final int x;
    private final int z;

    public ChunkContext(int x, int z, Object complex) {
        this(x, z, complex, true);
    }

    public ChunkContext(int x, int z, Object complex, boolean cache) {
        this.x = x;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public ChunkedDataCache<Double> getHeight() {
        throw new UnsupportedOperationException("stub");
    }

    public ChunkedDataCache<com.volmit.iris.engine.object.IrisBiome> getBiome() {
        throw new UnsupportedOperationException("stub");
    }

    public ChunkedDataCache<com.volmit.iris.engine.object.IrisBiome> getCave() {
        throw new UnsupportedOperationException("stub");
    }

    public ChunkedDataCache<BlockData> getRock() {
        throw new UnsupportedOperationException("stub");
    }

    public ChunkedDataCache<BlockData> getFluid() {
        throw new UnsupportedOperationException("stub");
    }

    public ChunkedDataCache<com.volmit.iris.engine.object.IrisRegion> getRegion() {
        throw new UnsupportedOperationException("stub");
    }
}
