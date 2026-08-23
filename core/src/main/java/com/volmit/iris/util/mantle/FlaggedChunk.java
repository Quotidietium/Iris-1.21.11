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

package com.volmit.iris.util.mantle;

import com.volmit.iris.util.data.Varint;
import com.volmit.iris.util.mantle.flag.MantleFlag;
import com.volmit.iris.util.parallel.AtomicBooleanArray;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Java port of the Kotlin original. A single reentrant lock per chunk now
 * guards the raise-flag critical sections instead of one kotlinx Mutex per
 * flag ordinal (256 mutexes allocated by every mantle chunk). The on-disk
 * flag byte format and the double-checked raise semantics are unchanged.
 */
public abstract class FlaggedChunk {
    private final AtomicBooleanArray flags = new AtomicBooleanArray(MantleFlag.MAX_ORDINAL + 1);
    private final ReentrantLock lock = new ReentrantLock();

    public abstract boolean isClosed();

    protected void copyFrom(FlaggedChunk other, Runnable action) {
        lock.lock();
        try {
            action.run();
            for (int i = 0; i < flags.length(); i++) {
                flags.set(i, other.flags.get(i));
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isFlagged(MantleFlag flag) {
        return flags.get(flag.ordinal());
    }

    public void flag(MantleFlag flag, boolean value) {
        if (isClosed()) throw new IllegalStateException("Chunk is closed!");
        flags.set(flag.ordinal(), value);
    }

    /**
     * Raise the flag if it is not already raised and run the task exactly once
     * under the chunk lock (double-checked, same contract as the old suspend
     * version). Replaces the Kotlin raiseFlagSuspend for plain executor tasks.
     */
    public void raiseFlag(MantleFlag flag, Runnable task) {
        if (isClosed()) throw new IllegalStateException("Chunk is closed!");
        if (isFlagged(flag)) return;

        lock.lock();
        try {
            if (isFlagged(flag)) return;
            task.run();
            if (flags.getAndSet(flag.ordinal(), true)) {
                throw new IllegalStateException("Flag " + flag.name() + " was already set after task ran!");
            }
        } finally {
            lock.unlock();
        }
    }

    public void raiseFlagUnchecked(MantleFlag flag, Runnable task) {
        if (isClosed()) throw new IllegalStateException("Chunk is closed!");
        if (flags.compareAndSet(flag.ordinal(), false, true)) {
            try {
                task.run();
            } catch (Throwable e) {
                flags.set(flag.ordinal(), false);
                throw e;
            }
        }
    }

    protected void readFlags(int version, DataInput din) throws IOException {
        int l = version < 0 ? 16 : Varint.readUnsignedVarInt(din);

        if (version >= 1) {
            int i = 0;
            while (i < l) {
                int f = din.readByte();
                int j = 0;
                while (j < Byte.SIZE && i < flags.length()) {
                    flags.set(i, (f & (1 << j)) != 0);
                    j++;
                    i++;
                }
            }
        } else {
            for (int i = 0; i < l; i++) {
                flags.set(i, din.readBoolean());
            }
        }
    }

    protected void writeFlags(DataOutput dos) throws IOException {
        Varint.writeUnsignedVarInt(flags.length(), dos);
        int count = flags.length();
        int i = 0;
        while (i < count) {
            int f = 0;
            for (int j = 0; j < Byte.SIZE; j++) {
                if (i >= count) break;
                f = f | (flags.get(i) ? 1 << j : 0);
                i++;
            }
            dos.write(f);
        }
    }
}
