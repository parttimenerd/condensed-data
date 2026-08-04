package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
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
import me.bechberger.condensed.types.VarIntType;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFROrJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.query.ValueFormatter;
import org.jetbrains.annotations.Nullable;

/** Prints events from one or more .cjfr (or .jfr) files in the same format as {@code jfr print}. */
@Command(
        name = "print",
        description = "Print events from a .cjfr (or .jfr) file in jfr-print format",
        mixinStandardHelpOptions = true)
public class PrintCommand implements Callable<Integer> {

    // Oracle's jfr print --json trims fractional seconds at 3-digit (ms/µs/ns) boundaries:
    // 0 trailing µs+ns → 3 digits (ms); 0 trailing ns → 6 digits (µs); else 9 digits (ns).
    // Java's ISO_OFFSET_DATE_TIME strips individual trailing zeros, giving wrong digit counts.
    private static final DateTimeFormatter JSON_TS_0 =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm")
                    .appendOffsetId()
                    .toFormatter();
    private static final DateTimeFormatter JSON_TS_3 =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 3, 3, true)
                    .appendOffsetId()
                    .toFormatter();
    private static final DateTimeFormatter JSON_TS_6 =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
                    .appendOffsetId()
                    .toFormatter();
    private static final DateTimeFormatter JSON_TS_9 =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, true)
                    .appendOffsetId()
                    .toFormatter();

    private static DateTimeFormatter jsonTimestampFmt(Instant instant) {
        int nano = instant.getNano();
        // Epoch zero (1970-01-01T00:00:00Z) renders without :ss.mmm — oracle omits the zero
        // seconds for this sentinel value (used as "no timeout" in ThreadPark.until etc.).
        if (nano == 0 && instant.getEpochSecond() == 0) return JSON_TS_0;
        if (nano % 1_000_000 == 0) return JSON_TS_3;
        if (nano % 1_000 == 0) return JSON_TS_6;
        return JSON_TS_9;
    }

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
            description = "Number of frames in stack traces (default: 5)",
            defaultValue = "5")
    private int stackDepth;

    @Option(
            names = {"--json"},
            description = "Print recording in JSON format")
    private boolean json;

    @Option(
            names = {"--xml"},
            description = "Print recording in XML format")
    private boolean xml;

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
            } else if (xml) {
                printXml(reader, filterPatterns, categoryPatterns);
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

    /** Reads all events matching the filters, sorted chronologically by startTime. */
    private List<ReadStruct> readSorted(
            CombiningJFRReader reader,
            List<Pattern> filterPatterns,
            List<Pattern> categoryPatterns) {
        List<ReadStruct> events = new ArrayList<>();
        ReadStruct event;
        while ((event = reader.readNextEvent()) != null) {
            if (!matchesFilter(filterPatterns, event.getType().getName())) continue;
            if (!matchesCategories(categoryPatterns, event.getType())) continue;
            events.add(event);
        }
        events.sort(
                (a, b) -> {
                    Object ta = a.get("startTime");
                    Object tb = b.get("startTime");
                    if (ta instanceof Instant ia && tb instanceof Instant ib)
                        return ia.compareTo(ib);
                    return 0;
                });
        return events;
    }

    private void printText(
            CombiningJFRReader reader,
            List<Pattern> filterPatterns,
            List<Pattern> categoryPatterns) {
        Set<String> seen = filterPatterns != null ? new HashSet<>() : null;
        for (ReadStruct event : readSorted(reader, filterPatterns, categoryPatterns)) {
            if (seen != null) seen.add(event.getType().getName());
            printTextEvent(event);
        }
        // Seed seen with all known type names (including 0-count types) so that we don't
        // falsely warn about filters that match types that exist but have no events.
        if (seen != null) seen.addAll(reader.getAllKnownTypeNames());
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
            } else if (value instanceof ReadStruct rs
                    && rs.getType().getName().endsWith("ClassLoader")) {
                // ClassLoader standalone fields include the pool id: "TypeName (id = N)"
                Integer poolId = event.getPoolId(field.name());
                System.out.println(
                        "  " + field.name() + " = " + formatClassLoaderStandalone(rs, poolId));
            } else {
                System.out.println("  " + field.name() + " = " + formatValue(value, field));
            }
        }
        for (StructType.Field<Object, ?, ?> field : tail) {
            Object value = event.get(field.name());
            if (shouldSuppressField(value, field)) continue;
            // ExecutionSample/NativeMethodSample: state field dropped at condense (always
            // STATE_RUNNABLE); inject it before stackTrace to match oracle output.
            if (field.name().equals("stackTrace")) {
                String typeName = event.getType().getName();
                if ((typeName.equals("jdk.ExecutionSample")
                                || typeName.equals("jdk.NativeMethodSample"))
                        && !event.hasField("state")) {
                    System.out.println("  state = \"STATE_RUNNABLE\"");
                }
            }
            System.out.println("  " + field.name() + " = " + formatValue(value, field));
        }
        System.out.println("}");
        System.out.println();
    }

    /**
     * Returns true for fields that oracle {@code jfr print} omits: null stackTrace, null
     * eventThread, zero event duration (but NOT zero domain duration fields like
     * lastMarkingDuration).
     */
    private static boolean shouldSuppressField(Object value, StructType.Field<?, ?, ?> field) {
        if (value == null && isStackTrace(field)) return true;
        if (value == null && "eventThread".equals(field.name())) return true;
        // Only suppress zero duration for the event-level "duration" field, not domain fields
        if (value instanceof Duration d && d.isZero() && "duration".equals(field.name()))
            return true;
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

    // ── XML output ───────────────────────────────────────────────────────────

    private static final String XML_NS = "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";

    private void printXml(
            CombiningJFRReader reader,
            List<Pattern> filterPatterns,
            List<Pattern> categoryPatterns) {
        System.out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        System.out.println("<recording " + XML_NS + ">");
        System.out.println("  <events>");
        for (ReadStruct event : readSorted(reader, filterPatterns, categoryPatterns)) {
            printXmlEvent(event, "    ");
        }
        System.out.println("  </events>");
        System.out.println("</recording>");
    }

    private void printXmlEvent(ReadStruct event, String indent) {
        System.out.println(indent + "<event type=\"" + event.getType().getName() + "\">");
        @SuppressWarnings("unchecked")
        List<StructType.Field<Object, ?, ?>> fields =
                (List<StructType.Field<Object, ?, ?>>) (List<?>) event.getType().getFields();
        // XML uses natural declaration order (unlike text which puts eventThread/stackTrace last).
        String childIndent = indent + "  ";
        for (StructType.Field<Object, ?, ?> f : fields) {
            printXmlField(event.get(f.name()), f.name(), f.type(), childIndent);
        }
        // ExecutionSample/NativeMethodSample: state field dropped at condense (always
        // STATE_RUNNABLE); inject it after stackTrace to match oracle XML output.
        String typeName = event.getType().getName();
        if ((typeName.equals("jdk.ExecutionSample") || typeName.equals("jdk.NativeMethodSample"))
                && !event.hasField("state")) {
            System.out.println(childIndent + "<value name=\"state\">STATE_RUNNABLE</value>");
        }
        System.out.println(indent + "</event>");
        System.out.println();
    }

    private void printXmlField(
            Object value, String name, CondensedType<?, ?> fieldType, String indent) {
        if (value == null && fieldType instanceof StructType<?, ?>) {
            System.out.println(indent + "<struct name=\"" + name + "\" xsi:nil=\"true\"/>");
            return;
        }
        if (value == null) {
            System.out.println(indent + "<value name=\"" + name + "\" xsi:nil=\"true\"/>");
            return;
        }
        if (value instanceof ReadStruct struct) {
            if (struct.getType() == null) {
                System.out.println(indent + "<struct name=\"" + name + "\" xsi:nil=\"true\"/>");
            } else {
                System.out.println(indent + "<struct name=\"" + name + "\">");
                printXmlStructFields(struct, indent + "  ");
                System.out.println(indent + "</struct>");
            }
        } else if (value instanceof ReadList<?> list) {
            printXmlArray(list, name, indent);
        } else if (value instanceof List<?> list) {
            printXmlArray(list, name, indent);
        } else {
            System.out.println(
                    indent
                            + "<value name=\""
                            + name
                            + "\">"
                            + xmlValue(value, fieldType)
                            + "</value>");
        }
    }

    private void printXmlStructFields(ReadStruct struct, String indent) {
        @SuppressWarnings("unchecked")
        List<StructType.Field<Object, ?, ?>> fields =
                (List<StructType.Field<Object, ?, ?>>) (List<?>) struct.getType().getFields();
        for (StructType.Field<Object, ?, ?> f : fields) {
            printXmlField(struct.get(f.name()), f.name(), f.type(), indent);
        }
    }

    private void printXmlArray(List<?> list, String name, String indent) {
        int size = list.size();
        int limit = stackDepth >= 0 ? Math.min(stackDepth, size) : size;
        System.out.println(indent + "<array name=\"" + name + "\" size=\"" + size + "\">");
        for (int i = 0; i < limit; i++) {
            Object item = list.get(i);
            if (item instanceof ReadStruct struct) {
                System.out.println(indent + "  <struct index=\"" + i + "\">");
                printXmlStructFields(struct, indent + "    ");
                System.out.println(indent + "  </struct>");
            } else {
                System.out.println(
                        indent
                                + "  <value index=\""
                                + i
                                + "\">"
                                + xmlValue(item, null)
                                + "</value>");
            }
        }
        System.out.println(indent + "</array>");
    }

    private String xmlValue(Object value, @Nullable CondensedType<?, ?> fieldType) {
        if (value == null) return "";
        if (value instanceof Instant instant) {
            try {
                return jsonTimestampFmt(instant)
                        .format(instant.atZone(ZoneId.systemDefault()))
                        .toString();
            } catch (DateTimeException e) {
                if (instant.compareTo(Instant.EPOCH) < 0) return "-999999999-01-01T00:00+18:00";
                return "+999999999-12-31T23:59:59.999999999-18:00";
            }
        }
        if (value instanceof Duration d) {
            return d.toString();
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Float f) {
            return Float.isFinite(f) ? String.valueOf(f) : "";
        }
        if (value instanceof Double d) {
            return Double.isFinite(d) ? String.valueOf(d) : "";
        }
        if (fieldType instanceof VarIntType vit && !vit.isSigned() && value instanceof Long l) {
            return Long.toUnsignedString(l);
        }
        if (value instanceof Number n) {
            return String.valueOf(n.longValue());
        }
        if (value instanceof String s) {
            return xmlEscape(s);
        }
        return xmlEscape(value.toString());
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ── JSON output ──────────────────────────────────────────────────────────

    private void printJson(
            CombiningJFRReader reader,
            List<Pattern> filterPatterns,
            List<Pattern> categoryPatterns) {
        System.out.println("{");
        System.out.println("  \"recording\": {");
        System.out.print("    \"events\": [");
        boolean first = true;
        for (ReadStruct event : readSorted(reader, filterPatterns, categoryPatterns)) {
            if (first) {
                // First event: attach { directly to [ on same line
                System.out.print("{");
            } else {
                // Subsequent events: separate with ", " then {
                System.out.print(", {");
            }
            first = false;
            printJsonEvent(event, "    ");
        }
        System.out.println("]");
        System.out.println("  }");
        System.out.print("}");
    }

    private void printJsonEvent(ReadStruct event, String indent) {
        System.out.print("\n" + indent + "  \"type\": \"" + event.getType().getName() + "\", ");
        System.out.print("\n" + indent + "  \"values\": {");
        @SuppressWarnings("unchecked")
        List<StructType.Field<Object, ?, ?>> fields =
                (List<StructType.Field<Object, ?, ?>>) (List<?>) event.getType().getFields();
        // ExecutionSample/NativeMethodSample: state field dropped at condense; inject after
        // stackTrace
        // (oracle JSON puts state after stackTrace, unlike text which puts it before)
        boolean needsStateInject =
                (event.getType().getName().equals("jdk.ExecutionSample")
                                || event.getType().getName().equals("jdk.NativeMethodSample"))
                        && !event.hasField("state");
        int totalFields = fields.size() + (needsStateInject ? 1 : 0);
        int written = 0;
        for (int i = 0; i < fields.size(); i++) {
            StructType.Field<?, ?, ?> field = fields.get(i);
            Object value = event.get(field.name());
            System.out.print(
                    "\n"
                            + indent
                            + "    \""
                            + field.name()
                            + "\": "
                            + toJson(value, indent + "    ", field.type()));
            written++;
            if (written < totalFields) System.out.print(", ");
            if (needsStateInject && field.name().equals("stackTrace")) {
                System.out.print("\n" + indent + "    \"state\": \"STATE_RUNNABLE\"");
                written++;
                if (written < totalFields) System.out.print(", ");
            }
        }
        System.out.print("\n" + indent + "  }");
        System.out.print("\n" + indent + "}");
    }

    private String toJson(Object value, String indent) {
        return toJson(value, indent, null);
    }

    private String toJson(Object value, String indent, @Nullable CondensedType<?, ?> fieldType) {
        if (value == null) return "null";
        if (value instanceof Instant instant) {
            try {
                return "\""
                        + jsonTimestampFmt(instant).format(instant.atZone(ZoneId.systemDefault()))
                        + "\"";
            } catch (java.time.DateTimeException e) {
                // Instant.MIN/MAX are outside LocalDate range; oracle uses the minimum/maximum
                // representable Java local date-time at the extreme UTC offsets, formatted without
                // zero seconds (oracle omits :00 seconds).
                if (instant.compareTo(Instant.EPOCH) < 0) {
                    return "\"-999999999-01-01T00:00+18:00\"";
                }
                return "\"+999999999-12-31T23:59:59.999999999-18:00\"";
            }
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
            // Float must use floatValue() to preserve float32 precision (e.g. 0.45 not
            // 0.44999998...)
            if (value instanceof Float) {
                float fv = n.floatValue();
                return Float.isFinite(fv) ? String.valueOf(fv) : "null";
            }
            if (value instanceof Double) {
                double dv = n.doubleValue();
                return Double.isFinite(dv) ? String.valueOf(dv) : "null";
            }
            // Unsigned long fields: Java stores as signed; render as unsigned to match oracle
            if (fieldType instanceof VarIntType vit && !vit.isSigned() && value instanceof Long l) {
                return Long.toUnsignedString(l);
            }
            // Memory DataAmount fields use signed VarInt internally but are @Unsigned in JFR.
            // Long.MIN_VALUE is the "not set" sentinel; oracle emits it as the unsigned value
            // 9223372036854775808 (= 2^63), not as a negative number.
            if (value instanceof Long l
                    && l == Long.MIN_VALUE
                    && fieldType instanceof VarIntType vit
                    && vit.getName().startsWith("memory varint")) {
                return Long.toUnsignedString(l);
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
                    .append(toJson(val, inner, field.type()));
            if (i < fields.size() - 1) sb.append(", ");
        }
        sb.append("\n").append(indent).append("}");
        return sb.toString();
    }

    private String listToJson(List<?> list, String indent) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int limit = stackDepth >= 0 ? Math.min(stackDepth, list.size()) : list.size();
        for (int i = 0; i < limit; i++) {
            if (i == 0) {
                sb.append(toJson(list.get(i), indent));
            } else {
                sb.append(", ").append(toJson(list.get(i), indent));
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("/", "\\/")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── text value formatting ─────────────────────────────────────────────────

    private String formatValue(Object value, StructType.Field<?, ?, ?> field) {
        return formatValue(value, field, "  ");
    }

    private String formatValue(Object value, StructType.Field<?, ?, ?> field, String indent) {
        if (value == null) return "N/A";
        String desc = field != null ? field.description() : null;

        if (value instanceof Instant instant) {
            // Epoch-millis sentinel Long.MIN_VALUE (e.g. ThreadPark.until "no timeout") maps to
            // an Instant far outside the DateTimeFormatter range; treat as N/A.
            if (instant.getEpochSecond() <= Instant.MIN.getEpochSecond() + 1
                    || instant.getEpochSecond() >= Instant.MAX.getEpochSecond() - 1) {
                return "N/A";
            }
            DateTimeFormatter fmt = exact ? TIMESTAMP_EXACT_FMT : TIMESTAMP_FMT;
            try {
                return fmt.format(instant.atZone(ZoneId.systemDefault()));
            } catch (DateTimeException e) {
                return "N/A";
            }
        }
        if (value instanceof Duration duration) {
            return formatDuration(duration);
        }
        if (value instanceof ReadStruct struct) {
            return formatStruct(struct, indent);
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
            // Oracle zero-pads to at least 8 hex digits (e.g. 0x00000000 for null address)
            return String.format(Locale.ROOT, "0x%08X", addr);
        }
        if (desc != null
                && desc.contains("jdk.jfr.DataAmount")
                && desc.contains("jdk.jfr.Frequency")
                && value instanceof Number n) {
            // Combined @DataAmount + @Frequency = data rate (byte/s or bit/s)
            long v = (long) n.doubleValue();
            boolean bits = desc.contains("BITS");
            if (exact) {
                if (bits) return v + (v >= -1 && v <= 1 ? " bit/s" : " bits/s");
                return v + (v >= -1 && v <= 1 ? " byte/s" : " bytes/s");
            }
            if (bits) {
                return ValueFormatter.formatBitrate(v);
            }
            String mem = ValueFormatter.formatMemory(v);
            // oracle uses "byte/s" (singular) when value stays at byte level; "kB/s", "MB/s" etc.
            // otherwise
            if (mem.endsWith(" bytes") || mem.endsWith(" byte")) {
                return mem.replaceAll(" bytes?$", " byte") + "/s";
            }
            return mem + "/s";
        }
        if (desc != null && desc.contains("jdk.jfr.DataAmount")) {
            long v = ((Number) value).longValue();
            // Long.MIN_VALUE is the "not set" sentinel for unsigned DataAmount fields (e.g.
            // YoungGenerationConfiguration.maxSize when ZGC has no young generation limit).
            if (v == Long.MIN_VALUE) return "N/A";
            if (exact) {
                return v + (v >= -1 && v <= 1 ? " byte" : " bytes");
            }
            return ValueFormatter.formatMemory(v);
        }
        if (desc != null && desc.contains("jdk.jfr.Percentage") && value instanceof Number n) {
            if (exact) {
                return String.format(Locale.ROOT, "%.9f%%", n.doubleValue() * 100.0);
            }
            return String.format(Locale.ROOT, "%.2f%%", n.doubleValue() * 100.0);
        }
        if (desc != null && desc.contains("jdk.jfr.Frequency") && value instanceof Number n) {
            // Frequency fields can be float (e.g. ThreadContextSwitchRate.switchRate).
            // Oracle renders with the decimal part if present; use float string for floats.
            if (value instanceof Float f) {
                float fv = f;
                if (fv == Math.rint(fv)) return (long) fv + " Hz";
                return fv + " Hz";
            }
            if (value instanceof Double d) {
                double dv = d;
                if (dv == Math.rint(dv)) return (long) dv + " Hz";
                return dv + " Hz";
            }
            return n.longValue() + " Hz";
        }
        // @Unsigned long fields: Java stores as signed -1 but should render as unsigned max
        if (field != null
                && field.type() instanceof VarIntType vit
                && !vit.isSigned()
                && value instanceof Long l) {
            return Long.toUnsignedString(l);
        }
        return value.toString();
    }

    private static boolean isStackTrace(StructType.Field<?, ?, ?> field) {
        if (field == null) return false;
        String desc = field.description();
        return desc != null && desc.contains("jdk.types.StackTrace");
    }

    private String formatDuration(Duration d) {
        // Duration.ofSeconds(Long.MAX/MIN_VALUE,...) are the "Forever"/"N/A" sentinels.
        // These cannot be passed to toNanos() (overflow). Oracle preserves sentinel strings even
        // in --exact mode. The sentinel check must use only getSeconds(), never toNanos().
        boolean isForever = d.getSeconds() == Long.MAX_VALUE;
        boolean isNA = d.getSeconds() == Long.MIN_VALUE;
        if (isForever) return "Forever";
        if (isNA) return "N/A";
        if (exact) {
            // toNanos() overflows for durations with abs(seconds) ~9.2e9; compose via getSeconds().
            return String.format(Locale.ROOT, "%d.%09d s", d.getSeconds(), d.getNano());
        }
        return ValueFormatter.formatTimespan(d);
    }

    private String formatStruct(ReadStruct s) {
        return formatStruct(s, "  ");
    }

    private String formatStruct(ReadStruct s, String indent) {
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
            return formatClassLoaderStandalone(s, null);
        }

        // Generic struct: render as nested block with depth-aware indentation.
        // indent is the current field indent (e.g. "  " at top level, "    " one level in).
        String fieldIndent = indent + "  ";
        String closeIndent = indent;
        StringBuilder sb = new StringBuilder("{\n");
        for (StructType.Field<?, ?, ?> field : s.getType().getFields()) {
            Object val = s.get(field.name());
            String rendered;
            if (val instanceof ReadStruct rs && rs.getType().getName().endsWith("ClassLoader")) {
                rendered = formatClassLoaderStandalone(rs, s.getPoolId(field.name()));
            } else {
                rendered = formatValue(val, field, fieldIndent);
            }
            sb.append(fieldIndent).append(field.name()).append(" = ").append(rendered).append("\n");
        }
        sb.append(closeIndent).append("}");
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
        if (!cls.hasField("classLoader")) return className;
        ReadStruct loader = cls.getStruct("classLoader");
        if (loader != null) {
            return className + " (classLoader = " + formatClassLoader(loader) + ")";
        }
        // null classLoader struct (anonymous/hidden class with no named loader) → oracle shows
        // "null"
        return className + " (classLoader = null)";
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

    /**
     * Format a ClassLoader struct as a standalone field value (not inline within a Class field).
     * Oracle shows the loader's type class name here, not the "name" field. Bootstrap loader (null
     * type) renders as "null" to match oracle. When poolId is non-null, appends " (id = N)".
     */
    private String formatClassLoaderStandalone(ReadStruct loader, Integer poolId) {
        ReadStruct type = loader.hasField("type") ? loader.getStruct("type") : null;
        String base;
        if (type != null) {
            Object typeName = type.get("name");
            if (typeName != null && !typeName.toString().isEmpty()) {
                base = decodeClassName(typeName.toString());
            } else {
                base = "null";
            }
        } else {
            // Null type = bootstrap loader; oracle prints "null" in struct context
            base = "null";
        }
        return (poolId != null && !"null".equals(base)) ? base + " (id = " + poolId + ")" : base;
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

        if (frames.isEmpty()) return "[\n  ]";

        boolean truncated = false;
        if (stackTrace.hasField("truncated")) {
            Object t = stackTrace.get("truncated");
            truncated = t instanceof Boolean b && b;
        }

        // Mirror oracle's PrettyWriter.printStackTrace() loop semantics:
        // - iterate all frames (including hidden), tracking total index i and visible count
        // - skip hidden/non-java frames without printing but still increment i
        // - stop when visible count >= maxDepth (maxDepth=0: loop body never executes, i stays 0)
        // - show "..." only when isTruncated OR i == maxDepth after the loop
        //   (i == maxDepth means the first maxDepth total slots were all non-hidden;
        //    when maxDepth=0, i=0=maxDepth so "..." always shown — matches oracle behavior)
        // maxDepth < 0 means unlimited (show all frames, no "..." from depth limit)
        StringBuilder sb = new StringBuilder("[\n");
        int visibleCount = 0;
        int i = 0;
        for (; i < frames.size(); i++) {
            if (maxDepth >= 0 && visibleCount >= maxDepth) break;
            Object frameObj = frames.get(i);
            if (frameObj instanceof ReadStruct frame) {
                if (isHiddenFrame(frame)) continue;
                sb.append("    ").append(formatStackFrame(frame)).append("\n");
                visibleCount++;
            }
        }
        boolean showEllipsis = truncated || (maxDepth >= 0 && i == maxDepth);
        if (showEllipsis) {
            sb.append("    ...\n");
        }
        sb.append("  ]");
        return sb.toString();
    }

    private static boolean isHiddenFrame(ReadStruct frame) {
        ReadStruct method = frame.hasField("method") ? frame.getStruct("method") : null;
        if (method == null) return false;
        Object hidden = method.hasField("hidden") ? method.get("hidden") : null;
        return hidden instanceof Boolean b && b;
    }

    private String formatListItems(List<?> list, boolean isStackTraceFrames) {
        if (list.isEmpty()) return "[]";
        int limit =
                (!isStackTraceFrames && stackDepth >= 0)
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
