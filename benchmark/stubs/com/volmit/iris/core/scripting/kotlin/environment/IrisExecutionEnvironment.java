package com.volmit.iris.core.scripting.kotlin.environment;

import com.volmit.iris.core.loader.IrisRegistrant;
import com.volmit.iris.core.scripting.environment.EngineEnvironment;
import com.volmit.iris.core.scripting.func.UpdateExecutor;
import com.volmit.iris.engine.framework.Engine;
import com.volmit.iris.util.mantle.MantleChunk;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin class). Type shape only.
 */
public class IrisExecutionEnvironment extends IrisPackExecutionEnvironment implements EngineEnvironment {
    public IrisExecutionEnvironment(Engine engine) {
        super(engine.getData());
    }

    @Override
    public Engine getEngine() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public @Nullable Object spawnMob(String script, Location location) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void postSpawnMob(String script, Location location, Entity mob) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void preprocessObject(String script, IrisRegistrant object) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void updateChunk(String script, MantleChunk mantleChunk, Chunk chunk, UpdateExecutor executor) {
        throw new UnsupportedOperationException("stub");
    }
}
