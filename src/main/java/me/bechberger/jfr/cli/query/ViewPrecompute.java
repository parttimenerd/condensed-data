package me.bechberger.jfr.cli.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.bechberger.condensed.CJFRFooter.PrecomputedCell;
import me.bechberger.jfr.cli.query.Aggregators.Reducer;

/**
 * Exact-aggregate FORM views precomputed at condense time and served from the {@code .cjfr} footer
 * with zero event reads.
 *
 * <p>Public facade over the (package-private) {@link Aggregators} and {@link ColumnType} internals,
 * so the collector ({@code me.bechberger.jfr.FooterCollector}) and the command ({@code
 * me.bechberger.jfr.cli.commands.ViewCommand}) never touch the query package's internal types —
 * they speak only in {@link PrecomputedCell} (which carries the display kind as an ordinal).
 *
 * <p>Correct-by-construction byte-identical: the collector feeds each view's FROM-event field
 * values through the very same {@link Aggregators} reducers the native renderer uses, and the serve
 * side renders through the same {@link ViewRenderer}. Values reverse-engineered from observed
 * {@code jfr view} output only (the GPLv2 {@code view.ini} / {@code jdk.jfr.internal.query} are
 * never copied).
 *
 * <p><b>Extending:</b> add a {@link PView} to {@link #REGISTRY}. After the one footer-format
 * version bump that introduced the precompute block, this is data-only — no format change, and old
 * readers ignore unknown view keys.
 */
public final class ViewPrecompute {

    private ViewPrecompute() {}

    /**
     * One SELECT column of a precomputed view: aggregate over {@code field}, displayed as {@code
     * kind}.
     */
    private record PColumn(String reducer, String field, ColumnType.Kind kind) {}

    /**
     * A precomputed FORM view: its {@code viewName}, single {@code fromType}, and ordered columns.
     */
    private record PView(String viewName, String fromType, List<PColumn> columns) {}

    // The three numeric-aggregate FORM views (clean-room, from observed jfr view output).
    // COUNT/DIFF's field is the aggregate's argument; COUNT(*) uses "*" (the accumulator feeds a
    // non-null marker so the count advances regardless of field value).
    private static final List<PView> REGISTRY =
            List.of(
                    new PView(
                            "gc-cpu-time",
                            "jdk.GCCPUTime",
                            List.of(
                                    new PColumn("SUM", "userTime", ColumnType.Kind.PLAIN),
                                    new PColumn("SUM", "systemTime", ColumnType.Kind.PLAIN),
                                    new PColumn("SUM", "realTime", ColumnType.Kind.PLAIN),
                                    new PColumn("DIFF", "startTime", ColumnType.Kind.PLAIN),
                                    new PColumn("COUNT", "*", ColumnType.Kind.PLAIN))),
                    new PView(
                            "cpu-load",
                            "jdk.CPULoad",
                            List.of(
                                    new PColumn("MIN", "jvmUser", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("AVG", "jvmUser", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("MAX", "jvmUser", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("MIN", "jvmSystem", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("AVG", "jvmSystem", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("MAX", "jvmSystem", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("MIN", "machineTotal", ColumnType.Kind.PERCENTAGE),
                                    new PColumn("AVG", "machineTotal", ColumnType.Kind.PERCENTAGE),
                                    new PColumn(
                                            "MAX", "machineTotal", ColumnType.Kind.PERCENTAGE))),
                    new PView(
                            "exception-count",
                            "jdk.ExceptionStatistics",
                            List.of(new PColumn("DIFF", "throwables", ColumnType.Kind.PLAIN))));

    /** Marker fed to a {@code COUNT(*)} reducer so it counts every source event. */
    public static final Object COUNT_STAR_MARKER = new Object();

    // ── collection side ───────────────────────────────────────────────────────

    /**
     * A stateful accumulator that the collector drives during condense. For each incoming event,
     * the collector calls {@link #acceptRow} with the event's startTime and all column values. At
     * the end, {@link #build} sorts buffered rows by startTime (matching the chronological
     * iteration that {@link QueryEvaluator} uses) before feeding order-sensitive reducers
     * (DIFF/FIRST/LAST), ensuring the footer-precomputed result matches the event-based render.
     */
    public static final class Accumulator {
        /**
         * Per-view buffer of (startTimeNanos, Object[colValues]) — used for order-sensitive views.
         */
        private final Map<String, List<long[]>> startTimesByView = new LinkedHashMap<>();

        /** Parallel buffer of column values, one entry per event per view. */
        private final Map<String, List<Object[]>> rowsByView = new LinkedHashMap<>();

        /** Whether any event was seen for each view. */
        private final Map<String, Boolean> anyByView = new LinkedHashMap<>();

        private static final Set<String> ORDER_SENSITIVE =
                Set.of("DIFF", "FIRST", "LAST", "LAST_BATCH");

        public Accumulator() {
            for (PView v : REGISTRY) {
                startTimesByView.put(v.viewName(), new ArrayList<>());
                rowsByView.put(v.viewName(), new ArrayList<>());
                anyByView.put(v.viewName(), false);
            }
        }

        /**
         * Feed one source event's column values and its startTime to the accumulator. The caller
         * must supply all column values (null for missing/inapplicable fields) so they remain
         * aligned with the column list when sorted by startTime at build time.
         */
        public void acceptRow(String viewName, Instant startTime, Object[] colValues) {
            long startNanos = startTime.getEpochSecond() * 1_000_000_000L + startTime.getNano();
            startTimesByView.get(viewName).add(new long[] {startNanos});
            rowsByView.get(viewName).add(colValues);
            anyByView.put(viewName, true);
        }

        /**
         * The precomputed views map for the footer; views that saw no source events are omitted.
         */
        public Map<String, List<PrecomputedCell>> build() {
            Map<String, List<PrecomputedCell>> out = new LinkedHashMap<>();
            for (PView v : REGISTRY) {
                if (!anyByView.get(v.viewName())) continue;
                List<long[]> starts = startTimesByView.get(v.viewName());
                List<Object[]> rows = rowsByView.get(v.viewName());
                // Sort rows by startTime if any column is order-sensitive.
                boolean needsSort =
                        v.columns().stream().anyMatch(c -> ORDER_SENSITIVE.contains(c.reducer()));
                List<Object[]> ordered;
                if (needsSort) {
                    Integer[] idx = new Integer[starts.size()];
                    for (int i = 0; i < idx.length; i++) idx[i] = i;
                    java.util.Arrays.sort(idx, Comparator.comparingLong(i -> starts.get(i)[0]));
                    ordered = new ArrayList<>(rows.size());
                    for (int i : idx) ordered.add(rows.get(i));
                } else {
                    ordered = rows;
                }
                Reducer[] rs = new Reducer[v.columns().size()];
                for (int i = 0; i < rs.length; i++) {
                    rs[i] = Aggregators.reducer(v.columns().get(i).reducer()).get();
                }
                for (Object[] row : ordered) {
                    for (int i = 0; i < row.length; i++) {
                        if (row[i] != null) rs[i].accept(row[i]);
                    }
                }
                List<PrecomputedCell> cells = new ArrayList<>(rs.length);
                for (int i = 0; i < rs.length; i++) {
                    cells.add(toCell(rs[i].result(), v.columns().get(i).kind()));
                }
                out.put(v.viewName(), cells);
            }
            return out;
        }
    }

    /** A view column the collector must supply: which field to read (or {@code "*"} for COUNT). */
    public record Column(String field) {}

    /** The columns of the precomputed view for {@code fromType}, or empty if none is registered. */
    public static Optional<List<Column>> columnsFor(String fromType) {
        for (PView v : REGISTRY) {
            if (v.fromType().equals(fromType)) {
                List<Column> cols = new ArrayList<>(v.columns().size());
                for (PColumn c : v.columns()) cols.add(new Column(c.field()));
                return Optional.of(cols);
            }
        }
        return Optional.empty();
    }

    /** The registered view name for {@code fromType}, or empty. */
    public static Optional<String> viewNameFor(String fromType) {
        for (PView v : REGISTRY) {
            if (v.fromType().equals(fromType)) return Optional.of(v.viewName());
        }
        return Optional.empty();
    }

    private static PrecomputedCell toCell(Object result, ColumnType.Kind kind) {
        int k = kind.ordinal();
        if (result == null) {
            return new PrecomputedCell(k, PrecomputedCell.TAG_NULL, 0L, 0.0);
        }
        if (result instanceof Duration d) {
            return new PrecomputedCell(
                    k, PrecomputedCell.TAG_DURATION_NANOS, ValueFormatter.safeToNanos(d), 0.0);
        }
        if (result instanceof Instant i) {
            long nanos = i.getEpochSecond() * 1_000_000_000L + i.getNano();
            return new PrecomputedCell(k, PrecomputedCell.TAG_INSTANT_EPOCH_NANOS, nanos, 0.0);
        }
        if (result instanceof Long l) {
            return new PrecomputedCell(k, PrecomputedCell.TAG_LONG, l, 0.0);
        }
        if (result instanceof Double dbl) {
            return new PrecomputedCell(k, PrecomputedCell.TAG_DOUBLE, 0L, dbl);
        }
        if (result instanceof Number n) {
            // Integer/Float/etc. — a whole number stays LONG, otherwise DOUBLE (mirrors the
            // reducer's own long-vs-double choice so formatting matches the event path).
            double dv = n.doubleValue();
            return dv == Math.rint(dv)
                    ? new PrecomputedCell(k, PrecomputedCell.TAG_LONG, (long) dv, 0.0)
                    : new PrecomputedCell(k, PrecomputedCell.TAG_DOUBLE, 0L, dv);
        }
        return new PrecomputedCell(k, PrecomputedCell.TAG_NULL, 0L, 0.0);
    }

    // ── serve side ────────────────────────────────────────────────────────────

    /**
     * Render a FORM view directly from footer {@link PrecomputedCell}s, with no event scan.
     * Produces the identical lines the event-based {@link NativeView#render} would (same {@link
     * ViewRenderer}, same values, same kinds). Returns empty for an unknown/unparseable view.
     */
    public static Optional<List<String>> render(
            String viewName, List<PrecomputedCell> cellsIn, NativeView.Options options) {
        List<Object> values = new ArrayList<>(cellsIn.size());
        ColumnType.Kind[] kinds = new ColumnType.Kind[cellsIn.size()];
        ColumnType.Kind[] all = ColumnType.Kind.values();
        for (int i = 0; i < cellsIn.size(); i++) {
            PrecomputedCell c = cellsIn.get(i);
            kinds[i] =
                    c.kindOrdinal() >= 0 && c.kindOrdinal() < all.length
                            ? all[c.kindOrdinal()]
                            : ColumnType.Kind.PLAIN;
            values.add(valueOf(c));
        }
        return NativeView.renderPrecomputedRow(viewName, values, kinds, options);
    }

    private static Object valueOf(PrecomputedCell c) {
        return switch (c.typeTag()) {
            case PrecomputedCell.TAG_LONG -> c.longBits();
            case PrecomputedCell.TAG_DOUBLE -> c.doubleVal();
            case PrecomputedCell.TAG_DURATION_NANOS -> Duration.ofNanos(c.longBits());
            case PrecomputedCell.TAG_INSTANT_EPOCH_NANOS ->
                    Instant.ofEpochSecond(
                            Math.floorDiv(c.longBits(), 1_000_000_000L),
                            Math.floorMod(c.longBits(), 1_000_000_000L));
            default -> null;
        };
    }
}
