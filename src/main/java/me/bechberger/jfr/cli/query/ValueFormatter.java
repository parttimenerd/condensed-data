package me.bechberger.jfr.cli.query;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.cli.query.ViewQuery.FormatHint;

/**
 * Formats a raw cell value (produced by {@link QueryEvaluator}) into the display string used by JDK
 * {@code jfr view}. Formatting is chosen from the value's runtime type plus any {@code FORMAT}
 * hints, and — for correctness on empty/sentinel values — the {@code missing:} hint.
 *
 * <p>Numbers use {@link Locale#ROOT}: dot decimal and comma grouping (e.g. {@code 3,242}). This
 * mirrors {@code jfr view}'s locale-sensitive digit grouping structurally; only the separator
 * character differs from a non-ROOT oracle locale (e.g. German {@code 3.242}), which tests
 * normalize for.
 *
 * <p>Timespans and memory sizes use a 3-significant-figure rule with a space before the unit, which
 * mirrors {@code jfr view}'s {@code Timespan}/{@code DataAmount} renderers structurally.
 */
public final class ValueFormatter {

    private ValueFormatter() {}

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** Format {@code value} for a column, applying the column's FORMAT hint (may be null). */
    static String format(Object value, FormatHint hint) {
        return format(value, hint, ColumnType.Kind.PLAIN);
    }

    /**
     * Format {@code value} for a column, applying its FORMAT hint and content {@link
     * ColumnType.Kind}. The kind resolves raw {@code long}/{@code double} values whose meaning
     * isn't carried by their runtime type — memory (byte counts) and percentages.
     */
    static String format(Object value, FormatHint hint, ColumnType.Kind kind) {
        String missing = missingText(hint);
        if (value == null || isEmpty(value)) {
            // jfr's default replacement for a null/empty scalar cell is "N/A"; a FORMAT
            // "missing:" hint overrides it (missing:whitespace → blank, missing:X → literal X).
            return missing != null ? missing : "N/A";
        }
        if (kind == ColumnType.Kind.MEMORY && value instanceof Number n) {
            return formatMemory(n.longValue());
        }
        if (kind == ColumnType.Kind.PERCENTAGE && value instanceof Number n) {
            return formatPercentage(n.doubleValue());
        }
        if (kind == ColumnType.Kind.FREQUENCY && value instanceof Number n) {
            return n.longValue() + " Hz";
        }
        if (kind == ColumnType.Kind.BITRATE && value instanceof Number n) {
            return formatBitrate(n.longValue());
        }
        if (kind == ColumnType.Kind.ADDRESS && value instanceof Number n) {
            // jfr renders jdk.jfr.MemoryAddress as "0x" + uppercase hex, zero-padded to at least 8
            // digits (a null/zero address shows as 0x00000000, larger addresses print naturally).
            return String.format(Locale.ROOT, "0x%08X", n.longValue());
        }
        if (value instanceof Duration d) {
            return formatTimespan(d);
        }
        if (value instanceof Instant i) {
            return TIME_FMT.format(i.atZone(ZoneId.systemDefault()));
        }
        if (value instanceof ReadStruct s) {
            return formatStruct(s);
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(format(list.get(i), null));
            }
            return sb.toString();
        }
        if (value instanceof Double dv) {
            return formatDouble(dv);
        }
        if (value instanceof Float fv) {
            return formatDouble(fv.doubleValue());
        }
        if (value instanceof Number n) {
            return groupInteger(n.longValue());
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return value.toString();
    }

    private static boolean isEmpty(Object v) {
        return false;
    }

    /** The {@code missing:VALUE} replacement text for null/empty cells, or null if no such hint. */
    private static String missingText(FormatHint hint) {
        // Hints arrive flattened; the caller passes the per-column hint. "missing:whitespace" means
        // render blank, "missing:Unknown" means literal "Unknown", "missing:N/A" etc.
        if (hint == null) return null;
        if (!"missing".equals(hint.name())) return null;
        String v = hint.value();
        if (v == null) return null;
        return "whitespace".equals(v) ? "" : v;
    }

    // ── numeric ────────────────────────────────────────────────────────────

    /**
     * Render a whole number with {@code jfr view}'s digit grouping. jfr groups plain integer counts
     * with the locale thousands separator (e.g. {@code 3242 → 3.242} in German); we use {@link
     * Locale#ROOT} so the separator is a comma ({@code 3,242}), matching jfr's structure with the
     * separator character being the only (documented) locale difference.
     */
    private static String groupInteger(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String formatDouble(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return groupInteger((long) v);
        }
        // jfr uses 4 significant figures with trailing-zero stripping for raw double fields
        // (e.g. JVM flag values like InitialRAMPercentage=1.5625 → "1.562", SweeperThreshold=0.5 →
        // "0.5")
        String s = String.format(Locale.ROOT, "%.4g", v);
        if (s.contains(".") && !s.contains("e") && !s.contains("E")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    // ── timespan (3 significant figures, space before unit) ───────────────────

    public static String formatTimespan(Duration d) {
        long nanos = d.toNanos();
        // jfr treats the extreme sentinels as "unset": Long.MIN nanos → N/A, Long.MAX → Indefinite.
        // The reader reduces to millisecond precision, so the stored value is within ~1ms of the
        // raw extreme rather than exactly it; match anything within that tolerance (no real
        // timespan
        // approaches ±292 years).
        if (nanos <= Long.MIN_VALUE + 1_000_000L) return "N/A";
        if (nanos >= Long.MAX_VALUE - 1_000_000L) return "Indefinite";
        if (nanos == 0) return "0 s";
        boolean neg = nanos < 0;
        long abs = Math.abs(nanos);
        String s = formatTimespanAbs(abs);
        return neg ? "-" + s : s;
    }

    private static String formatTimespanAbs(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds >= 3600) {
            long totalSec = Math.round(seconds);
            long h = totalSec / 3600;
            long m = (totalSec % 3600) / 60;
            return h + " h " + m + " m";
        }
        if (seconds >= 60) {
            long totalSec = Math.round(seconds);
            long m = totalSec / 60;
            long sec = totalSec % 60;
            return m + " m " + sec + " s";
        }
        if (nanos >= 1_000_000_000L) {
            return threeSigFigs(seconds) + " s";
        }
        // jfr's timespan renderer keeps sub-second values in milliseconds (with more significant
        // decimals) rather than switching to microseconds/nanoseconds.
        return threeSigFigs(nanos / 1_000_000.0) + " ms";
    }

    // ── memory (fixed 1 decimal above bytes, binary units, space before unit) ─

    public static String formatMemory(long bytes) {
        boolean neg = bytes < 0;
        long abs = Math.abs(bytes);
        String[] units = {"bytes", "kB", "MB", "GB", "TB", "PB"};
        double value = abs;
        int u = 0;
        while (value >= 1024 && u < units.length - 1) {
            value /= 1024;
            u++;
        }
        // jfr renders raw bytes as an integer count, and any larger unit with exactly one decimal.
        String num =
                u == 0 ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
        String s = num + " " + units[u];
        return neg ? "-" + s : s;
    }

    // ── bit rate (DataAmount(BITS)+Frequency: binary scaling, "bps" suffix) ────

    static String formatBitrate(long bits) {
        boolean neg = bits < 0;
        long abs = Math.abs(bits);
        String[] units = {"bps", "kbps", "Mbps", "Gbps", "Tbps", "Pbps"};
        double value = abs;
        int u = 0;
        while (value >= 1024 && u < units.length - 1) {
            value /= 1024;
            u++;
        }
        // Like memory: raw bits as an integer count, larger units with exactly one decimal.
        String num =
                u == 0 ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
        String s = num + " " + units[u];
        return neg ? "-" + s : s;
    }

    static String formatPercentage(double fraction) {
        // jfr renders percentages with exactly two decimals and no space before the '%'.
        double pct = fraction * 100.0;
        return String.format(Locale.ROOT, "%.2f", pct) + "%";
    }

    /** Round to 3 significant figures, ROOT locale, trailing-zero-preserving like jfr. */
    static String threeSigFigs(double v) {
        if (v == 0) return "0";
        double abs = Math.abs(v);
        int intDigits = (int) Math.floor(Math.log10(abs)) + 1;
        // jfr caps the decimal places at 6, so a value below 1e-3 shows fewer than 3 significant
        // figures rather than growing the decimal count: 83ns → 0.000083 ms (6 decimals, 2 sig
        // figs),
        // not 0.0000830 ms. Verified across all views: jfr never emits more than 6 decimals for a
        // ms
        // value. The cap is a no-op for values ≥ 1e-3 (decimals ≤ 5) and for seconds/counts.
        int decimals = Math.min(6, Math.max(0, 3 - intDigits));
        // Rounding can push the value across a power-of-10 boundary (0.9999 -> 1.00): recompute the
        // integer-digit count from the rounded magnitude so the decimal places match jfr.
        double rounded =
                Math.abs(Double.parseDouble(String.format(Locale.ROOT, "%." + decimals + "f", v)));
        if (rounded != 0) {
            int roundedIntDigits = (int) Math.floor(Math.log10(rounded)) + 1;
            if (roundedIntDigits > intDigits) {
                decimals = Math.min(6, Math.max(0, 3 - roundedIntDigits));
            }
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    // ── structs (class, method, thread, stacktrace) ──────────────────────────

    private static String formatStruct(ReadStruct s) {
        String typeName = s.getType().getName();
        // StackFrame: render as its method's full signature (jfr shows the top frame's method).
        if (typeName.endsWith(".StackFrame")) {
            ReadStruct method = s.getStruct("method");
            return method != null ? formatMethod(method) : s.toString();
        }
        // StackTrace selected directly (not via .topFrame): jfr renders it as the top frame's
        // method
        // signature (e.g. thread-start's Stack Trace column). Empty/absent frames leave the cell
        // blank
        // so a "missing:" FORMAT hint (N/A) can fill it, matching jfr's N/A for traceless events.
        if (typeName.endsWith(".StackTrace")) {
            if (s.hasField("frames")
                    && s.get("frames") instanceof List<?> frames
                    && !frames.isEmpty()
                    && frames.get(0) instanceof ReadStruct top) {
                ReadStruct method = top.hasField("method") ? top.getStruct("method") : null;
                return method != null ? formatMethod(method) : "";
            }
            return "";
        }
        // Thread
        if (s.hasField("javaName") || s.hasField("osName")) {
            Object jn = s.hasField("javaName") ? s.get("javaName") : null;
            if (jn != null && !jn.toString().isEmpty()) return jn.toString();
            Object on = s.hasField("osName") ? s.get("osName") : null;
            return on != null ? on.toString() : "";
        }
        // Class: package + name
        if (typeName.endsWith(".Class") || typeName.equals("java.lang.Class")) {
            return className(s);
        }
        // ClassLoader: jfr renders it as the loader's class (its "type" Class), NOT its "name"
        // field
        // (e.g. name="app" still displays as jdk.internal.loader.ClassLoaders$AppClassLoader). A
        // null
        // type leaves the cell empty so a "missing:" FORMAT hint (e.g. null-bootstrap) can replace
        // it.
        if (typeName.endsWith(".ClassLoader")) {
            ReadStruct type = s.getStruct("type");
            return type != null ? className(type) : "";
        }
        // Method: class + "." + name + parameter signature (jfr always shows the parameter types).
        if (typeName.endsWith(".Method")) {
            return formatMethod(s);
        }
        // Fallback: a "name" field if present, else toString
        if (s.hasField("name")) {
            Object n = s.get("name");
            if (n != null) return n.toString();
        }
        return s.toString();
    }

    /**
     * Format a Method struct as {@code fqcn.name(SimpleParamType, ...)}, mirroring {@code jfr
     * view}.
     */
    private static String formatMethod(ReadStruct method) {
        ReadStruct type = method.getStruct("type");
        String cls = type != null ? className(type) : "";
        Object name = method.get("name");
        Object descriptor = method.hasField("descriptor") ? method.get("descriptor") : null;
        String params = descriptor != null ? decodeParams(descriptor.toString()) : "";
        return cls + "." + (name != null ? name : "") + "(" + params + ")";
    }

    /**
     * Decode a JVM method descriptor's parameter list into jfr's display form: comma-separated
     * <em>simple</em> type names (last dotted component; arrays keep {@code []}). Only the portion
     * between the parentheses is read; the return type is ignored.
     */
    static String decodeParams(String descriptor) {
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
                base = simpleName(fqcn);
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

    /** The simple (unqualified) name: the substring after the last {@code .}. */
    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static String className(ReadStruct classStruct) {
        Object name = classStruct.get("name");
        return name != null ? decodeClassName(name.toString()) : "";
    }

    /** Decode JVM internal class descriptors (e.g. {@code [I} → {@code int[]}). */
    static String decodeClassName(String raw) {
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
}
