package net.momirealms.craftengine.bukkit.api;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;

import java.util.Map;

public final class CraftEngineFurniture {
    public static Furniture byId(Key key) { return null; }
    public static Map<Key, Furniture> loadedFurniture() { return Map.of(); }
    public static void place(Location location, Furniture furniture, String variant, boolean flag) {}
    public interface Furniture {
        String anyVariantName();
        Map<String, ?> variants();
    }
}
