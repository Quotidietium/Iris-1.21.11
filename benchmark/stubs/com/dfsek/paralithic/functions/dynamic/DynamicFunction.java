package com.dfsek.paralithic.functions.dynamic;

/** BENCHMARK-ONLY STUB (mirrors the real paralithic DynamicFunction shape). */
public interface DynamicFunction {
    int getArgNumber();

    com.dfsek.paralithic.node.Statefulness statefulness();

    double eval(double... doubles);

    double eval(Context context, double... args);
}
