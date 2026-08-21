package com.volmit.iris.util.mantle;

import com.volmit.iris.util.data.Varint;
import com.volmit.iris.util.mantle.flag.MantleFlag;
import com.volmit.iris.util.parallel.AtomicBooleanArray;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin FlaggedChunk).
 * Behavior-faithful for the members exercised from Java (flags storage,
 * raiseFlagUnchecked, copyFrom, read/writeFlags).
 */
public abstract class FlaggedChunk {
    private final AtomicBooleanArray flags = new AtomicBooleanArray(MantleFlag.MAX_ORDINAL + 1);

    public abstract boolean isClosed();

    protected void copyFrom(FlaggedChunk other, Runnable action) {
        action.run();
        for (int i = 0; i < flags.length(); i++) {
            flags.set(i, other.flags.get(i));
        }
    }

    public boolean isFlagged(MantleFlag flag) {
        return flags.get(flag.ordinal());
    }

    public void flag(MantleFlag flag, boolean value) {
        if (isClosed()) throw new IllegalStateException("Chunk is closed!");
        flags.set(flag.ordinal(), value);
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
                byte f = din.readByte();
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
