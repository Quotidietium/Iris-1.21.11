package net.momirealms.craftengine.bukkit.api;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;

import java.util.Map;

public final class CraftEngineBlocks {
    public static Block byId(Key key) { return null; }
    public static Map<Key, Block> loadedBlocks() { return Map.of(); }
    public static void place(Location location, ImmutableBlockState state, boolean flag) {}
    public interface Block {
        java.util.List<Property<?>> properties();
        default ImmutableBlockState defaultState() { return null; }
        default Property<?> getProperty(String name) { return null; }
    }
}
