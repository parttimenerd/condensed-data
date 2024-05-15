package me.bechberger.jfr;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.TypeCollection;

/**
 * Configuration for the JFR condenser
 *
 * @param memoryAsBFloat16 store memory as BFloat16, looses some precision
 * @param ignoreUnnecessaryEvents ignore events that don't add any information like
 *     jdk.G1HeapRegionTypeChange without a change
 * @param maxStackTraceDepth maximum stacktrace depth to store, -1 for unlimited
 */
public record Configuration(
        String name,
        long timeStampTicksPerSecond,
        long durationTicksPerSecond,
        boolean memoryAsBFloat16,
        boolean ignoreUnnecessaryEvents,
        long maxStackTraceDepth,
        boolean useSpecificHashesAndRefs)
        implements Cloneable, Comparable<Configuration> {

    public static final Configuration DEFAULT =
            new Configuration(
                    "default",
                    /* nano seconds */ 1_000_000_000, /* nano seconds
                                                       */
                    1_000_000_000,
                    false,
                    true,
                    -1,
                    false);

    public Configuration {
        if (timeStampTicksPerSecond <= 0) {
            throw new IllegalArgumentException("timeStampTicksPerSecond must be positive");
        }
        if (durationTicksPerSecond <= 0) {
            throw new IllegalArgumentException("durationTicksPerSecond must be positive");
        }
        if (maxStackTraceDepth != -1 && maxStackTraceDepth <= 0) {
            throw new IllegalArgumentException("maxStackTraceDepth must be -1 or positive");
        }
    }

    /** with conservative lossy compression */
    public static final Configuration REASONABLE_DEFAULT =
            new Configuration(
                    "reasonable default", /* milli seconds */
                    1_000, /* 10 us
                           granularity */
                    100_000,
                    true,
                    true,
                    -1,
                    true);

    public static final Map<String, Configuration> configurations =
            Map.of(
                    "default", DEFAULT,
                    "reasonable-default", REASONABLE_DEFAULT);

    static StructType<Configuration, Configuration> createType(TypeCollection collection) {
        return TypeUtil.createStructWithPrimitiveFields(collection, Configuration.class);
    }

    public Configuration withTimeStampTicksPerSecond(long ttps) {
        return withFieldValue("timeStampTicksPerSecond", ttps);
    }

    public Configuration withDurationTicksPerSecond(long dtps) {
        return withFieldValue("durationTicksPerSecond", dtps);
    }

    public Configuration withMemoryAsBFloat16(boolean asBFloat16) {
        return withFieldValue("memoryAsBFloat16", asBFloat16);
    }

    public Configuration withIgnoreUnnecessaryEvents(boolean ignore) {
        return withFieldValue("ignoreUnnecessaryEvents", ignore);
    }

    public Configuration withMaxStackTraceDepth(long maxStackTraceDepth) {
        return withFieldValue("maxStackTraceDepth", maxStackTraceDepth);
    }

    public Configuration withName(String name) {
        return withFieldValue("name", name);
    }

    public Configuration withUseSpecificHashesAndRefs(boolean useSpecificHashesAndRefs) {
        return withFieldValue("useSpecificHashesAndRefs", useSpecificHashesAndRefs);
    }

    public Configuration withFieldValue(String fieldName, Object value) {
        // use reflection to call the constructor
        try {
            var constructor = Configuration.class.getDeclaredConstructors()[0];
            var params =
                    Arrays.stream(constructor.getParameters())
                            .map(
                                    p -> {
                                        try {
                                            return p.getName().equals(fieldName)
                                                    ? value
                                                    : Configuration.class
                                                            .getDeclaredField(p.getName())
                                                            .get(this);
                                        } catch (IllegalAccessException | NoSuchFieldException e) {
                                            throw new RuntimeException(e);
                                        }
                                    })
                            .toArray();
            return (Configuration) constructor.newInstance(params);
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int compareTo(Configuration o) {
        return name.compareTo(o.name);
    }
}
