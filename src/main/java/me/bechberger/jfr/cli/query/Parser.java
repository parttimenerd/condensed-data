package me.bechberger.jfr.cli.query;

import java.util.ArrayList;
import java.util.List;
import me.bechberger.jfr.cli.query.ViewQuery.Aggregate;
import me.bechberger.jfr.cli.query.ViewQuery.Coalesce;
import me.bechberger.jfr.cli.query.ViewQuery.Equality;
import me.bechberger.jfr.cli.query.ViewQuery.Expr;
import me.bechberger.jfr.cli.query.ViewQuery.FieldPath;
import me.bechberger.jfr.cli.query.ViewQuery.FormatHint;
import me.bechberger.jfr.cli.query.ViewQuery.FromItem;
import me.bechberger.jfr.cli.query.ViewQuery.OrderItem;
import me.bechberger.jfr.cli.query.ViewQuery.SelectItem;
import me.bechberger.jfr.cli.query.ViewQuery.Shape;
import me.bechberger.jfr.cli.query.ViewQuery.Star;

/**
 * Recursive-descent parser for a {@code view.ini} query body.
 *
 * <p>Deliberately narrow: it accepts only the shapes current JDKs (21–27) actually emit. WHERE is a
 * flat {@code AND} of {@code field = 'value'} equalities; there is no boolean tree. Any syntax
 * outside this subset — {@code OR}/{@code NOT}, inequalities, {@code HAVING}, parenthesized
 * predicates — tokenizes fine (the lexer is wide) but is rejected here with a {@link
 * QueryParseException}, so the caller can delegate the view to {@code jfr view}.
 */
public final class Parser {

    private final List<Token> tokens;
    private int p = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * Parse a query body (the string value of a {@code form =} / {@code table =} entry). The shape
     * is supplied by the caller since it comes from the ini key, not the query text.
     */
    public static ViewQuery parse(Shape shape, String query) {
        var parser = new Parser(new Lexer(query).tokenize());
        return parser.parseQuery(shape);
    }

    private ViewQuery parseQuery(Shape shape) {
        List<String> columnLabels = check(TokenType.COLUMN) ? parseColumn() : List.of();
        List<FormatHint> formatHints = check(TokenType.FORMAT) ? parseFormat() : List.of();

        expect(TokenType.SELECT);
        List<SelectItem> select = parseSelectList();

        expect(TokenType.FROM);
        List<FromItem> from = parseFromList();

        List<Equality> where = check(TokenType.WHERE) ? parseWhere() : List.of();

        if (check(TokenType.HAVING)) {
            throw err("HAVING is not supported");
        }

        List<String> groupBy = List.of();
        if (accept(TokenType.GROUP)) {
            expect(TokenType.BY);
            groupBy = parseFieldPathList();
        }

        List<OrderItem> orderBy = List.of();
        if (accept(TokenType.ORDER)) {
            expect(TokenType.BY);
            orderBy = parseOrderList();
        }

        int limit = -1;
        if (accept(TokenType.LIMIT)) {
            limit = Integer.parseInt(expect(TokenType.NUMBER).text());
        }

        expect(TokenType.EOF);
        return new ViewQuery(
                shape, columnLabels, formatHints, select, from, where, groupBy, orderBy, limit);
    }

    // ── COLUMN / FORMAT ──────────────────────────────────────────────────────

    private List<String> parseColumn() {
        expect(TokenType.COLUMN);
        List<String> labels = new ArrayList<>();
        do {
            labels.add(expect(TokenType.STRING).text());
        } while (accept(TokenType.COMMA));
        return labels;
    }

    private List<FormatHint> parseFormat() {
        expect(TokenType.FORMAT);
        List<FormatHint> hints = new ArrayList<>();
        do {
            String name = expect(TokenType.IDENT).text();
            String value = null;
            if (accept(TokenType.COLON)) {
                // value may be an ident, a number, or a quoted string
                Token v = advance();
                value =
                        switch (v.type()) {
                            case IDENT, NUMBER, STRING -> v.text();
                            default -> throw err("expected FORMAT hint value");
                        };
            }
            hints.add(new FormatHint(name, value));
            // Hints are separated by ',' between column slots and by ';' when several are stacked
            // on one column (e.g. "cell-height:10;truncate-beginning"). We flatten both; the
            // renderer applies hints it recognizes and ignores the rest.
        } while (accept(TokenType.COMMA) || accept(TokenType.SEMICOLON));
        return hints;
    }

    // ── SELECT ───────────────────────────────────────────────────────────────

    private List<SelectItem> parseSelectList() {
        List<SelectItem> items = new ArrayList<>();
        do {
            // Bare `SELECT *` selects all fields (e.g. thread-count); represent it as a top-level
            // Star select item. `*` as an aggregate argument (COUNT(*)) is handled in parseExpr.
            Expr expr = accept(TokenType.STAR) ? new Star() : parseExpr();
            String alias = null;
            if (accept(TokenType.AS)) {
                alias = expect(TokenType.IDENT).text();
            }
            items.add(new SelectItem(expr, alias));
        } while (accept(TokenType.COMMA));
        return items;
    }

    private Expr parseExpr() {
        // aggregate:  IDENT '(' (STAR | coalesce | fieldPath) ')'
        if (check(TokenType.IDENT) && checkAt(1, TokenType.LPAREN)) {
            String fn = advance().text();
            expect(TokenType.LPAREN);
            Expr arg;
            if (accept(TokenType.STAR)) {
                arg = new Star();
            } else if (check(TokenType.LBRACKET)) {
                arg = parseCoalesce();
            } else {
                arg = parseFieldPath();
            }
            expect(TokenType.RPAREN);
            return new Aggregate(fn, arg);
        }
        if (check(TokenType.LBRACKET)) {
            return parseCoalesce();
        }
        return parseFieldPath();
    }

    /**
     * Alias-alternation (coalesce): {@code '[' IDENT ('|' IDENT)+ ']' ('.' IDENT)*}. Used in join
     * views to take a field from whichever aliased event produced a row for the current group, e.g.
     * {@code [Y|O].eventType.label}.
     */
    private Coalesce parseCoalesce() {
        expect(TokenType.LBRACKET);
        List<String> aliases = new ArrayList<>();
        aliases.add(expect(TokenType.IDENT).text());
        while (accept(TokenType.PIPE)) {
            aliases.add(expect(TokenType.IDENT).text());
        }
        expect(TokenType.RBRACKET);
        List<String> parts = new ArrayList<>();
        while (accept(TokenType.DOT)) {
            parts.add(expect(TokenType.IDENT).text());
        }
        return new Coalesce(aliases, parts);
    }

    private FieldPath parseFieldPath() {
        List<String> parts = new ArrayList<>();
        parts.add(expect(TokenType.IDENT).text());
        while (accept(TokenType.DOT)) {
            parts.add(expect(TokenType.IDENT).text());
        }
        return new FieldPath(parts);
    }

    private List<String> parseFieldPathList() {
        List<String> paths = new ArrayList<>();
        do {
            paths.add(parseFieldPath().joined());
        } while (accept(TokenType.COMMA));
        return paths;
    }

    // ── FROM ───────────────────────────────────────────────────────────────

    private List<FromItem> parseFromList() {
        // FROM * is valid syntax but never natively evaluable; represent it and let the evaluator
        // reject it (so the caller delegates).
        if (accept(TokenType.STAR)) {
            return List.of(new FromItem("*", null));
        }
        List<FromItem> items = new ArrayList<>();
        do {
            String type = parseFieldPath().joined(); // event type names may be dotted (jdk.Foo)
            String alias = null;
            if (accept(TokenType.AS)) {
                alias = expect(TokenType.IDENT).text();
            }
            items.add(new FromItem(type, alias));
        } while (accept(TokenType.COMMA));
        return items;
    }

    // ── WHERE (flat AND of equalities only) ──────────────────────────────────

    private List<Equality> parseWhere() {
        expect(TokenType.WHERE);
        List<Equality> eqs = new ArrayList<>();
        do {
            FieldPath field = parseFieldPath();
            if (!accept(TokenType.EQ)) {
                throw err("only '=' comparisons are supported in WHERE");
            }
            String value = expect(TokenType.STRING).text();
            eqs.add(new Equality(field, value));
        } while (accept(TokenType.AND));
        // Reject anything that would continue the predicate with unsupported connectives.
        if (check(TokenType.OR) || check(TokenType.NOT)) {
            throw err("OR/NOT are not supported in WHERE");
        }
        return eqs;
    }

    // ── ORDER BY ─────────────────────────────────────────────────────────────

    private List<OrderItem> parseOrderList() {
        List<OrderItem> items = new ArrayList<>();
        do {
            String ref = parseFieldPath().joined();
            OrderItem.Direction dir = OrderItem.Direction.DEFAULT;
            if (accept(TokenType.DESC)) {
                dir = OrderItem.Direction.DESC;
            } else if (accept(TokenType.ASC)) {
                dir = OrderItem.Direction.ASC;
            }
            items.add(new OrderItem(ref, dir));
        } while (accept(TokenType.COMMA));
        return items;
    }

    // ── token helpers ────────────────────────────────────────────────────────

    private Token peek() {
        return tokens.get(p);
    }

    private boolean check(TokenType t) {
        return peek().type() == t;
    }

    private boolean checkAt(int ahead, TokenType t) {
        int j = p + ahead;
        return j < tokens.size() && tokens.get(j).type() == t;
    }

    private Token advance() {
        return tokens.get(p++);
    }

    private boolean accept(TokenType t) {
        if (check(t)) {
            p++;
            return true;
        }
        return false;
    }

    private Token expect(TokenType t) {
        if (!check(t)) {
            throw err("expected " + t + " but found " + peek().type());
        }
        return advance();
    }

    private QueryParseException err(String message) {
        return new QueryParseException(message, peek().pos());
    }
}
