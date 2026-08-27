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

import com.volmit.iris.util.data.Varint;
import it.unimi.dsi.fastutil.ints.*;

import java.io.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
public class DataContainer<T> {
    private static final boolean TRIM = Boolean.getBoolean("iris.trim-palette");
    protected static final int INITIAL_BITS = 3;
    /**
     * Palette crossover: at most 2^2 = 4 ids live in the scan-backed
     * LinearPalette; anything larger uses the hash-backed palette. Both
     * implementations assign sequential insertion-order ids and derive bit
     * width from size, so the serialized form is identical either way.
     */
    protected static final int LINEAR_BITS_LIMIT = 2;
    protected static final int LINEAR_INITIAL_LENGTH = (int) Math.pow(2, LINEAR_BITS_LIMIT) + 2;
    protected static final int[] BIT = computeBitLimits();
    private final Lock write;

    /**
     * Structural sequence number for lock-free {@link #get(int)}. Odd means a
     * (data, palette) swap is in progress. Writers bump it around the only two
     * places that reassign both fields (both under the write lock, both always
     * installing fresh objects); readers snapshot the field pair between two
     * version reads and retry when a swap interleaved. Cell writes need no
     * fence: they are per-long volatile (AtomicLongArray) and palette ids are
     * append-only, with the id's palette entry published before the cell via
     * the writer's program order.
     */
    private volatile int structureVersion;

    private volatile Palette<T> palette;
    private volatile DataBits data;
    private final int length;
    private final Writable<T> writer;

    /**
     * Set on any mutation (set()), cleared by trim(). writeDos skips the
     * O(length) trim scan when clean: the used-id histogram can only change
     * through set(), so an unmodified container serializes its already-trimmed
     * representation byte-identically. Guarded by the write lock.
     */
    private boolean dirty;

    public DataContainer(Writable<T> writer, int length) {
        this.write = new ReentrantReadWriteLock().writeLock();

        this.writer = writer;
        this.length = length;
        this.data = new DataBits(INITIAL_BITS, length);
        this.palette = newPalette(INITIAL_BITS);
    }

    public DataContainer(DataInputStream din, Writable<T> writer) throws IOException {
        this.write = new ReentrantReadWriteLock().writeLock();

        this.writer = writer;
        this.length = Varint.readUnsignedVarInt(din);
        this.palette = newPalette(din);
        this.data = new DataBits(palette.bits(), length, din);
        trim();
    }

    private static int[] computeBitLimits() {
        int[] m = new int[16];

        for (int i = 0; i < m.length; i++) {
            m[i] = (int) Math.pow(2, i);
        }

        return m;
    }

    protected static int bits(int size) {
        if (DataContainer.BIT[INITIAL_BITS] >= size) {
            return INITIAL_BITS;
        }

        for (int i = 0; i < DataContainer.BIT.length; i++) {
            if (DataContainer.BIT[i] >= size) {
                return i;
            }
        }

        return DataContainer.BIT.length - 1;
    }

    public String toString() {
        return "DataContainer <" + length + " x " + data.getBits() + " bits> -> Palette<" + palette.getClass().getSimpleName().replaceAll("\\QPalette\\E", "") + ">: " + palette.size() +
                " " + data.toString() + " PalBit: " + palette.bits();
    }

    public byte[] write() throws IOException {
        ByteArrayOutputStream boas = new ByteArrayOutputStream();
        write(boas);
        return boas.toByteArray();
    }

    public void write(OutputStream out) throws IOException {
        writeDos(new DataOutputStream(out));
    }

    public void writeDos(DataOutputStream dos) throws IOException {
        write.lock();
        try {
            trim();
            Varint.writeUnsignedVarInt(length, dos);
            Varint.writeUnsignedVarInt(palette.size(), dos);
            palette.iterateIO((data, __) -> writer.writeNodeData(dos, data));
            data.write(dos);
            dos.flush();
        } finally {
            write.unlock();
        }
    }

    private Palette<T> newPalette(DataInputStream din) throws IOException {
        int paletteSize = Varint.readUnsignedVarInt(din);
        Palette<T> d = newPalette(bits(paletteSize + 1));
        d.from(paletteSize, writer, din);
        return d;
    }

    private Palette<T> newPalette(int bits) {
        if (bits <= LINEAR_BITS_LIMIT) {
            return new LinearPalette<>(LINEAR_INITIAL_LENGTH);
        }

        return new HashPalette<>();
    }

    public void set(int position, T t) {
        int id;

        write.lock();
        try {
            dirty = true;
            id = palette.id(t);
            if (id == -1) {
                id = palette.add(t);
                updateBits();
            }
            data.set(position, id);
        } finally {
            write.unlock();
        }
    }

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    private void updateBits() {
        int bits = palette.bits();
        if (bits == data.getBits())
            return;

        structureVersion++;
        try {
            if (data.getBits() <= LINEAR_BITS_LIMIT != bits <= LINEAR_BITS_LIMIT) {
                palette = newPalette(bits).from(palette);
            }

            data = data.setBits(bits);
        } finally {
            structureVersion++;
        }
    }

    public T get(int position) {
        // Lock-free seqlock read: see structureVersion javadoc. The old
        // read-lock/unlock pair cost more than the lookup itself (AQLS
        // signalNext alone showed up to ~30% of carve-modify samples).
        for (; ; ) {
            int v = structureVersion;
            if ((v & 1) != 0) {
                continue;
            }

            DataBits d = data;
            Palette<T> p = palette;
            T value = p.get(d.get(position));

            if (structureVersion == v) {
                return value;
            }
        }
    }

    public int size() {
        return data.getSize();
    }

    private void trim() {
        int paletteSize = palette.size();
        // Histogram of used palette ids; ids are <= palette.size(). Runs under
        // the write lock (writeDos) or before publication (read constructor):
        // plain-coherent element access suffices, and the repacked array is
        // published through the structureVersion release below.
        int[] used = new int[paletteSize + 2];
        int distinct = 0;
        for (int i = 0; i < length; i++) {
            int x = data.getOpaque(i);
            if (x <= 0 || x > paletteSize) continue;
            if (used[x]++ == 0) distinct++;
        }
        if (distinct == paletteSize) {
            dirty = false;
            return;
        }

        // Re-add survivors in ascending old-id order (same mapping order the
        // previous tree-map implementation produced). The palette may hold
        // DUPLICATE values under different ids (a palette key mutated in
        // place rehashes away from its CHM bucket, so a later add of an equal
        // value misses and appends a second id). trimmed.add dedups those,
        // making trimmed.size() potentially SMALLER than distinct — so the
        // repacked DataBits MUST be sized from the post-dedup palette, not
        // from distinct. Sizing from distinct produced a (palette.size(),
        // data.getBits()) mismatch, and since the reader derives its varlong
        // count from bits(paletteSize+1), every such slice wrote more varlongs
        // than any reader would consume — the round20/21 "Matter slice read
        // size mismatch" plate corruption.
        int[] remap = new int[paletteSize + 2];
        var trimmed = newPalette(Math.max(3, bits(distinct + 1)));
        for (int id = 1; id <= paletteSize; id++) {
            if (used[id] > 0) {
                remap[id] = trimmed.add(palette.get(id));
            }
        }
        int bits = Math.max(3, bits(trimmed.size() + 1));
        var tBits = new DataBits(bits, length);
        for (int i = 0; i < length; i++) {
            int x = data.getOpaque(i);
            tBits.setOpaque(i, x <= 0 || x > paletteSize ? 0 : remap[x]);
        }

        structureVersion++;
        try {
            data = tBits;
            palette = trimmed;
        } finally {
            structureVersion++;
            dirty = false;
        }
    }
}
