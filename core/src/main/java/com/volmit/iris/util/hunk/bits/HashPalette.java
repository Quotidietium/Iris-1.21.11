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

package com.volmit.iris.util.hunk.bits;

import com.volmit.iris.util.collection.KMap;
import com.volmit.iris.util.function.Consumer2;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class HashPalette<T> implements Palette<T> {
    private final Object lock = new Object();
    private final KMap<T, Integer> palette;
    private volatile AtomicReferenceArray<T> byId;
    private final AtomicInteger size;

    public HashPalette() {
        this.size = new AtomicInteger(1);
        this.palette = new KMap<>();
        this.byId = new AtomicReferenceArray<>(16);
        byId.set(0, null);
    }

    @Override
    public T get(int id) {
        // No size.get() probe: unwritten AtomicReferenceArray slots read as
        // null, which is exactly what the old size-based early return
        // produced. size is monotonic (ids are append-only, never reused),
        // so the set of ids returning non-null is identical, and the
        // id's entry is published before the cell write that references it.
        if (id <= 0) {
            return null;
        }

        AtomicReferenceArray<T> a = byId;
        return id < a.length() ? a.get(id) : null;
    }

    @Override
    public int add(T t) {
        if (t == null) {
            return 0;
        }

        return palette.computeIfAbsent(t, $ -> {
            synchronized (lock) {
                int index = size.getAndIncrement();
                ensureCapacity(index);
                byId.set(index, t);
                return index;
            }
        });
    }

    @Override
    public int id(T t) {
        if (t == null) {
            return 0;
        }

        Integer v = palette.get(t);
        return v != null ? v : -1;
    }

    @Override
    public int size() {
        return size.get() - 1;
    }

    @Override
    public void iterate(Consumer2<T, Integer> c) {
        synchronized (lock) {
            AtomicReferenceArray<T> a = byId;
            int n = Math.min(size.get(), a.length());
            for (int i = 1; i < n; i++) {
                c.accept(a.get(i), i);
            }
        }
    }

    @Override
    public Palette<T> from(Palette<T> oldPalette) {
        oldPalette.iterate((t, i) -> {
            if (t == null) throw new NullPointerException("Null palette entries are not allowed!");
            palette.put(t, i);
            ensureCapacity(i);
            byId.set(i, t);
        });
        size.set(oldPalette.size() + 1);
        return this;
    }

    @Override
    public Palette<T> from(int size, Writable<T> writable, DataInputStream in) throws IOException {
        for (int i = 1; i <= size; i++) {
            T t = writable.readNodeData(in);
            if (t == null) throw new NullPointerException("Null palette entries are not allowed!");
            palette.put(t, i);
            ensureCapacity(i);
            byId.set(i, t);
        }
        this.size.set(size + 1);
        return this;
    }

    private void ensureCapacity(int index) {
        if (index >= byId.length()) {
            grow(index);
        }
    }

    private synchronized void grow(int lastIndex) {
        if (lastIndex < byId.length()) {
            return;
        }

        AtomicReferenceArray<T> a = new AtomicReferenceArray<>(Math.max(lastIndex + 1, byId.length() * 2));
        for (int i = 0; i < byId.length(); i++) {
            a.set(i, byId.get(i));
        }

        byId = a;
    }
}
