package me.bechberger.jfr.cli.query;

import java.util.List;

/**
 * Immutable AST for a parsed {@code view.ini} query.
 *
 * <p>The parser only produces the narrow subset of the grammar that current JDKs actually use, so
 * this AST is intentionally small: WHERE is a flat list of equalities (no boolean tree), FROM is a
 * list of event-type items, and expressions are either a field path or an aggregate over one.
 */
public record ViewQuery(
        Shape shape,
        List<String> columnLabels, // COLUMN clause; empty if absent
        List<FormatHint> formatHints, // FORMAT clause; empty if absent
        List<SelectItem> select,
        List<FromItem> from,
        List<Equality> where, // AND of equalities; empty if no WHERE
        List<String> groupBy, // field paths; empty if none
        List<OrderItem> orderBy, // empty if none
        int limit) { // -1 if no LIMIT

    /** Vertical key/value ({@code form}) or row-oriented ({@code table}). */
    public enum Shape {
        FORM,
        TABLE
    }

    /** A selected expression, optionally aliased via {@code AS}. */
    public record SelectItem(Expr expr, String alias) {}

    /**
     * An event type in the FROM list, optionally aliased (self-join alias; triggers delegation).
     */
    public record FromItem(String type, String alias) {}

    /** A {@code field = 'value'} equality from the WHERE clause. */
    public record Equality(FieldPath field, String value) {}

    /**
     * An ORDER BY term; {@code ref} may be a SELECT alias or a field path. {@code direction} is
     * {@code DEFAULT} when the query gave no ASC/DESC keyword — the evaluator then picks a direction
     * from the referenced expression (aggregates sort descending, plain fields ascending), matching
     * {@code jfr view}.
     */
    public record OrderItem(String ref, Direction direction) {
        public enum Direction {
            DEFAULT,
            ASC,
            DESC
        }
    }

    /** A FORMAT hint: bare {@code name} (value null) or {@code name:value}. */
    public record FormatHint(String name, String value) {}

    // ── expressions ────────────────────────────────────────────────────────

    public sealed interface Expr permits FieldPath, Coalesce, Aggregate, Star {}

    /**
     * A dotted field access, e.g. {@code duration} or {@code stackTrace.topFrame}. In a join query
     * the leading part may be a FROM alias (e.g. {@code G.startTime}); the evaluator decides.
     */
    public record FieldPath(List<String> parts) implements Expr {
        public String joined() {
            return String.join(".", parts);
        }
    }

    /**
     * An alias-alternation (coalesce) access from a join query, e.g. {@code [Y|O].eventType.label}.
     * The value is taken from whichever of {@code aliases} produced a row for the current group; the
     * remaining {@code parts} are the dotted field path applied to that row.
     */
    public record Coalesce(List<String> aliases, List<String> parts) implements Expr {
        public String joinedParts() {
            return String.join(".", parts);
        }
    }

    /**
     * An aggregate function over an inner expression, e.g. {@code AVG(duration)}, {@code COUNT(*)}.
     */
    public record Aggregate(String function, Expr arg) implements Expr {}

    /** The {@code *} argument, used only as {@code COUNT(*)}. */
    public record Star() implements Expr {}
}
