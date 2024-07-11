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
                    "jdk.YoungGenerationConfiguration");

    public JFREventDeduplication(Configuration configuration) {
        FLAG_EVENTS.forEach(this::putFlag);
        SINGLETON_EVENTS.forEach(this::putSingleton);

        put("jdk.NativeLibrary", "name", "baseAddress", "topAddress");
        put("jdk.ModuleRequire", "source", "requiredModule");
        put("jdk.ModuleResolution", "exportedPackage", "targetModule");
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
