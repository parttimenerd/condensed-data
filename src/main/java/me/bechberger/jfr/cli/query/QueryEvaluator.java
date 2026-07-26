package me.bechberger.jfr.cli.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.cli.query.Aggregators.Reducer;
import me.bechberger.jfr.cli.query.ViewQuery.Aggregate;
import me.bechberger.jfr.cli.query.ViewQuery.Coalesce;
import me.bechberger.jfr.cli.query.ViewQuery.Equality;
import me.bechberger.jfr.cli.query.ViewQuery.Expr;
import me.bechberger.jfr.cli.query.ViewQuery.FieldPath;
import me.bechberger.jfr.cli.query.ViewQuery.FromItem;
import me.bechberger.jfr.cli.query.ViewQuery.OrderItem;
import me.bechberger.jfr.cli.query.ViewQuery.SelectItem;
import me.bechberger.jfr.cli.query.ViewQuery.Star;

/**
 * Executes a parsed {@link ViewQuery} against a list of events, producing rows of raw (unformatted)
 * values. Formatting/layout is the renderer's job.
 *
 * <p>Handles three FROM shapes actually used by JDK {@code view.ini}:
 *
 * <ul>
 *   <li><b>single / UNION</b> — one or more event types with no aliases; all matching events feed a
 *       single stream, optionally grouped and aggregated.
 *   <li><b>correlated self-join</b> — multiple aliases (possibly of the same event type), each
 *       filtered by its own WHERE equality on a discriminator, correlated by the GROUP BY key. A
 *       SELECT of {@code alias.field} reads that alias's row for the group; {@code [A|B].field}
 *       ({@link Coalesce}) takes the first non-null across the listed aliases.
 * </ul>
 *
 * <p>{@code FROM *} and any shape the parser could not narrow are not evaluable here — the caller
 * catches {@link UnsupportedViewException} and delegates to {@code jfr view}.
 */
final class QueryEvaluator {

    /** Thrown when a parsed query is well-formed but not natively evaluable (caller delegates). */
    static final class UnsupportedViewException extends RuntimeException {
        UnsupportedViewException(String message) {
            super(message);
        }
    }

    private final ViewQuery query;
    private final boolean join;

    /**
     * Per-column numeric totals over the full result set <em>before</em> LIMIT is applied. {@code
     * jfr view}'s {@code normalized} FORMAT renders each cell as its share of the column total, and
     * that total is taken over every group — not just the rows that survive LIMIT. Populated by
     * {@link #evaluate}.
     */
    private double[] preLimitColumnTotals;

    QueryEvaluator(ViewQuery query) {
        this.query = query;
        if (query.from().size() == 1 && "*".equals(query.from().get(0).type())) {
            throw new UnsupportedViewException("FROM * is not natively evaluable");
        }
        this.join = query.from().stream().anyMatch(f -> f.alias() != null);
    }

    /** A computed output row: one raw value per SELECT item, in SELECT order. */
    record Row(List<Object> cells) {}

    /**
     * Evaluate the query. {@code eventsByType} maps event-type name → its events (already collected
     * by the caller). Returns rows in final display order (grouped, aggregated, ordered, limited).
     */
    List<Row> evaluate(Map<String, List<ReadStruct>> eventsByType) {
        List<Row> rows = join ? evaluateJoin(eventsByType) : evaluateFlat(eventsByType);
        rows = applyOrder(rows);
        preLimitColumnTotals = computeColumnTotals(rows);
        if (query.limit() >= 0 && rows.size() > query.limit()) {
            rows = new ArrayList<>(rows.subList(0, query.limit()));
        }
        return rows;
    }

    /** Sum each column's numeric cells across {@code rows} (NaN-safe; non-numbers contribute 0). */
    private double[] computeColumnTotals(List<Row> rows) {
        double[] totals = new double[query.select().size()];
        for (Row row : rows) {
            for (int c = 0; c < totals.length && c < row.cells().size(); c++) {
                if (row.cells().get(c) instanceof Number n) {
                    totals[c] += n.doubleValue();
                }
            }
        }
        return totals;
    }

    /** The pre-LIMIT sum of column {@code col}, for {@code normalized} FORMAT denominators. */
    double preLimitColumnTotal(int col) {
        if (preLimitColumnTotals == null || col < 0 || col >= preLimitColumnTotals.length) {
            return 0.0;
        }
        return preLimitColumnTotals[col];
    }

    // ── flat (single / UNION) ────────────────────────────────────────────────

    private List<Row> evaluateFlat(Map<String, List<ReadStruct>> eventsByType) {
        List<ReadStruct> stream = new ArrayList<>();
        for (FromItem f : query.from()) {
            List<ReadStruct> evs = eventsByType.get(f.type());
            if (evs != null) {
                stream.addAll(evs);
            }
        }
        // WHERE (unaliased equalities only in the flat case)
        List<ReadStruct> filtered = new ArrayList<>();
        for (ReadStruct e : stream) {
            if (matchesWhere(e, null)) {
                filtered.add(e);
            }
        }

        boolean aggregated = hasAggregate();
        if (query.groupBy().isEmpty() && !aggregated) {
            // Plain projection, one output row per event.
            List<Row> out = new ArrayList<>(filtered.size());
            for (ReadStruct e : filtered) {
                out.add(projectFlat(e, null));
            }
            return out;
        }

        // Grouped (or whole-stream aggregation when GROUP BY is absent but aggregates present).
        // LAST_BATCH restricts the view to events in the final periodic-emission batch (the global
        // max startTime); groups over the full stream still see all history (so DIFF sees every
        // event), but LAST_BATCH columns draw only from that batch and groups with no last-batch
        // member are dropped.
        boolean lastBatch = hasLastBatch();
        Instant lastBatchTs = lastBatch ? lastBatchTimestamp(filtered) : null;
        Map<Object, List<ReadStruct>> groups = new LinkedHashMap<>();
        for (ReadStruct e : filtered) {
            Object key = query.groupBy().isEmpty() ? SINGLETON_KEY : groupKey(e, null);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Row> out = new ArrayList<>(groups.size());
        for (List<ReadStruct> group : groups.values()) {
            if (lastBatch && group.stream().noneMatch(e -> inLastBatch(e, lastBatchTs))) {
                continue;
            }
            out.add(projectGroupFlat(group, lastBatchTs));
        }
        return out;
    }

    private Row projectFlat(ReadStruct e, Map<String, ReadStruct> aliasRow) {
        List<Object> cells = new ArrayList<>(query.select().size());
        for (SelectItem item : query.select()) {
            cells.add(evalScalar(item.expr(), e, aliasRow));
        }
        return new Row(cells);
    }

    private Row projectGroupFlat(List<ReadStruct> group, Instant lastBatchTs) {
        List<Object> cells = new ArrayList<>(query.select().size());
        for (SelectItem item : query.select()) {
            cells.add(evalOverGroup(item.expr(), group, lastBatchTs));
        }
        return new Row(cells);
    }

    // ── correlated self-join ─────────────────────────────────────────────────

    private List<Row> evaluateJoin(Map<String, List<ReadStruct>> eventsByType) {
        // Per-alias filtered events, then group each alias's rows by the GROUP BY key.
        // group key -> (alias -> list of rows for that alias in that group)
        Map<Object, Map<String, List<ReadStruct>>> groups = new LinkedHashMap<>();
        List<ReadStruct> allFiltered = new ArrayList<>();
        for (FromItem f : query.from()) {
            String alias = f.alias();
            List<ReadStruct> evs = eventsByType.getOrDefault(f.type(), List.of());
            for (ReadStruct e : evs) {
                if (!matchesWhere(e, alias)) {
                    continue;
                }
                allFiltered.add(e);
                Object key = groupKey(e, alias);
                groups.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .computeIfAbsent(alias, a -> new ArrayList<>())
                        .add(e);
            }
        }
        // active-settings uses LAST_BATCH(E.value): restrict its aggregate to the final emission
        // batch across all aliases, and drop groups with no member in that batch.
        boolean lastBatch = hasLastBatch();
        Instant lastBatchTs = lastBatch ? lastBatchTimestamp(allFiltered) : null;
        List<Row> out = new ArrayList<>(groups.size());
        for (Map<String, List<ReadStruct>> aliasRows : groups.values()) {
            if (lastBatch
                    && aliasRows.values().stream()
                            .flatMap(List::stream)
                            .noneMatch(e -> inLastBatch(e, lastBatchTs))) {
                continue;
            }
            List<Object> cells = new ArrayList<>(query.select().size());
            for (SelectItem item : query.select()) {
                cells.add(evalJoinCell(item.expr(), aliasRows, lastBatchTs));
            }
            out.add(new Row(cells));
        }
        return out;
    }

    private Object evalJoinCell(
            Expr expr, Map<String, List<ReadStruct>> aliasRows, Instant lastBatchTs) {
        if (expr instanceof Aggregate agg) {
            // Aggregate over the alias(es) referenced by its argument, across the group's rows.
            boolean lastBatch = Aggregators.isLastBatch(agg.function());
            List<String> aliases = aliasesOf(agg.arg());
            List<String> parts = trailingParts(agg.arg());
            Reducer r = Aggregators.reducer(agg.function()).get();
            for (String a : aliases) {
                for (ReadStruct e : aliasRows.getOrDefault(a, List.of())) {
                    if (lastBatch && !inLastBatch(e, lastBatchTs)) {
                        continue;
                    }
                    r.accept(parts.isEmpty() ? e : FieldResolver.resolve(e, parts));
                }
            }
            return r.result();
        }
        if (expr instanceof Coalesce c) {
            for (String a : c.aliases()) {
                List<ReadStruct> rs = aliasRows.get(a);
                if (rs != null && !rs.isEmpty()) {
                    return FieldResolver.resolve(rs.get(0), c.parts());
                }
            }
            return null;
        }
        if (expr instanceof FieldPath fp) {
            // Alias-qualified (A.field) or bare (group-key field shared across aliases).
            List<String> parts = fp.parts();
            if (parts.size() >= 2 && aliasRows.containsKey(parts.get(0))) {
                List<ReadStruct> rs = aliasRows.get(parts.get(0));
                if (rs == null || rs.isEmpty()) return null;
                return FieldResolver.resolve(rs.get(0), parts.subList(1, parts.size()));
            }
            // Bare field: read from any alias's first row (e.g. the GROUP BY key like gcId).
            for (List<ReadStruct> rs : aliasRows.values()) {
                if (!rs.isEmpty()) {
                    Object v = FieldResolver.resolve(rs.get(0), parts);
                    if (v != null) return v;
                }
            }
            return null;
        }
        return null;
    }

    // ── expression evaluation (flat) ─────────────────────────────────────────

    private Object evalScalar(Expr expr, ReadStruct e, Map<String, ReadStruct> aliasRow) {
        if (expr instanceof FieldPath fp) {
            return FieldResolver.resolve(e, stripAlias(fp.parts()));
        }
        if (expr instanceof Star) {
            return e;
        }
        if (expr instanceof Aggregate) {
            // A bare aggregate in a non-grouped projection aggregates the single row.
            return evalOverGroup(expr, List.of(e), (Instant) null);
        }
        if (expr instanceof Coalesce c) {
            return FieldResolver.resolve(e, c.parts());
        }
        return null;
    }

    private Object evalOverGroup(Expr expr, List<ReadStruct> group, Instant lastBatchTs) {
        if (expr instanceof Aggregate agg) {
            boolean lastBatch = Aggregators.isLastBatch(agg.function());
            Reducer r = Aggregators.reducer(agg.function()).get();
            // Order-sensitive aggregates (DIFF = last−first, FIRST/LAST, LAST_BATCH) need the group
            // in chronological order. A multi-type FROM (e.g. object-statistics'
            // ObjectCountAfterGC + ObjectCount) is concatenated per-type, not interleaved, so sort
            // by startTime here to recover jfr's chronological iteration.
            List<ReadStruct> ordered = orderedForAggregate(agg.function(), group);
            for (ReadStruct e : ordered) {
                // In a multi-type FROM (e.g. system-information's CPUInformation, PhysicalMemory,
                // OSInformation, ...), each LAST(field) column aggregates only over the events that
                // actually declare that field; events of the other types don't contribute a (null)
                // value that would clobber a LAST/FIRST result. COUNT(*) has no field and always
                // counts every event.
                if (!aggregandApplies(agg.arg(), e)) {
                    continue;
                }
                // LAST_BATCH draws only from the final periodic-emission batch; other aggregates
                // (e.g. DIFF) see the whole group's history.
                if (lastBatch && !inLastBatch(e, lastBatchTs)) {
                    continue;
                }
                r.accept(aggregandValue(agg.arg(), e));
            }
            return r.result();
        }
        // Non-aggregate expr in a grouped select: take the value from a representative row. When the
        // view has a LAST_BATCH column, prefer a last-batch representative so a non-aggregate column
        // reflects the final batch too.
        // For most views the SELECT column is the GROUP BY key (constant within the group), so the
        // choice of representative row is immaterial. It matters only for a multi-type UNION where a
        // non-aggregate column (e.g. eventType.label) varies within a group — as in gc-pause-phases,
        // where a phase name can occur under several GCPhasePauseLevelN types. jfr's representative is
        // whichever event its internal chronological iteration visits last, which is not reproducible
        // from a per-FROM-type grouping; first-row matches the common case and is left as-is (the
        // divergence is a documented known diff for multi-level phase-name collisions).
        if (!group.isEmpty()) {
            ReadStruct rep = group.get(0);
            if (lastBatchTs != null) {
                for (ReadStruct e : group) {
                    if (inLastBatch(e, lastBatchTs)) {
                        rep = e;
                        break;
                    }
                }
            }
            return evalScalar(expr, rep, null);
        }
        return null;
    }

    /**
     * The value an aggregate consumes from one event in a flat (non-join) query. {@code COUNT(*)}
     * feeds the whole event; a field-path argument (e.g. {@code source.name}) resolves against the
     * event, stripping only a leading FROM alias if one is actually present — unlike {@link
     * #trailingParts}, which unconditionally drops the first segment for join aggregates and would
     * wrongly discard a real leading field like {@code source}.
     */
    private Object aggregandValue(Expr arg, ReadStruct e) {
        if (arg instanceof Star) {
            return e;
        }
        if (arg instanceof FieldPath fp) {
            return FieldResolver.resolve(e, stripAlias(fp.parts()));
        }
        if (arg instanceof Coalesce c) {
            return FieldResolver.resolve(e, c.parts());
        }
        return null;
    }

    /**
     * Whether an aggregate's argument is applicable to event {@code e} — i.e. {@code e}'s type
     * declares the field the argument reads. {@code COUNT(*)} (a {@link Star}) applies to every
     * event. A field-path argument applies only when the event has that path's root segment, so a
     * multi-type FROM lets each per-field aggregate (LAST(totalSize), LAST(osVersion), …) draw only
     * from the event type that owns the field rather than being nulled out by the others.
     */
    private boolean aggregandApplies(Expr arg, ReadStruct e) {
        List<String> parts;
        if (arg instanceof Star) {
            return true;
        } else if (arg instanceof FieldPath fp) {
            parts = stripAlias(fp.parts());
        } else if (arg instanceof Coalesce c) {
            parts = c.parts();
        } else {
            return true;
        }
        if (parts.isEmpty()) {
            return true;
        }
        String root = parts.get(0);
        return "eventType".equals(root) || e.hasField(root);
    }

    // ── WHERE / GROUP BY helpers ──────────────────────────────────────────────

    private boolean matchesWhere(ReadStruct e, String alias) {
        for (Equality eq : query.where()) {
            List<String> parts = eq.field().parts();
            // In a join, only apply equalities scoped to this alias (A.when = ...).
            if (alias != null) {
                if (parts.size() >= 2 && parts.get(0).equals(alias)) {
                    Object v = FieldResolver.resolve(e, parts.subList(1, parts.size()));
                    if (!valueEquals(v, eq.value())) return false;
                }
                // equalities for other aliases don't constrain this event
            } else {
                Object v = FieldResolver.resolve(e, parts);
                if (!valueEquals(v, eq.value())) return false;
            }
        }
        return true;
    }

    private static boolean valueEquals(Object v, String expected) {
        if (v == null) return false;
        return v.toString().equals(expected);
    }

    private static final Object SINGLETON_KEY = new Object();

    private Object groupKey(ReadStruct e, String alias) {
        if (query.groupBy().size() == 1) {
            return canonicalKey(resolveGroupTerm(query.groupBy().get(0), e));
        }
        List<Object> key = new ArrayList<>(query.groupBy().size());
        for (String path : query.groupBy()) {
            key.add(canonicalKey(resolveGroupTerm(path, e)));
        }
        return key;
    }

    /**
     * Canonicalize a group-key value. {@code jfr view} groups struct-valued keys (a stack frame,
     * method, thread, class) by their <em>displayed</em> identity rather than raw struct equality —
     * e.g. all samples whose top frame is the same method collapse into one group even when their
     * bytecode index or line number differs. Mapping a struct key to its formatted string reproduces
     * that: two structs that render identically share a group. Non-struct keys pass through unchanged.
     */
    private static Object canonicalKey(Object value) {
        if (value instanceof ReadStruct s) {
            return ValueFormatter.format(s, null);
        }
        return value;
    }

    /**
     * Resolve a GROUP BY term against an event. The term may name a SELECT alias (e.g. {@code GROUP
     * BY E} where {@code eventThread AS E}), in which case we group by that column's underlying
     * expression; otherwise it's a direct field path.
     */
    private Object resolveGroupTerm(String term, ReadStruct e) {
        for (SelectItem item : query.select()) {
            if (term.equals(item.alias())) {
                return evalScalar(item.expr(), e, null);
            }
        }
        return FieldResolver.resolve(e, splitPath(term));
    }

    // ── ORDER BY ─────────────────────────────────────────────────────────────

    private List<Row> applyOrder(List<Row> rows) {
        if (query.orderBy().isEmpty()) return applyDefaultOrder(rows);
        List<Row> sorted = new ArrayList<>(rows);
        var comparators = new ArrayList<java.util.Comparator<Row>>();
        for (OrderItem oi : query.orderBy()) {
            int idx = selectIndexFor(oi.ref());
            java.util.Comparator<Row> c =
                    (a, b) ->
                            compareCells(
                                    cellForOrder(a, oi.ref(), idx),
                                    cellForOrder(b, oi.ref(), idx));
            if (descending(oi, idx, rows)) c = c.reversed();
            comparators.add(c);
        }
        java.util.Comparator<Row> combined = comparators.get(0);
        for (int i = 1; i < comparators.size(); i++) {
            combined = combined.thenComparing(comparators.get(i));
        }
        sorted.sort(combined);
        return sorted;
    }

    /**
     * When a query gives no ORDER BY, {@code jfr view} still imposes a default order on grouped/
     * aggregated output: it sorts by the <em>last</em> column. A magnitude column (Number or
     * Duration) sorts descending (largest first); a textual column sorts ascending (e.g.
     * system-processes with no ORDER BY comes out ordered by its Command Line column). A non-grouped,
     * non-aggregated projection (one row per event) is never reordered.
     */
    private List<Row> applyDefaultOrder(List<Row> rows) {
        if (rows.isEmpty()) return rows;
        if (query.groupBy().isEmpty() && !hasAggregate()) return rows;
        int idx = query.select().size() - 1;
        if (idx < 0) return rows;
        boolean magnitude = columnIsMagnitude(idx, rows);
        List<Row> sorted = new ArrayList<>(rows);
        java.util.Comparator<Row> c =
                (a, b) ->
                        compareCells(
                                idx < a.cells().size() ? a.cells().get(idx) : null,
                                idx < b.cells().size() ? b.cells().get(idx) : null);
        // Time (Instant) last columns give no default order — keep group-insertion order.
        if (!magnitude && columnIsInstant(idx, rows)) return rows;
        sorted.sort(magnitude ? c.reversed() : c);
        return sorted;
    }

    /** Whether the column's first non-null value is an {@link java.time.Instant} (a timestamp). */
    private static boolean columnIsInstant(int idx, List<Row> rows) {
        for (Row r : rows) {
            Object v = idx < r.cells().size() ? r.cells().get(idx) : null;
            if (v != null) {
                return v instanceof java.time.Instant;
            }
        }
        return false;
    }

    /**
     * The effective sort direction: explicit ASC/DESC wins; with no keyword, {@code jfr view}
     * defaults to descending for a magnitude column (Number/Duration — largest first) and ascending
     * for textual or time columns. Time (Instant) columns sort chronologically (ascending) and an
     * aggregate over a String (e.g. {@code LAST(key)}) sorts ascending, so the decision is made from
     * the actual cell type rather than aggregate-ness.
     */
    private boolean descending(OrderItem oi, int idx, List<Row> rows) {
        return switch (oi.direction()) {
            case DESC -> true;
            case ASC -> false;
            case DEFAULT -> idx >= 0 && columnIsMagnitude(idx, rows);
        };
    }

    /** Whether the column's first non-null value is a sortable magnitude (Number or Duration). */
    private static boolean columnIsMagnitude(int idx, List<Row> rows) {
        for (Row r : rows) {
            Object v = idx < r.cells().size() ? r.cells().get(idx) : null;
            if (v != null) {
                return v instanceof Number || v instanceof java.time.Duration;
            }
        }
        return false;
    }

    /** ORDER BY may reference a SELECT alias or field; resolve to a SELECT column index, else -1. */
    private int selectIndexFor(String ref) {
        for (int i = 0; i < query.select().size(); i++) {
            SelectItem item = query.select().get(i);
            if (ref.equals(item.alias())) return i;
            if (item.expr() instanceof FieldPath fp && fp.joined().equals(ref)) return i;
        }
        // ORDER BY a field that appears only inside an aggregate (e.g. object-statistics'
        // `ORDER BY totalSize` where SELECT has LAST_BATCH(totalSize) and DIFF(totalSize)): match
        // the first SELECT aggregate whose argument field path equals the reference.
        for (int i = 0; i < query.select().size(); i++) {
            if (query.select().get(i).expr() instanceof Aggregate agg
                    && agg.arg() instanceof FieldPath fp
                    && fp.joined().equals(ref)) {
                return i;
            }
        }
        return -1;
    }

    private Object cellForOrder(Row row, String ref, int idx) {
        if (idx >= 0) return row.cells().get(idx);
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareCells(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && a.getClass().isInstance(b)) {
            return ca.compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    // ── small utilities ────────────────────────────────────────────────────

    private boolean hasAggregate() {
        return query.select().stream().anyMatch(s -> s.expr() instanceof Aggregate);
    }

    /** True if any SELECT column is a {@code LAST_BATCH(...)} aggregate. */
    private boolean hasLastBatch() {
        return query.select().stream()
                .anyMatch(
                        s ->
                                s.expr() instanceof Aggregate agg
                                        && Aggregators.isLastBatch(agg.function()));
    }

    /**
     * The final periodic-emission batch timestamp for {@code LAST_BATCH}: the global maximum
     * {@code startTime} across all filtered events (all FROM types combined — the batch is the
     * shared final emission, e.g. object-statistics' last GC). Returns {@code null} if no event
     * carries a readable {@code startTime}.
     */
    private static Instant lastBatchTimestamp(List<ReadStruct> events) {
        Instant max = null;
        for (ReadStruct e : events) {
            Instant t;
            try {
                t = e.getInstant("startTime");
            } catch (RuntimeException ex) {
                t = null;
            }
            if (t != null && (max == null || t.isAfter(max))) {
                max = t;
            }
        }
        return max;
    }

    /** Whether {@code e} belongs to the final batch (its {@code startTime} equals {@code ts}). */
    private static boolean inLastBatch(ReadStruct e, Instant ts) {
        if (ts == null) {
            return true;
        }
        try {
            return ts.equals(e.getInstant("startTime"));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Return the group in the iteration order an aggregate needs. Order-sensitive functions
     * ({@code DIFF} = last−first, {@code FIRST}/{@code LAST}, {@code LAST_BATCH}) require
     * chronological order; a multi-type FROM is concatenated per-type rather than interleaved, so
     * sort a copy by {@code startTime}. Order-insensitive functions (COUNT/SUM/AVG/MIN/MAX/…) keep
     * the original group untouched.
     */
    private static List<ReadStruct> orderedForAggregate(String fn, List<ReadStruct> group) {
        String f = fn.toUpperCase(java.util.Locale.ROOT);
        boolean orderSensitive =
                switch (f) {
                    case "DIFF", "FIRST", "LAST", "LAST_BATCH" -> true;
                    default -> false;
                };
        if (!orderSensitive || group.size() < 2) {
            return group;
        }
        List<ReadStruct> sorted = new ArrayList<>(group);
        sorted.sort(
                (a, b) -> {
                    Instant ta = instantOrNull(a);
                    Instant tb = instantOrNull(b);
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return -1;
                    if (tb == null) return 1;
                    return ta.compareTo(tb);
                });
        return sorted;
    }

    private static Instant instantOrNull(ReadStruct e) {
        try {
            return e.getInstant("startTime");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Strip a leading FROM alias from a field path in a flat query (rare; usually none). */
    private List<String> stripAlias(List<String> parts) {
        if (parts.size() >= 2) {
            for (FromItem f : query.from()) {
                if (parts.get(0).equals(f.alias())) {
                    return parts.subList(1, parts.size());
                }
            }
        }
        return parts;
    }

    private static List<String> splitPath(String dotted) {
        return List.of(dotted.split("\\."));
    }

    /** The aliases an aggregate's argument references (for join aggregates). */
    private static List<String> aliasesOf(Expr arg) {
        if (arg instanceof Coalesce c) return c.aliases();
        if (arg instanceof FieldPath fp && fp.parts().size() >= 2) return List.of(fp.parts().get(0));
        return List.of();
    }

    /** The trailing field path of an aggregate argument (after any alias/coalesce prefix). */
    private static List<String> trailingParts(Expr arg) {
        if (arg instanceof Coalesce c) return c.parts();
        if (arg instanceof FieldPath fp) {
            return fp.parts().size() >= 2 ? fp.parts().subList(1, fp.parts().size()) : fp.parts();
        }
        return List.of();
    }
}
