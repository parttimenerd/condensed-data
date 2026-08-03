package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.formatMemory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.query.ValueFormatter;
import me.bechberger.util.MemoryUtil;

/** Tabular view for JFR events */
public class JFRView {

    private static final Set<String> warnedTypes = new HashSet<>();

    enum Alignment {
        LEFT,
        RIGHT
    }

    interface Column {
        String header();

        /** -1 for no limit */
        int width();

        default int maxWidth() {
            return width();
        }

        /** rows that this can produce */
        default int rows(ReadStruct event) {
            return 1;
        }

        /** Format the event, producing the specified number of rows */
        List<String> format(ReadStruct event, int rows);

        Alignment alignment();

        /**
         * Whether this column has fixed width in oracle's distribute() pass 3. Oracle sets
         * fixedWidth=true for non-String fields (integers, timestamps, etc.) and fixedWidth=false
         * for String-valued fields (names, class names, etc.). In pass 3, only non-fixed columns
         * receive extra width. Default: true (fixed). Override to false in string-valued columns.
         */
        default boolean isOracleFixedWidth() {
            return true;
        }

        /**
         * Compact representation of a value that doesn't fit in its column width. Oracle uses the
         * last dot-separated component (e.g. strips package prefix from class names). Default: no
         * compaction (return value as-is, rely on truncation).
         */
        default String compact(String value) {
            return value;
        }
    }

    private static String propertyToHeader(String property) {
        var sb = new StringBuilder();
        for (var c : property.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.substring(0, 1).toUpperCase() + sb.substring(1);
    }

    record DurationColumn(String header, String property) implements Column {

        public DurationColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(8, header.length());
        }

        @Override
        public int maxWidth() {
            return -1;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var raw = event.get(property);
            if (raw == null) {
                return List.of("N/A");
            }
            Duration val;
            if (raw instanceof Duration d) {
                val = d;
            } else if (raw instanceof Long nanos) {
                val = Duration.ofNanos(nanos);
            } else {
                return List.of(raw.toString());
            }
            return List.of(ValueFormatter.formatTimespan(val));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record InstantColumn(String header, String property) implements Column {

        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        public InstantColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(8, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var raw = event.get(property);
            if (raw == null) {
                return List.of("N/A");
            }
            Instant value;
            if (raw instanceof Instant inst) {
                value = inst;
            } else if (raw instanceof Long nanos) {
                value = Instant.ofEpochSecond(0, nanos);
            } else {
                return List.of(raw.toString());
            }
            try {
                return List.of(
                        formatter.format(LocalDateTime.ofInstant(value, ZoneId.systemDefault())));
            } catch (java.time.DateTimeException e) {
                return List.of("N/A");
            }
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    record ThreadColumn(String header, String property) implements Column {

        public ThreadColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(15, header.length());
        }

        @Override
        public int maxWidth() {
            return Math.max(width(), 25);
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var value = event.getStruct(property);
            if (value == null) {
                return List.of("N/A");
            }
            var name = value.get("javaName", String.class);
            if (name == null) {
                name = value.get("osName", String.class);
            }
            return List.of(name != null ? name : "N/A");
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    record MemoryColumn(String header, String property, MemoryUtil.MemoryUnit unit)
            implements Column {

        public MemoryColumn(String property, MemoryUtil.MemoryUnit unit) {
            this(propertyToHeader(property), property, unit);
        }

        @Override
        public int width() {
            return Math.max(10, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var prop = event.get(property);
            if (prop == null) {
                return List.of("N/A");
            }
            var value = prop instanceof Number ? ((Number) prop).longValue() : (long) prop;
            if (unit == MemoryUtil.MemoryUnit.BITS) {
                return List.of(formatMemory(value, 1, 2, unit));
            }
            return List.of(ValueFormatter.formatMemory(value));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record MemoryAddressColumn(String header, String property) implements Column {

        public MemoryAddressColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(10, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var prop = event.get(property);
            if (prop == null) {
                return List.of("N/A");
            }
            long value = prop instanceof Number ? ((Number) prop).longValue() : (long) prop;
            return List.of(String.format("0x%08X", value));
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    record StringColumn(String header, String property) implements Column {

        public StringColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return -1;
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property, String.class);
            return List.of(val != null ? val : "N/A");
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    record IntegerColumn(String header, String property, int width) implements Column {

        public IntegerColumn(String property, int width) {
            this(propertyToHeader(property), property, width);
        }

        @Override
        public int width() {
            return Math.max(width, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property);
            if (val == null) return List.of("N/A");
            if (val instanceof Number n)
                return List.of(String.format(java.util.Locale.ROOT, "%,d", n.longValue()));
            return List.of(String.valueOf(val));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    /**
     * Integer column where {@link Integer#MIN_VALUE} is a "not applicable" sentinel (e.g.
     * OldObjectSample.arrayElements is MIN_VALUE when the object is not an array).
     */
    record SentinelIntegerColumn(String header, String property, int width) implements Column {

        public SentinelIntegerColumn(String property, int width) {
            this(propertyToHeader(property), property, width);
        }

        @Override
        public int width() {
            return Math.max(width, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property);
            if (val == null) {
                return List.of("N/A");
            }
            if (val instanceof Number n && n.longValue() == Integer.MIN_VALUE) {
                return List.of("N/A");
            }
            if (val instanceof Number n)
                return List.of(String.format(java.util.Locale.ROOT, "%,d", n.longValue()));
            return List.of(String.valueOf(val));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record FloatColumn(String header, String property, int width, int precision) implements Column {

        public FloatColumn(String property, int width, int precision) {
            this(propertyToHeader(property), property, width, precision);
        }

        @Override
        public int width() {
            return Math.max(width, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property);
            if (val == null) {
                return List.of("N/A");
            }
            double d = val instanceof Number n ? n.doubleValue() : Double.parseDouble(val.toString());
            return List.of(me.bechberger.jfr.cli.query.ValueFormatter.formatDoublePublic(d));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record BooleanColumn(String header, String property) implements Column {

        public BooleanColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(5, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property, Boolean.class);
            return List.of(val != null ? (val ? "true" : "false") : "N/A");
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record PercentageColumn(String header, String property) implements Column {

        public PercentageColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(8, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property);
            if (val == null) {
                return List.of("N/A");
            }
            double d = val instanceof Double ? (double) val : ((Number) val).doubleValue();
            return List.of(String.format(java.util.Locale.ROOT, "%.2f%%", d * 100));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record FrequencyColumn(String header, String property) implements Column {

        public FrequencyColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return Math.max(12, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property);
            if (val == null) {
                return List.of("N/A");
            }
            long hz = val instanceof Long ? (long) val : ((Number) val).longValue();
            return List.of(hz + " Hz");
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    /**
     * Renders a data-rate field (@DataAmount + @Frequency, e.g. bytes/second) as a memory size with
     * a "/s" suffix, matching the JDK {@code jfr print} output (e.g. {@code 450.5 MB/s}). Without
     * this, such fields fall through to {@link MemoryColumn} and drop the per-second semantics, or
     * to {@link FrequencyColumn} and are wrongly labelled Hz.
     */
    record DataRateColumn(String header, String property, MemoryUtil.MemoryUnit unit)
            implements Column {

        public DataRateColumn(String property, MemoryUtil.MemoryUnit unit) {
            this(propertyToHeader(property), property, unit);
        }

        @Override
        public int width() {
            return Math.max(12, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var prop = event.get(property);
            if (prop == null) {
                return List.of("N/A");
            }
            long value = prop instanceof Number ? ((Number) prop).longValue() : (long) prop;
            return List.of(formatMemory(value, 1, 2, unit) + "/s");
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
        }
    }

    record ClassColumn(String header, String property) implements Column {

        public ClassColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return -1;
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var klass = event.getStruct(property);
            if (klass == null) {
                return List.of("N/A");
            }
            String pkg = null;
            var pkgStruct = klass.getStruct("package");
            if (pkgStruct != null) {
                pkg = pkgStruct.get("name", String.class);
            }
            if (pkg == null) {
                pkg = klass.get("package", String.class);
            }
            var rawName = klass.get("name", String.class);
            var klassName = rawName != null ? decodeClassName(rawName) : "N/A";
            if (pkg == null) {
                return List.of(klassName);
            }
            if (klassName.startsWith(pkg + ".") || klassName.contains(".")) {
                return List.of(klassName);
            }
            return List.of(pkg + "." + klassName);
        }

        /**
         * Decode a JVM class name into its readable form. Array classes are stored as JVM type
         * descriptors ({@code [B}, {@code [Ljava/lang/Object;}); the JDK {@code jfr print} tool
         * renders them as {@code byte[]}, {@code java.lang.Object[]}. Non-array names just get
         * their {@code /} separators turned into {@code .}.
         */
        static String decodeClassName(String rawName) {
            if (!rawName.startsWith("[")) {
                return rawName.replace('/', '.');
            }
            int dims = 0;
            while (dims < rawName.length() && rawName.charAt(dims) == '[') {
                dims++;
            }
            String element = rawName.substring(dims);
            String base;
            if (element.startsWith("L") && element.endsWith(";")) {
                base = element.substring(1, element.length() - 1).replace('/', '.');
            } else {
                base =
                        switch (element) {
                            case "B" -> "byte";
                            case "S" -> "short";
                            case "I" -> "int";
                            case "J" -> "long";
                            case "F" -> "float";
                            case "D" -> "double";
                            case "C" -> "char";
                            case "Z" -> "boolean";
                            case "V" -> "void";
                            default -> element.replace('/', '.');
                        };
            }
            return base + "[]".repeat(dims);
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }

        @Override
        public String compact(String value) {
            int dot = value.lastIndexOf('.');
            return dot >= 0 ? value.substring(dot + 1) : value;
        }
    }

    record ClassLoaderColumn(String header, String property) implements Column {

        public ClassLoaderColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return -1;
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var cl = event.getStruct(property);
            if (cl == null) {
                return List.of("N/A");
            }
            // jdk.types.ClassLoader has a `type` (the loader's class) and a `name` (the loader's
            // instance name, e.g. "app"/"platform", usually null for VM-internal loaders). The JDK
            // `jfr print` tool renders the type's class name (e.g.
            // "jdk.internal.loader.ClassLoaders$AppClassLoader"), so prefer that and fall back to
            // `name` only when the type is unavailable.
            var type = cl.getStruct("type");
            if (type != null) {
                var typeName = type.get("name", String.class);
                if (typeName != null) {
                    return List.of(ClassColumn.decodeClassName(typeName));
                }
            }
            var name = cl.get("name", String.class);
            return List.of(name != null && !name.isEmpty() ? name : "N/A");
        }

        @Override
        public String compact(String value) {
            int dot = value.lastIndexOf('.');
            return dot >= 0 ? value.substring(dot + 1) : value;
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    record MethodColumn(String header, String property) implements Column {

        private static final ClassColumn CLASS_COLUMN = new ClassColumn("type");

        public MethodColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return -1;
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var method = event.getStruct(property);
            if (method == null) {
                return List.of("N/A");
            }
            return List.of(ValueFormatter.formatMethod(method));
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    record StackTraceColumn(String header, String property) implements Column {

        private static final MethodColumn METHOD_COLUMN = new MethodColumn("method");

        public StackTraceColumn(String property) {
            this(propertyToHeader(property), property);
        }

        @Override
        public int width() {
            return -1;
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public int maxWidth() {
            return 50;
        }

        @Override
        public int rows(ReadStruct event) {
            var val = event.getStruct(property);
            return val == null ? 1 : val.size();
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.getStruct(property);
            if (val == null) {
                return List.of("N/A");
            }
            var frames = val.<ReadStruct>getList("frames");
            return frames.stream()
                    .map(
                            f -> {
                                String m = METHOD_COLUMN.format(f, 1).get(0);
                                if (f.hasField("lineNumber")) {
                                    Object ln = f.get("lineNumber");
                                    if (ln instanceof Number n && n.intValue() >= 0) {
                                        m = m + ":" + n.intValue();
                                    }
                                }
                                return m;
                            })
                    .limit(rows)
                    .toList();
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    /**
     * Wraps a Column so it reads from a nested struct field instead of the event root. Used to
     * expand a struct field into flat top-level columns (e.g. "Heap Space : Committed Size").
     */
    record NestedColumn(String header, String parentProperty, Column inner) implements Column {

        @Override
        public int width() {
            return inner.width();
        }

        @Override
        public int maxWidth() {
            return inner.maxWidth();
        }

        @Override
        public Alignment alignment() {
            return inner.alignment();
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var nested = event.getStruct(parentProperty);
            if (nested == null) {
                return List.of("N/A");
            }
            return inner.format(nested, rows);
        }

        @Override
        public String compact(String value) {
            return inner.compact(value);
        }

        @Override
        public boolean isOracleFixedWidth() {
            return inner.isOracleFixedWidth();
        }
    }

    /**
     * Renders jdk.ActiveSetting/jdk.RecordingSetting {@code id} field: cjfr stores the target event
     * type's name (e.g. "jdk.ThreadStart"); oracle shows the @Label (e.g. "Java Thread Start").
     * Falls back to raw value when label is unknown.
     */
    record EventIdColumn(String header, String property, Map<String, String> typeLabels)
            implements Column {

        @Override
        public int width() {
            return -1;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property);
            if (val == null) return List.of("N/A");
            String raw = val.toString();
            String label = typeLabels.getOrDefault(raw, raw);
            return List.of(label);
        }

        @Override
        public boolean isOracleFixedWidth() {
            return false;
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    /** Generic formatter for structs */
    record StructColumn(String header, String property, List<Column> parts) implements Column {

        public StructColumn(String property, List<Column> parts) {
            this(propertyToHeader(property), property, parts);
        }

        static Column of(String property, CondensedType<?, ?> type, int avDepth) {
            return of(property, propertyToHeader(property), type, avDepth);
        }

        static Column of(String property, String header, CondensedType<?, ?> type, int avDepth) {
            if (avDepth <= 0 || !(type instanceof StructType<?, ?> structType)) {
                return new Column() {
                    @Override
                    public String header() {
                        return header;
                    }

                    @Override
                    public int width() {
                        return -1;
                    }

                    @Override
                    public List<String> format(ReadStruct event, int rows) {
                        var val = event.get(property);
                        if (val == null) {
                            return List.of("N/A");
                        }
                        return List.of(val.toString());
                    }

                    @Override
                    public Alignment alignment() {
                        return Alignment.LEFT;
                    }
                };
            }
            return new StructColumn(
                    property,
                    structType.getFields().stream()
                            .map(f -> fieldToColumn(f, avDepth - 1))
                            .toList());
        }

        @Override
        public int width() {
            return -1;
        }

        @Override
        public int rows(ReadStruct event) {
            var struct = event.getStruct(property);
            return struct != null ? struct.size() : 1;
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var struct = event.getStruct(property);
            if (struct == null) {
                return List.of("N/A");
            }
            if (rows == 1) {
                if (parts.size() == 1) {
                    return parts.get(0).format(struct, 1);
                }
                // Show all fields as "label=value" for a compact, unambiguous single-row summary
                return List.of(
                        parts.stream()
                                .map(p -> p.header() + "=" + p.format(struct, 1).get(0))
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
            List<String> ret = new ArrayList<>();
            for (var part : parts) {
                var partRows = part.format(struct, rows);
                String line = part.header() + ": " + partRows.get(0);
                ret.add(line);
                for (int i = 1; i < partRows.size(); i++) {
                    ret.add(" ".repeat(part.header().length()) + " " + partRows.get(i));
                }
            }
            return ret;
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
        }
    }

    static Column fieldToColumn(Field<?, ?, ?> field) {
        return fieldToColumn(field, 2, true);
    }

    static Column fieldToColumn(Field<?, ?, ?> field, int avDepth) {
        return fieldToColumn(field, avDepth, true);
    }

    static Column fieldToColumn(Field<?, ?, ?> field, boolean hasDuration) {
        return fieldToColumn(field, 2, hasDuration);
    }

    /**
     * Expands a top-level event field into columns. Generic struct fields are flattened into one
     * column per sub-field with header "Parent : Child" (matching jfr oracle output). Fields with
     * dedicated formatters (Thread, StackTrace, Class, etc.) return a singleton list.
     */
    static List<Column> topLevelFieldColumns(Field<?, ?, ?> field) {
        return topLevelFieldColumns(field, null, Map.of());
    }

    static List<Column> topLevelFieldColumns(
            Field<?, ?, ?> field, String parentTypeName, Map<String, String> typeLabels) {
        return topLevelFieldColumns(field, parentTypeName, typeLabels, true);
    }

    static List<Column> topLevelFieldColumns(
            Field<?, ?, ?> field,
            String parentTypeName,
            Map<String, String> typeLabels,
            boolean hasDuration) {
        return topLevelFieldColumns(
                field, parentTypeName, typeLabels, hasDuration, new java.util.HashSet<>());
    }

    static List<Column> topLevelFieldColumns(
            Field<?, ?, ?> field,
            String parentTypeName,
            Map<String, String> typeLabels,
            boolean hasDuration,
            java.util.Set<String> expandedStructTypes) {
        // ActiveSetting/RecordingSetting.id: stored as event-type name, render as @Label
        if ("id".equals(field.name())
                && ("jdk.ActiveSetting".equals(parentTypeName)
                        || "jdk.RecordingSetting".equals(parentTypeName))
                && !typeLabels.isEmpty()) {
            return List.of(
                    new EventIdColumn(
                            fieldDisplayName(field, hasDuration), field.name(), typeLabels));
        }
        Column col = fieldToColumn(field, hasDuration);
        if (col instanceof StructColumn && field.type() instanceof StructType<?, ?> structType) {
            String structTypeName = structType.getName();
            // Oracle deduplicates struct-type expansions: the first occurrence is expanded into
            // sub-columns; subsequent fields with the same struct type are dropped entirely
            // (e.g. MetaspaceSummary's dataSpace and classSpace use the same MetaspaceSizes type
            // as metaspace and are omitted — oracle's HashSet dedup skips them completely).
            if (!expandedStructTypes.add(structTypeName)) {
                return List.of();
            }
            String parentHeader = fieldDisplayName(field, hasDuration);
            String parentProp = field.name();
            return structType.getFields().stream()
                    .map(
                            subField -> {
                                Column inner = fieldToColumn(subField, 1);
                                // Skip sub-fields that are themselves generic structs — the oracle
                                // does not recurse into nested structs within an already-expanded
                                // struct (e.g. Package.module is omitted in ModuleExport view).
                                // Check the field's type directly; StructColumn.of() may return
                                // an anonymous Column at depth 0 rather than a StructColumn.
                                if (subField.type() instanceof StructType<?, ?>
                                        && !(inner instanceof ThreadColumn)
                                        && !(inner instanceof ClassColumn)
                                        && !(inner instanceof ClassLoaderColumn)
                                        && !(inner instanceof MethodColumn)
                                        && !(inner instanceof StackTraceColumn)) {
                                    return null;
                                }
                                String compoundHeader = parentHeader + " : " + inner.header();
                                return (Column) new NestedColumn(compoundHeader, parentProp, inner);
                            })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
        }
        return List.of(col);
    }

    /** Check if the field description JSON contains a specific annotation type */
    private static boolean hasAnnotation(Field<?, ?, ?> field, String annotationType) {
        var desc = field.description();
        return desc != null && desc.contains("\"" + annotationType + "\"");
    }

    /**
     * Heuristic to detect fields that should be @DataAmount but lost their annotation in newer JDK
     * JFR files (same annotation stripping issue as Bug 168).
     */
    private static boolean isLikelyDataAmountField(String name) {
        return name.endsWith("Size")
                || name.endsWith("Used")
                || name.endsWith("Capacity")
                || name.endsWith("Bytes")
                || name.endsWith("Memory")
                || name.equals("committed")
                || name.equals("reserved")
                || name.equals("used")
                || name.equals("total")
                || name.equals("empty")
                || name.equals("uncommitted")
                || name.equals("unmapped")
                || name.equals("gcThreshold")
                || name.equals("heapUsed")
                || name.equals("lastKnownHeapUsage");
    }

    /** Returns the @Label for the field, falling back to the camelCase-converted field name. */
    private static String fieldDisplayName(Field<?, ?, ?> field) {
        return fieldDisplayName(field, true);
    }

    /** Returns the @Label for the field, applying oracle's field-name abbreviations. */
    private static String fieldDisplayName(Field<?, ?, ?> field, boolean hasDuration) {
        // Oracle's FieldBuilder.makeLabel() hardcodes abbreviations for a few field names.
        switch (field.name()) {
            case "gcId":
                return "GC ID";
            case "compilerId":
                return "Compiler ID";
            case "startTime":
                if (!hasDuration) return "Time";
        }
        String desc = field.description();
        if (desc != null && !desc.isEmpty()) {
            try {
                String label = BasicJFRWriter.parseFieldDescription(desc).label();
                if (label != null && !label.isEmpty()) return label;
            } catch (RuntimeException ignored) {
            }
        }
        return propertyToHeader(field.name());
    }

    private static Column fieldToColumn(Field<?, ?, ?> field, int avDepth, boolean hasDuration) {
        var typeName = field.type().getName();
        var prop = field.name();
        var header = fieldDisplayName(field, hasDuration);
        // @MemoryAddress renders as a hex address regardless of the underlying numeric type.
        if (hasAnnotation(field, "jdk.jfr.MemoryAddress")) {
            return new JFRView.MemoryAddressColumn(header, prop);
        }
        // A @DataAmount + @Frequency field is a data rate (bytes/second or bits/second), e.g.
        // G1BasicIHOP.recentAllocationRate or NetworkUtilization.readRate. It must render as
        // "MB/s", not a plain byte size (MemoryColumn) nor Hz (FrequencyColumn).
        if (hasAnnotation(field, "jdk.jfr.Frequency")
                && hasAnnotation(field, "jdk.jfr.DataAmount")) {
            var desc = field.description();
            var unit =
                    desc != null && desc.contains("BITS")
                            ? MemoryUtil.MemoryUnit.BITS
                            : MemoryUtil.MemoryUnit.BYTES;
            return new JFRView.DataRateColumn(header, prop, unit);
        }
        // Check for fields where @Unsigned shadows @Timespan or @Timestamp in the type name
        if (typeName.equals("jdk.jfr.Unsigned")) {
            if (hasAnnotation(field, "jdk.jfr.Timespan")) {
                return new JFRView.DurationColumn(header, prop);
            }
            if (hasAnnotation(field, "jdk.jfr.Timestamp")) {
                return new JFRView.InstantColumn(header, prop);
            }
        }
        return switch (typeName) {
            case "millis", "nanos", "tickspan", "microseconds", "timespan" ->
                    new JFRView.DurationColumn(header, prop);
            case "timestamp" -> new JFRView.InstantColumn(header, prop);
            case "bytes", "memory BYTES", "memory varint BYTES", "jdk.jfr.DataAmount" ->
                    new JFRView.MemoryColumn(header, prop, MemoryUtil.MemoryUnit.BYTES);
            case "memory BITS", "memory varint BITS" ->
                    new JFRView.MemoryColumn(header, prop, MemoryUtil.MemoryUnit.BITS);
            case "java.lang.String" -> new JFRView.StringColumn(header, prop);
            case "int", "jdk.jfr.Unsigned", "uint1", "uint2", "int1" -> {
                // Some int fields use Integer.MIN_VALUE as a "not applicable" sentinel, e.g.
                // OldObjectSample.arrayElements ("... or minimum value for the type int if it is
                // not an array"). Render the sentinel as N/A instead of -2147483648.
                var desc = field.description();
                if (desc != null && desc.contains("minimum value for the type int")) {
                    yield new JFRView.SentinelIntegerColumn(header, prop, 10);
                }
                yield new JFRView.IntegerColumn(header, prop, 10);
            }
            case "long" -> {
                // Fallback for fields that should be timestamp/duration but lost their
                // type info due to missing annotations in the original JFR file (Bug 168)
                if (prop.equals("startTime")) {
                    yield new JFRView.InstantColumn(header, prop);
                }
                if (prop.equals("duration")) {
                    yield new JFRView.DurationColumn(header, prop);
                }
                // Fallback for @DataAmount fields that lost their annotation (Bug 212)
                if (isLikelyDataAmountField(prop)) {
                    yield new JFRView.MemoryColumn(header, prop, MemoryUtil.MemoryUnit.BYTES);
                }
                yield new JFRView.IntegerColumn(header, prop, 20);
            }
            case "float", "double" -> new JFRView.FloatColumn(header, prop, 10, 2);
            case "boolean" -> new JFRView.BooleanColumn(header, prop);
            case "jdk.jfr.Percentage", "percentage" -> new JFRView.PercentageColumn(header, prop);
            case "jdk.jfr.Frequency" -> new JFRView.FrequencyColumn(header, prop);
            case "jdk.types.Class", "java.lang.Class" -> new JFRView.ClassColumn(header, prop);
            case "jdk.types.ClassLoader" -> new JFRView.ClassLoaderColumn(header, prop);
            case "jdk.types.Method" -> new JFRView.MethodColumn(header, prop);
            case "jdk.types.StackTrace" -> new JFRView.StackTraceColumn(header, prop);
            case "java.lang.Thread" -> new JFRView.ThreadColumn(header, prop);
            default -> {
                if (field.type() instanceof StructType<?, ?>) {
                    // Known struct types are handled via StructColumn
                    yield StructColumn.of(prop, header, field.type(), avDepth - 1);
                }
                if (warnedTypes.add(typeName)) {
                    System.err.println("Warning: potentially unknown type: " + typeName);
                }
                yield StructColumn.of(prop, header, field.type(), avDepth - 1);
            }
        };
    }

    public record JFRViewConfig(String name, List<Column> columns) {
        public JFRViewConfig(StructType<?, ?> type) {
            this(type, Map.of());
        }

        public JFRViewConfig(StructType<?, ?> type, Map<String, String> typeLabels) {
            this(typeDisplayName(type), buildColumns(type, typeLabels));
        }

        private static List<Column> buildColumns(
                StructType<?, ?> type, Map<String, String> typeLabels) {
            boolean hasDuration =
                    type.getFields().stream().anyMatch(f -> "duration".equals(f.name()));
            // Oracle deduplicates struct-type expansions: if two fields share the same struct type
            // (same ValueDescriptor identity), only the first is expanded into sub-columns; the
            // rest are left as leaf columns. Replicate this by tracking expanded type names.
            java.util.Set<String> expandedStructTypes = new java.util.HashSet<>();
            List<Column> cols =
                    new java.util.ArrayList<>(
                            type.getFields().stream()
                                    .flatMap(
                                            f ->
                                                    topLevelFieldColumns(
                                                            f,
                                                            type.getName(),
                                                            typeLabels,
                                                            hasDuration,
                                                            expandedStructTypes)
                                                            .stream())
                                    .toList());
            // ExecutionSample/NativeMethodSample: state field dropped at condense (always
            // STATE_RUNNABLE); inject it after stackTrace to match oracle output.
            String typeName = type.getName();
            if (("jdk.ExecutionSample".equals(typeName)
                            || "jdk.NativeMethodSample".equals(typeName))
                    && type.getFields().stream().noneMatch(f -> "state".equals(f.name()))) {
                int stackIdx = -1;
                for (int i = 0; i < cols.size(); i++) {
                    Column c = cols.get(i);
                    if (c instanceof StackTraceColumn) {
                        stackIdx = i;
                        break;
                    }
                }
                Column stateCol =
                        new Column() {
                            @Override
                            public String header() {
                                return "Thread State";
                            }

                            @Override
                            public int width() {
                                return -1;
                            }

                            @Override
                            public List<String> format(ReadStruct event, int rows) {
                                Object val = event.get("state");
                                return List.of(val != null ? val.toString() : "STATE_RUNNABLE");
                            }

                            @Override
                            public Alignment alignment() {
                                return Alignment.LEFT;
                            }
                        };
                if (stackIdx >= 0) {
                    cols.add(stackIdx + 1, stateCol);
                } else {
                    cols.add(stateCol);
                }
            }
            return cols;
        }

        /** Derive the display name: the @Label from the type description, or the raw type name. */
        private static String typeDisplayName(StructType<?, ?> type) {
            String desc = type.getDescription();
            if (desc != null && !desc.isEmpty()) {
                try {
                    String label = BasicJFRWriter.parseEventDescription(desc).label();
                    if (label != null && !label.isEmpty()) return label;
                } catch (RuntimeException ignored) {
                }
            }
            return type.getName();
        }

        /**
         * Data-driven width computation: each column is first sized to its natural width (max of
         * header length and max formatted data value). When natural widths + separators leave
         * unused terminal space, flex columns expand proportionally to fill it. When all columns
         * are fixed and total < terminal, all columns expand to fill (oracle distributes remainder
         * round-robin). When total exceeds terminal, flex columns shrink. Matches oracle behavior.
         */
        List<Integer> computeColumnWidths(
                int termWidth, boolean userSetWidth, List<ReadStruct> events, int cellHeight) {
            // Simulate oracle's TableRenderer.setColumnWidths() exactly.
            // Oracle uses cell.width units (= content + 1 separator). We work in those units here,
            // then convert to content widths at the end.
            int MINIMAL = 2; // TableCell.MINIMAL_CELL_WIDTH = 1 + len(" ")
            int n = columns.size();

            // Compute preferredWidth per cell = max(data_content, header_len) + 1
            int[] preferred = new int[n]; // in cell.width units
            for (int i = 0; i < n; i++) {
                Column c = columns.get(i);
                int w = c.header().length();
                for (var ev : events) {
                    for (var line : c.format(ev, cellHeight)) {
                        w = Math.max(w, line.length());
                    }
                }
                preferred[i] = w + 1;
            }

            // determineTableWidth: if user set a width, use it; else sum of preferredWidths,
            // capped at 120, min 40 or 80 (oracle's determineTableWidth logic)
            int prefSum = 0;
            for (int p : preferred) prefSum += p;
            int tableWidth;
            if (userSetWidth) {
                tableWidth = termWidth;
            } else if (prefSum > 120) {
                tableWidth = 120;
            } else if (prefSum < 40 && n < 3) {
                tableWidth = 40;
            } else if (prefSum < 80) {
                tableWidth = 80;
            } else {
                tableWidth = prefSum;
            }

            // Simulate oracle's 4-pass distribute():
            // Each pass: while (amountLeft > 0 && amountLeft != lastAmountLeft):
            //   iterate ALL cells, give +1 to qualifying cells (no per-cell budget check)
            int[] widths = new int[n]; // starts at 0
            int[] rendererWidth = {0};

            java.util.function.IntPredicate[] passes = {
                i -> widths[i] < MINIMAL, // pass 1: fill to minimal
                i -> widths[i] < preferred[i], // pass 2: fill to preferred
                i -> !columns.get(i).isOracleFixedWidth(), // pass 3: fill non-fixed (String cols)
                i -> true // pass 4: fill all
            };
            for (var pred : passes) {
                long amountLeft = (long) tableWidth - rendererWidth[0];
                long lastAmountLeft = -1;
                while (amountLeft > 0 && amountLeft != lastAmountLeft) {
                    lastAmountLeft = amountLeft;
                    for (int i = 0; i < n; i++) {
                        if (pred.test(i)) {
                            widths[i]++;
                            rendererWidth[0]++;
                            amountLeft--;
                        }
                    }
                }
            }

            // Convert cell.widths to content widths
            int[] result = new int[n];
            for (int i = 0; i < n; i++) result[i] = Math.max(0, widths[i] - 1);
            return java.util.Arrays.stream(result).boxed().toList();
        }

        List<Integer> computeColumnWidths(int width) {
            // idea: distribute the remaining width evenly among columns with width=-1 up to their
            // maxWidth
            var remaining =
                    width - columns.stream().mapToInt(Column::width).filter(w -> w > -1).sum();
            var start = columns.stream().mapToInt(Column::width);
            var variableWidthColumns = columns.stream().filter(c -> c.width() == -1).toList();
            var variableWidthColumnsWithoutMaxWidth =
                    variableWidthColumns.stream().filter(c -> c.maxWidth() == -1).toList();
            if (variableWidthColumns.isEmpty()) {
                return start.boxed().toList();
            }
            var remainingPerColumn = remaining / variableWidthColumns.size();
            if (remainingPerColumn == 0) {
                return start.boxed().toList();
            }
            long remainingForColsWithoutMax = 0;
            if (!variableWidthColumnsWithoutMaxWidth.isEmpty()) {
                remainingForColsWithoutMax =
                        variableWidthColumns.stream()
                                        .filter(c -> c.maxWidth() != -1)
                                        .mapToLong(
                                                c -> {
                                                    if (c.maxWidth() < remainingPerColumn) {
                                                        return remainingPerColumn - c.maxWidth();
                                                    }
                                                    return 0;
                                                })
                                        .sum()
                                / variableWidthColumnsWithoutMaxWidth.size();
            }
            var rem = remainingForColsWithoutMax;
            return columns.stream()
                    .mapToLong(
                            c -> {
                                if (c.width() == -1) {
                                    if (c.maxWidth() == -1) {
                                        return remainingPerColumn + rem;
                                    }
                                    return Math.min(remainingPerColumn, c.maxWidth());
                                }
                                return c.width();
                            })
                    .mapToInt(l -> (int) l)
                    .boxed()
                    .toList();
        }
    }

    private final JFRViewConfig view;
    private final PrintConfig config;
    private final List<Integer> columnWidths;

    public JFRView(JFRViewConfig view, PrintConfig config) {
        this(view, config, List.of());
    }

    public JFRView(JFRViewConfig view, PrintConfig config, List<ReadStruct> events) {
        this.view = view;
        this.config = config;
        if (!events.isEmpty()) {
            this.columnWidths =
                    view.computeColumnWidths(
                            config.width(), config.widthIsUserSet(), events, config.cellHeight());
        } else {
            this.columnWidths = view.computeColumnWidths(config.width() - view.columns.size() + 1);
        }
    }

    public record PrintConfig(
            int width, boolean widthIsUserSet, int cellHeight, TruncateMode truncateMode) {
        public PrintConfig() {
            this(160, false, 1, TruncateMode.END);
        }

        public PrintConfig(int width, int cellHeight, TruncateMode truncateMode) {
            this(width, true, cellHeight, truncateMode);
        }
    }

    public List<String> header() {
        // print name centered
        var name = view.name;
        var headerLine = new StringBuilder();
        var sepLine = new StringBuilder();
        for (int i = 0; i < view.columns.size(); i++) {
            var column = view.columns.get(i);
            var width = columnWidths.get(i);
            if (width > 0) {
                var hdr = column.header();
                if (hdr.length() > width) {
                    hdr = truncate(hdr, width);
                }
                headerLine.append(pad(hdr, width, column.alignment()));
                sepLine.append("-".repeat(width));
            }
            if (i < view.columns.size() - 1) {
                headerLine.append(" ");
                sepLine.append(" ");
            }
        }
        var padding = Math.max(0, (headerLine.length() - name.length() + 1) / 2);
        List<String> header = new ArrayList<>(List.of("", " ".repeat(padding) + name, ""));
        header.add(headerLine.toString());
        header.add(sepLine.toString());
        return header;
    }

    public List<String> rows(ReadStruct struct) {
        List<String> rows = new ArrayList<>();
        List<List<String>> rowsPerColumn = new ArrayList<>();
        // Track which column indices have width > 0
        List<Integer> visibleColumnIndices = new ArrayList<>();
        for (int j = 0; j < view.columns.size(); j++) {
            var column = view.columns.get(j);
            var width = columnWidths.get(j);
            if (width > 0) {
                var rowsForColumn =
                        column.format(struct, Math.min(column.rows(struct), config.cellHeight));
                rowsPerColumn.add(rowsForColumn);
                visibleColumnIndices.add(j);
            }
        }
        int maxRows = rowsPerColumn.stream().mapToInt(List::size).max().orElse(0);
        for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
            StringBuilder row = new StringBuilder();
            for (int colIdx = 0; colIdx < visibleColumnIndices.size(); colIdx++) {
                int j = visibleColumnIndices.get(colIdx);
                var column = view.columns.get(j);
                var width = columnWidths.get(j);
                var rowsForColumn = rowsPerColumn.get(colIdx);
                var value = rowIndex < rowsForColumn.size() ? rowsForColumn.get(rowIndex) : "";
                // If the value doesn't fit, try the compact representation before hard truncation
                if (value.length() > width) {
                    value = column.compact(value);
                }
                row.append(pad(truncate(value, width), width, column.alignment()));
                if (colIdx < visibleColumnIndices.size() - 1) {
                    row.append(" ");
                }
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private String truncate(String s, int width) {
        if (width <= 0 || s.length() <= width) {
            return s;
        }
        // Mirror oracle: truncate with ellipsis (3 dots). If width < 3, just cut.
        String ellipsis = "...";
        if (width < ellipsis.length()) {
            return switch (config.truncateMode) {
                case BEGIN -> s.substring(s.length() - width);
                case END -> s.substring(0, width);
            };
        }
        return switch (config.truncateMode) {
            case BEGIN -> ellipsis + s.substring(s.length() - (width - ellipsis.length()));
            case END -> s.substring(0, width - ellipsis.length()) + ellipsis;
        };
    }

    private String pad(String s, int width, Alignment alignment) {
        if (s.length() >= width) {
            return s;
        }
        final String padding = " ".repeat(width - s.length());
        return switch (alignment) {
            case LEFT -> s + padding;
            case RIGHT -> padding + s;
        };
    }
}
