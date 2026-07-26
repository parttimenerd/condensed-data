package me.bechberger.jfr.cli.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.cli.query.QueryEvaluator.UnsupportedViewException;
import me.bechberger.jfr.cli.query.ViewIniReader.ViewDef;

/**
 * Entry point for rendering a JDK named view natively, without shelling out to {@code jfr view}.
 *
 * <p>Given a view name, it looks up the definition in the on-system {@code view.ini}, parses the
 * query, works out which event types the query needs, and — if the caller can supply those events —
 * evaluates and renders them. Any step that can't be handled natively (unknown view, unparseable
 * query, {@code FROM *}, missing event types) yields {@link Optional#empty()} so the caller
 * delegates to {@code jfr view}.
 */
public final class NativeView {

    private static volatile Map<String, ViewDef> viewCache;

    private NativeView() {}

    /**
     * Rendering options mirroring the relevant {@code jfr view} switches: terminal {@code width};
     * {@code cellHeight} (max physical lines a wrapping cell may occupy — {@code null} means the user
     * did not pass {@code --cell-height}, so each column falls back to its view.ini {@code
     * cell-height:N} FORMAT value, or 1 if none); and {@code truncateBeginning} (true = elide from the
     * start with a leading {@code ...}, matching {@code --truncate beginning}).
     */
    public record Options(int width, Integer cellHeight, boolean truncateBeginning) {
        /** Width-only options with {@code jfr view} defaults (view-driven cell-height, end-truncate). */
        public Options(int width) {
            this(width, null, false);
        }
    }

    private static Map<String, ViewDef> views() {
        Map<String, ViewDef> v = viewCache;
        if (v == null) {
            synchronized (NativeView.class) {
                v = viewCache;
                if (v == null) {
                    v = ViewIniReader.load();
                    viewCache = v;
                }
            }
        }
        return v;
    }

    /** True if {@code viewName} names a known view in the on-system view.ini. */
    public static boolean isKnownView(String viewName) {
        return views().containsKey(viewName.toLowerCase(java.util.Locale.ROOT));
    }

    /** The parsed query for a view, or empty if unknown/unparseable. Package-visible for tests. */
    static Optional<ViewQuery> parsedQuery(String viewName) {
        ViewDef def = views().get(viewName.toLowerCase(java.util.Locale.ROOT));
        if (def == null) return Optional.empty();
        try {
            return Optional.of(Parser.parse(def.shape(), def.body()));
        } catch (QueryParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The distinct event-type names a view's query reads from, or empty if the view is unknown,
     * unparseable, or not natively evaluable ({@code FROM *}). The caller uses this to collect just
     * the needed events before calling {@link #render}.
     */
    public static Optional<List<String>> requiredEventTypes(String viewName) {
        Optional<ViewQuery> q = parsedQuery(viewName);
        if (q.isEmpty()) return Optional.empty();
        ViewQuery query = q.get();
        List<String> types = new ArrayList<>();
        for (var f : query.from()) {
            if ("*".equals(f.type())) return Optional.empty();
            String t = normalizeType(f.type());
            if (!types.contains(t)) types.add(t);
        }
        return Optional.of(types);
    }

    /**
     * Render a view natively. {@code eventsByType} must contain (at least) the types reported by
     * {@link #requiredEventTypes}; keys are matched flexibly (short name or fully-qualified). Returns
     * the rendered lines, or empty to signal the caller should delegate to {@code jfr view}.
     */
    public static Optional<List<String>> render(
            String viewName, Map<String, List<ReadStruct>> eventsByType, Options options) {
        ViewDef def = views().get(viewName.toLowerCase(java.util.Locale.ROOT));
        if (def == null) return Optional.empty();
        ViewQuery query;
        try {
            query = Parser.parse(def.shape(), def.body());
        } catch (QueryParseException e) {
            return Optional.empty();
        }
        query = expandStar(query, eventsByType);
        try {
            var evaluator = new QueryEvaluator(query);
            var rows = evaluator.evaluate(eventsByType);
            String title = def.label() != null ? def.label() : viewName;
            // jfr prints a single "No events found" line (not an empty table) when nothing matched.
            if (rows.isEmpty()) {
                return Optional.of(List.of("", "No events found for '" + title + "'."));
            }
            var kinds = ColumnType.resolve(query, eventsByType);
            var renderer =
                    new ViewRenderer(
                            query,
                            title,
                            kinds,
                            options.width(),
                            options.cellHeight(),
                            options.truncateBeginning(),
                            eventsByType,
                            evaluator);
            return Optional.of(renderer.render(rows));
        } catch (UnsupportedViewException e) {
            return Optional.empty();
        }
    }

    /**
     * Render a single precomputed FORM row straight from footer-supplied cell values and their
     * resolved {@link ColumnType.Kind}s, bypassing {@link ColumnType#resolve} (which needs events).
     * Package-visible so the {@link ViewPrecompute} facade — the sole caller — can hand over the
     * cells without leaking {@code Kind} across packages. Returns empty for an unknown/unparseable
     * view. FORM rendering never touches the evaluator, so a {@code null} evaluator is safe here.
     */
    static Optional<List<String>> renderPrecomputedRow(
            String viewName, List<Object> cells, ColumnType.Kind[] kinds, Options options) {
        ViewDef def = views().get(viewName.toLowerCase(java.util.Locale.ROOT));
        if (def == null) return Optional.empty();
        ViewQuery query;
        try {
            query = Parser.parse(def.shape(), def.body());
        } catch (QueryParseException e) {
            return Optional.empty();
        }
        String title = def.label() != null ? def.label() : viewName;
        var columns = new ColumnType.Columns(kinds, new boolean[kinds.length]);
        var renderer =
                new ViewRenderer(
                        query,
                        title,
                        columns,
                        options.width(),
                        options.cellHeight(),
                        options.truncateBeginning(),
                        Map.of(),
                        null);
        var row = new QueryEvaluator.Row(new ArrayList<>(cells));
        return Optional.of(renderer.render(List.of(row)));
    }

    /**
     * Expand a bare {@code SELECT *} into one {@code SelectItem} per field of the single FROM event
     * type, mirroring {@code jfr view}: fields appear in declaration order, and the synthetic {@code
     * startTime} field is surfaced under the label "Time" (jfr's convention for {@code *} views).
     * Queries that are not a single {@code *} over a single FROM type are returned unchanged.
     */
    private static ViewQuery expandStar(
            ViewQuery query, Map<String, List<ReadStruct>> eventsByType) {
        if (query.select().size() != 1 || !(query.select().get(0).expr() instanceof ViewQuery.Star)) {
            return query;
        }
        if (query.from().size() != 1) return query;
        String type = query.from().get(0).type();
        List<ReadStruct> evs = eventsByType.get(type);
        if (evs == null) evs = eventsByType.get(normalizeType(type));
        if (evs == null || evs.isEmpty()) return query;
        var st = evs.get(0).getType();
        List<ViewQuery.SelectItem> select = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (var f : st.getFields()) {
            String name = f.name();
            select.add(
                    new ViewQuery.SelectItem(new ViewQuery.FieldPath(List.of(name)), null));
            // jfr labels the startTime column "Time" in a SELECT * view; other columns use their
            // declared metadata label (resolved later by the renderer), so leave them blank here.
            labels.add("startTime".equals(name) ? "Time" : null);
        }
        // Only supply explicit labels if we overrode at least one (the startTime → "Time" case);
        // otherwise leave columnLabels empty so the renderer derives metadata labels per column.
        List<String> columnLabels =
                labels.stream().anyMatch(l -> l != null) ? materializeLabels(st, labels) : List.of();
        return new ViewQuery(
                query.shape(),
                columnLabels,
                query.formatHints(),
                select,
                query.from(),
                query.where(),
                query.groupBy(),
                query.orderBy(),
                query.limit());
    }

    /** Fill in null label slots with each field's declared metadata label (falling back to name). */
    private static List<String> materializeLabels(
            me.bechberger.condensed.types.StructType<?, ReadStruct> st, List<String> labels) {
        List<String> out = new ArrayList<>(labels.size());
        var fields = st.getFields();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) != null) {
                out.add(labels.get(i));
            } else {
                String lbl = ColumnType.metadataLabel(fields.get(i).description());
                out.add(lbl != null ? lbl : fields.get(i).name());
            }
        }
        return out;
    }

    /**
     * Normalize a view.ini event-type reference to the JFR event type name. view.ini uses short
     * names (e.g. {@code GarbageCollection}) that correspond to {@code jdk.GarbageCollection}, and
     * occasionally fully-qualified ones ({@code jdk.Shutdown.reason} style dotted paths); this maps a
     * bare simple name to the {@code jdk.} namespace, leaving already-qualified names untouched.
     */
    static String normalizeType(String type) {
        if (type.contains(".")) return type;
        return "jdk." + type;
    }

    /**
     * Build the {@code eventsByType} map the evaluator expects from a flat event list, keyed by both
     * the fully-qualified type name and its simple name so field/type lookups in the query resolve
     * regardless of how view.ini spells the type.
     */
    public static Map<String, List<ReadStruct>> indexByType(List<ReadStruct> events) {
        Map<String, List<ReadStruct>> byType = new LinkedHashMap<>();
        for (ReadStruct e : events) {
            String full = e.getType().getName();
            byType.computeIfAbsent(full, k -> new ArrayList<>()).add(e);
            int dot = full.lastIndexOf('.');
            if (dot >= 0) {
                String simple = full.substring(dot + 1);
                byType.computeIfAbsent(simple, k -> new ArrayList<>()).add(e);
            }
        }
        return byType;
    }
}
