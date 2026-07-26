package me.bechberger.jfr.cli.query;

import java.util.List;
import java.util.Map;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.cli.query.ViewQuery.Aggregate;
import me.bechberger.jfr.cli.query.ViewQuery.Coalesce;
import me.bechberger.jfr.cli.query.ViewQuery.Expr;
import me.bechberger.jfr.cli.query.ViewQuery.FieldPath;
import me.bechberger.jfr.cli.query.ViewQuery.FromItem;
import me.bechberger.jfr.cli.query.ViewQuery.SelectItem;

/**
 * Resolves, per SELECT column, the JFR content kind that decides display formatting. Raw values
 * alone are ambiguous — a {@code long} may be a byte count ({@code jdk.jfr.DataAmount}), a
 * percentage ({@code jdk.jfr.Percentage}), an address, or a plain number. {@code jfr view} chooses
 * the formatter from the field's declared content type / annotations, so we do the same by walking
 * the SELECT expression's field path down to its declaring {@link StructType.Field} and reading its
 * metadata.
 *
 * <p>Timespans and timestamps already arrive as {@link java.time.Duration}/{@link
 * java.time.Instant} from the reader, so those need no hint here; the interesting cases are the
 * ones that stay raw {@code long}/{@code double}: memory and percentage.
 */
final class ColumnType {

    private ColumnType() {}

    /** The formatting kind for a column, beyond what the runtime value type already implies. */
    enum Kind {
        /** No special handling — format by runtime value type. */
        PLAIN,
        /** {@code jdk.jfr.DataAmount} — render bytes as binary memory sizes (23,0 MB). */
        MEMORY,
        /** {@code jdk.jfr.Percentage} — a 0..1 fraction rendered as a percentage. */
        PERCENTAGE,
        /** {@code jdk.jfr.Frequency} — a count rendered with a trailing " Hz". */
        FREQUENCY,
        /** {@code jdk.jfr.MemoryAddress} — an unsigned long rendered as {@code 0x}-prefixed hex. */
        ADDRESS
    }

    /**
     * Per-column display metadata: the content {@link Kind} and whether the column is "flexible"
     * (text-like — String/Class/Method/StackTrace/Thread/struct). Flexible columns absorb leftover
     * terminal width in {@code jfr view}; fixed columns are sized to their content.
     */
    record Columns(Kind[] kinds, boolean[] flexible) {}

    /**
     * Determine the {@link Kind} and flexibility for each SELECT column. {@code eventsByType}
     * supplies the concrete {@link StructType}s to read field metadata from. A column whose field
     * type can't be resolved (aggregate over {@code *}, synthetic {@code eventType}, absent type)
     * is {@link Kind#PLAIN} and non-flexible.
     */
    static Columns resolve(ViewQuery query, Map<String, List<ReadStruct>> eventsByType) {
        List<SelectItem> select = query.select();
        Kind[] kinds = new Kind[select.size()];
        boolean[] flexible = new boolean[select.size()];
        for (int c = 0; c < select.size(); c++) {
            Expr expr = select.get(c).expr();
            kinds[c] = kindFor(expr, query, eventsByType);
            flexible[c] = flexibleFor(expr, query, eventsByType);
        }
        return new Columns(kinds, flexible);
    }

    private static Kind kindFor(
            Expr expr, ViewQuery query, Map<String, List<ReadStruct>> eventsByType) {
        List<String> parts;
        List<String> aliases;
        if (expr instanceof Aggregate agg) {
            // COUNT is always a plain count regardless of its argument.
            if ("COUNT".equalsIgnoreCase(agg.function())
                    || "UNIQUE".equalsIgnoreCase(agg.function())) {
                return Kind.PLAIN;
            }
            return kindFor(agg.arg(), query, eventsByType);
        } else if (expr instanceof Coalesce c) {
            aliases = c.aliases();
            parts = c.parts();
        } else if (expr instanceof FieldPath fp) {
            parts = fp.parts();
            aliases = List.of();
        } else {
            return Kind.PLAIN;
        }
        return kindForPath(aliases, parts, query, eventsByType);
    }

    private static Kind kindForPath(
            List<String> aliases,
            List<String> parts,
            ViewQuery query,
            Map<String, List<ReadStruct>> eventsByType) {
        if (parts.isEmpty()) return Kind.PLAIN;
        // A path may be alias-qualified (A.field); strip a leading FROM alias.
        List<String> path = stripAlias(parts, query);
        // Try each candidate event type this column could come from.
        for (String type : candidateTypes(aliases, query)) {
            StructType<?, ReadStruct> st = typeOf(type, eventsByType);
            if (st == null) continue;
            Kind k = kindForFieldPath(st, path);
            if (k != Kind.PLAIN) return k;
        }
        return Kind.PLAIN;
    }

    /** Walk {@code path} through nested struct types to the leaf field, then classify it. */
    private static Kind kindForFieldPath(StructType<?, ReadStruct> type, List<String> path) {
        StructType<?, ReadStruct> current = type;
        for (int i = 0; i < path.size(); i++) {
            String name = path.get(i);
            if ("eventType".equals(name)) return Kind.PLAIN;
            StructType<?, ReadStruct> vf = virtualStruct(current, name);
            if (vf != null) {
                // A synthetic struct-valued field (e.g. stackTrace.topFrame): no scalar content
                // type.
                if (i == path.size() - 1) return Kind.PLAIN;
                current = vf;
                continue;
            }
            StructType.Field<?, ?, ?> field = current.getField(name);
            if (field == null) return Kind.PLAIN;
            if (i == path.size() - 1) {
                return classify(field.description());
            }
            // Descend into a nested struct type, if this field is one.
            var inner = field.type();
            if (inner instanceof StructType<?, ?> innerStruct) {
                @SuppressWarnings("unchecked")
                StructType<?, ReadStruct> next = (StructType<?, ReadStruct>) innerStruct;
                current = next;
            } else {
                return Kind.PLAIN;
            }
        }
        return Kind.PLAIN;
    }

    /**
     * Resolve a synthetic struct-valued field that has no declared {@link StructType.Field}. These
     * are jfr's StackTrace frame accessors — {@code topFrame} (first frame), {@code
     * topNotInitFrame} (first non-{@code <init>}/{@code <clinit>} frame), {@code
     * topApplicationFrame} (first non-JDK frame) — all of which surface a {@code frames[]} element.
     * Returns the frames' element struct type, or null if {@code name} is a real/absent field.
     */
    private static StructType<?, ReadStruct> virtualStruct(
            StructType<?, ReadStruct> current, String name) {
        boolean syntheticFrame =
                ("topFrame".equals(name)
                        || "topNotInitFrame".equals(name)
                        || "topApplicationFrame".equals(name));
        if (!syntheticFrame || current.getField(name) != null) return null;
        StructType.Field<?, ?, ?> frames = current.getField("frames");
        if (frames == null) return null;
        if (frames.type() instanceof me.bechberger.condensed.types.ArrayType<?, ?> arr
                && arr.getValueType() instanceof StructType<?, ?> elem) {
            @SuppressWarnings("unchecked")
            StructType<?, ReadStruct> s = (StructType<?, ReadStruct>) elem;
            return s;
        }
        return null;
    }

    /**
     * Classify a field from its encoded description. The description is JSON {@code [type,
     * contentType, annotations, label, description, isArray]}; we look for a {@code DataAmount} or
     * {@code Percentage} content type / annotation via substring probes (the shape is fixed and
     * internal, so a full parse isn't warranted here).
     */
    private static Kind classify(String description) {
        if (description == null) return Kind.PLAIN;
        if (description.contains("jdk.jfr.DataAmount")) return Kind.MEMORY;
        if (description.contains("jdk.jfr.Percentage")) return Kind.PERCENTAGE;
        if (description.contains("jdk.jfr.Frequency")) return Kind.FREQUENCY;
        if (description.contains("jdk.jfr.MemoryAddress")) return Kind.ADDRESS;
        return Kind.PLAIN;
    }

    // ── field metadata labels (for FORM views without a COLUMN clause) ─────────

    /**
     * The display label {@code jfr view} uses for a column when the query gives no explicit COLUMN
     * clause. For a plain (or aggregated) field path, this is the leaf field's declared metadata
     * label — element index 3 of {@link StructType.Field#description()} (the {@code jdk.jfr.Label}
     * value). Returns {@code null} when no field-derived label applies (aggregate over {@code *},
     * synthetic {@code eventType}, unresolved type/field), so the caller can fall back to the raw
     * field name.
     */
    static String labelFor(Expr expr, ViewQuery query, Map<String, List<ReadStruct>> eventsByType) {
        List<String> parts;
        List<String> aliases;
        if (expr instanceof Aggregate agg) {
            return labelFor(agg.arg(), query, eventsByType);
        } else if (expr instanceof Coalesce c) {
            aliases = c.aliases();
            parts = c.parts();
        } else if (expr instanceof FieldPath fp) {
            parts = fp.parts();
            aliases = List.of();
        } else {
            return null;
        }
        if (parts.isEmpty() || parts.contains("eventType")) return null;
        List<String> path = stripAlias(parts, query);
        for (String type : candidateTypes(aliases, query)) {
            StructType<?, ReadStruct> st = typeOf(type, eventsByType);
            if (st == null) continue;
            String label = labelForPath(st, path);
            if (label != null) return label;
        }
        return null;
    }

    /** Walk {@code path} to the leaf field and return its metadata label, or null if absent. */
    private static String labelForPath(StructType<?, ReadStruct> type, List<String> path) {
        StructType<?, ReadStruct> current = type;
        for (int i = 0; i < path.size(); i++) {
            StructType<?, ReadStruct> vf = virtualStruct(current, path.get(i));
            if (vf != null) {
                if (i == path.size() - 1) return null; // synthetic struct: no metadata label
                current = vf;
                continue;
            }
            StructType.Field<?, ?, ?> field = current.getField(path.get(i));
            if (field == null) return null;
            if (i == path.size() - 1) {
                return metadataLabel(field.description());
            }
            if (field.type() instanceof StructType<?, ?> innerStruct) {
                @SuppressWarnings("unchecked")
                StructType<?, ReadStruct> next = (StructType<?, ReadStruct>) innerStruct;
                current = next;
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract the metadata label from a field description. The description is a JSON array {@code
     * [type, contentType, annotations, label, description, isArray]}; the label sits at top-level
     * element index 3. We split the outer array into its top-level elements (respecting nested
     * brackets and quoted strings) and read element 3 as a JSON string, or null if it's not present
     * or not a string.
     */
    static String metadataLabel(String description) {
        if (description == null) return null;
        List<String> elems = topLevelElements(description);
        if (elems.size() <= 3) return null;
        String label = elems.get(3).trim();
        if (label.length() < 2 || label.charAt(0) != '"') return null;
        int end = label.indexOf('"', 1);
        if (end < 0) return null;
        return label.substring(1, end);
    }

    /** Split the JSON array {@code s} into its top-level element substrings (commas at depth 1). */
    private static List<String> topLevelElements(String s) {
        List<String> out = new java.util.ArrayList<>();
        int open = s.indexOf('[');
        if (open < 0) return out;
        int depth = 0;
        boolean inStr = false;
        StringBuilder cur = new StringBuilder();
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"' -> {
                    inStr = true;
                    cur.append(c);
                }
                case '[' -> {
                    depth++;
                    if (depth > 1) cur.append(c);
                }
                case ']' -> {
                    depth--;
                    if (depth == 0) {
                        if (cur.length() > 0) out.add(cur.toString());
                        return out;
                    }
                    cur.append(c);
                }
                case ',' -> {
                    if (depth == 1) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else {
                        cur.append(c);
                    }
                }
                default -> cur.append(c);
            }
        }
        return out;
    }

    // ── flexibility (text-like columns absorb leftover width) ─────────────────

    private static boolean flexibleFor(
            Expr expr, ViewQuery query, Map<String, List<ReadStruct>> eventsByType) {
        List<String> parts;
        List<String> aliases;
        if (expr instanceof Aggregate agg) {
            // MIN/MAX/FIRST/LAST/SET over a text field keep that field's flexibility; counts don't.
            if ("COUNT".equalsIgnoreCase(agg.function())
                    || "UNIQUE".equalsIgnoreCase(agg.function())) {
                return false;
            }
            return flexibleFor(agg.arg(), query, eventsByType);
        } else if (expr instanceof Coalesce c) {
            aliases = c.aliases();
            parts = c.parts();
        } else if (expr instanceof FieldPath fp) {
            parts = fp.parts();
            aliases = List.of();
        } else {
            return false;
        }
        if (parts.isEmpty()) return false;
        // eventType.label / eventType.name render as free text — flexible like a String column.
        if (parts.contains("eventType")) return true;
        List<String> path = stripAlias(parts, query);
        for (String type : candidateTypes(aliases, query)) {
            StructType<?, ReadStruct> st = typeOf(type, eventsByType);
            if (st == null) continue;
            Boolean flex = flexibleForPath(st, path);
            if (flex != null) return flex;
        }
        return false;
    }

    /**
     * Whether the leaf field of {@code path} is a text-like (flexible) column, or null if absent.
     */
    private static Boolean flexibleForPath(StructType<?, ReadStruct> type, List<String> path) {
        StructType<?, ReadStruct> current = type;
        for (int i = 0; i < path.size(); i++) {
            StructType<?, ReadStruct> vf = virtualStruct(current, path.get(i));
            if (vf != null) {
                // A synthetic struct-valued field (topFrame) renders as free text → flexible.
                if (i == path.size() - 1) return true;
                current = vf;
                continue;
            }
            StructType.Field<?, ?, ?> field = current.getField(path.get(i));
            if (field == null) return null;
            if (i == path.size() - 1) {
                return isFlexibleField(field);
            }
            if (field.type() instanceof StructType<?, ?> innerStruct) {
                @SuppressWarnings("unchecked")
                StructType<?, ReadStruct> next = (StructType<?, ReadStruct>) innerStruct;
                current = next;
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * A field is flexible if it renders as free-flowing text: a {@code String}, a
     * class/method/thread reference, a stack trace, or any nested struct. These mirror {@code jfr
     * view}'s columns with an unbounded width that expand to fill the terminal.
     */
    private static boolean isFlexibleField(StructType.Field<?, ?, ?> field) {
        if (field.type() instanceof StructType<?, ?>) return true;
        String desc = field.description();
        if (desc == null) return false;
        return desc.contains("java.lang.String")
                || desc.contains("java.lang.Thread")
                || desc.contains("java.lang.Class")
                || desc.contains("jdk.types.Class")
                || desc.contains("jdk.types.Method")
                || desc.contains("jdk.types.StackTrace")
                || desc.contains("jdk.types.ClassLoader");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> stripAlias(List<String> parts, ViewQuery query) {
        if (parts.size() >= 2) {
            for (FromItem f : query.from()) {
                if (parts.get(0).equals(f.alias())) {
                    return parts.subList(1, parts.size());
                }
            }
        }
        return parts;
    }

    /** The FROM types a column could resolve against: the aliases' types, else all FROM types. */
    private static List<String> candidateTypes(List<String> aliases, ViewQuery query) {
        if (!aliases.isEmpty()) {
            List<String> out = new java.util.ArrayList<>();
            for (FromItem f : query.from()) {
                if (aliases.contains(f.alias())) out.add(f.type());
            }
            if (!out.isEmpty()) return out;
        }
        List<String> out = new java.util.ArrayList<>();
        for (FromItem f : query.from()) out.add(f.type());
        return out;
    }

    private static StructType<?, ReadStruct> typeOf(
            String viewType, Map<String, List<ReadStruct>> eventsByType) {
        List<ReadStruct> evs = eventsByType.get(viewType);
        if (evs == null) evs = eventsByType.get(NativeView.normalizeType(viewType));
        if (evs == null || evs.isEmpty()) return null;
        return evs.get(0).getType();
    }
}
