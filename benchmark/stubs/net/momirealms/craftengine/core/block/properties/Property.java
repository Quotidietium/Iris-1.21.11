package net.momirealms.craftengine.core.block.properties;

import java.util.Set;

public interface Property<T extends Comparable<T>> {
    String name();
    T defaultValue();
    Class<T> valueClass();
    Set<T> possibleValues();
    String valueName(T value);
    java.util.Optional<T> optional(String value);
}
