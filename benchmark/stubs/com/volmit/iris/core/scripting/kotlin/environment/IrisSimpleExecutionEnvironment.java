package com.volmit.iris.core.scripting.kotlin.environment;

import com.volmit.iris.core.scripting.environment.SimpleEnvironment;

import java.io.File;
import java.util.Map;

/**
 * BENCHMARK-ONLY STUB (Java mirror of the Kotlin class). Type shape only.
 */
public class IrisSimpleExecutionEnvironment implements SimpleEnvironment {
    public IrisSimpleExecutionEnvironment() {
    }

    public IrisSimpleExecutionEnvironment(File projectDir) {
    }

    @Override
    public void configureProject() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void execute(String script) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void execute(String script, Class<?> type, Map<String, Object> vars) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object evaluate(String script) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Object evaluate(String script, Class<?> type, Map<String, Object> vars) {
        throw new UnsupportedOperationException("stub");
    }
}
