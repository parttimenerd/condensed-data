package me.bechberger.jfr.cli.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-written lexer for the JDK {@code view.ini} query language.
 *
 * <p>Recognizes a deliberately wide token set (all comparison operators, {@code OR}/{@code NOT},
 * {@code HAVING}) even though the parser only accepts the narrow subset actually used by current
 * JDKs. Recognizing the wider set means a future JDK's syntax still tokenizes cleanly and fails at
 * the parser stage (triggering delegation to {@code jfr view}) rather than crashing here.
 *
 * <p>Keywords are matched case-insensitively.
 */
final class Lexer {

    private static final Map<String, TokenType> KEYWORDS =
            Map.ofEntries(
                    Map.entry("SELECT", TokenType.SELECT),
                    Map.entry("FROM", TokenType.FROM),
                    Map.entry("WHERE", TokenType.WHERE),
                    Map.entry("GROUP", TokenType.GROUP),
                    Map.entry("BY", TokenType.BY),
                    Map.entry("ORDER", TokenType.ORDER),
                    Map.entry("LIMIT", TokenType.LIMIT),
                    Map.entry("HAVING", TokenType.HAVING),
                    Map.entry("AS", TokenType.AS),
                    Map.entry("ASC", TokenType.ASC),
                    Map.entry("DESC", TokenType.DESC),
                    Map.entry("COLUMN", TokenType.COLUMN),
                    Map.entry("FORMAT", TokenType.FORMAT),
                    Map.entry("AND", TokenType.AND),
                    Map.entry("OR", TokenType.OR),
                    Map.entry("NOT", TokenType.NOT));

    private final String src;
    private int i = 0;

    Lexer(String src) {
        this.src = src;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = next();
            tokens.add(t);
        } while (t.type() != TokenType.EOF);
        return tokens;
    }

    private Token next() {
        skipWhitespace();
        if (i >= src.length()) {
            return new Token(TokenType.EOF, "", i);
        }
        int start = i;
        char c = src.charAt(i);

        switch (c) {
            case '(' -> {
                i++;
                return new Token(TokenType.LPAREN, "(", start);
            }
            case ')' -> {
                i++;
                return new Token(TokenType.RPAREN, ")", start);
            }
            case '[' -> {
                i++;
                return new Token(TokenType.LBRACKET, "[", start);
            }
            case ']' -> {
                i++;
                return new Token(TokenType.RBRACKET, "]", start);
            }
            case '|' -> {
                i++;
                return new Token(TokenType.PIPE, "|", start);
            }
            case ',' -> {
                i++;
                return new Token(TokenType.COMMA, ",", start);
            }
            case '*' -> {
                i++;
                return new Token(TokenType.STAR, "*", start);
            }
            case '.' -> {
                i++;
                return new Token(TokenType.DOT, ".", start);
            }
            case ':' -> {
                i++;
                return new Token(TokenType.COLON, ":", start);
            }
            case ';' -> {
                i++;
                return new Token(TokenType.SEMICOLON, ";", start);
            }
            case '=' -> {
                i++;
                return new Token(TokenType.EQ, "=", start);
            }
            case '!' -> {
                if (peek(1) == '=') {
                    i += 2;
                    return new Token(TokenType.NE, "!=", start);
                }
                throw new QueryParseException("unexpected character '!'", start);
            }
            case '<' -> {
                if (peek(1) == '=') {
                    i += 2;
                    return new Token(TokenType.LE, "<=", start);
                }
                i++;
                return new Token(TokenType.LT, "<", start);
            }
            case '>' -> {
                if (peek(1) == '=') {
                    i += 2;
                    return new Token(TokenType.GE, ">=", start);
                }
                i++;
                return new Token(TokenType.GT, ">", start);
            }
            case '\'', '"' -> {
                return string(c, start);
            }
            default -> {
                if (Character.isDigit(c) || (c == '-' && Character.isDigit(peek(1)))) {
                    return number(start);
                }
                if (isIdentStart(c)) {
                    return identOrKeyword(start);
                }
                throw new QueryParseException("unexpected character '" + c + "'", start);
            }
        }
    }

    private Token string(char quote, int start) {
        i++; // opening quote
        var sb = new StringBuilder();
        while (i < src.length() && src.charAt(i) != quote) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < src.length()) {
                // tolerate simple escapes (headroom; not seen in real view.ini)
                i++;
                sb.append(src.charAt(i));
            } else {
                sb.append(c);
            }
            i++;
        }
        if (i >= src.length()) {
            throw new QueryParseException("unterminated string literal", start);
        }
        i++; // closing quote
        return new Token(TokenType.STRING, sb.toString(), start);
    }

    private Token number(int start) {
        if (src.charAt(i) == '-') {
            i++;
        }
        while (i < src.length() && Character.isDigit(src.charAt(i))) {
            i++;
        }
        if (i < src.length() && src.charAt(i) == '.' && Character.isDigit(peek(1))) {
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        return new Token(TokenType.NUMBER, src.substring(start, i), start);
    }

    private Token identOrKeyword(int start) {
        while (i < src.length() && isIdentPart(src.charAt(i))) {
            i++;
        }
        String text = src.substring(start, i);
        TokenType kw = KEYWORDS.get(text.toUpperCase(java.util.Locale.ROOT));
        return new Token(kw != null ? kw : TokenType.IDENT, text, start);
    }

    private void skipWhitespace() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
    }

    private char peek(int ahead) {
        int j = i + ahead;
        return j < src.length() ? src.charAt(j) : '\0';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        // '-' is allowed so FORMAT hint names like cell-height / truncate-beginning tokenize as one
        // identifier; the parser decides how to interpret them.
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
