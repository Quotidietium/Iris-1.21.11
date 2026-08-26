package net.momirealms.craftengine.bukkit.api;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class CraftEngineItems {
    public static Item byId(Key key) { return null; }
    public static Map<Key, Item> loadedItems() { return Map.of(); }
    public static interface Item { ItemStack buildItemStack(); }
}
