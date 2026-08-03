package me.bechberger.cjfr;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.ReadStruct;
import org.jetbrains.annotations.Nullable;

/**
 * A single event read from a {@code .cjfr} recording.
 *
 * <p>Field values are loaded lazily; accessing any field completes the event.
 *
 * <pre>{@code
 * try (CJFRFile f = CJFRFile.open(path)) {
 *     CJFREvent e;
 *     while ((e = f.readEvent()) != null) {
 *         System.out.println(e.getEventType().getName() + " at " + e.getStartTime());
 *     }
 * }
 * }</pre>
 */
public final class CJFREvent {

    private final ReadStruct raw;
    private final CJFREventType eventType;

    CJFREvent(ReadStruct raw) {
        this.raw = raw;
        this.eventType = new CJFREventType(raw.getType());
    }

    /** The event type metadata for this event. */
    public CJFREventType getEventType() {
        return eventType;
    }

    /**
     * The event start time, or {@code null} when not present (e.g. instant events without a {@code
     * startTime} field).
     */
    public @Nullable Instant getStartTime() {
        if (!raw.containsKey("startTime")) return null;
        return raw.getInstant("startTime");
    }

    /** The event duration, or {@code null} when not present. */
    public @Nullable Duration getDuration() {
        if (!raw.containsKey("duration")) return null;
        Object val = raw.get("duration");
        if (val == null) return null;
        if (val instanceof Duration d) return d;
        if (val instanceof Long nanos) return Duration.ofNanos(nanos);
        return null;
    }

    /**
     * Returns the raw value for the named field, or {@code null} when the field is absent or null.
     *
     * <p>Possible return types: {@link String}, {@link Long}, {@link Integer}, {@link Double},
     * {@link Boolean}, {@link Instant}, {@link Duration}, {@link ReadStruct} (nested struct),
     * {@link ReadList} (array), or {@code null}.
     */
    public @Nullable Object getValue(String fieldName) {
        return raw.get(fieldName);
    }

    /** Returns a string field value, or {@code null}. */
    public @Nullable String getString(String fieldName) {
        Object val = raw.get(fieldName);
        return val == null ? null : val.toString();
    }

    /**
     * Returns a {@code long} field value, or throws {@link ClassCastException} when the field is
     * not a numeric type.
     */
    public long getLong(String fieldName) {
        return ((Number) raw.get(fieldName)).longValue();
    }

    /** Returns an {@code int} field value. */
    public int getInt(String fieldName) {
        return ((Number) raw.get(fieldName)).intValue();
    }

    /** Returns a {@code double} field value. */
    public double getDouble(String fieldName) {
        return ((Number) raw.get(fieldName)).doubleValue();
    }

    /** Returns a {@code boolean} field value. */
    public boolean getBoolean(String fieldName) {
        Object val = raw.get(fieldName);
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.longValue() != 0;
        throw new ClassCastException(
                "Cannot convert " + val + " to boolean for field " + fieldName);
    }

    /** Returns an {@link Instant} field value, handling both Instant and Long (ns) storage. */
    public @Nullable Instant getInstant(String fieldName) {
        return raw.getInstant(fieldName);
    }

    /** Returns a {@link Duration} field value. */
    public @Nullable Duration getDuration(String fieldName) {
        return getDurationOf(raw.get(fieldName));
    }

    private static @Nullable Duration getDurationOf(@Nullable Object val) {
        if (val == null) return null;
        if (val instanceof Duration d) return d;
        if (val instanceof Long nanos) return Duration.ofNanos(nanos);
        return null;
    }

    /** Returns a nested struct field as a {@link CJFREvent}, or {@code null} when absent. */
    public @Nullable CJFREvent getStruct(String fieldName) {
        ReadStruct nested = raw.getStruct(fieldName);
        return nested == null ? null : new CJFREvent(nested);
    }

    /**
     * Returns a list field. Elements may be primitives, structs ({@link CJFREvent}), or other
     * values depending on the field type.
     */
    public @Nullable List<?> getList(String fieldName) {
        Object val = raw.get(fieldName);
        if (val == null) return null;
        if (val instanceof ReadList<?> list) {
            return list.stream()
                    .map(item -> item instanceof ReadStruct s ? new CJFREvent(s) : item)
                    .collect(Collectors.toList());
        }
        if (val instanceof List<?> list) {
            return list.stream()
                    .map(item -> item instanceof ReadStruct s ? new CJFREvent(s) : item)
                    .collect(Collectors.toList());
        }
        return null;
    }

    /** Returns the names of all fields declared on this event's type. */
    public List<String> getFieldNames() {
        return eventType.getFieldNames();
    }

    /** Returns {@code true} if the event type declares a field with the given name. */
    public boolean hasField(String fieldName) {
        return raw.containsKey(fieldName);
    }

    /**
     * Access to the underlying {@link ReadStruct} for advanced use. The struct may be lazily
     * loaded; call {@link ReadStruct#ensureComplete()} before iterating all fields.
     */
    public ReadStruct getRawStruct() {
        return raw;
    }

    @Override
    public String toString() {
        return "CJFREvent{" + eventType.getName() + "}";
    }
}
