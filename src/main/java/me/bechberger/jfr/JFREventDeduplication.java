package me.bechberger.jfr;

import java.util.List;
import java.util.Objects;
import jdk.jfr.consumer.RecordedEvent;

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

    /**
     * Returns a stable, value-based string key from a field. JFR RecordedObject uses
     * identity-based equals/hashCode, so complex-type fields cannot be used as HashMap keys
     * directly. Using toString() gives a stable representation across JFR chunks.
     */
    private static String stableKey(RecordedEvent event, String field) {
        var value = event.getValue(field);
        return value == null ? "null" : value.toString();
    }

    public JFREventDeduplication(Configuration configuration) {
        FLAG_EVENTS.forEach(this::putFlag);
        SINGLETON_EVENTS.forEach(this::putSingleton);

        put("jdk.NativeLibrary", "name", "baseAddress", "topAddress");
        // ModuleRequire: token = (source.name, requiredModule.name) — stable string key
        put(
                "jdk.ModuleRequire",
                e -> stableKey(e, "source") + "|" + stableKey(e, "requiredModule"),
                (a, b) -> true);
        // ModuleResolution: token = (exportedPackage, targetModule) — stable string key
        put(
                "jdk.ModuleResolution",
                e -> stableKey(e, "exportedPackage") + "|" + stableKey(e, "targetModule"),
                (a, b) -> true);

        // Per-chunk repeated events with a key field
        put("jdk.InitialEnvironmentVariable", "key", "value");
        put("jdk.InitialSystemProperty", "key", "value");
        put("jdk.InitialSecurityProperty", "key", "value");
        put("jdk.ActiveSetting", e -> e.getLong("id") + "/" + e.getString("name"), "value");
        // ModuleExport: token = (exportedPackage.name, targetModule.name) — stable string key
        put(
                "jdk.ModuleExport",
                e -> stableKey(e, "exportedPackage") + "|" + stableKey(e, "targetModule"),
                (a, b) -> true);

        // SystemProcess can repeat per chunk with same content
        put("jdk.SystemProcess", "pid", "commandLine");
        put("jdk.NativeLibraryLoad", "name", "success");

        // Periodic events that repeat per chunk with same values
        put(
                "jdk.NetworkUtilization",
                e -> stableKey(e, "networkInterface"),
                (a, b) ->
                        a.getLong("readRate") == b.getLong("readRate")
                                && a.getLong("writeRate") == b.getLong("writeRate"));
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

        // GC-related singleton periodic events
        putSingleton("jdk.ExceptionStatistics");
        putSingleton("jdk.GCHeapMemoryUsage");

        // ThreadCPULoad: per-thread, stable string token for eventThread
        put(
                "jdk.ThreadCPULoad",
                e -> stableKey(e, "eventThread"),
                (a, b) ->
                        Float.compare(a.getFloat("user"), b.getFloat("user")) == 0
                                && Float.compare(a.getFloat("system"), b.getFloat("system")) == 0);

        // CPULoad: singleton periodic, may repeat between chunks
        putSingleton("jdk.CPULoad");

        // ThreadAllocationStatistics can repeat with identical payload across
        // periodic emissions. Keeping all entries preserves strict round-trips.

        // FinalizerStatistics can legitimately repeat with identical payload across
        // different timestamps/chunks. Dropping these observations changes event counts
        // after condense+inflate roundtrips, so keep all entries.

        // JavaMonitorStatistics: singleton periodic event
        putSingleton("jdk.JavaMonitorStatistics");

        // Agent events: endChunk, identical across chunks
        put("jdk.JavaAgent", "name", "options", "dynamic");
        put("jdk.NativeAgent", "name", "options", "dynamic", "path");

        // DeprecatedInvocation: endChunk, stable string token for method
        put(
                "jdk.DeprecatedInvocation",
                e -> stableKey(e, "method"),
                (a, b) -> Objects.equals(a.getValue("forRemoval"), b.getValue("forRemoval")));

        // ClassLoaderStatistics can also repeat unchanged across chunks and those
        // observations are still semantically relevant in strict round-trips.

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
