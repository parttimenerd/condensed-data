package me.bechberger.jfr;

import java.util.List;
import java.util.Objects;

/**
 * Deduplication for all events that represent (mostly) static properties, like flags or settings.
 */
public class JFREventDeduplication extends EventDeduplication {

    private final List<String> FLAG_EVENTS =
            List.of(
                    "jdk.IntFlag",
                    "jdk.UnsignedIntFlag",
                    "jdk.BooleanFlag",
                    "jdk.StringFlag",
                    "jdk.DoubleFlag",
                    "jdk.LongFlag",
                    "jdk.UnsignedLongFlag");
    private final List<String> SINGLETON_EVENTS =
            List.of(
                    "jdk.GCConfiguration",
                    "jdk.GCSurvivorConfiguration",
                    "jdk.GCTLABConfiguration",
                    "jdk.GCHeapConfiguration",
                    "jdk.YoungGenerationConfiguration",
                    "jdk.CPUInformation",
                    "jdk.OSInformation",
                    "jdk.JVMInformation",
                    "jdk.VirtualizationInformation",
                    "jdk.CodeCacheConfiguration",
                    "jdk.CPUTimeStampCounter",
                    "jdk.CompilerConfiguration");

    public JFREventDeduplication(Configuration configuration) {
        FLAG_EVENTS.forEach(this::putFlag);
        SINGLETON_EVENTS.forEach(this::putSingleton);

        put("jdk.NativeLibrary", "name", "baseAddress", "topAddress");
        put("jdk.ModuleRequire", "source", "requiredModule");
        put("jdk.ModuleResolution", "exportedPackage", "targetModule");

        // Per-chunk repeated events with a key field
        put("jdk.InitialEnvironmentVariable", "key", "value");
        put("jdk.InitialSystemProperty", "key", "value");
        put("jdk.InitialSecurityProperty", "key", "value");
        put("jdk.ActiveSetting", e -> e.getLong("id") + "/" + e.getString("name"), "value");
        put("jdk.ModuleExport", "exportedPackage", "targetModule");

        // SystemProcess can repeat per chunk with same content
        put("jdk.SystemProcess", "pid", "commandLine");
        put("jdk.NativeLibraryLoad", "name", "success");

        // Periodic events that repeat per chunk with same values
        put("jdk.NetworkUtilization", "networkInterface", "readRate", "writeRate");
        put(
                "jdk.CompilerQueueUtilization",
                "compiler",
                "addedRate",
                "removedRate",
                "queueSize",
                "peakQueueSize",
                "addedCount",
                "removedCount",
                "totalAddedCount",
                "totalRemovedCount",
                "compilerThreadCount");
        put(
                "jdk.DirectBufferStatistics",
                e -> 0,
                (a, b) ->
                        a.getLong("maxCapacity") == b.getLong("maxCapacity")
                                && a.getLong("count") == b.getLong("count")
                                && a.getLong("totalCapacity") == b.getLong("totalCapacity")
                                && a.getLong("memoryUsed") == b.getLong("memoryUsed"));
        put("jdk.GCHeapMemoryPoolUsage", "name", "used", "committed", "max");

        // G1HeapRegionInformation is periodic (everyChunk), dedup by region index
        put("jdk.G1HeapRegionInformation", "index", "type", "start", "used");

        // NativeMemoryUsage is periodic (everyChunk), dedup by memory type
        put("jdk.NativeMemoryUsage", "type", "reserved", "committed");
        putSingleton("jdk.NativeMemoryUsageTotal");

        // Singleton periodic events (one value per chunk, dedup if unchanged)
        putSingleton("jdk.ClassLoadingStatistics");
        putSingleton("jdk.JavaThreadStatistics");
        putSingleton("jdk.CompilerStatistics");
        putSingleton("jdk.PhysicalMemory");
        putSingleton("jdk.SwapSpace");
        putSingleton("jdk.SymbolTableStatistics");
        putSingleton("jdk.StringTableStatistics");
        putSingleton("jdk.ResidentSetSize");

        // ThreadAllocationStatistics: per-thread, ~52% duplicate rate
        put("jdk.ThreadAllocationStatistics", "thread", "allocated");

        // FinalizerStatistics: per-class, ~89% duplicate rate
        put("jdk.FinalizerStatistics", "finalizableClass", "objects", "totalFinalizersRun");

        // JavaMonitorStatistics: singleton periodic event
        putSingleton("jdk.JavaMonitorStatistics");

        // Agent events: endChunk, identical across chunks
        put("jdk.JavaAgent", "name", "options", "dynamic");
        put("jdk.NativeAgent", "name", "options", "dynamic", "path");

        // DeprecatedInvocation: endChunk, same methods across chunks
        put("jdk.DeprecatedInvocation", "method", "forRemoval");

        // ClassLoaderStatistics: per classLoader, most don't change between chunks
        put(
                "jdk.ClassLoaderStatistics",
                e -> {
                    try {
                        return e.getValue("classLoaderData");
                    } catch (Exception ex) {
                        return e.getValue("classLoader");
                    }
                },
                (a, b) -> {
                    var type = a.getEventType();
                    for (var field : type.getFields()) {
                        var fieldName = field.getName();
                        if (fieldName.equals("startTime") || fieldName.equals("endTime")) {
                            continue;
                        }
                        if (!Objects.equals(a.getValue(fieldName), b.getValue(fieldName))) {
                            return false;
                        }
                    }
                    return true;
                });

        // CodeCacheStatistics: per code blob type
        put(
                "jdk.CodeCacheStatistics",
                e -> e.getString("codeBlobType"),
                (a, b) -> {
                    var type = a.getEventType();
                    for (var field : type.getFields()) {
                        var fieldName = field.getName();
                        if (fieldName.equals("startTime") || fieldName.equals("endTime")) {
                            continue;
                        }
                        if (!Objects.equals(a.getValue(fieldName), b.getValue(fieldName))) {
                            return false;
                        }
                    }
                    return true;
                });
    }

    private void putFlag(String flagType) {
        put(flagType, e -> e.getString("name"), "value");
    }

    private void putSingleton(String flagType) {
        put(
                flagType,
                e -> 0,
                (a, b) -> {
                    var type = a.getEventType();
                    for (var field : type.getFields()) {
                        var fieldName = field.getName();
                        if (fieldName.equals("startTime") || fieldName.equals("endTime")) {
                            continue;
                        }
                        if (!Objects.equals(a.getValue(fieldName), b.getValue(fieldName))) {
                            return false;
                        }
                    }
                    return true;
                });
    }
}
