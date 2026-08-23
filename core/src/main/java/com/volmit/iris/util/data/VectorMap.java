package com.volmit.iris.util.data;

import com.volmit.iris.util.collection.KMap;
import lombok.NonNull;
import org.bukkit.util.BlockVector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class VectorMap<T> implements Iterable<Map.Entry<BlockVector, T>> {
    private final Map<Key, Map<Key, T>> map = new KMap<>();
    private int count;

    public int size() {
        return count;
    }

    /**
     * O(1) emptiness probe (the old implementation streamed every chunk map).
     * Hot callers use it to skip per-block tile-state lookups entirely for
     * stateless objects, which is the common case.
     */
    public boolean isEmpty() {
        return count == 0;
    }

    public boolean containsKey(@NonNull BlockVector vector) {
        var chunk = map.get(chunk(vector));
        return chunk != null && chunk.containsKey(relative(vector));
    }

    public boolean containsValue(@NonNull T value) {
        return map.values().stream().anyMatch(m -> m.containsValue(value));
    }

    public @Nullable T get(@NonNull BlockVector vector) {
        var chunk = map.get(chunk(vector));
        return chunk == null ? null : chunk.get(relative(vector));
    }

    public @Nullable T get(int x, int y, int z) {
        var chunk = map.get(new Key(x >> 10, y >> 10, z >> 10));
        return chunk == null ? null : chunk.get(new Key(x & 0x3FF, y & 0x3FF, z & 0x3FF));
    }

    public @Nullable T put(@NonNull BlockVector vector, @NonNull T value) {
        T old = map.computeIfAbsent(chunk(vector), k -> new KMap<>())
                .put(relative(vector), value);
        if (old == null) {
            count++;
        }
        return old;
    }

    public @Nullable T computeIfAbsent(@NonNull BlockVector vector, @NonNull Function<@NonNull BlockVector, @NonNull T> mappingFunction) {
        Map<Key, T> chunkMap = map.computeIfAbsent(chunk(vector), k -> new KMap<>());
        Key rel = relative(vector);
        T v = chunkMap.get(rel);
        if (v == null) {
            v = chunkMap.computeIfAbsent(rel, $ -> mappingFunction.apply(vector));
            if (v != null) {
                count++;
            }
        }
        return v;
    }

    public @Nullable T remove(@NonNull BlockVector vector) {
        var chunk = map.get(chunk(vector));
        if (chunk == null) {
            return null;
        }
        T old = chunk.remove(relative(vector));
        if (old != null) {
            count--;
        }
        return old;
    }

    public void putAll(@NonNull VectorMap<T> map) {
        map.forEach(this::put);
    }

    public void clear() {
        map.clear();
        count = 0;
    }

    public void forEach(@NonNull BiConsumer<@NonNull BlockVector, @NonNull T> consumer) {
        map.forEach((chunk, values) -> {
            int rX = chunk.x << 10;
            int rY = chunk.y << 10;
            int rZ = chunk.z << 10;

            values.forEach((relative, value) -> consumer.accept(
                    relative.resolve(rX, rY, rZ),
                    value
            ));
        });
    }

    /**
     * Allocation-free entry iteration: delivers the resolved coordinates
     * directly instead of a fresh BlockVector per entry (plus, unlike the
     * EntryIterator, no SimpleEntry wrapper). Same chunk-then-relative
     * traversal order as {@link #forEach(BiConsumer)}.
     */
    public void forEachCoords(@NonNull CoordinateConsumer<@NonNull T> consumer) {
        map.forEach((chunk, values) -> {
            int rX = chunk.x << 10;
            int rY = chunk.y << 10;
            int rZ = chunk.z << 10;

            values.forEach((relative, value) ->
                    consumer.accept(rX + relative.x, rY + relative.y, rZ + relative.z, value));
        });
    }

    @FunctionalInterface
    public interface CoordinateConsumer<T> {
        void accept(int x, int y, int z, T value);
    }

    private static Key chunk(BlockVector vector) {
        return new Key(vector.getBlockX() >> 10, vector.getBlockY() >> 10, vector.getBlockZ() >> 10);
    }

    private static Key relative(BlockVector vector) {
        return new Key(vector.getBlockX() & 0x3FF, vector.getBlockY() & 0x3FF, vector.getBlockZ() & 0x3FF);
    }

    @Override
    public @NotNull EntryIterator iterator() {
        return new EntryIterator();
    }

    public @NotNull KeyIterator keys() {
        return new KeyIterator();
    }

    public @NotNull ValueIterator values() {
        return new ValueIterator();
    }

    /**
     * Zero-allocation-per-entry iteration: next() always returns the same
     * mutable cursor (coordinates + value), so placement loops that only read
     * each block pay no BlockVector / SimpleEntry allocation. Traversal order
     * is identical to {@link #iterator()}. The cursor must not be retained
     * across iterations.
     */
    public @NotNull CursorIterator cursorIterator() {
        return new CursorIterator();
    }

    public static final class Cursor<T> {
        public int x, y, z;
        public T value;
    }

    public class CursorIterator implements Iterator<Cursor<T>> {
        private final Iterator<Map.Entry<Key, Map<Key, T>>> chunkIterator = map.entrySet().iterator();
        private Iterator<Map.Entry<Key, T>> relativeIterator;
        private final Cursor<T> cursor = new Cursor<>();
        private int rX, rY, rZ;

        @Override
        public boolean hasNext() {
            return relativeIterator != null && relativeIterator.hasNext() || chunkIterator.hasNext();
        }

        @Override
        public Cursor<T> next() {
            if (relativeIterator == null || !relativeIterator.hasNext()) {
                if (!chunkIterator.hasNext()) throw new IllegalStateException("No more elements");
                var chunk = chunkIterator.next();
                rX = chunk.getKey().x << 10;
                rY = chunk.getKey().y << 10;
                rZ = chunk.getKey().z << 10;
                relativeIterator = chunk.getValue().entrySet().iterator();
            }

            var entry = relativeIterator.next();
            Key k = entry.getKey();
            cursor.x = rX + k.x;
            cursor.y = rY + k.y;
            cursor.z = rZ + k.z;
            cursor.value = entry.getValue();
            return cursor;
        }
    }

    public class EntryIterator implements Iterator<Map.Entry<BlockVector, T>> {
        private final Iterator<Map.Entry<Key, Map<Key, T>>> chunkIterator = map.entrySet().iterator();
        private Iterator<Map.Entry<Key, T>> relativeIterator;
        private int rX, rY, rZ;

        @Override
        public boolean hasNext() {
            return relativeIterator != null && relativeIterator.hasNext() || chunkIterator.hasNext();
        }

        @Override
        public Map.Entry<BlockVector, T> next() {
            if (relativeIterator == null || !relativeIterator.hasNext()) {
                if (!chunkIterator.hasNext()) throw new IllegalStateException("No more elements");
                var chunk = chunkIterator.next();
                rX = chunk.getKey().x << 10;
                rY = chunk.getKey().y << 10;
                rZ = chunk.getKey().z << 10;
                relativeIterator = chunk.getValue().entrySet().iterator();
            }

            var entry = relativeIterator.next();
            return Map.entry(entry.getKey().resolve(rX, rY, rZ), entry.getValue());
        }

        @Override
        public void remove() {
            if (relativeIterator == null) throw new IllegalStateException("No element to remove");
            relativeIterator.remove();
        }
    }

    public class KeyIterator implements Iterator<BlockVector>, Iterable<BlockVector> {
        private final Iterator<Map.Entry<Key, Map<Key, T>>> chunkIterator = map.entrySet().iterator();
        private Iterator<Key> relativeIterator;
        private int rX, rY, rZ;

        @Override
        public boolean hasNext() {
            return relativeIterator != null && relativeIterator.hasNext() || chunkIterator.hasNext();
        }

        @Override
        public BlockVector next() {
            if (relativeIterator == null || !relativeIterator.hasNext()) {
                var chunk = chunkIterator.next();
                rX = chunk.getKey().x << 10;
                rY = chunk.getKey().y << 10;
                rZ = chunk.getKey().z << 10;
                relativeIterator = chunk.getValue().keySet().iterator();
            }

            return relativeIterator.next().resolve(rX, rY, rZ);
        }

        @Override
        public void remove() {
            if (relativeIterator == null) throw new IllegalStateException("No element to remove");
            relativeIterator.remove();
        }

        @Override
        public @NotNull Iterator<BlockVector> iterator() {
            return this;
        }
    }

    public class ValueIterator implements Iterator<T>, Iterable<T> {
        private final Iterator<Map<Key, T>> chunkIterator = map.values().iterator();
        private Iterator<T> relativeIterator;

        @Override
        public boolean hasNext() {
            return relativeIterator != null && relativeIterator.hasNext() || chunkIterator.hasNext();
        }

        @Override
        public T next() {
            if (relativeIterator == null || !relativeIterator.hasNext()) {
                relativeIterator = chunkIterator.next().values().iterator();
            }
            return relativeIterator.next();
        }

        @Override
        public void remove() {
            if (relativeIterator == null) throw new IllegalStateException("No element to remove");
            relativeIterator.remove();
        }

        @Override
        public @NotNull Iterator<T> iterator() {
            return this;
        }
    }

    private static final class Key {
        private final int x;
        private final int y;
        private final int z;
        private final int hashCode;

        private Key(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.hashCode = (x << 20) | (y << 10) | z;
        }

        private BlockVector resolve(int rX, int rY, int rZ) {
            return new BlockVector(rX + x, rY + y, rZ + z);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key key)) return false;
            return x == key.x && y == key.y && z == key.z;
        }
    }
}
