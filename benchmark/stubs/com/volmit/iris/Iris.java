package com.volmit.iris;

import com.volmit.iris.core.loader.IrisData;
import com.volmit.iris.engine.object.IrisDimension;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.function.NastyRunnable;
import com.volmit.iris.util.misc.Bindings;
import com.volmit.iris.util.plugin.VolmitSender;
import org.bukkit.event.Event;

import java.io.File;

/**
 * BENCHMARK-ONLY STUB.
 * Shadows the real com.volmit.iris.Iris plugin class so the pure engine/math
 * classes can be compiled and measured without a Bukkit runtime.
 * Signatures mirror the real class; logging is a no-op, IO helpers throw.
 */
public class Iris extends com.volmit.iris.util.plugin.VolmitPlugin {
    public static Iris instance;
    public static IrisData data;
    public static Bindings.Adventure audiences;
    public static com.volmit.iris.util.plugin.chunk.ChunkTickets tickets;
    public static com.volmit.iris.core.link.MultiverseCoreLink linkMultiverseCore;
    public static com.volmit.iris.engine.object.IrisCompat compat;

    public void postShutdown(Runnable r) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    public int getIrisVersion() {
        return 0;
    }

    public int getMCVersion() {
        return 0;
    }

    public String getTag(String subTag) {
        return "[Iris/" + subTag + "]";
    }

    public void checkForBukkitWorlds(java.util.function.Predicate<String> filter) {
    }

    public static VolmitSender getSender() {
        throw new UnsupportedOperationException("stub");
    }

    public static <T> T service(Class<T> c) {
        return null;
    }

    public static void callEvent(Event e) {
    }

    public static KList<Object> initialize(String s, Class<? extends java.lang.annotation.Annotation> slicedClass) {
        throw new UnsupportedOperationException("stub");
    }

    public static KList<Class<?>> getClasses(String s, Class<? extends java.lang.annotation.Annotation> slicedClass) {
        throw new UnsupportedOperationException("stub");
    }

    public static KList<Object> initialize(String s) {
        throw new UnsupportedOperationException("stub");
    }

    public static File getTemp() {
        throw new UnsupportedOperationException("stub");
    }

    public static void msg(String string) {
    }

    public static File getCached(String name, String url) {
        throw new UnsupportedOperationException("stub");
    }

    public static String getNonCached(String name, String url) {
        throw new UnsupportedOperationException("stub");
    }

    public static File getNonCachedFile(String name, String url) {
        throw new UnsupportedOperationException("stub");
    }

    public static void warn(String format, Object... objs) {
    }

    public static void error(String format, Object... objs) {
    }

    public static void debug(String string) {
    }

    public static void debug(String category, int line, String string) {
    }

    public static void verbose(String string) {
    }

    public static void success(String string) {
    }

    public static void info(String format, Object... args) {
    }

    public static void later(NastyRunnable object) {
    }

    public static void reportErrorChunk(int x, int z, Throwable e, String extra) {
    }

    public static void reportError(Throwable e) {
    }

    public static void panic() {
    }

    public static void addPanic(String s, String v) {
    }

    public static IrisDimension loadDimension(String worldName, String id) {
        throw new UnsupportedOperationException("stub");
    }
}
