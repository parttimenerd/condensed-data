package me.bechberger.jfr.cli.query;

/**
 * A single lexical token. {@code pos} is the 0-based character offset of the token in the source
 * query, used for error reporting.
 */
public record Token(TokenType type, String text, int pos) {
    @Override
    public String toString() {
        return type + "(" + text + ")@" + pos;
    }
}
