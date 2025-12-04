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
 * @param sumObjectSizes sum object sizes in jdk.ObjectAllocationInNewTLAB,
 *     jdk.ObjectAllocationOutsideTLAB and ObjectAllocation events
 * @param combineEventsWithoutDataLoss combine events without data loss
 * @param combinePLABPromotionEvents combine and reduce jdk.PromoteObjectInNewPLAB and
 *     jdk.PromoteObjectOutsidePLAB events
 * @param combineObjectAllocationSampleEvents combine and reduce jdk.ObjectSample events
 */
public record Configuration(
        String name,
        long timeStampTicksPerSecond,
        long durationTicksPerSecond,
        boolean memoryAsBFloat16,
        boolean ignoreUnnecessaryEvents,
        long maxStackTraceDepth,
        boolean useSpecificHashesAndRefs,
        boolean combineEventsWithoutDataLoss,
        boolean combinePLABPromotionEvents,
        boolean combineObjectAllocationSampleEvents,
        boolean sumObjectSizes,
        boolean ignoreZeroSizedTenuredAges,
        boolean ignoreTooShortGCPauses,
        boolean removeBCIAndLineNumberFromStackFrames,
        boolean removeTypeInformationFromStackFrames)
        implements Comparable<Configuration> {

    public static final Configuration DEFAULT =
            new Configuration(
                    "default",
                    /* nano seconds */ 1_000_000_000, /* nano seconds
                                                       */
                    1_000_000_000,
                    false,
                    true,
                    -1,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false);

    /** with conservative lossy compression */
    public static final Configuration REASONABLE_DEFAULT =
            DEFAULT.withName("reasonable-default")
                    .withMemoryAsBFloat16(true)
                    .withTimeStampTicksPerSecond(1_000)
                    .withDurationTicksPerSecond(1_000_000)
                    .withUseSpecificHashesAndRefs(true)
                    .withIgnoreZeroSizedTenuredAges(true)
                    .withIgnoreTooShortGCPauses(true)
                    .withRemoveBCIAndLineNumberFromStackFrames(true)
                    .withMaxStackTraceDepth(32);

    public static final Configuration REDUCED_DEFAULT =
            REASONABLE_DEFAULT
                    .withName("reduced-default")
                    .withCombinePLABPromotionEvents(true)
                    .withCombineObjectAllocationSampleEvents(true)
                    .withSumObjectSizes(true)
                    .withIgnoreUnnecessaryEvents(true)
                    .withRemoveTypeInformationFromStackFrames(true)
                    .withMaxStackTraceDepth(16);

    public Configuration(
            String name,
            long timeStampTicksPerSecond,
            long durationTicksPerSecond,
            boolean memoryAsBFloat16,
            boolean ignoreUnnecessaryEvents,
            long maxStackTraceDepth,
            boolean useSpecificHashesAndRefs,
            boolean combineEventsWithoutDataLoss,
            boolean combinePLABPromotionEvents,
            boolean combineObjectAllocationSampleEvents,
            boolean sumObjectSizes,
            boolean ignoreZeroSizedTenuredAges,
            boolean ignoreTooShortGCPauses) {
        this(
                name,
                timeStampTicksPerSecond,
                durationTicksPerSecond,
                memoryAsBFloat16,
                ignoreUnnecessaryEvents,
                maxStackTraceDepth,
                useSpecificHashesAndRefs,
                combineEventsWithoutDataLoss,
                combinePLABPromotionEvents,
                combineObjectAllocationSampleEvents,
                sumObjectSizes,
                ignoreZeroSizedTenuredAges,
                ignoreTooShortGCPauses,
                false,
                false);
    }

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

    public static final Map<String, Configuration> configurations =
            Map.of(
                    "default", DEFAULT,
                    "reasonable-default", REASONABLE_DEFAULT,
                    "reduced-default", REDUCED_DEFAULT);

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

    public Configuration withCombineEventsWithoutDataLoss(boolean combineEventsWithoutDataLoss) {
        return withFieldValue("combineEventsWithoutDataLoss", combineEventsWithoutDataLoss);
    }

    public Configuration withCombinePLABPromotionEvents(boolean combinePLABPromotionEvents) {
        return withFieldValue("combinePLABPromotionEvents", combinePLABPromotionEvents);
    }

    public Configuration withCombineObjectAllocationSampleEvents(
            boolean combineObjectAllocationSampleEvents) {
        return withFieldValue(
                "combineObjectAllocationSampleEvents", combineObjectAllocationSampleEvents);
    }

    public Configuration withSumObjectSizes(boolean sumObjectSizes) {
        return withFieldValue("sumObjectSizes", sumObjectSizes);
    }

    public Configuration withIgnoreZeroSizedTenuredAges(boolean ignoreZeroSizedTenuredAges) {
        return withFieldValue("ignoreZeroSizedTenuredAges", ignoreZeroSizedTenuredAges);
    }

    public Configuration withIgnoreTooShortGCPauses(boolean ignoreTooShortGCPauses) {
        return withFieldValue("ignoreTooShortGCPauses", ignoreTooShortGCPauses);
    }

    public Configuration withRemoveBCIAndLineNumberFromStackFrames(
            boolean removeBCIAndLineNumberFromStackFrames) {
        return withFieldValue(
                "removeBCIAndLineNumberFromStackFrames", removeBCIAndLineNumberFromStackFrames);
    }

    public Configuration withRemoveTypeInformationFromStackFrames(
            boolean removeTypeInformationFromStackFrames) {
        return withFieldValue(
                "removeTypeInformationFromStackFrames", removeTypeInformationFromStackFrames);
    }

    public Configuration withFieldValue(String fieldName, Object value) {
        // use reflection to call the constructor
        try {
            var constructor =
                    Arrays.stream(Configuration.class.getDeclaredConstructors())
                            .min(
                                    (c1, c2) ->
                                            Integer.compare(
                                                    c2.getParameterCount(), c1.getParameterCount()))
                            .orElseThrow();
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

    public boolean eventCombinersEnabled() {
        return combinePLABPromotionEvents;
    }

    @Override
    public int compareTo(Configuration o) {
        return name.compareTo(o.name);
    }
}
