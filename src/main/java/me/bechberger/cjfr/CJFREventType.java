package me.bechberger.cjfr;

import java.util.List;
import me.bechberger.condensed.types.StructType;

/**
 * Metadata for a single event type in a {@code .cjfr} recording.
 *
 * <p>Wraps a {@link StructType} and exposes only the fields that are part of the public reader API.
 */
public final class CJFREventType {

    private final StructType<?, ?> type;

    CJFREventType(StructType<?, ?> type) {
        this.type = type;
    }

    /** Fully-qualified event type name, e.g. {@code jdk.GarbageCollection}. */
    public String getName() {
        return type.getName();
    }

    /**
     * Human-readable label from the {@code @Label} annotation, e.g. {@code Garbage Collection}.
     * Falls back to the type name when no label is present.
     */
    public String getLabel() {
        return extractLabel(type.getDescription(), type.getName());
    }

    /** Returns {@code true} if the type carries a {@code @jdk.jfr.Experimental} annotation. */
    public boolean isExperimental() {
        String desc = type.getDescription();
        return desc != null && desc.contains("jdk.jfr.Experimental");
    }

    /** Field names declared on this event type, in declaration order. */
    public List<String> getFieldNames() {
        return type.getFieldNames();
    }

    /** Fields declared on this event type, in declaration order. */
    public List<CJFRFieldType> getFields() {
        return type.getFields().stream()
                .map(f -> new CJFRFieldType(f.name(), f.description(), f.type().getName()))
                .toList();
    }

    /**
     * Extracts the human {@code @Label} from a condensed description (a compact JSON array {@code
     * ["Label","Description",…]}), falling back to {@code fallbackName} when absent.
     */
    static String extractLabel(String description, String fallbackName) {
        if (description == null || description.isEmpty()) return fallbackName;
        int firstQuote = description.indexOf('"');
        if (firstQuote < 0) return fallbackName;
        int secondQuote = description.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return fallbackName;
        String label = description.substring(firstQuote + 1, secondQuote);
        return label.isEmpty() ? fallbackName : label;
    }

    @Override
    public String toString() {
        return "CJFREventType{" + getName() + "}";
    }
}
