package me.bechberger.jfr;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

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
        boolean removeTypeInformationFromStackFrames,
        boolean removeUnnecessaryAddresses,
        boolean combineExceptionEvents,
        boolean combineG1HeapRegionTypeChangeEvents,
        boolean combineBlockingEvents,
        boolean dropGCWorkerThreadFromGCPhaseParallel,
        long cpuBucketSeconds)
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
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    10L);

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
                    .withCombinePLABPromotionEvents(true)
                    .withRemoveUnnecessaryAddresses(true)
                    .withMaxStackTraceDepth(32);

    public static final Configuration REDUCED_DEFAULT =
            REASONABLE_DEFAULT
                    .withName("reduced-default")
                    .withCombineObjectAllocationSampleEvents(true)
                    .withSumObjectSizes(true)
                    .withIgnoreUnnecessaryEvents(true)
                    .withRemoveTypeInformationFromStackFrames(true)
                    .withMaxStackTraceDepth(16)
                    .withCombineExceptionEvents(true)
                    .withCombineG1HeapRegionTypeChangeEvents(true)
                    .withCombineBlockingEvents(true)
                    .withDropGCWorkerThreadFromGCPhaseParallel(true);

    /**
     * Explicit alias for {@link #DEFAULT}: no data reduction at all. Handy as a clearly-named "keep
     * everything" preset that pairs with a stronger compression level.
     */
    public static final Configuration LOSSLESS = DEFAULT.withName("lossless");

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
                false,
                false,
                false,
                false,
                false,
                false,
                10L);
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
        // cpuBucketSeconds=0 means "not serialized in old format" — treat as default 10
        if (cpuBucketSeconds == 0) {
            cpuBucketSeconds = 10L;
        } else if (cpuBucketSeconds < 0) {
            throw new IllegalArgumentException("cpuBucketSeconds must be positive");
        }
    }

    public static final Map<String, Configuration> configurations =
            Map.of(
                    "default", DEFAULT,
                    "lossless", LOSSLESS,
                    "reasonable-default", REASONABLE_DEFAULT,
                    "reduced-default", REDUCED_DEFAULT);

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

    public Configuration withRemoveUnnecessaryAddresses(boolean removeUnnecessaryAddresses) {
        return withFieldValue("removeUnnecessaryAddresses", removeUnnecessaryAddresses);
    }

    public Configuration withCombineExceptionEvents(boolean combineExceptionEvents) {
        return withFieldValue("combineExceptionEvents", combineExceptionEvents);
    }

    public Configuration withCombineG1HeapRegionTypeChangeEvents(
            boolean combineG1HeapRegionTypeChangeEvents) {
        return withFieldValue(
                "combineG1HeapRegionTypeChangeEvents", combineG1HeapRegionTypeChangeEvents);
    }

    public Configuration withCombineBlockingEvents(boolean combineBlockingEvents) {
        return withFieldValue("combineBlockingEvents", combineBlockingEvents);
    }

    public Configuration withCpuBucketSeconds(long cpuBucketSeconds) {
        return withFieldValue("cpuBucketSeconds", cpuBucketSeconds);
    }

    public Configuration withDropGCWorkerThreadFromGCPhaseParallel(boolean drop) {
        return withFieldValue("dropGCWorkerThreadFromGCPhaseParallel", drop);
    }

    public Configuration withFieldValue(String fieldName, Object value) {
        // Record components retain their names even when MethodParameters is stripped
        // (e.g. by ProGuard with -g:none / parameters=false), so we resolve names via
        // getRecordComponents() rather than constructor.getParameters().
        try {
            var components = Configuration.class.getRecordComponents();
            var paramTypes = new Class<?>[components.length];
            var params = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var c = components[i];
                paramTypes[i] = c.getType();
                params[i] =
                        c.getName().equals(fieldName)
                                ? value
                                : Configuration.class.getDeclaredField(c.getName()).get(this);
            }
            var constructor = Configuration.class.getDeclaredConstructor(paramTypes);
            return constructor.newInstance(params);
        } catch (IllegalAccessException
                | InvocationTargetException
                | InstantiationException
                | NoSuchFieldException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean eventCombinersEnabled() {
        return combinePLABPromotionEvents
                || combineObjectAllocationSampleEvents
                || combineEventsWithoutDataLoss
                || combineExceptionEvents
                || combineG1HeapRegionTypeChangeEvents
                || combineBlockingEvents;
    }

    @Override
    public int compareTo(Configuration o) {
        return name.compareTo(o.name);
    }

    /**
     * Renders a Markdown table of the boolean data-reduction flags for the built-in presets (one
     * row per boolean record component, one column per preset). Used to keep {@code
     * docs/configurations.md} in sync with the code.
     */
    public static String toFlagTable() {
        var presets = List.of(DEFAULT, LOSSLESS, REASONABLE_DEFAULT, REDUCED_DEFAULT);
        var booleanComponents =
                java.util.Arrays.stream(Configuration.class.getRecordComponents())
                        .filter(c -> c.getType() == boolean.class)
                        .toList();
        var sb = new StringBuilder();
        // header
        sb.append("| flag |");
        for (var p : presets) {
            sb.append(' ').append(p.name()).append(" |");
        }
        sb.append('\n');
        // separator
        sb.append("| --- |");
        for (int i = 0; i < presets.size(); i++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        // rows
        for (var c : booleanComponents) {
            sb.append("| ").append(c.getName()).append(" |");
            for (var p : presets) {
                boolean value;
                try {
                    value = (boolean) c.getAccessor().invoke(p);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
                sb.append(' ').append(value ? "yes" : "no").append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
