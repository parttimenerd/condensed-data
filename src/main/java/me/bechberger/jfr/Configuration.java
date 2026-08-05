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
 * @param combineObjectAllocationSampleLossless group jdk.ObjectAllocationSample by (objectClass,
 *     stackTrace), storing an array of weights per unique stack — lossless except per-event
 *     timestamps (enabled in default preset)
 * @param collapseInternalFramesPrefixes newline-delimited class-name prefixes to collapse in
 *     profiling stack traces (reduced only); empty = disabled
 * @param collapseAppFramesPrefixes newline-delimited class-name prefixes to force-keep even if they
 *     match collapseInternalFramesPrefixes; empty = none
 * @param aggregateGCPhaseParallelStats collapse jdk.GCPhaseParallel events per (gcId, phaseName)
 *     into a single stat entry {count, sumDuration, minDuration, maxDuration}; drops per-worker and
 *     per-region-scan granularity; reduces GCPhaseParallel size by ~7-11x
 * @param dropStartTimeFromGCPhaseParallelEntries omit the per-entry startTime field from
 *     GCPhaseParallel combined GCWorker structs (non-aggregate mode only); individual phase start
 *     times are lost but the outer combined event's startTime, duration, and workerId are preserved
 * @param combineExecutionSampleEvents group jdk.ExecutionSample and jdk.NativeMethodSample by
 *     stackTrace, storing an array of (startTime, sampledThread) per unique stack — lossless except
 *     per-event ordering; reduces event count by ~8x on typical recordings
 * @param combineExceptionEventsLossless group jdk.JavaExceptionThrow and jdk.JavaErrorThrow by
 *     (thrownClass, stackTrace), storing an array of (startTime, eventThread, message) per unique
 *     key — lossless except per-event ordering; ~18 unique keys from 96K events in typical
 *     recordings
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
        boolean combineObjectAllocationSampleLossless,
        boolean sumObjectSizes,
        boolean ignoreZeroSizedTenuredAges,
        boolean ignoreTooShortGCPauses,
        boolean removeBCIAndLineNumberFromStackFrames,
        boolean removeTypeInformationFromStackFrames,
        boolean removeUnnecessaryAddresses,
        boolean combineExceptionEvents,
        boolean combineG1HeapRegionTypeChangeEvents,
        boolean combineBlockingEvents,
        boolean combineThreadParkLossless,
        boolean combineExecutionSampleEvents,
        boolean combineExceptionEventsLossless,
        boolean dropGCWorkerThreadFromGCPhaseParallel,
        long cpuBucketSeconds,
        String collapseInternalFramesPrefixes,
        String collapseAppFramesPrefixes,
        boolean aggregateGCPhaseParallelStats,
        boolean dropStartTimeFromGCPhaseParallelEntries,
        boolean combineGCHeapSummaryPairs)
        implements Comparable<Configuration> {

    /**
     * Default set of class-name prefixes that are considered "internal" and eligible for collapsing
     * in profiling stack traces when runs of ≥ 3 consecutive frames match.
     */
    public static final String DEFAULT_COLLAPSE_PREFIXES =
            "java.\njavax.\njdk.\nsun.\ncom.sun.\norg.springframework.\nscala.\nkotlin.";

    /**
     * Minimal base — all booleans false, numeric fields at their natural defaults. Use {@code
     * withXxx()} chains to build any preset from this.
     */
    private static Configuration allDefaults(String name) {
        return new Configuration(
                name,
                1_000_000_000L,
                1_000_000_000L,
                false,
                false,
                -1L,
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
                false,
                false,
                false,
                false,
                false,
                false,
                10L,
                "",
                "",
                false,
                false,
                false);
    }

    public static final Configuration LOSSLESS =
            allDefaults("lossless")
                    .withIgnoreUnnecessaryEvents(true)
                    .withUseSpecificHashesAndRefs(true)
                    .withCombineEventsWithoutDataLoss(true)
                    .withCombinePLABPromotionEvents(true)
                    .withCombineG1HeapRegionTypeChangeEvents(true)
                    .withCombineThreadParkLossless(true)
                    .withCombineGCHeapSummaryPairs(true);

    /** with conservative lossy compression */
    public static final Configuration DEFAULT =
            LOSSLESS.withName("default")
                    .withMemoryAsBFloat16(true)
                    .withTimeStampTicksPerSecond(1_000)
                    .withDurationTicksPerSecond(1_000_000)
                    .withUseSpecificHashesAndRefs(true)
                    .withIgnoreZeroSizedTenuredAges(true)
                    .withRemoveBCIAndLineNumberFromStackFrames(true)
                    .withRemoveUnnecessaryAddresses(true)
                    .withCombineObjectAllocationSampleLossless(true)
                    .withMaxStackTraceDepth(32);

    public static final Configuration REDUCED =
            DEFAULT.withName("reduced")
                    .withCombineObjectAllocationSampleEvents(true)
                    .withSumObjectSizes(true)
                    .withIgnoreUnnecessaryEvents(true)
                    .withRemoveTypeInformationFromStackFrames(true)
                    .withMaxStackTraceDepth(16)
                    .withCombineExceptionEvents(true)
                    .withCombineBlockingEvents(true)
                    .withCollapseInternalFramesPrefixes(DEFAULT_COLLAPSE_PREFIXES);

    /**
     * GC-log equivalent — enable only GC events (matches the bundled gc-log.jfc recording config).
     * No allocation profiling, no execution samples. Use with {@code --config gc-log}.
     */
    public static final Configuration GC_LOG =
            LOSSLESS.withName("gc-log")
                    .withMemoryAsBFloat16(true)
                    .withTimeStampTicksPerSecond(1_000_000)
                    .withDurationTicksPerSecond(1_000_000)
                    .withCombineObjectAllocationSampleEvents(false)
                    .withCombineObjectAllocationSampleLossless(false)
                    .withCombineExceptionEvents(false)
                    .withCombineExceptionEventsLossless(false)
                    .withCombineBlockingEvents(false)
                    .withCombineExecutionSampleEvents(false)
                    .withCombinePLABPromotionEvents(false)
                    .withAggregateGCPhaseParallelStats(true)
                    .withRemoveUnnecessaryAddresses(true)
                    .withIgnoreZeroSizedTenuredAges(true);

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
        if (collapseInternalFramesPrefixes == null) collapseInternalFramesPrefixes = "";
        if (collapseAppFramesPrefixes == null) collapseAppFramesPrefixes = "";
    }

    public static final Map<String, Configuration> configurations =
            Map.of(
                    "lossless", LOSSLESS,
                    "default", DEFAULT,
                    "reduced", REDUCED,
                    "gc-log", GC_LOG);

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

    public Configuration withCombineObjectAllocationSampleLossless(
            boolean combineObjectAllocationSampleLossless) {
        return withFieldValue(
                "combineObjectAllocationSampleLossless", combineObjectAllocationSampleLossless);
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

    public Configuration withCombineThreadParkLossless(boolean combineThreadParkLossless) {
        return withFieldValue("combineThreadParkLossless", combineThreadParkLossless);
    }

    public Configuration withCombineExecutionSampleEvents(boolean combineExecutionSampleEvents) {
        return withFieldValue("combineExecutionSampleEvents", combineExecutionSampleEvents);
    }

    public Configuration withCombineExceptionEventsLossless(
            boolean combineExceptionEventsLossless) {
        return withFieldValue("combineExceptionEventsLossless", combineExceptionEventsLossless);
    }

    public Configuration withCpuBucketSeconds(long cpuBucketSeconds) {
        return withFieldValue("cpuBucketSeconds", cpuBucketSeconds);
    }

    public Configuration withDropGCWorkerThreadFromGCPhaseParallel(boolean drop) {
        return withFieldValue("dropGCWorkerThreadFromGCPhaseParallel", drop);
    }

    public Configuration withAggregateGCPhaseParallelStats(boolean aggregate) {
        return withFieldValue("aggregateGCPhaseParallelStats", aggregate);
    }

    public Configuration withDropStartTimeFromGCPhaseParallelEntries(boolean drop) {
        return withFieldValue("dropStartTimeFromGCPhaseParallelEntries", drop);
    }

    public Configuration withCombineGCHeapSummaryPairs(boolean combineGCHeapSummaryPairs) {
        return withFieldValue("combineGCHeapSummaryPairs", combineGCHeapSummaryPairs);
    }

    public Configuration withCollapseInternalFramesPrefixes(String prefixes) {
        return withFieldValue("collapseInternalFramesPrefixes", prefixes == null ? "" : prefixes);
    }

    public Configuration withCollapseAppFramesPrefixes(String prefixes) {
        return withFieldValue("collapseAppFramesPrefixes", prefixes == null ? "" : prefixes);
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
                || combineObjectAllocationSampleLossless
                || combineEventsWithoutDataLoss
                || combineExceptionEvents
                || combineExceptionEventsLossless
                || combineG1HeapRegionTypeChangeEvents
                || combineBlockingEvents
                || combineThreadParkLossless
                || combineExecutionSampleEvents
                || combineGCHeapSummaryPairs;
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
        var presets = List.of(LOSSLESS, DEFAULT, REDUCED);
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
