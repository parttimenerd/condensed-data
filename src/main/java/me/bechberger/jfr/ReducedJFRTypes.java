package me.bechberger.jfr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A registry of JFR types for which a reduced version exists, so that missing fields can be added
 *
 * <p>The types need to be common JFR types
 *
 * <p>Used by {@link me.bechberger.jfr.BasicJFRWriter} during writing and by {@link
 * me.bechberger.jfr.WritingJFRReader} during writing JFR files
 *
 * <p><b>For now it only supports primitive fields</b>
 */
public class ReducedJFRTypes {

    /** A field that has been removed from a JFR type */
    public sealed interface RemovedField {
        String fieldName();

        /** Condition under which the field should be removed when writing the reduced type */
        Predicate<Configuration> conditionForRemoval();
    }

    public record RemovedPrimitiveField(
            String fieldName, Predicate<Configuration> conditionForRemoval)
            implements RemovedField {}

    public static class ReducedTypeDefinition {
        private final String typeName;
        private final List<RemovedField> removedFields;

        public ReducedTypeDefinition(String typeName, List<RemovedField> removedFields) {
            this.typeName = typeName;
            this.removedFields = removedFields;
        }

        public List<RemovedField> getRemovedFields(
                Configuration configuration, boolean ignoreJFRHandledFields) {
            return removedFields.stream()
                    .filter(f -> f.conditionForRemoval().test(configuration))
                    .toList();
        }
    }

    private static ReducedTypeDefinition entry(String typeName, RemovedField... fields) {
        return new ReducedTypeDefinition(typeName, List.of(fields));
    }

    private static RemovedPrimitiveField addressField(String name) {
        return new RemovedPrimitiveField(name, Configuration::removeUnnecessaryAddresses);
    }

    public static final Map<String, ReducedTypeDefinition> REDUCED_JFR_TYPES =
            Map.ofEntries(
                    Map.entry(
                            "jdk.types.StackFrame",
                            entry(
                                    "jdk.types.StackFrame",
                                    new RemovedPrimitiveField(
                                            "bytecodeIndex",
                                            Configuration::removeBCIAndLineNumberFromStackFrames),
                                    new RemovedPrimitiveField(
                                            "lineNumber",
                                            Configuration::removeBCIAndLineNumberFromStackFrames),
                                    new RemovedPrimitiveField(
                                            "type",
                                            Configuration::removeTypeInformationFromStackFrames))),
                    Map.entry(
                            "jdk.G1HeapRegionTypeChange",
                            entry(
                                    "jdk.G1HeapRegionTypeChange",
                                    new RemovedPrimitiveField(
                                            "start", Configuration::ignoreUnnecessaryEvents))),
                    Map.entry(
                            "jdk.G1HeapRegionInformation",
                            entry(
                                    "jdk.G1HeapRegionInformation",
                                    new RemovedPrimitiveField(
                                            "start", Configuration::ignoreUnnecessaryEvents))),
                    Map.entry("jdk.ThreadPark", entry("jdk.ThreadPark", addressField("address"))),
                    // Monitor address fields: raw object addresses, monitorClass provides the
                    // semantic info
                    Map.entry(
                            "jdk.JavaMonitorEnter",
                            entry("jdk.JavaMonitorEnter", addressField("address"))),
                    Map.entry(
                            "jdk.JavaMonitorWait",
                            entry("jdk.JavaMonitorWait", addressField("address"))),
                    Map.entry(
                            "jdk.JavaMonitorNotify",
                            entry("jdk.JavaMonitorNotify", addressField("address"))),
                    Map.entry(
                            "jdk.JavaMonitorInflate",
                            entry("jdk.JavaMonitorInflate", addressField("address"))),
                    Map.entry(
                            "jdk.JavaMonitorDeflate",
                            entry("jdk.JavaMonitorDeflate", addressField("address"))),
                    // VirtualSpace: committedEnd = start + committedSize, reservedEnd = start +
                    // reservedSize, start is constant per run
                    Map.entry(
                            "jdk.types.VirtualSpace",
                            entry(
                                    "jdk.types.VirtualSpace",
                                    addressField("start"),
                                    addressField("committedEnd"),
                                    addressField("reservedEnd"))),
                    // ObjectSpace: end = start + size, start is derivable from heap layout
                    Map.entry(
                            "jdk.types.ObjectSpace",
                            entry(
                                    "jdk.types.ObjectSpace",
                                    addressField("start"),
                                    addressField("end"))),
                    // Shenandoah: start = heapBase + index * regionSize (same as G1)
                    Map.entry(
                            "jdk.ShenandoahHeapRegionStateChange",
                            entry(
                                    "jdk.ShenandoahHeapRegionStateChange",
                                    new RemovedPrimitiveField(
                                            "start", Configuration::ignoreUnnecessaryEvents))),
                    Map.entry(
                            "jdk.ShenandoahHeapRegionInformation",
                            entry(
                                    "jdk.ShenandoahHeapRegionInformation",
                                    new RemovedPrimitiveField(
                                            "start", Configuration::ignoreUnnecessaryEvents))),
                    // ClassLoaderStatistics: classLoaderData is a raw JVM pointer
                    Map.entry(
                            "jdk.ClassLoaderStatistics",
                            entry("jdk.ClassLoaderStatistics", addressField("classLoaderData"))),
                    // CodeCacheFull: raw address fields
                    Map.entry(
                            "jdk.CodeCacheFull",
                            entry(
                                    "jdk.CodeCacheFull",
                                    addressField("startAddress"),
                                    addressField("commitedTopAddress"),
                                    addressField("reservedTopAddress"))),
                    // CodeCacheStatistics: constant addresses, already deduped as periodic
                    Map.entry(
                            "jdk.CodeCacheStatistics",
                            entry(
                                    "jdk.CodeCacheStatistics",
                                    addressField("startAddress"),
                                    addressField("reservedTopAddress"))),
                    // CodeCacheConfiguration: constant addresses
                    Map.entry(
                            "jdk.CodeCacheConfiguration",
                            entry(
                                    "jdk.CodeCacheConfiguration",
                                    addressField("startAddress"),
                                    addressField("reservedTopAddress"))),
                    // NativeLibrary: base/top addresses are raw load addresses
                    Map.entry(
                            "jdk.NativeLibrary",
                            entry(
                                    "jdk.NativeLibrary",
                                    addressField("baseAddress"),
                                    addressField("topAddress"))),
                    // ParallelOldGarbageCollection: densePrefix is a raw compaction address
                    Map.entry(
                            "jdk.ParallelOldGarbageCollection",
                            entry("jdk.ParallelOldGarbageCollection", addressField("densePrefix"))),
                    // OldObject: raw heap address, type/description/referrer provide
                    // the meaningful info
                    Map.entry(
                            "jdk.types.OldObject",
                            entry("jdk.types.OldObject", addressField("address"))));

    public static Set<String> getRemovedFields(
            String typeName, Configuration configuration, boolean ignoreJFRHandledFields) {
        ReducedTypeDefinition def = REDUCED_JFR_TYPES.get(typeName);
        if (def == null) {
            return Set.of();
        }
        return def.getRemovedFields(configuration, ignoreJFRHandledFields).stream()
                .map(RemovedField::fieldName)
                .collect(java.util.stream.Collectors.toSet());
    }
}
