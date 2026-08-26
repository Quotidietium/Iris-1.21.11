package net.momirealms.craftengine.core.block;

import net.momirealms.craftengine.core.block.properties.Property;

public interface ImmutableBlockState {
    static ImmutableBlockState with(ImmutableBlockState state, Property<?> property, Comparable<?> tag) { return state; }
}
