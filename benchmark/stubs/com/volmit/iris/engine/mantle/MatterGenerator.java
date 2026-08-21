package com.volmit.iris.engine.mantle;

import com.volmit.iris.core.nms.container.Pair;
import com.volmit.iris.engine.framework.Engine;
import com.volmit.iris.util.context.ChunkContext;
import com.volmit.iris.util.mantle.Mantle;

import java.util.List;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin MatterGenerator interface).
 * Type shape only; generateMatter is a no-op (never executed by the benchmark).
 */
public interface MatterGenerator {
    Engine getEngine();

    Mantle getMantle();

    int getRadius();

    int getRealRadius();

    List<Pair<List<MantleComponent>, Integer>> getComponents();

    default void generateMatter(int x, int z, boolean multicore, ChunkContext context) {
    }
}
