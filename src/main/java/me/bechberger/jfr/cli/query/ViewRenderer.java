package me.bechberger.jfr.cli.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.cli.query.QueryEvaluator.Row;
import me.bechberger.jfr.cli.query.ViewQuery.FormatHint;
import me.bechberger.jfr.cli.query.ViewQuery.Shape;

/**
 * Renders evaluated {@link Row}s into the text layout produced by JDK {@code jfr view}: a centered
 * title, then either a key/value {@code form} or a columnar {@code table} with a dashed header
 * separator. Column widths are sized to content (header label vs widest cell), matching {@code jfr
 * view}'s adaptive layout; numeric/unit columns are right-aligned, text left-aligned.
 */
final class ViewRenderer {

    private final ViewQuery query;
    private final String title;
    private final List<String> labels;
    private final ColumnType.Columns columns;
    private final int termWidth;
    private final Integer cliCellHeight;
    private final boolean truncateBeginning;
    private final QueryEvaluator evaluator;

    ViewRenderer(
            ViewQuery query,
            String title,
            ColumnType.Columns columns,
            int termWidth,
            Integer cellHeight,
            boolean truncateBeginning,
            Map<String, List<ReadStruct>> eventsByType,
            QueryEvaluator evaluator) {
        this.query = query;
        this.title = title;
        this.labels = resolveLabels(query, eventsByType);
        this.columns = columns;
        this.termWidth = termWidth;
        this.cliCellHeight = cellHeight;
        this.truncateBeginning = truncateBeginning;
        this.evaluator = evaluator;
    }

    /**
     * The maximum physical line count for a cell in column {@code col}. The CLI {@code
     * --cell-height} (when the user passed one) overrides everything; otherwise the column's
     * view.ini {@code cell-height:N} FORMAT value applies (e.g. environment-variables → 20);
     * otherwise a cell is a single line. Always at least 1.
     */
    private int cellHeightFor(int col) {
        if (cliCellHeight != null) {
            return Math.max(1, cliCellHeight);
        }
        List<FormatHint> hints = query.formatHints();
        if (col < hints.size()) {
            FormatHint h = hints.get(col);
            if (h != null && "cell-height".equals(h.name()) && h.value() != null) {
                try {
                    return Math.max(1, Integer.parseInt(h.value().trim()));
                } catch (NumberFormatException ignore) {
                    // fall through to default
                }
            }
        }
        return 1;
    }

    /** The content kind for a column, or PLAIN if none was resolved for that slot. */
    private ColumnType.Kind kindFor(int col) {
        ColumnType.Kind[] kinds = columns == null ? null : columns.kinds();
        return kinds != null && col < kinds.length ? kinds[col] : ColumnType.Kind.PLAIN;
    }

    /** Whether a column is flexible (text-like) and should absorb leftover terminal width. */
    private boolean flexibleFor(int col) {
        boolean[] flex = columns == null ? null : columns.flexible();
        return flex != null && col < flex.length && flex[col];
    }

    /**
     * Column header labels: from the COLUMN clause when present; otherwise the field's declared
     * metadata label (e.g. {@code initialSize} → "Initial Heap Size"), falling back to the raw
     * field path when no metadata label resolves. A SELECT {@code AS} alias is <em>not</em> a
     * display label — it only names the column for ORDER BY references — so it is not used here.
     */
    static List<String> resolveLabels(ViewQuery query, Map<String, List<ReadStruct>> eventsByType) {
        if (!query.columnLabels().isEmpty()) {
            return query.columnLabels();
        }
        // jfr relabels a bare startTime column "Time" only for an instantaneous/sampled event —
        // i.e. one whose SELECT has no duration column (cpu-load-samples → "Time"). When a duration
        // column is present the event is an interval and startTime keeps its "Start Time" label
        // (longest-compilations, blocked-by-system-gc).
        boolean hasDuration =
                query.select().stream()
                        .anyMatch(
                                s ->
                                        s.expr() instanceof ViewQuery.FieldPath fp
                                                && fp.parts().size() == 1
                                                && "duration".equals(fp.parts().get(0)));
        List<String> out = new ArrayList<>();
        for (var item : query.select()) {
            // A COUNT column with no explicit COLUMN clause is headed "Count" by jfr view — it does
            // not inherit the counted field's label (COUNT(reason) → "Count", not "Reason"). Other
            // aggregates (LAST/FIRST/SUM/…) do surface their argument field's metadata label.
            if (item.expr() instanceof ViewQuery.Aggregate agg
                    && "COUNT".equalsIgnoreCase(agg.function())) {
                out.add("Count");
                continue;
            }
            // A bare startTime column with no explicit COLUMN clause is headed "Time" by jfr view
            // (not its metadata label "Start Time") when the row is instantaneous — no duration
            // column alongside it (same convention as a SELECT * view).
            if (!hasDuration
                    && item.expr() instanceof ViewQuery.FieldPath fp
                    && fp.parts().size() == 1
                    && "startTime".equals(fp.parts().get(0))) {
                out.add("Time");
                continue;
            }
            String metaLabel = ColumnType.labelFor(item.expr(), query, eventsByType);
            if (metaLabel != null) {
                out.add(aggregatePrefix(item.expr()) + metaLabel);
            } else if (item.expr() instanceof ViewQuery.FieldPath fp) {
                out.add(fp.joined());
            } else if (item.expr() instanceof ViewQuery.Aggregate agg
                    && agg.arg() instanceof ViewQuery.FieldPath fp) {
                // No metadata label resolved for the aggregate's field — typically the field is
                // absent from this recording's event type (e.g. LAST(dynamicCompilerThreadCount)
                // on a JDK that lacks it). Fall back to the raw field path so the row stays
                // identifiable instead of rendering a blank label (": N/A").
                out.add(aggregatePrefix(item.expr()) + fp.joined());
            } else {
                out.add("");
            }
        }
        return out;
    }

    /**
     * The label prefix {@code jfr view} prepends to a statistical aggregate's derived field label
     * when the query has no explicit COLUMN clause: {@code AVG(readRate)} → "Avg. Read Rate",
     * {@code MAX} → "Max. ", {@code MIN} → "Min. ". Other aggregates
     * (LAST/FIRST/SUM/LAST_BATCH/percentiles) surface the bare field label with no prefix (verified
     * against network-utilization, which is the only bundled view relying on derived aggregate
     * labels; COLUMN-clause views like contention-by-class supply their own "Avg."/"P90" headers).
     * Returns "" for non-prefixed cases.
     */
    private static String aggregatePrefix(ViewQuery.Expr expr) {
        if (expr instanceof ViewQuery.Aggregate agg) {
            switch (agg.function().toUpperCase(java.util.Locale.ROOT)) {
                case "AVG":
                    return "Avg. ";
                case "MAX":
                    return "Max. ";
                case "MIN":
                    return "Min. ";
                default:
                    return "";
            }
        }
        return "";
    }

    private FormatHint hintFor(int col) {
        // FORMAT hints are positional per column slot; a bare "none" occupies a slot with no
        // effect.
        List<FormatHint> hints = query.formatHints();
        if (col < hints.size()) {
            FormatHint h = hints.get(col);
            if (h != null && !"none".equals(h.name())) {
                return h;
            }
        }
        return null;
    }

    List<String> render(List<Row> rows) {
        return query.shape() == Shape.FORM ? renderForm(rows) : renderTable(rows);
    }

    // ── form (vertical key/value) ────────────────────────────────────────────

    private List<String> renderForm(List<Row> rows) {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add(title);
        out.add("-".repeat(title.length()));
        // A form shows a single (aggregated) row; each SELECT column becomes a "Label: value"
        // block.
        Row row = rows.isEmpty() ? null : rows.get(0);
        for (int c = 0; c < labels.size(); c++) {
            out.add("");
            Object raw = row == null ? null : (c < row.cells().size() ? row.cells().get(c) : null);
            String value = sanitize(ValueFormatter.format(raw, hintFor(c), kindFor(c)));
            String prefix = labels.get(c) + ": ";
            out.addAll(wrapForm(prefix, value));
        }
        return out;
    }

    /**
     * Wrap a form entry {@code "Label: value"} to the terminal width the way {@code jfr view} does:
     * a hard character break at {@code termWidth - 1} columns, with continuation lines indented by
     * the label prefix's width. Short values stay on one line.
     */
    private List<String> wrapForm(String prefix, String value) {
        int lineWidth = termWidth - 1;
        int avail = lineWidth - prefix.length();
        // Degenerate prefixes (>= line width) or non-positive room: emit unwrapped.
        if (avail <= 0 || prefix.length() + value.length() <= lineWidth) {
            return List.of(prefix + value);
        }
        List<String> out = new ArrayList<>();
        String indent = " ".repeat(prefix.length());
        int pos = 0;
        boolean firstLine = true;
        while (pos < value.length()) {
            int chunk = Math.min(avail, value.length() - pos);
            String piece = value.substring(pos, pos + chunk);
            out.add((firstLine ? prefix : indent) + piece);
            pos += chunk;
            firstLine = false;
        }
        return out;
    }

    // ── table (columnar) ──────────────────────────────────────────────────────

    private List<String> renderTable(List<Row> rows) {
        int nCols = labels.size();
        String[][] cells = new String[rows.size()][nCols];
        boolean[] rightAlign = new boolean[nCols];
        boolean[] instant = new boolean[nCols];
        boolean[] anyValue = new boolean[nCols];
        // A "normalized" FORMAT column renders each numeric cell as its share of the column total
        // (a percentage). Precompute the per-column raw sums so each cell can divide by them.
        double[] colSum = new double[nCols];
        boolean[] normalized = new boolean[nCols];
        for (int c = 0; c < nCols; c++) {
            normalized[c] = normalizedFor(c);
            if (normalized[c]) {
                // jfr's normalized denominator is the column total over ALL groups (pre-LIMIT), not
                // just the rows that survive LIMIT, so read it from the evaluator when available.
                double total = evaluator != null ? evaluator.preLimitColumnTotal(c) : 0.0;
                if (total != 0.0) {
                    colSum[c] = total;
                } else {
                    for (Row row : rows) {
                        Object raw = c < row.cells().size() ? row.cells().get(c) : null;
                        if (raw instanceof Number n) colSum[c] += n.doubleValue();
                    }
                }
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            for (int c = 0; c < nCols; c++) {
                Object raw = c < row.cells().size() ? row.cells().get(c) : null;
                if (normalized[c]) {
                    double frac =
                            (raw instanceof Number n && colSum[c] != 0)
                                    ? n.doubleValue() / colSum[c]
                                    : 0.0;
                    cells[r][c] = ValueFormatter.formatPercentage(frac);
                    if (raw != null) {
                        anyValue[c] = true;
                        rightAlign[c] = true;
                    }
                    continue;
                }
                // A List-valued cell (e.g. from SET()) with cell-height > 1: render each
                // element on its own physical line (oracle inserts one row per element).
                // With cell-height 1 the oracle comma-joins them into one line — same as our
                // existing ValueFormatter.format(List) path. We use \n as an intra-cell separator;
                // wrapCell splits on \n before hard-wrapping individual sub-lines.
                if (raw instanceof List<?> list && cellHeightFor(c) > 1) {
                    var sb = new StringBuilder();
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(ValueFormatter.format(list.get(i), hintFor(c), kindFor(c)));
                    }
                    cells[r][c] = sb.toString();
                } else {
                    cells[r][c] = sanitize(ValueFormatter.format(raw, hintFor(c), kindFor(c)));
                }
                if (raw != null) {
                    anyValue[c] = true;
                    if (raw instanceof java.time.Instant) {
                        instant[c] = true;
                    }
                    // jfr left-aligns time (Instant) columns for both header and data; numeric,
                    // unit, and boolean values are right-aligned.
                    if (!(raw instanceof java.time.Instant)
                            && (isNumericLike(raw)
                                    || raw instanceof Boolean
                                    || kindFor(c) != ColumnType.Kind.PLAIN)) {
                        rightAlign[c] = true;
                    }
                }
            }
        }
        // jfr right-aligns a numeric header like its data, but time (Instant) headers stay left.
        boolean[] headerRight = new boolean[nCols];
        for (int c = 0; c < nCols; c++) {
            headerRight[c] = rightAlign[c] && !instant[c];
        }
        // Fixed columns size to their widest cell (or header). Flexible columns also start from
        // their content width as a floor, then take a share of leftover terminal width (below).
        int[] widths = new int[nCols];
        for (int c = 0; c < nCols; c++) {
            int w = labels.get(c).length();
            for (int r = 0; r < rows.size(); r++) {
                // A \n-separated cell (multi-line list) contributes the width of the longest line.
                String cell = cells[r][c];
                if (cell.indexOf('\n') >= 0) {
                    for (String line : cell.split("\n", -1)) {
                        w = Math.max(w, line.length());
                    }
                } else {
                    w = Math.max(w, cell.length());
                }
            }
            widths[c] = w;
        }
        distributeFlexibleWidth(widths);

        // After widths are finalized: compact-format any method/frame cells that exceed
        // their column width. Oracle's TableRenderer does the same: when a formatted
        // method string is longer than the column, it falls back to formatCompact() which
        // renders "ClassName.methodName(...)" — hiding the parameter types. For class-name
        // cells (no parens, only dots/dollars/brackets) oracle uses the simple class name
        // (everything after the last dot), e.g. "TypedFieldValueImpl" or "539690370".
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < nCols; c++) {
                String cell = cells[r][c];
                if (cell.length() > widths[c]) {
                    String compact = compactMethod(cell);
                    if (compact == null) compact = compactClass(cell);
                    if (compact != null && compact.length() < cell.length()) {
                        cells[r][c] = compact;
                    }
                }
            }
        }

        List<String> out = new ArrayList<>();
        int totalWidth = 0;
        for (int c = 0; c < nCols; c++) totalWidth += widths[c];
        totalWidth += nCols - 1;

        out.add("");
        out.add(center(title, totalWidth));
        out.add("");
        out.add(headerLine(widths, headerRight));
        out.add(separatorLine(widths));
        for (int r = 0; r < rows.size(); r++) {
            out.addAll(renderRow(cells[r], widths, rightAlign));
        }
        return out;
    }

    /**
     * Render one data row, wrapping any cell that overflows its column width into extra physical
     * lines the way {@code jfr view} does: each cell is broken at a hard character boundary into
     * chunks of its column width, the row's line count is the tallest cell, and cells shorter than
     * that leave blanks on the continuation lines. Non-wrapping cells appear only on the first
     * line.
     */
    private List<String> renderRow(String[] cells, int[] widths, boolean[] rightAlign) {
        int nCols = cells.length;
        // Split each cell into up to cellHeight width-sized lines; a cell within width is one line.
        List<List<String>> chunks = new ArrayList<>(nCols);
        int lineCount = 1;
        for (int c = 0; c < nCols; c++) {
            chunks.add(wrapCell(cells[c], widths[c], cellHeightFor(c), truncateBeginningFor(c)));
            lineCount = Math.max(lineCount, chunks.get(c).size());
        }
        List<String> out = new ArrayList<>(lineCount);
        for (int line = 0; line < lineCount; line++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < nCols; c++) {
                if (c > 0) sb.append(" ");
                List<String> cc = chunks.get(c);
                String piece = line < cc.size() ? cc.get(line) : "";
                sb.append(pad(piece, widths[c], rightAlign[c]));
            }
            // jfr pads every physical row line out to the full table width (trailing spaces kept),
            // so continuation lines of a wrapped cell and short trailing cells are space-filled.
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * Break {@code s} to fit column {@code width} across at most {@code maxLines} physical lines,
     * the way {@code jfr view} does. A string within width is a single line. Otherwise it wraps
     * into {@code width}-char lines; if it needs more than {@code maxLines} lines, the content that
     * would not fit is elided with a three-dot ellipsis on the boundary line — at the end of the
     * last kept line for {@code --truncate end}, or at the start of the first line (keeping the
     * tail) for {@code --truncate beginning}. With {@code maxLines == 1} this collapses to
     * single-line truncation ({@code "GC Phase P..."} / {@code "...ase Level 1"}).
     *
     * <p>When {@code s} contains {@code \n} separators (a multi-element SET() cell), each sub-line
     * is hard-wrapped independently; the resulting lines are concatenated and then truncated to
     * {@code maxLines} as a whole.
     */
    private List<String> wrapCell(String s, int width, int maxLines, boolean colTruncateBeginning) {
        // Multi-element cells use \n as an intra-cell separator. Split, wrap each sub-line, then
        // apply the maxLines cap to the combined result.
        if (s.indexOf('\n') >= 0) {
            List<String> all = new ArrayList<>();
            for (String sub : s.split("\n", -1)) {
                all.addAll(hardWrap(sub, width));
                if (all.size() >= maxLines) break;
            }
            if (all.size() <= maxLines) return all;
            // Truncate to maxLines, replacing the last kept line with an ellipsis suffix/prefix.
            return truncateLines(all, maxLines, width, truncateBeginning || colTruncateBeginning);
        }
        if (width <= 0 || s.length() <= width) return List.of(s);
        int capacity = width * maxLines;
        if (s.length() <= capacity) {
            return hardWrap(s, width);
        }
        // Content exceeds the visible box: keep (capacity - 3) characters and mark the elision.
        String ell = "...";
        int keep = Math.max(0, capacity - ell.length());
        boolean tb = truncateBeginning || colTruncateBeginning;
        String kept =
                tb
                        ? ell + s.substring(s.length() - keep)
                        : s.substring(0, keep) + ell;
        return hardWrap(kept, width);
    }

    /** Truncate a line list to {@code maxLines}, appending/prepending "..." on the boundary. */
    private List<String> truncateLines(List<String> lines, int maxLines, int width, boolean tb) {
        if (tb) {
            // Keep the last maxLines lines, prefix the first kept line with "...".
            List<String> kept = lines.subList(lines.size() - maxLines, lines.size());
            List<String> out = new ArrayList<>(kept);
            String first = out.get(0);
            out.set(0, ("..." + first).substring(0, Math.min(("..." + first).length(), width)));
            return out;
        } else {
            List<String> out = new ArrayList<>(lines.subList(0, maxLines));
            String last = out.get(maxLines - 1);
            String truncated =
                    last.length() + 3 <= width
                            ? last + "..."
                            : last.substring(0, Math.max(0, width - 3)) + "...";
            out.set(maxLines - 1, truncated);
            return out;
        }
    }

    /**
     * Hard-break {@code s} into {@code width}-char lines (no ellipsis); a short string is one line.
     */
    private static List<String> hardWrap(String s, int width) {
        if (width <= 0 || s.length() <= width) return List.of(s);
        List<String> out = new ArrayList<>();
        for (int pos = 0; pos < s.length(); pos += width) {
            out.add(s.substring(pos, Math.min(pos + width, s.length())));
        }
        return out;
    }

    /** True if column {@code col} carries a {@code cell-height} FORMAT hint (its content wraps). */
    private boolean shrinkable(int col) {
        List<FormatHint> hints = query.formatHints();
        if (col < hints.size()) {
            FormatHint h = hints.get(col);
            return h != null && "cell-height".equals(h.name());
        }
        return false;
    }

    /** True if column {@code col} carries a {@code truncate-beginning} FORMAT hint. */
    private boolean truncateBeginningFor(int col) {
        List<FormatHint> hints = query.formatHints();
        if (col < hints.size()) {
            FormatHint h = hints.get(col);
            return h != null && "truncate-beginning".equals(h.name());
        }
        return false;
    }

    /**
     * If {@code s} looks like a fully-qualified Java method signature
     * ("pkg.Class.method(Param, ...)"), return the compact form "pkg.Class.method(...)", else null.
     * Oracle does this when a method cell is too wide for its column — it calls formatCompact(),
     * which replaces the parameter list with "..." (but keeps the empty-parens form when there are
     * no parameters, matching oracle's isEmpty() check).
     */
    private static String compactMethod(String s) {
        int open = s.lastIndexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close != s.length() - 1) return null;
        // Must have a dot before the open paren (method separator).
        int dot = s.lastIndexOf('.', open);
        if (dot < 0) return null;
        String params = s.substring(open + 1, close);
        // Empty params → already compact; no transformation needed.
        if (params.isEmpty()) return null;
        return s.substring(0, open + 1) + "..." + ")";
    }

    /**
     * If {@code s} looks like a Java class name (no spaces, no parentheses, contains a dot),
     * return the simple class name (everything after the last dot), else null. Oracle uses this
     * when a class-typed cell exceeds its column width, e.g. showing "TypedFieldValueImpl"
     * instead of "org.openjdk.jmc.flightrecorder.writer.TypedFieldValueImpl", or "539690370"
     * for a hidden-class ID like "...$$Lambda$N+0x...HEX.539690370".
     */
    private static String compactClass(String s) {
        if (s.isEmpty()) return null;
        // Exclude methods (already handled by compactMethod) and anything with spaces.
        if (s.contains("(") || s.contains(")") || s.contains(" ")) return null;
        int lastDot = s.lastIndexOf('.');
        if (lastDot < 0 || lastDot == s.length() - 1) return null;
        return s.substring(lastDot + 1);
    }

    /**
     * True if column {@code col} carries a {@code normalized} FORMAT hint (render as % of total).
     */
    private boolean normalizedFor(int col) {
        List<FormatHint> hints = query.formatHints();
        if (col < hints.size()) {
            FormatHint h = hints.get(col);
            return h != null && "normalized".equals(h.name());
        }
        return false;
    }

    /**
     * Size the table to the terminal width. {@code jfr view} sizes every column to its preferred
     * (content/header) width, then reconciles the total against a fill target:
     *
     * <ul>
     *   <li><b>Target.</b> Views with a {@code cell-height} (wrapping) column fill to {@code width
     *       - 1}; views without one fill to {@code width - 2 + flexCount} (a single flexible column
     *       stops one short of the terminal, two fill it exactly, and so on).
     *   <li><b>Grow.</b> When the preferred total is under target, the leftover is split evenly
     *       among the flexible (text-like) columns; the last flexible column absorbs the rounding
     *       remainder.
     *   <li><b>Shrink.</b> When the preferred total exceeds target, the wrapping ({@code
     *       cell-height}) columns give back the overflow (splitting it evenly) and their content
     *       wraps; non-wrapping columns keep their preferred width.
     * </ul>
     */
    private void distributeFlexibleWidth(int[] widths) {
        int nCols = widths.length;
        List<Integer> flexIdx = new ArrayList<>();
        List<Integer> shrinkIdx = new ArrayList<>();
        for (int c = 0; c < nCols; c++) {
            if (flexibleFor(c)) flexIdx.add(c);
            if (shrinkable(c)) shrinkIdx.add(c);
        }
        if (flexIdx.isEmpty() && shrinkIdx.isEmpty()) return;

        int used = nCols - 1; // single-space separators
        for (int w : widths) used += w;

        int target = shrinkIdx.isEmpty() ? termWidth + flexIdx.size() - 2 : termWidth - 1;
        int delta = target - used;

        if (delta > 0) {
            if (flexIdx.isEmpty()) return;
            int per = delta / flexIdx.size();
            int extra = delta - per * flexIdx.size();
            for (int i = 0; i < flexIdx.size(); i++) {
                int c = flexIdx.get(i);
                widths[c] += per + (i == flexIdx.size() - 1 ? extra : 0);
            }
        } else if (delta < 0 && !shrinkIdx.isEmpty()) {
            // Overflow: the wrapping columns give back the excess (evenly), then wrap their
            // content.
            int over = -delta;
            int per = over / shrinkIdx.size();
            int extra = over - per * shrinkIdx.size();
            for (int i = 0; i < shrinkIdx.size(); i++) {
                int c = shrinkIdx.get(i);
                int give = per + (i == shrinkIdx.size() - 1 ? extra : 0);
                widths[c] = Math.max(labels.get(c).length(), widths[c] - give);
            }
        } else if (delta < 0 && !flexIdx.isEmpty()) {
            // Overflow with no wrapping column: the flexible (text) columns share the available
            // budget evenly and their content is truncated with an ellipsis. jfr assigns each
            // flexible column an equal slice of the leftover width (not a proportional shrink from
            // its preferred size), the last column absorbing the rounding remainder — reproduced
            // from observed output (gc-pause-phases w80 → two 13-wide flex cols; vm-operations w80
            // → single 24-wide flex col). A flex column whose preferred width already fits within
            // its fair share keeps that smaller width, and the space it frees is re-split among the
            // still-oversized flex columns.
            int fixed = used;
            for (int c : flexIdx) fixed -= widths[c];
            int budget = target - fixed;
            if (budget < 0) budget = 0;
            distributeBudgetEvenly(widths, flexIdx, budget);
        }
    }

    /**
     * Give each column in {@code idx} an equal slice of {@code budget}, but never more than its
     * current (preferred) width and never below its header label width. Columns that fit under
     * their fair share are fixed first; the space they free is re-split among the remaining
     * oversized columns, repeating until stable. The last still-flexible column absorbs the
     * rounding remainder.
     */
    private void distributeBudgetEvenly(int[] widths, List<Integer> idx, int budget) {
        List<Integer> remaining = new ArrayList<>(idx);
        int pool = budget;
        boolean changed = true;
        while (changed && !remaining.isEmpty()) {
            changed = false;
            int share = pool / remaining.size();
            for (var it = remaining.iterator(); it.hasNext(); ) {
                int c = it.next();
                int floor = labels.get(c).length();
                int fits = Math.max(widths[c], floor) <= share ? Math.max(widths[c], floor) : -1;
                if (fits >= 0 && widths[c] <= share) {
                    widths[c] = Math.max(widths[c], floor);
                    pool -= widths[c];
                    it.remove();
                    changed = true;
                }
            }
        }
        if (remaining.isEmpty()) return;
        int per = pool / remaining.size();
        int extra = pool - per * remaining.size();
        for (int i = 0; i < remaining.size(); i++) {
            int c = remaining.get(i);
            int w = per + (i == remaining.size() - 1 ? extra : 0);
            widths[c] = Math.max(labels.get(c).length(), w);
        }
    }

    private String headerLine(int[] widths, boolean[] headerRight) {
        // jfr right-aligns numeric headers like their data; text and time headers stay left. The
        // header row is padded to the full table width (trailing spaces kept), like data rows.
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < labels.size(); c++) {
            if (c > 0) sb.append(" ");
            sb.append(pad(labels.get(c), widths[c], headerRight[c]));
        }
        return sb.toString();
    }

    private String separatorLine(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) sb.append(" ");
            sb.append("-".repeat(widths[c]));
        }
        return sb.toString();
    }

    private static boolean isNumericLike(Object raw) {
        return raw instanceof Number
                || raw instanceof java.time.Duration
                || raw instanceof java.time.Instant;
    }

    /** Collapse embedded control chars (newline/CR/tab) to spaces, as {@code jfr view} does. */
    private static String sanitize(String s) {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0 && s.indexOf('\t') < 0) return s;
        return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static String pad(String s, int width, boolean right) {
        if (s.length() >= width) return s;
        String padding = " ".repeat(width - s.length());
        return right ? padding + s : s + padding;
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s;
        // jfr rounds the leading pad up when the remainder is odd.
        int pad = (width - s.length() + 1) / 2;
        return " ".repeat(pad) + s;
    }
}
