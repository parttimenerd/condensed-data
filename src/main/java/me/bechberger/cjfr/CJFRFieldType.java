package me.bechberger.cjfr;

/**
 * Metadata for a single field on a {@link CJFREventType}.
 *
 * @param name field name, e.g. {@code gcId}
 * @param description raw encoded description string (may be a JSON array with label/annotations)
 * @param typeName internal type name, e.g. {@code long} or {@code jdk.types.StackTrace}
 */
public record CJFRFieldType(String name, String description, String typeName) {

    /** Human-readable label; falls back to {@code name} when absent. */
    public String getLabel() {
        return CJFREventType.extractLabel(description, name);
    }
}
