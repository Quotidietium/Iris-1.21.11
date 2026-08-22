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
        // IrisData's loader construction registers its KCache with the
        // preservation service; the real class is offline-safe (registerCache
        // only appends a WeakReference), so hand out a bare instance.
        if (c == com.volmit.iris.core.service.PreservationSVC.class) {
            return c.cast(new com.volmit.iris.core.service.PreservationSVC());
        }
        return null;
    }

    public static void callEvent(Event e) {
    }

    /**
     * Offline classpath scan: finds classes in package {@code s} (compilation
     * output directory) carrying the annotation, mirroring the real plugin's
     * package initializer. Used by IrisMatter.buildSlicers() to discover the
     * matter slices without a plugin runtime.
     */
    public static KList<Object> initialize(String s, Class<? extends java.lang.annotation.Annotation> slicedClass) {
        KList<Object> instances = new KList<>();
        for (Class<?> c : getClasses(s, slicedClass)) {
            try {
                instances.add(c.getDeclaredConstructor().newInstance());
            } catch (Throwable e) {
                throw new RuntimeException("Failed to initialize " + c, e);
            }
        }
        return instances;
    }

    public static KList<Class<?>> getClasses(String s, Class<? extends java.lang.annotation.Annotation> slicedClass) {
        KList<Class<?>> classes = new KList<>();
        try {
            String path = s.replace('.', '/');
            java.util.Enumeration<java.net.URL> resources =
                    Iris.class.getClassLoader().getResources(path);
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                if (!"file".equals(url.getProtocol())) {
                    continue;
                }
                File dir = new File(url.toURI());
                String[] files = dir.list((d, n) -> n.endsWith(".class") && !n.contains("$"));
                if (files == null) {
                    continue;
                }
                for (String f : files) {
                    String cn = s + "." + f.substring(0, f.length() - ".class".length());
                    Class<?> c = Class.forName(cn, false, Iris.class.getClassLoader());
                    if (c.isAnnotationPresent(slicedClass)
                            && !java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                        classes.add(c);
                    }
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Offline package scan failed for " + s, e);
        }
        return classes;
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
