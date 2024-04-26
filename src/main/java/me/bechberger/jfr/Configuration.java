package me.bechberger.jfr;

import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.TypeCollection;

public record Configuration(
        long timeStampTicksPerSecond, long durationTicksPerSecond, boolean memoryAsBFloat16) {
    public static final Configuration DEFAULT =
            new Configuration(
                    /* nano seconds */ 1_000_000_000, /* nano seconds
                                                       */
                    1_000_000_000,
                    false);

    /** with conservative lossy compression */
    public static final Configuration REASONABLE_DEFAULT =
            new Configuration(/* milli seconds */ 1_000, /* 10 us
    granularity */ 100_000, true);

    static StructType<Configuration, Configuration> createType(TypeCollection collection) {
        return TypeUtil.createStructWithPrimitiveFields(collection, Configuration.class);
    }

    public Configuration withTimeStampTicksPerSecond(long ttps) {
        return new Configuration(ttps, durationTicksPerSecond, memoryAsBFloat16);
    }

    public Configuration withDurationTicksPerSecond(long dtps) {
        return new Configuration(timeStampTicksPerSecond, dtps, memoryAsBFloat16);
    }

    public Configuration withMemoryAsBFloat16(boolean asBFloat16) {
        return new Configuration(timeStampTicksPerSecond, durationTicksPerSecond, asBFloat16);
    }
}
