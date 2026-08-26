// BENCHMARK-ONLY STUB (R14): compile-shape only, never called at runtime
// (the real plugin is present on servers; the offline build just needs the
// symbols). Values are irrelevant.
package com.nexomc.nexo.api;

import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public final class NexoItems {
    public static boolean exists(String id) { return false; }
    public static ItemBuilder itemFromId(String id) { return null; }
    public static Set<String> itemNames() { return Set.of(); }
    public static ItemStack itemFromIdBuilt(String id) { return null; }
}
