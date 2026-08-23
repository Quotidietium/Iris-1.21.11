/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.volmit.iris.util.context;

import com.volmit.iris.util.documentation.BlockCoordinates;
import com.volmit.iris.util.stream.ProceduralStream;

/**
 * A 16x16 prefilled snapshot of one 2D procedural stream, filled once per
 * chunk context. Java port of the Kotlin original: the fill used to launch one
 * coroutine per cell (256 dispatched coroutines per stream); rows are now
 * plain loops driven by ChunkContext's burst tasks. Cells are pure functions
 * of the stream and coordinates, so any fill order or parallelism produces
 * the identical grid.
 */
public class ChunkedDataCache<T> {
    private final int x;
    private final int z;
    private final ProceduralStream<T> stream;
    private final boolean cache;
    private final Object[] data;

    @BlockCoordinates
    public ChunkedDataCache(ProceduralStream<T> stream, int x, int z, boolean cache) {
        this.stream = stream;
        this.x = x;
        this.z = z;
        this.cache = stream != null && cache;
        this.data = this.cache ? new Object[256] : null;
    }

    public ChunkedDataCache(ProceduralStream<T> stream, int x, int z) {
        this(stream, x, z, true);
    }

    boolean isCache() {
        return cache;
    }

    /**
     * Sample one grid row (z-relative {@code j}) into the backing array.
     * Rows are disjoint, so this is safe to call from any thread.
     */
    void fillRow(int j) {
        if (!cache) return;
        for (int i = 0; i < 16; i++) {
            data[(j << 4) + i] = stream.get((double) (x + i), (double) (z + j));
        }
    }

    @BlockCoordinates
    @SuppressWarnings("unchecked")
    public T get(int x, int z) {
        if (!cache) {
            return stream.get((double) (this.x + x), (double) (this.z + z));
        }

        T t = (T) data[(z << 4) + x];
        return t != null ? t : stream.get((double) (this.x + x), (double) (this.z + z));
    }
}
