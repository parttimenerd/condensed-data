package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFROrJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.query.ValueFormatter;

/** Prints events from one or more .cjfr (or .jfr) files in the same format as {@code jfr print}. */
@Command(
        name = "print",
        description = "Print events from a .cjfr (or .jfr) file in jfr-print format",
        mixinStandardHelpOptions = true)
public class PrintCommand implements Callable<Integer> {

    @Parameters(
            description = "The input .cjfr or .jfr files, can be folders or zips",
            arity = "1..*",
            converter = ExistingCJFROrJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Option(
            names = {"--events"},
            description =
                    "Select events matching an event name. Comma-separated list of names and/or"
                            + " glob patterns (e.g. 'jdk.*')",
            split = ",")
    private List<String> eventFilter;

    @Option(
            names = {"--stack-depth"},
            description = "Number of frames in stack traces (default: all)",
            defaultValue = "-1")
    private int stackDepth;

    @Option(
            names = {"--json"},
            description = "Print recording in JSON format")
    private boolean json;

    @Option(
            names = {"--categories"},
            description =
                    "Select events matching a category name. Comma-separated list of names and/or"
                            + " glob patterns (e.g. 'GC,Profiling'). Matches any segment of the"
                            + " event's category path.",
            split = ",")
    private List<String> categoryFilter;

    @Option(
            names = {"--exact"},
            description =
                    "Print numbers and timestamps with full precision (nanosecond timestamps,"
                            + " raw byte counts, full-precision floats)")
    private boolean exact;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS (yyyy-MM-dd)", Locale.ROOT);
    private static final DateTimeFormatter TIMESTAMP_EXACT_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS (yyyy-MM-dd)", Locale.ROOT);

    @Override
    public Integer call() {
        List<Pattern> filterPatterns = buildFilterPatterns();
        List<Pattern> categoryPatterns = buildCategoryPatterns();
        var reader = CombiningJFRReader.fromPaths(inputFiles);
        try {
            if (json) {
                printJson(reader, filterPatterns, categoryPatterns);
            } else {
                printText(reader, filterPatterns, categoryPatterns);
            }
        } catch (Exception e) {
            return CLIUtils.printError(e);
        }
        return 0;
    }

    private List<Pattern> buildCategoryPatterns() {
        if (categoryFilter == null || categoryFilter.isEmpty()) return null;
        List<Pattern> patterns = new ArrayList<>();
        for (String f : categoryFilter) {
            patterns.add(globToPattern(f.trim()));
        }
        return patterns;
    }

    /**
     * Extracts category path segments from the type description JSON. Description format: [label,
     * desc, [[annotationName, [args...]], ...]] The jdk.jfr.Category annotation has one arg: a list
     * of path segments.
     */
    static List<String> extractCategories(CondensedType<?, ?> type) {
        String desc = type.getDescription();
        if (desc == null || !desc.contains("jdk.jfr.Category")) return List.of();
        // Find "jdk.jfr.Category" and extract the following array
        int catIdx = desc.indexOf("\"jdk.jfr.Category\"");
        if (catIdx < 0) return List.of();
        // After "jdk.jfr.Category", expect: ,[[seg1,seg2,...]]
        int bracketStart = desc.indexOf("[[", catIdx);
        if (bracketStart < 0) return List.of();
        int bracketEnd = desc.indexOf("]]", bracketStart);
        if (bracketEnd < 0) return List.of();
        String inner = desc.substring(bracketStart + 2, bracketEnd);
        // inner is like: "Java Virtual Machine","GC","Phases"
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (i < inner.length()) {
            if (inner.charAt(i) == '"') {
                int end = inner.indexOf('"', i + 1);
                if (end < 0) break;
                segments.add(inner.substring(i + 1, end));
                i = end + 1;
            } else {
                i++;
            }
        }
        return segments;
    }

    private boolean matchesCategories(List<Pattern> categoryPatterns, CondensedType<?, ?> type) {
        if (categoryPatterns == null) return true;
        List<String> segments = extractCategories(type);
        if (segments.isEmpty()) return false;
        for (Pattern p : categoryPatterns) {
            for (String seg : segments) {
                if (p.matcher(seg).matches()) return true;
            }
        }
        return false;
    }

    private List<Pattern> buildFilterPatterns() {
        if (eventFilter == null || eventFilter.isEmpty()) return null;
        List<Pattern> patterns = new ArrayList<>();
        for (String f : eventFilter) {
            // Convert glob (* and ?) to regex; also allow bare simple name match (e.g. "CPULoad"
            // matches "jdk.CPULoad")
            patterns.add(globToPattern(f.trim()));
        }
        return patterns;
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("(?i)");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                case '.' -> sb.append("\\.");
                case '$', '^', '(', ')', '{', '}', '[', ']', '|', '+', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return Pattern.compile(sb.toString());
    }

    private boolean matchesFilter(List<Pattern> patterns, String typeName) {
        if (patterns == null) return true;
        String simpleName =
                typeName.contains(".")
                        ? typeName.substring(typeName.lastIndexOf('.') + 1)
                        : typeName;
        for (Pattern p : patterns) {
            if (p.matcher(typeName).matches() || p.matcher(simpleName).matches()) return true;
        }
        return false;
    }

    // ── text output ──────────────────────────────────────────────────────────

    private void printText(
            CombiningJFRReader reader,
            List<Pattern> filterPatterns,
            List<Pattern> categoryPatterns) {
        Set<String> seen = filterPatterns != null ? new HashSet<>() : null;
        ReadStruct event;
        while ((event = reader.readNextEvent()) != null) {
            String typeName = event.getType().getName();
            if (!matchesFilter(filterPatterns, typeName)) continue;
            if (!matchesCategories(categoryPatterns, event.getType())) continue;
            if (seen != null) seen.add(typeName);
            printTextEvent(event);
        }
        if (seen != null) warnUnknownFilters(filterPatterns, seen);
    }

    private void printTextEvent(ReadStruct event) {
        System.out.println(event.getType().getName() + " {");
        @SuppressWarnings("unchecked")
        List<StructType.Field<Object, ?, ?>> fields =
                (List<StructType.Field<Object, ?, ?>>) (List<?>) event.getType().getFields();
        // Oracle jfr print order: startTime/duration first, then domain fields, then
        // eventThread/stackTrace last — mirror that ordering here.
        List<StructType.Field<Object, ?, ?>> meta = new ArrayList<>();
        List<StructType.Field<Object, ?, ?>> domain = new ArrayList<>();
        List<StructType.Field<Object, ?, ?>> tail = new ArrayList<>();
        for (StructType.Field<Object, ?, ?> f : fields) {
            String n = f.name();
            if (n.equals("startTime") || n.equals("duration")) meta.add(f);
            else if (n.equals("eventThread") || n.equals("stackTrace")) tail.add(f);
            else domain.add(f);
        }
        for (StructType.Field<Object, ?, ?> field : meta) {
            Object value = event.get(field.name());
            if (shouldSuppressField(value, field)) continue;
            System.out.println("  " + field.name() + " = " + formatValue(value, field));
        }
        for (StructType.Field<Object, ?, ?> field : domain) {
            Object value = event.get(field.name());
            if (shouldSuppressField(value, field)) continue;
            if (field.name().equals("object")
                    && value instanceof ReadStruct rs
                    && rs.hasField("type")
                    && rs.hasField("description")) {
                // OldObject special rendering: "  object =  [\n    ClassName [desc/size]\n  ]"
                Object ae = event.hasField("arrayElements") ? event.get("arrayElements") : null;
                long arrayLen = ae instanceof Number n ? n.longValue() : Integer.MIN_VALUE;
                System.out.println("  object =  [");
                System.out.println("    " + formatOldObject(rs, arrayLen));
                System.out.println("  ]");
            } else {
                System.out.println("  " + field.name() + " = " + formatValue(value, field));
            }
        }
        for (StructType.Field<Object, ?, ?> field : tail) {
            Object value = event.get(field.name());
            if (shouldSuppressField(value, field)) continue;
            System.out.println("  " + field.name() + " = " + formatValue(value, field));
        }
        System.out.println("}");
        System.out.println();
    }

    /**
     * Returns true for fields that oracle {@code jfr print} omits: null stackTrace, zero duration.
     */
    private static boolean shouldSuppressField(Object value, StructType.Field<?, ?, ?> field) {
        if (value == null && isStackTrace(field)) return true;
        if (value instanceof Duration d && d.isZero()) return true;
        return false;
    }

    private void warnUnknownFilters(List<Pattern> filterPatterns, Set<String> seen) {
        if (eventFilter == null) return;
        for (int i = 0; i < eventFilter.size(); i++) {
            String f = eventFilter.get(i).trim();
            Pattern p = filterPatterns.get(i);
            boolean matched =
                    seen.stream()
                            .anyMatch(
                                    t -> {
                                        String simple =
                                                t.contains(".")
                                                        ? t.substring(t.lastIndexOf('.') + 1)
                                                        : t;
                                        return p.matcher(t).matches()
                                                || p.matcher(simple).matches();
                                    });
            if (!matched) {
                System.err.println("Warning: No events found matching filter: " + f);
            }
        }
    }

    // ── JSON output ──────────────────────────────────────────────────────────

    private void printJson(
            CombiningJFRReader reader,
            List<Pattern> filterPatterns,
            List<Pattern> categoryPatterns) {
        System.out.println("{");
        System.out.println("  \"recording\": {");
        System.out.println("    \"events\": [");
        boolean first = true;
        ReadStruct event;
        while ((event = reader.readNextEvent()) != null) {
            if (!matchesFilter(filterPatterns, event.getType().getName())) continue;
            if (!matchesCategories(categoryPatterns, event.getType())) continue;
            if (!first) System.out.println(",");
            first = false;
            printJsonEvent(event, "    ");
        }
        if (!first) System.out.println();
        System.out.println("    ]");
        System.out.println("  }");
        System.out.println("}");
    }

    private void printJsonEvent(ReadStruct event, String indent) {
        System.out.print(indent + "{");
        System.out.print("\n" + indent + "  \"type\": \"" + event.getType().getName() + "\", ");
        System.out.print("\n" + indent + "  \"values\": {");
        @SuppressWarnings("unchecked")
        List<StructType.Field<Object, ?, ?>> fields =
                (List<StructType.Field<Object, ?, ?>>) (List<?>) event.getType().getFields();
        for (int i = 0; i < fields.size(); i++) {
            StructType.Field<?, ?, ?> field = fields.get(i);
            Object value = event.get(field.name());
            System.out.print(
                    "\n"
                            + indent
                            + "    \""
                            + field.name()
                            + "\": "
                            + toJson(value, indent + "    "));
            if (i < fields.size() - 1) System.out.print(", ");
        }
        System.out.print("\n" + indent + "  }");
        System.out.print("\n" + indent + "}");
    }

    private String toJson(Object value, String indent) {
        if (value == null) return "null";
        if (value instanceof Instant instant) {
            return "\""
                    + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                            instant.atZone(ZoneId.systemDefault()))
                    + "\"";
        }
        if (value instanceof Duration d) {
            // jfr renders durations as ISO-8601 duration strings in JSON
            return "\"" + d + "\"";
        }
        if (value instanceof ReadStruct struct) {
            return structToJson(struct, indent);
        }
        if (value instanceof ReadList<?> list) {
            return listToJson(list, indent);
        }
        if (value instanceof List<?> list) {
            return listToJson(list, indent);
        }
        if (value instanceof String s) {
            return "\"" + jsonEscape(s) + "\"";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n) {
            // render integers without decimal point, doubles as-is
            if (value instanceof Double || value instanceof Float) {
                return String.valueOf(n.doubleValue());
            }
            return String.valueOf(n.longValue());
        }
        return "\"" + jsonEscape(value.toString()) + "\"";
    }

    private String structToJson(ReadStruct s, String indent) {
        String inner = indent + "  ";
        StringBuilder sb = new StringBuilder("{");
        @SuppressWarnings("unchecked")
        List<StructType.Field<Object, ?, ?>> fields =
                (List<StructType.Field<Object, ?, ?>>) (List<?>) s.getType().getFields();
        for (int i = 0; i < fields.size(); i++) {
            StructType.Field<?, ?, ?> field = fields.get(i);
            Object val = s.get(field.name());
            sb.append("\n")
                    .append(inner)
                    .append("\"")
                    .append(field.name())
                    .append("\": ")
                    .append(toJson(val, inner));
            if (i < fields.size() - 1) sb.append(", ");
        }
        sb.append("\n").append(indent).append("}");
        return sb.toString();
    }

    private String listToJson(List<?> list, String indent) {
        if (list.isEmpty()) return "[]";
        String inner = indent + "  ";
        StringBuilder sb = new StringBuilder("[");
        int limit = stackDepth > 0 ? Math.min(stackDepth, list.size()) : list.size();
        for (int i = 0; i < limit; i++) {
            sb.append("\n").append(inner).append(toJson(list.get(i), inner));
            if (i < list.size() - 1) sb.append(", ");
        }
        sb.append("\n").append(indent).append("]");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── text value formatting ─────────────────────────────────────────────────

    private String formatValue(Object value, StructType.Field<?, ?, ?> field) {
        if (value == null) return "N/A";
        String desc = field != null ? field.description() : null;

        if (value instanceof Instant instant) {
            DateTimeFormatter fmt = exact ? TIMESTAMP_EXACT_FMT : TIMESTAMP_FMT;
            return fmt.format(instant.atZone(ZoneId.systemDefault()));
        }
        if (value instanceof Duration duration) {
            return formatDuration(duration);
        }
        if (value instanceof ReadStruct struct) {
            return formatStruct(struct);
        }
        if (value instanceof ReadList<?> list) {
            return formatListItems(list, isStackTrace(field));
        }
        if (value instanceof List<?> list) {
            return formatListItems(list, isStackTrace(field));
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n
                && n.longValue() == Integer.MIN_VALUE
                && desc != null
                && desc.contains("minimum value for the type int")) {
            return "N/A";
        }
        if (desc != null && desc.contains("jdk.jfr.MemoryAddress")) {
            long addr = ((Number) value).longValue();
            return String.format(Locale.ROOT, "0x%X", addr);
        }
        if (desc != null && desc.contains("jdk.jfr.DataAmount")) {
            if (exact) {
                return ((Number) value).longValue() + " bytes";
            }
            return ValueFormatter.formatMemory(((Number) value).longValue());
        }
        if (desc != null && desc.contains("jdk.jfr.Percentage") && value instanceof Number n) {
            if (exact) {
                return String.format(Locale.ROOT, "%.9f%%", n.doubleValue() * 100.0);
            }
            return String.format(Locale.ROOT, "%.2f%%", n.doubleValue() * 100.0);
        }
        if (desc != null && desc.contains("jdk.jfr.Frequency") && value instanceof Number n) {
            return n.longValue() + " Hz";
        }
        return value.toString();
    }

    private static boolean isStackTrace(StructType.Field<?, ?, ?> field) {
        if (field == null) return false;
        String desc = field.description();
        return desc != null && desc.contains("jdk.types.StackTrace");
    }

    private String formatDuration(Duration d) {
        long nanos = d.toNanos();
        if (nanos >= Long.MAX_VALUE - 1_000_000L) return "Forever";
        if (nanos <= Long.MIN_VALUE + 1_000_000L) return "N/A";
        if (exact) {
            // Oracle --exact: raw seconds as decimal with nanosecond precision
            double secs = nanos / 1_000_000_000.0;
            return String.format(Locale.ROOT, "%.9f s", secs);
        }
        return ValueFormatter.formatTimespan(d);
    }

    private String formatStruct(ReadStruct s) {
        String typeName = s.getType().getName();

        if (typeName.endsWith("Thread") || s.hasField("javaName") || s.hasField("osName")) {
            return formatThread(s);
        }
        if (typeName.endsWith("StackTrace")) {
            return formatStackTrace(s, stackDepth);
        }
        if (typeName.endsWith("StackFrame")) {
            return formatStackFrame(s);
        }
        if (typeName.endsWith(".Class") || typeName.equals("java.lang.Class")) {
            return formatClass(s);
        }
        if (typeName.endsWith("Method")) {
            return formatMethod(s);
        }
        if (typeName.endsWith("ClassLoader")) {
            return formatClassLoader(s);
        }

        // Generic struct: render as nested block
        StringBuilder sb = new StringBuilder("{\n");
        for (StructType.Field<?, ?, ?> field : s.getType().getFields()) {
            Object val = s.get(field.name());
            sb.append("    ")
                    .append(field.name())
                    .append(" = ")
                    .append(formatValue(val, field))
                    .append("\n");
        }
        sb.append("  }");
        return sb.toString();
    }

    private String formatOldObject(ReadStruct obj, long arrayElements) {
        ReadStruct type = obj.hasField("type") ? obj.getStruct("type") : null;
        String className = "";
        if (type != null) {
            Object typeName = type.get("name");
            className = typeName != null ? decodeClassName(typeName.toString()) : "";
        }
        if (arrayElements != Integer.MIN_VALUE && arrayElements > 0) {
            // For array types (e.g. "byte[]"), replace trailing [] with [N]: byte[N]
            if (className.endsWith("[]")) {
                return className.substring(0, className.length() - 2) + "[" + arrayElements + "]";
            }
            return className + "[" + arrayElements + "]";
        }
        Object desc = obj.get("description");
        if (desc != null && !desc.toString().isEmpty()) {
            return className + " " + desc;
        }
        return className;
    }

    private String formatThread(ReadStruct thread) {
        Object javaName = thread.hasField("javaName") ? thread.get("javaName") : null;
        Object osName = thread.hasField("osName") ? thread.get("osName") : null;
        String name =
                javaName != null && !javaName.toString().isEmpty()
                        ? javaName.toString()
                        : (osName != null ? osName.toString() : "");

        // Prefer javaThreadId when > 0 (GC threads have javaThreadId=0 but valid osThreadId)
        if (thread.hasField("javaThreadId")) {
            Object tid = thread.get("javaThreadId");
            if (tid instanceof Number n && n.longValue() > 0) {
                return "\"" + name + "\" (javaThreadId = " + n.longValue() + ")";
            }
        }
        if (thread.hasField("osThreadId")) {
            Object tid = thread.get("osThreadId");
            if (tid instanceof Number n && n.longValue() > 0) {
                return "\"" + name + "\" (osThreadId = " + n.longValue() + ")";
            }
        }
        // javaThreadId=0 with no valid osThreadId — show javaThreadId=0
        if (thread.hasField("javaThreadId")) {
            Object tid = thread.get("javaThreadId");
            if (tid != null) {
                return "\"" + name + "\" (javaThreadId = " + tid + ")";
            }
        }
        return "\"" + name + "\"";
    }

    private String formatClass(ReadStruct cls) {
        Object name = cls.get("name");
        String className = name != null ? decodeClassName(name.toString()) : "N/A";
        ReadStruct loader = cls.hasField("classLoader") ? cls.getStruct("classLoader") : null;
        if (loader != null) {
            return className + " (classLoader = " + formatClassLoader(loader) + ")";
        }
        return className;
    }

    private String formatClassLoader(ReadStruct loader) {
        // Use the loader's own name field when available (e.g. "app", "bootstrap")
        if (loader.hasField("name")) {
            Object loaderName = loader.get("name");
            if (loaderName != null && !loaderName.toString().isEmpty()) {
                return loaderName.toString();
            }
        }
        ReadStruct type = loader.hasField("type") ? loader.getStruct("type") : null;
        if (type == null) return "bootstrap";
        Object typeName = type.get("name");
        if (typeName == null || typeName.toString().isEmpty()) return "bootstrap";
        return decodeClassName(typeName.toString());
    }

    private String formatMethod(ReadStruct method) {
        ReadStruct type = method.hasField("type") ? method.getStruct("type") : null;
        // oracle jfr print omits classLoader in method fields (class name only, no "(classLoader =
        // x)")
        String cls = "";
        if (type != null) {
            Object typeName = type.get("name");
            cls = typeName != null ? decodeClassName(typeName.toString()) : "";
        }
        Object name = method.get("name");
        Object descriptor = method.hasField("descriptor") ? method.get("descriptor") : null;
        String params = descriptor != null ? decodeParams(descriptor.toString()) : "";
        return cls + "." + (name != null ? name : "") + "(" + params + ")";
    }

    private String formatStackFrame(ReadStruct frame) {
        ReadStruct method = frame.hasField("method") ? frame.getStruct("method") : null;
        String methodStr = method != null ? formatMethodForFrame(method) : "unknown";
        Object lineNumber = frame.hasField("lineNumber") ? frame.get("lineNumber") : null;
        if (lineNumber != null && ((Number) lineNumber).intValue() > 0) {
            return methodStr + " line: " + lineNumber;
        }
        return methodStr;
    }

    private String formatMethodForFrame(ReadStruct method) {
        ReadStruct type = method.hasField("type") ? method.getStruct("type") : null;
        String cls = "";
        if (type != null) {
            Object name = type.get("name");
            cls = name != null ? decodeClassName(name.toString()) : "";
        }
        Object name = method.get("name");
        Object descriptor = method.hasField("descriptor") ? method.get("descriptor") : null;
        String params = descriptor != null ? decodeParams(descriptor.toString()) : "";
        return cls + "." + (name != null ? name : "") + "(" + params + ")";
    }

    private String formatStackTrace(ReadStruct stackTrace, int maxDepth) {
        Object framesObj = stackTrace.hasField("frames") ? stackTrace.get("frames") : null;
        if (framesObj == null) return "[]";

        List<?> frames;
        if (framesObj instanceof ReadList<?> rl) {
            frames = rl;
        } else if (framesObj instanceof List<?> l) {
            frames = l;
        } else {
            return "[]";
        }

        if (frames.isEmpty()) return "[]";

        boolean truncated = false;
        if (stackTrace.hasField("truncated")) {
            Object t = stackTrace.get("truncated");
            truncated = t instanceof Boolean b && b;
        }

        int limit = maxDepth > 0 ? Math.min(maxDepth, frames.size()) : frames.size();
        boolean showEllipsis = truncated || (maxDepth > 0 && frames.size() > maxDepth);

        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < limit; i++) {
            Object frameObj = frames.get(i);
            if (frameObj instanceof ReadStruct frame) {
                sb.append("    ").append(formatStackFrame(frame)).append("\n");
            }
        }
        if (showEllipsis) {
            sb.append("    ...\n");
        }
        sb.append("  ]");
        return sb.toString();
    }

    private String formatListItems(List<?> list, boolean isStackTraceFrames) {
        if (list.isEmpty()) return "[]";
        int limit =
                (!isStackTraceFrames && stackDepth > 0)
                        ? Math.min(stackDepth, list.size())
                        : list.size();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatValue(list.get(i), null));
        }
        if (limit < list.size()) sb.append(", ...");
        sb.append("]");
        return sb.toString();
    }

    // ── JVM class/method descriptor helpers ──────────────────────────────────

    private static String decodeClassName(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        int dims = 0;
        int i = 0;
        while (i < raw.length() && raw.charAt(i) == '[') {
            dims++;
            i++;
        }
        String base;
        if (dims == 0) {
            base = raw.replace('/', '.');
        } else {
            char c = raw.charAt(i);
            base =
                    switch (c) {
                        case 'I' -> "int";
                        case 'J' -> "long";
                        case 'Z' -> "boolean";
                        case 'B' -> "byte";
                        case 'C' -> "char";
                        case 'S' -> "short";
                        case 'F' -> "float";
                        case 'D' -> "double";
                        case 'L' -> raw.substring(i + 1, raw.length() - 1).replace('/', '.');
                        default -> raw.substring(i).replace('/', '.');
                    };
        }
        return base + "[]".repeat(dims);
    }

    private static String decodeParams(String descriptor) {
        int open = descriptor.indexOf('(');
        int close = descriptor.indexOf(')');
        if (open < 0 || close < 0 || close < open) return "";
        String args = descriptor.substring(open + 1, close);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < args.length()) {
            int dims = 0;
            while (i < args.length() && args.charAt(i) == '[') {
                dims++;
                i++;
            }
            if (i >= args.length()) break;
            char c = args.charAt(i);
            String base;
            if (c == 'L') {
                int semi = args.indexOf(';', i);
                if (semi < 0) semi = args.length();
                String fqcn = args.substring(i + 1, semi).replace('/', '.');
                int dot = fqcn.lastIndexOf('.');
                base = dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
                i = semi + 1;
            } else {
                base =
                        switch (c) {
                            case 'I' -> "int";
                            case 'J' -> "long";
                            case 'Z' -> "boolean";
                            case 'B' -> "byte";
                            case 'C' -> "char";
                            case 'S' -> "short";
                            case 'F' -> "float";
                            case 'D' -> "double";
                            case 'V' -> "void";
                            default -> String.valueOf(c);
                        };
                i++;
            }
            if (out.length() > 0) out.append(", ");
            out.append(base).append("[]".repeat(dims));
        }
        return out.toString();
    }
}
