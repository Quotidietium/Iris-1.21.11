package org.bukkit;

import org.bukkit.block.data.BlockData;

/**
 * BENCHMARK-ONLY STUB (R29).
 *
 * Production-shaped BlockData: a concrete class whose {@link #getMaterial()}
 * and {@link #getAsString()} are plain final-field reads, mirroring how the
 * server's CraftBlockData answers them. The pre-R29 stub returned a JDK
 * dynamic proxy for every createBlockData call, so each of the ~140 call-site
 * getMaterial() reads in measured paths paid a reflective Handler.invoke plus
 * a string switch — an artifact that R25's profile measured at ~40% of
 * object-place execution samples.
 *
 * Behavioral compatibility with the proxy stub is method-for-method exact
 * (identity string, hashCode, equals, matches, merge, clone independence,
 * defaults for the never-called members) so palette dedup and every golden
 * digest fold identical values; only the dispatch cost is realistic now.
 */
public final class BenchBlockData implements BlockData {
    private final Material material;
    private final String bare; // "minecraft:key"
    private final String full; // "minecraft:key[states]"
    private final int hash;

    BenchBlockData(Material material, String bare, String full, int hash) {
        this.material = material;
        this.bare = bare;
        this.full = full;
        this.hash = hash;
    }

    @Override
    public Material getMaterial() {
        return material;
    }

    @Override
    public String getAsString() {
        return full;
    }

    @Override
    public String getAsString(boolean withStates) {
        return withStates ? full : bare;
    }

    @Override
    public BlockData merge(BlockData data) {
        return this;
    }

    @Override
    public boolean matches(BlockData data) {
        return data != null && data.getAsString().equals(full);
    }

    @Override
    public BlockData clone() {
        // New instance, like CraftBlockData and like the proxy stub before it:
        // callers clone specifically because the target may be mutated.
        return new BenchBlockData(material, bare, full, hash);
    }

    @Override
    public SoundGroup getSoundGroup() {
        return null;
    }

    @Override
    public int getLightEmission() {
        return 0;
    }

    @Override
    public boolean isOccluding() {
        return material.isOccluding();
    }

    @Override
    public boolean requiresCorrectToolForDrops() {
        return false;
    }

    @Override
    public boolean isPreferredTool(org.bukkit.inventory.ItemStack item) {
        return false;
    }

    @Override
    public org.bukkit.block.PistonMoveReaction getPistonMoveReaction() {
        return null;
    }

    @Override
    public boolean isSupported(org.bukkit.block.Block block) {
        return false;
    }

    @Override
    public boolean isSupported(org.bukkit.Location location) {
        return false;
    }

    @Override
    public boolean isFaceSturdy(org.bukkit.block.BlockFace face, org.bukkit.block.BlockSupport support) {
        return false;
    }

    @Override
    public Material getPlacementMaterial() {
        return null;
    }

    @Override
    public void rotate(org.bukkit.block.structure.StructureRotation rotation) {
    }

    @Override
    public void mirror(org.bukkit.block.structure.Mirror mirror) {
    }

    @Override
    public org.bukkit.block.BlockState createBlockState() {
        return null;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockData && ((BlockData) o).getAsString().equals(full);
    }

    @Override
    public String toString() {
        return "BenchBlockData{" + full + "}";
    }
}
