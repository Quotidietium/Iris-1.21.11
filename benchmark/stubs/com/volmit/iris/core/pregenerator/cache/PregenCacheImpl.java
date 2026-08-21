package com.volmit.iris.core.pregenerator.cache;

import java.io.File;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin PregenCacheImpl). Type shape only.
 */
public class PregenCacheImpl implements PregenCache {
    public PregenCacheImpl(File directory, int maxSize) {
    }

    @Override
    public boolean isChunkCached(int x, int z) {
        return false;
    }

    @Override
    public boolean isRegionCached(int x, int z) {
        return false;
    }

    @Override
    public void cacheChunk(int x, int z) {
    }

    @Override
    public void cacheRegion(int x, int z) {
    }

    @Override
    public void write() {
    }

    @Override
    public void trim(long unloadDuration) {
    }
}
