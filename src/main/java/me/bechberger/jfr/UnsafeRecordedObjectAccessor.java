package me.bechberger.jfr;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedObject;

/**
 * Bypasses the overhead of {@link RecordedObject}'s field-name lookup by directly accessing the
 * internal {@code Object[]} values array via {@link VarHandle}. Falls back to the public API if
 * module access is not available, if the field does not exist, or if the field has an unexpected
 * type (for cross-version compatibility).
 *
 * <p>Fast path is enabled when either:
 *
 * <ul>
 *   <li>Running as a java agent (call {@link #openModule(Instrumentation)} from premain)
 *   <li>CLI passes {@code --add-opens jdk.jfr/jdk.jfr.consumer=ALL-UNNAMED}
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // Create once, reuse in hot loops:
 * var lineNo = UnsafeRecordedObjectAccessor.intField("lineNumber", -1);
 * var bci    = UnsafeRecordedObjectAccessor.intField("bytecodeIndex", -1);
 * var method = UnsafeRecordedObjectAccessor.<RecordedMethod>field("method", null);
 *
 * for (RecordedFrame f : frames) {
 *     int ln = lineNo.get(f);
 *     int b  = bci.get(f);
 *     RecordedMethod m = method.get(f);
 * }
 * }</pre>
 */
public final class UnsafeRecordedObjectAccessor {

    /**
     * Holder class for lazy, JIT-friendly initialization. The VarHandle is stored in a static final
     * field so the JIT can constant-fold it, eliminating VarHandle guard overhead. Loaded on first
     * call to {@link #values(RecordedObject)}, which is after module access has been established
     * (either via --add-opens or openModule).
     */
    private static class Holder {
        static final VarHandle HANDLE;

        static {
            VarHandle h = null;
            try {
                var lookup =
                        MethodHandles.privateLookupIn(RecordedObject.class, MethodHandles.lookup());
                h = lookup.findVarHandle(RecordedObject.class, "objects", Object[].class);
            } catch (Exception | Error e) {
                // Module not open — will use public API fallback
            }
            HANDLE = h;
        }
    }

    private UnsafeRecordedObjectAccessor() {}

    // ─── Module opening ───────────────────────────────────────────────────────

    /**
     * Call from {@code premain(String, Instrumentation)} to open {@code jdk.jfr.consumer} to us.
     * Must be called before any accessor's get() method is invoked.
     */
    public static void openModule(Instrumentation inst) {
        Module jfrModule = RecordedObject.class.getModule();
        Module ourModule = UnsafeRecordedObjectAccessor.class.getModule();
        inst.redefineModule(
                jfrModule,
                Set.of(),
                Map.of(),
                Map.of("jdk.jfr.consumer", Set.of(ourModule)),
                Set.of(),
                Map.of());
        // Force Holder class initialization now that module is open
        @SuppressWarnings("unused")
        var unused = Holder.HANDLE;
    }

    /** Returns true if the fast (direct array access) path is active. */
    public static boolean isFastPathActive() {
        return Holder.HANDLE != null;
    }

    // ─── Factory methods ──────────────────────────────────────────────────────

    /** Creates a reusable accessor for a reference-typed field. */
    public static <T> FieldAccessor<T> field(String fieldName, T defaultValue) {
        return new FieldAccessor<>(fieldName, defaultValue);
    }

    /** Creates a reusable accessor for an int field — zero boxing on fast path. */
    public static IntFieldAccessor intField(String fieldName, int defaultValue) {
        return new IntFieldAccessor(fieldName, defaultValue);
    }

    /** Creates a reusable accessor for a long field — zero boxing on fast path. */
    public static LongFieldAccessor longField(String fieldName, long defaultValue) {
        return new LongFieldAccessor(fieldName, defaultValue);
    }

    /** Creates a reusable accessor for a boolean field. */
    public static BooleanFieldAccessor booleanField(String fieldName, boolean defaultValue) {
        return new BooleanFieldAccessor(fieldName, defaultValue);
    }

    // ─── Base class for cached index logic ────────────────────────────────────

    /**
     * Shared base that caches the field index per fields-list identity. This correctly handles
     * RecordedEvent (where all instances share the same class but different event types have
     * different field layouts) because each event type's getFields() returns the same cached List
     * reference for all events of that type.
     *
     * <p>Note: For RecordedEvent, getFields() includes built-in fields (startTime, and duration for
     * timed events) that are NOT stored in the internal Object[] array. The index is adjusted by
     * the offset (fields.size() - objects.length) so it correctly addresses the Object[] array.
     */
    public abstract static sealed class AbstractFieldAccessor
            permits FieldAccessor, IntFieldAccessor, LongFieldAccessor, BooleanFieldAccessor {

        protected final String fieldName;
        private List<ValueDescriptor> cachedFields;
        private int cachedIndex = -1;

        private AbstractFieldAccessor(String fieldName) {
            this.fieldName = fieldName;
        }

        /** Returns the internal Object[] or null if fast path is not available. */
        protected static Object[] values(RecordedObject obj) {
            VarHandle h = Holder.HANDLE;
            return h != null ? (Object[]) h.get(obj) : null;
        }

        /**
         * Returns the cached array index into Object[], resolving on first call or when field
         * layout changes. Accounts for built-in fields (startTime, duration) that are in
         * getFields() but not in the Object[] array.
         */
        protected int index(RecordedObject obj, Object[] vals) {
            List<ValueDescriptor> fields = obj.getFields();
            if (fields == cachedFields) return cachedIndex;
            cachedFields = fields;
            int fieldIdx = findFieldIndex(fields, fieldName);
            if (fieldIdx < 0) {
                cachedIndex = -1;
            } else {
                // offset = number of built-in fields (startTime, duration) not in Object[]
                int offset = fields.size() - vals.length;
                cachedIndex = fieldIdx - offset;
            }
            return cachedIndex;
        }
    }

    // ─── Concrete accessor types ──────────────────────────────────────────────

    /** Generic field accessor for reference types. */
    public static final class FieldAccessor<T> extends AbstractFieldAccessor {

        private final T defaultValue;

        private FieldAccessor(String fieldName, T defaultValue) {
            super(fieldName);
            this.defaultValue = defaultValue;
        }

        @SuppressWarnings("unchecked")
        public T get(RecordedObject obj) {
            Object[] vals = values(obj);
            if (vals != null) {
                int idx = index(obj, vals);
                if (idx >= 0 && idx < vals.length) {
                    Object v = vals[idx];
                    if (v == null) return defaultValue;
                    // Fast path: value is already resolved (not a JFR internal Reference/wrapper)
                    try {
                        return (T) v;
                    } catch (ClassCastException e) {
                        // Fall through to public API (handles UnsignedValue, Reference, etc.)
                    }
                }
            }
            // Slow path: use public API which handles internal types correctly
            try {
                T v = obj.getValue(fieldName);
                return v == null ? defaultValue : v;
            } catch (IllegalArgumentException | ClassCastException e) {
                return defaultValue;
            }
        }
    }

    /** Specialized int field accessor — zero boxing on fast path. */
    public static final class IntFieldAccessor extends AbstractFieldAccessor {

        private final int defaultValue;

        private IntFieldAccessor(String fieldName, int defaultValue) {
            super(fieldName);
            this.defaultValue = defaultValue;
        }

        public int get(RecordedObject obj) {
            Object[] vals = values(obj);
            if (vals != null) {
                int idx = index(obj, vals);
                if (idx >= 0 && idx < vals.length) {
                    Object v = vals[idx];
                    if (v instanceof Integer i) return i;
                    if (v instanceof Long l) return l.intValue(); // unsigned int stored as long
                    if (v == null) return defaultValue;
                    // Fall through: UnsignedValue, Reference, or other internal type
                }
            }
            // Slow path: use typed public API which handles unsigned/coercion
            try {
                return obj.getInt(fieldName);
            } catch (IllegalArgumentException | NullPointerException e) {
                return defaultValue;
            }
        }
    }

    /** Specialized long field accessor — zero boxing on fast path. */
    public static final class LongFieldAccessor extends AbstractFieldAccessor {

        private final long defaultValue;

        private LongFieldAccessor(String fieldName, long defaultValue) {
            super(fieldName);
            this.defaultValue = defaultValue;
        }

        public long get(RecordedObject obj) {
            Object[] vals = values(obj);
            if (vals != null) {
                int idx = index(obj, vals);
                if (idx >= 0 && idx < vals.length) {
                    Object v = vals[idx];
                    if (v instanceof Long l) return l;
                    if (v instanceof Integer i) return i.longValue();
                    if (v == null) return defaultValue;
                    // Fall through: UnsignedValue, Reference, or other internal type
                }
            }
            // Slow path: use typed public API which handles unsigned/coercion
            try {
                return obj.getLong(fieldName);
            } catch (IllegalArgumentException | NullPointerException e) {
                return defaultValue;
            }
        }
    }

    /** Specialized boolean field accessor. */
    public static final class BooleanFieldAccessor extends AbstractFieldAccessor {

        private final boolean defaultValue;

        private BooleanFieldAccessor(String fieldName, boolean defaultValue) {
            super(fieldName);
            this.defaultValue = defaultValue;
        }

        public boolean get(RecordedObject obj) {
            Object[] vals = values(obj);
            if (vals != null) {
                int idx = index(obj, vals);
                if (idx >= 0 && idx < vals.length) {
                    Object v = vals[idx];
                    if (v instanceof Boolean b) return b;
                    if (v == null) return defaultValue;
                    // Fall through: internal type
                }
            }
            // Slow path: use public API which handles internal types correctly
            try {
                Boolean v = obj.getValue(fieldName);
                return v == null ? defaultValue : v;
            } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
                return defaultValue;
            }
        }
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private static int findFieldIndex(List<ValueDescriptor> fields, String fieldName) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equals(fieldName)) {
                return i;
            }
        }
        return -1;
    }
}
