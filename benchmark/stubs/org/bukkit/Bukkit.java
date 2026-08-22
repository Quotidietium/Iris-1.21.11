package org.bukkit;

import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * BENCHMARK-ONLY STUB.
 * Shadows the real org.bukkit.Bukkit server facade at compile time AND runtime
 * (benchmark/classes precedes the spigot-api jar on the run classpath). All
 * server-facing statics are inert; the one member that must actually WORK
 * offline is {@link #createBlockData}, which returns a deterministic JDK
 * dynamic proxy. That unlocks every "produces BlockData" engine path
 * (BlockMatter/IrisMatter slicers, B, decorators) for offline measurement.
 *
 * The proxy carries a normalized identity string (material key + bracket
 * states) so palette dedup (hashCode/equals) behaves like the server's
 * CraftBlockData: same state -> same palette entry.
 */
public final class Bukkit {
    private Bukkit() {
    }

    // ------------------------------------------------------------------
    // BlockData factory (the only functional members)
    // ------------------------------------------------------------------

    public static BlockData createBlockData(Material material) {
        return proxy(material, "");
    }

    public static BlockData createBlockData(Material material, Consumer<? super BlockData> consumer) {
        BlockData data = proxy(material, "");
        if (consumer != null) {
            consumer.accept(data);
        }
        return data;
    }

    public static BlockData createBlockData(String data) {
        String states = "";
        String base = data;
        int bracket = base.indexOf('[');
        if (bracket >= 0) {
            states = base.substring(bracket);
            base = base.substring(0, bracket);
        }
        int colon = base.indexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        if (base.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse '" + data + "'");
        }
        Material material = Material.matchMaterial(base);
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("Unknown block type ('" + data + "')");
        }
        return proxy(material, states.isEmpty() ? "" : canonicalStates(states));
    }

    /** Lower-cases, sorts and re-joins "[k=v,...]" state segments deterministically. */
    private static String canonicalStates(String states) {
        String inner = states.substring(states.indexOf('[') + 1, states.lastIndexOf(']'));
        String[] parts = inner.toLowerCase().split(",");
        java.util.Arrays.sort(parts);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i].trim());
        }
        return sb.append(']').toString();
    }

    private static BlockData proxy(Material material, String states) {
        String full = material.getKey().toString() + states;
        return (BlockData) Proxy.newProxyInstance(
                Bukkit.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                new BlockDataHandler(material, full, full.hashCode()));
    }

    private static final class BlockDataHandler implements InvocationHandler {
        private final Material material;
        private final String full;
        private final int hash;

        BlockDataHandler(Material material, String full, int hash) {
            this.material = material;
            this.full = full;
            this.hash = hash;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getMaterial":
                    return material;
                case "getAsString":
                    boolean withStates = args == null || args.length == 0 || (Boolean) args[0];
                    return withStates ? full : material.getKey().toString();
                case "hashCode":
                    return hash;
                case "equals":
                    return args[0] instanceof BlockData
                            && ((BlockData) args[0]).getAsString().equals(full);
                case "clone":
                    return Bukkit.proxy(material, full.substring(material.getKey().toString().length()));
                case "toString":
                    return "BenchBlockData{" + full + "}";
                case "matches":
                    return args[0] instanceof BlockData
                            && ((BlockData) args[0]).getAsString().equals(full);
                case "merge":
                    return proxy;
                case "isOccluding":
                    return material.isOccluding();
                case "getLightEmission":
                    return 0;
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0D;
            if (type == float.class) return 0F;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return (char) 0;
            if (type == void.class) return null;
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Inert statics: signatures referenced by core sources, server == absent
    // ------------------------------------------------------------------

    public static String getName() {
        return "BenchBukkit";
    }

    public static String getVersion() {
        return "BenchBukkit Version 1.20.1 (Offline)";
    }

    public static String getBukkitVersion() {
        return "1.20.1-R0.1-SNAPSHOT";
    }

    public static Logger getLogger() {
        return Logger.getLogger("BenchBukkit");
    }

    public static boolean isPrimaryThread() {
        return true;
    }

    public static Server getServer() {
        return null;
    }

    public static PluginManager getPluginManager() {
        return null;
    }

    public static BukkitScheduler getScheduler() {
        return null;
    }

    public static File getWorldContainer() {
        return new File("bench-world-container");
    }

    public static List<World> getWorlds() {
        return List.of();
    }

    public static World getWorld(String name) {
        return null;
    }

    public static World getWorld(UUID uid) {
        return null;
    }

    public static Player getPlayer(String name) {
        return null;
    }

    public static Player getPlayer(UUID uid) {
        return null;
    }

    public static org.bukkit.loot.LootTable getLootTable(NamespacedKey key) {
        return null;
    }

    /**
     * Always returns an inert proxy Registry. Registry's static initializer
     * requireNonNull's MusicInstrument/GameEvent lookups, and every field it
     * assigns from this factory must be non-null or Iris' reflective
     * DefaultRegistryLookup NPEs while folding fields into a map. Concrete
     * enum-backed lookups (Material, Biome, ...) still resolve offline through
     * Registry's own SimpleRegistry fields, which this proxy never shadows.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Keyed> Registry<T> getRegistry(Class<T> clazz) {
        return (Registry<T>) Proxy.newProxyInstance(
                Bukkit.class.getClassLoader(),
                new Class<?>[]{Registry.class},
                new EmptyRegistryHandler());
    }

    private static final class EmptyRegistryHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "BenchEmptyRegistry";
                case "stream" -> java.util.stream.Stream.empty();
                default -> null;
            };
        }
    }

    public static Collection<? extends Player> getOnlinePlayers() {
        return List.of();
    }

    public static ConsoleCommandSender getConsoleSender() {
        return null;
    }

    public static ScoreboardManager getScoreboardManager() {
        return null;
    }

    public static boolean unloadWorld(World world, boolean save) {
        return false;
    }

    public static boolean unloadWorld(String name, boolean save) {
        return false;
    }

    public static void shutdown() {
    }

    public static boolean dispatchCommand(CommandSender sender, String command) {
        return false;
    }

    public static Inventory createInventory(InventoryHolder owner, int size) {
        return null;
    }

    public static Inventory createInventory(InventoryHolder owner, int size, String title) {
        return null;
    }

    public static Inventory createInventory(InventoryHolder owner, InventoryType type) {
        return null;
    }

    public static Inventory createInventory(InventoryHolder owner, InventoryType type, String title) {
        return null;
    }

    public static ChunkGenerator.ChunkData createChunkData(World world) {
        return null;
    }

    public static <T extends Keyed> Tag<T> getTag(String registry, NamespacedKey key, Class<T> clazz) {
        return null;
    }

    public static <T extends Keyed> Iterable<Tag<T>> getTags(String registry, Class<T> clazz) {
        return List.of();
    }
}
