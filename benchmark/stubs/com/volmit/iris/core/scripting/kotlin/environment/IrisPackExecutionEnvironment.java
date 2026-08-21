package com.volmit.iris.core.scripting.kotlin.environment;

import com.volmit.iris.core.loader.IrisData;
import com.volmit.iris.core.scripting.environment.EngineEnvironment;
import com.volmit.iris.core.scripting.environment.PackEnvironment;
import com.volmit.iris.util.math.RNG;
import org.jetbrains.annotations.Nullable;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin class). Type shape only.
 */
public class IrisPackExecutionEnvironment extends IrisSimpleExecutionEnvironment implements PackEnvironment {
    public IrisPackExecutionEnvironment(IrisData data) {
    }

    @Override
    public IrisData getData() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public @Nullable Object createNoise(String script, RNG rng) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public EngineEnvironment with(com.volmit.iris.engine.framework.Engine engine) {
        throw new UnsupportedOperationException("stub");
    }
}
