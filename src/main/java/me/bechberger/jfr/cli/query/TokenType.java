package me.bechberger.jfr.cli.query;

/**
 * Lexical token categories for the JDK {@code view.ini} query language.
 *
 * <p>The grammar was reverse-engineered from the {@code view.ini} shipped with JDK 21 through 27
 * (master); the vocabulary is stable and closed across those versions. The set here is deliberately
 * slightly wider than currently-observed usage (extra comparison operators, {@code OR}/{@code NOT},
 * {@code HAVING}) so that a future JDK adding such syntax still tokenizes cleanly and the query can
 * be delegated to {@code jfr view} rather than crashing the lexer.
 */
public enum TokenType {
    // Punctuation
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    PIPE,
    COMMA,
    STAR,
    DOT,
    COLON,
    SEMICOLON,

    // Comparison operators (only EQ is observed in real view.ini; the rest are headroom).
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,

    // Literals
    STRING,
    NUMBER,
    IDENT,

    // Clause keywords
    SELECT,
    FROM,
    WHERE,
    GROUP,
    BY,
    ORDER,
    LIMIT,
    HAVING,
    AS,
    ASC,
    DESC,
    COLUMN,
    FORMAT,

    // Boolean connectives (AND is observed; OR/NOT are headroom).
    AND,
    OR,
    NOT,

    // Sentinel marking the end of input.
    EOF
}
