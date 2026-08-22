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

package com.volmit.iris.util.parallel;

import com.volmit.iris.Iris;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.function.NastyRunnable;
import com.volmit.iris.util.function.NastySupplier;
import com.volmit.iris.util.io.IORunnable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Key-space striped lock manager. Every (x, z) key maps to one of a fixed,
 * power-of-two pool of {@link ReentrantLock}s (fibonacci-hashed so clustered
 * coordinates spread evenly).
 *
 * <p>Compared to the previous bounded LRU lock cache, mutual exclusion per key
 * is preserved (colliding keys additionally exclude each other, which only
 * over-serializes), while lock acquisition no longer allocates a boxed key or
 * maintains LRU recency on every hit. A fixed pool also cannot evict a lock
 * that is currently held.</p>
 */
public class HyperLock {
    private static final int MIN_STRIPES = 64;
    private final ReentrantLock[] locks;
    private final int indexShift;
    private volatile boolean enabled = true;

    public HyperLock() {
        this(1024, false);
    }

    public HyperLock(int capacity) {
        this(capacity, false);
    }

    public HyperLock(int capacity, boolean fair) {
        int stripes = Math.max(MIN_STRIPES, Integer.highestOneBit(Math.max(capacity - 1, 1)) << 1);
        locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            locks[i] = new ReentrantLock(fair);
        }
        indexShift = 64 - Integer.numberOfTrailingZeros(stripes);
    }

    private ReentrantLock getLock(int x, int z) {
        long key = Cache.key(x, z) * 0x9E3779B97F4A7C15L;
        return locks[(int) (key >>> indexShift)];
    }

    public void with(int x, int z, Runnable r) {
        lock(x, z);
        try {
            r.run();
        } finally {
            unlock(x, z);
        }
    }

    public void withLong(long k, Runnable r) {
        int x = Cache.keyX(k), z = Cache.keyZ(k);
        lock(x, z);
        try {
            r.run();
        } finally {
            unlock(x, z);
        }
    }

    public void withNasty(int x, int z, NastyRunnable r) throws Throwable {
        lock(x, z);
        Throwable ee = null;
        try {
            r.run();
        } catch (Throwable e) {
            ee = e;
        } finally {
            unlock(x, z);

            if (ee != null) {
                throw ee;
            }
        }
    }

    public void withIO(int x, int z, IORunnable r) throws IOException {
        lock(x, z);
        IOException ee = null;
        try {
            r.run();
        } catch (IOException e) {
            ee = e;
        } finally {
            unlock(x, z);

            if (ee != null) {
                throw ee;
            }
        }
    }

    public <T> T withResult(int x, int z, Supplier<T> r) {
        lock(x, z);
        try {
            return r.get();
        } finally {
            unlock(x, z);
        }
    }

    public <T> T withNastyResult(int x, int z, NastySupplier<T> r) throws Throwable {
        lock(x, z);
        try {
            return r.get();
        } finally {
            unlock(x, z);
        }
    }

    public boolean tryLock(int x, int z) {
        return getLock(x, z).tryLock();
    }

    public boolean tryLock(int x, int z, long timeout) {
        try {
            return getLock(x, z).tryLock(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Iris.reportError(e);
        }

        return false;
    }

    public void lock(int x, int z) {
        if (!enabled) {
            return;
        }

        getLock(x, z).lock();
    }

    public void unlock(int x, int z) {
        if (!enabled) {
            return;
        }

        getLock(x, z).unlock();
    }

    public void disable() {
        enabled = false;
        for (ReentrantLock lock : locks) {
            lock.lock();
        }
    }
}
