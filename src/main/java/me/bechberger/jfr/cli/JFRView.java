package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.formatMemory;
import static me.bechberger.util.TimeUtil.formatDuration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
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
            return Math.max(10, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var raw = event.get(property);
            if (raw == null) {
                return List.of("-");
            }
            Duration val;
            if (raw instanceof Duration d) {
                val = d;
            } else if (raw instanceof Long nanos) {
                val = Duration.ofNanos(nanos);
            } else {
                return List.of(raw.toString());
            }
            return List.of(formatDuration(val));
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
                return List.of("-");
            }
            Instant value;
            if (raw instanceof Instant inst) {
                value = inst;
            } else if (raw instanceof Long nanos) {
                value = Instant.ofEpochSecond(0, nanos);
            } else {
                return List.of(raw.toString());
            }
            return List.of(
                    formatter.format(LocalDateTime.ofInstant(value, ZoneId.systemDefault())));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
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
        public List<String> format(ReadStruct event, int rows) {
            var value = event.getStruct(property);
            if (value == null) {
                return List.of("-");
            }
            var name = value.get("javaName", String.class);
            if (name == null) {
                name = value.get("osName", String.class);
            }
            return List.of(name != null ? name : "-");
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
                return List.of("-");
            }
            var value = prop instanceof Number ? ((Number) prop).longValue() : (long) prop;
            return List.of(formatMemory(value, 1, 2, unit));
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
                return List.of("-");
            }
            long value = prop instanceof Number ? ((Number) prop).longValue() : (long) prop;
            return List.of("0x" + Long.toHexString(value));
        }

        @Override
        public Alignment alignment() {
            return Alignment.RIGHT;
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
        public List<String> format(ReadStruct event, int rows) {
            var val = event.get(property, String.class);
            return List.of(val != null ? val : "-");
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
            return List.of(val != null ? String.valueOf(val) : "-");
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
                return List.of("-");
            }
            if (val instanceof Number n && n.longValue() == Integer.MIN_VALUE) {
                return List.of("N/A");
            }
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
                return List.of("-");
            }
            return List.of(String.format(java.util.Locale.ROOT, "%." + precision + "f", val));
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
            return List.of(val != null ? (val ? "true" : "false") : "-");
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
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
                return List.of("-");
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
                return List.of("-");
            }
            long hz = val instanceof Long ? (long) val : ((Number) val).longValue();
            if (hz >= 1_000_000_000L) {
                return List.of(
                        String.format(java.util.Locale.ROOT, "%.2f GHz", hz / 1_000_000_000.0));
            } else if (hz >= 1_000_000L) {
                return List.of(String.format(java.util.Locale.ROOT, "%.2f MHz", hz / 1_000_000.0));
            } else if (hz >= 1_000L) {
                return List.of(String.format(java.util.Locale.ROOT, "%.2f kHz", hz / 1_000.0));
            }
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
                return List.of("-");
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
        public List<String> format(ReadStruct event, int rows) {
            var klass = event.getStruct(property);
            if (klass == null) {
                return List.of("-");
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
            var klassName = rawName != null ? decodeClassName(rawName) : "-";
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
        public List<String> format(ReadStruct event, int rows) {
            var cl = event.getStruct(property);
            if (cl == null) {
                return List.of("-");
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
            return List.of(name != null && !name.isEmpty() ? name : "-");
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
        public List<String> format(ReadStruct event, int rows) {
            var method = event.getStruct(property);
            if (method == null) {
                return List.of("-");
            }
            var methodName = method.get("name", String.class);
            return List.of(
                    CLASS_COLUMN.format(method, 1).get(0)
                            + "."
                            + (methodName != null ? methodName : "?"));
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
                return List.of("-");
            }
            var frames = val.<ReadStruct>getList("frames");
            return frames.stream()
                    .map(
                            f ->
                                    METHOD_COLUMN.format(f, 1).get(0)
                                            + (f.hasField("lineNumber")
                                                    ? (":" + f.get("lineNumber"))
                                                    : ""))
                    .limit(rows)
                    .toList();
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
            if (avDepth <= 0 || !(type instanceof StructType<?, ?> structType)) {
                return new Column() {
                    @Override
                    public String header() {
                        return propertyToHeader(property);
                    }

                    @Override
                    public int width() {
                        return -1;
                    }

                    @Override
                    public List<String> format(ReadStruct event, int rows) {
                        var val = event.get(property);
                        if (val == null) {
                            return List.of("-");
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
                return List.of("-");
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
        return fieldToColumn(field, 2);
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

    private static Column fieldToColumn(Field<?, ?, ?> field, int avDepth) {
        var typeName = field.type().getName();
        // @MemoryAddress renders as a hex address regardless of the underlying numeric type.
        if (hasAnnotation(field, "jdk.jfr.MemoryAddress")) {
            return new JFRView.MemoryAddressColumn(field.name());
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
            return new JFRView.DataRateColumn(field.name(), unit);
        }
        // Check for fields where @Unsigned shadows @Timespan or @Timestamp in the type name
        if (typeName.equals("jdk.jfr.Unsigned")) {
            if (hasAnnotation(field, "jdk.jfr.Timespan")) {
                return new JFRView.DurationColumn(field.name());
            }
            if (hasAnnotation(field, "jdk.jfr.Timestamp")) {
                return new JFRView.InstantColumn(field.name());
            }
        }
        return switch (typeName) {
            case "millis", "nanos", "tickspan", "microseconds", "timespan" ->
                    new JFRView.DurationColumn(field.name());
            case "timestamp" -> new JFRView.InstantColumn(field.name());
            case "bytes", "memory BYTES", "memory varint BYTES", "jdk.jfr.DataAmount" ->
                    new JFRView.MemoryColumn(field.name(), MemoryUtil.MemoryUnit.BYTES);
            case "memory BITS", "memory varint BITS" ->
                    new JFRView.MemoryColumn(field.name(), MemoryUtil.MemoryUnit.BITS);
            case "java.lang.String" -> new JFRView.StringColumn(field.name());
            case "int", "jdk.jfr.Unsigned", "uint1", "uint2", "int1" -> {
                // Some int fields use Integer.MIN_VALUE as a "not applicable" sentinel, e.g.
                // OldObjectSample.arrayElements ("... or minimum value for the type int if it is
                // not an array"). Render the sentinel as N/A instead of -2147483648.
                var desc = field.description();
                if (desc != null && desc.contains("minimum value for the type int")) {
                    yield new JFRView.SentinelIntegerColumn(field.name(), 10);
                }
                yield new JFRView.IntegerColumn(field.name(), 10);
            }
            case "long" -> {
                // Fallback for fields that should be timestamp/duration but lost their
                // type info due to missing annotations in the original JFR file (Bug 168)
                if (field.name().equals("startTime")) {
                    yield new JFRView.InstantColumn(field.name());
                }
                if (field.name().equals("duration")) {
                    yield new JFRView.DurationColumn(field.name());
                }
                // Fallback for @DataAmount fields that lost their annotation (Bug 212)
                if (isLikelyDataAmountField(field.name())) {
                    yield new JFRView.MemoryColumn(field.name(), MemoryUtil.MemoryUnit.BYTES);
                }
                yield new JFRView.IntegerColumn(field.name(), 20);
            }
            case "float", "double" -> new JFRView.FloatColumn(field.name(), 10, 2);
            case "boolean" -> new JFRView.BooleanColumn(field.name());
            case "jdk.jfr.Percentage", "percentage" -> new JFRView.PercentageColumn(field.name());
            case "jdk.jfr.Frequency" -> new JFRView.FrequencyColumn(field.name());
            case "jdk.types.Class", "java.lang.Class" -> new JFRView.ClassColumn(field.name());
            case "jdk.types.ClassLoader" -> new JFRView.ClassLoaderColumn(field.name());
            case "jdk.types.Method" -> new JFRView.MethodColumn(field.name());
            case "jdk.types.StackTrace" -> new JFRView.StackTraceColumn(field.name());
            case "java.lang.Thread" -> new JFRView.ThreadColumn(field.name());
            default -> {
                if (field.type() instanceof StructType<?, ?>) {
                    // Known struct types are handled via StructColumn
                    yield StructColumn.of(field.name(), field.type(), avDepth - 1);
                }
                if (warnedTypes.add(typeName)) {
                    System.err.println("Warning: potentially unknown type: " + typeName);
                }
                yield StructColumn.of(field.name(), field.type(), avDepth - 1);
            }
        };
    }

    public record JFRViewConfig(String name, List<Column> columns) {
        public JFRViewConfig(StructType<?, ?> type) {
            this(type.getName(), type.getFields().stream().map(JFRView::fieldToColumn).toList());
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
        this.view = view;
        this.config = config;
        this.columnWidths = view.computeColumnWidths(config.width() - view.columns.size() + 1);
    }

    public record PrintConfig(int width, int cellHeight, TruncateMode truncateMode) {
        public PrintConfig() {
            this(160, 1, TruncateMode.END);
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
                    hdr = hdr.substring(0, width);
                }
                headerLine.append(hdr);
                headerLine.append(" ".repeat(width - hdr.length()));
                sepLine.append("-".repeat(width));
            }
            if (i < view.columns.size() - 1) {
                headerLine.append(" ");
                sepLine.append(" ");
            }
        }
        var padding = Math.max(0, (headerLine.length() - name.length()) / 2);
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
        return switch (config.truncateMode) {
            case BEGIN -> s.substring(s.length() - width);
            case END -> s.substring(0, width);
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
