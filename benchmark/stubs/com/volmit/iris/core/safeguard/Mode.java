package com.volmit.iris.core.safeguard;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin Mode enum). Shape only.
 */
public enum Mode {
    STABLE, WARNING, UNSTABLE;

    public String getId() {
        return name().toLowerCase();
    }

    public String tag(String subTag) {
        return "[Iris]: ";
    }

    public void trySplash() {
    }
}
