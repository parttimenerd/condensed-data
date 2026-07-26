package me.bechberger.jfr.cli.query;

/**
 * Thrown when a {@code view.ini} query cannot be tokenized or parsed into a shape the native
 * evaluator understands. Queries are internal (they come from the JDK's own {@code view.ini}, never
 * from user input), so this is not surfaced to end users; the caller catches it and delegates the
 * view to {@code jfr view} instead. The position aids diagnosis when a future JDK changes the
 * grammar.
 */
public class QueryParseException extends RuntimeException {
    private final int pos;

    public QueryParseException(String message, int pos) {
        super(message + " (at offset " + pos + ")");
        this.pos = pos;
    }

    public int pos() {
        return pos;
    }
}
