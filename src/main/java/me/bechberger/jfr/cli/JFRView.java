package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.formatMemory;
import static me.bechberger.util.TimeUtil.formatDuration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.util.MemoryUtil;

/** Tabular view for JFR events */
public class JFRView {

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
            var val = event.get(property, Duration.class);
            if (val == null) {
                return List.of("-");
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
            var value = event.get(property, Instant.class);
            if (value == null) {
                return List.of("-");
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
            return List.of(name);
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
            var value = prop instanceof Long ? (long) prop : (long) (float) prop;
            return List.of(formatMemory((long) value, 1));
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
            return List.of(event.get(property, String.class));
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
            return List.of(String.valueOf(event.get(property)));
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
            return List.of(String.format("%." + precision + "f", event.get(property)));
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
            return List.of(event.get(property, Boolean.class) ? "true" : "false");
        }

        @Override
        public Alignment alignment() {
            return Alignment.LEFT;
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
            var pkg = event.get("package", String.class);
            var klassName = klass.get("name", String.class).replace('/', '.');
            if (pkg == null) {
                return List.of(klassName);
            } else {
                return List.of(pkg + "." + klassName);
            }
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
            return Math.max(10, header.length());
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var cl = event.getStruct(property);
            if (cl == null) {
                return List.of("-");
            }
            return List.of(cl.get("name", String.class));
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
            return List.of(
                    CLASS_COLUMN.format(method, 1).get(0) + "." + method.get("name", String.class));
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
                    .map(f -> METHOD_COLUMN.format(f, 1).get(0) + (f.hasField("lineNumber") ? (":" + f.get("lineNumber")) : ""))
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
            if (avDepth == 0 || !(type instanceof StructType<?, ?> structType)) {
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
            return event.getStruct(property).size();
        }

        @Override
        public List<String> format(ReadStruct event, int rows) {
            var struct = event.getStruct(property);
            if (struct == null) {
                return List.of("-");
            }
            if (rows == 1) {
                return parts.get(0).format(struct, rows);
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

    private static Column fieldToColumn(Field<?, ?, ?> field, int avDepth) {
        var typeName = field.type().getName();
        return switch (typeName) {
            case "millis", "nanos", "tickspan", "microseconds", "timespan" ->
                    new JFRView.DurationColumn(field.name());
            case "timestamp" -> new JFRView.InstantColumn(field.name());
            case "bytes", "memory BYTES", "memory varint BYTES", "jdk.jfr.DataAmount" ->
                    new JFRView.MemoryColumn(field.name(), MemoryUtil.MemoryUnit.BYTES);
            case "memory BITS", "memory varint BITS" ->
                    new JFRView.MemoryColumn(field.name(), MemoryUtil.MemoryUnit.BITS);
            case "java.lang.String" -> new JFRView.StringColumn(field.name());
            case "int", "jdk.jfr.Unsigned" -> new JFRView.IntegerColumn(field.name(), 10);
            case "float" -> new JFRView.FloatColumn(field.name(), 10, 2);
            case "boolean" -> new JFRView.BooleanColumn(field.name());
            case "jdk.types.Class" -> new JFRView.ClassColumn(field.name());
            case "jdk.types.ClassLoader" -> new JFRView.ClassLoaderColumn(field.name());
            case "jdk.types.Method" -> new JFRView.MethodColumn(field.name());
            case "jdk.types.StackTrace" -> new JFRView.StackTraceColumn(field.name());
            case "java.lang.Thread" -> new JFRView.ThreadColumn(field.name());
            default -> {
                System.err.println("Warning: potentially unknown type: " + typeName);
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
                headerLine.append(column.header());
                headerLine.append(" ".repeat(width - column.header().length()));
                sepLine.append("-".repeat(width));
            }
            if (i < view.columns.size() - 1) {
                headerLine.append(" ");
                sepLine.append(" ");
            }
        }
        var padding = (headerLine.length() - name.length()) / 2;
        List<String> header = new ArrayList<>(List.of("", " ".repeat(padding) + name, ""));
        header.add(headerLine.toString());
        header.add(sepLine.toString());
        return header;
    }

    public List<String> rows(ReadStruct struct) {
        List<String> rows = new ArrayList<>();
        List<List<String>> rowsPerColumn = new ArrayList<>();
        for (int j = 0; j < view.columns.size(); j++) {
            var column = view.columns.get(j);
            var width = columnWidths.get(j);
            if (width > 0) {
                var rowsForColumn =
                        column.format(struct, Math.min(column.rows(struct), config.cellHeight));
                rowsPerColumn.add(rowsForColumn);
            }
        }
        int maxRows = rowsPerColumn.stream().mapToInt(List::size).max().orElse(0);
        for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
            StringBuilder row = new StringBuilder();
            for (int j = 0; j < view.columns.size(); j++) {
                var column = view.columns.get(j);
                var width = columnWidths.get(j);
                var rowsForColumn = rowsPerColumn.get(j);
                var value = rowIndex < rowsForColumn.size() ? rowsForColumn.get(rowIndex) : "";
                row.append(pad(truncate(value, width), width, column.alignment()));
                if (j < view.columns.size() - 1) {
                    row.append(" ");
                }
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private String truncate(String s, int width) {
        if (s.length() <= width) {
            return s;
        }
        return switch (config.truncateMode) {
            case BEGIN -> s.substring(0, width);
            case END -> s.substring(s.length() - width);
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