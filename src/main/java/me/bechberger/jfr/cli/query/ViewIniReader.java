package me.bechberger.jfr.cli.query;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import me.bechberger.jfr.cli.query.ViewQuery.Shape;

/**
 * Reads the JDK's own {@code view.ini} at runtime from the {@code jrt:} image and exposes the named
 * view definitions (label + query body). The file is <em>read</em> from the running JDK, never
 * copied into this (MIT-licensed) repository — {@code view.ini} is GPLv2, so redistribution is not
 * permitted, but reading the user's installed copy in-process is fine.
 *
 * <p>The {@code jdk.jfr} module is always present because this tool links against {@code
 * jdk.jfr.consumer}. {@code view.ini} exists only from JDK 21 onward; on older JDKs {@link #load}
 * returns an empty registry and the caller falls back to {@code jfr view} delegation.
 */
final class ViewIniReader {

    /** One named view: its human-readable title and the raw query body plus shape. */
    record ViewDef(String name, String label, Shape shape, String body) {}

    private ViewIniReader() {}

    /**
     * Load all view definitions from the on-system {@code view.ini}. Keys are the short view name
     * (the part after the last dot in the ini section header, e.g. {@code gc-configuration} from
     * {@code [jvm.gc-configuration]}), lower-cased. Returns an empty map if the file is absent
     * (pre-21 JDK) or unreadable.
     */
    static Map<String, ViewDef> load() {
        String content = readViewIni();
        if (content == null) {
            return Map.of();
        }
        return parseIni(content);
    }

    private static String readViewIni() {
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path p = jrt.getPath("modules", "jdk.jfr", "jdk/jfr/internal/query/view.ini");
            if (Files.exists(p)) {
                return Files.readString(p);
            }
        } catch (IOException | RuntimeException e) {
            // Absent module, pre-21 JDK, or jrt unavailable — caller falls back to delegation.
        }
        return null;
    }

    /**
     * Minimal ini parser: sections {@code [group.name]}, keys {@code label}, {@code form}, {@code
     * table} whose values are single- or double-quoted and may span multiple lines. Only the last
     * of form/table wins per section (they are mutually exclusive in practice).
     */
    static Map<String, ViewDef> parseIni(String content) {
        Map<String, ViewDef> out = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        String section = null;
        String label = null;
        Shape shape = null;
        String body = null;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (section != null && body != null) {
                    out.put(
                            shortName(section),
                            new ViewDef(shortName(section), label, shape, body));
                }
                section = trimmed.substring(1, trimmed.length() - 1);
                label = null;
                shape = null;
                body = null;
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String key = trimmed.substring(0, eq).trim();
            String rest = trimmed.substring(eq + 1).trim();
            if (key.equals("label")) {
                label = unquoteSingleLine(rest);
            } else if (key.equals("form") || key.equals("table")) {
                shape = key.equals("form") ? Shape.FORM : Shape.TABLE;
                int[] next = {i};
                body = readQuoted(lines, next, rest);
                i = next[0];
            }
        }
        if (section != null && body != null) {
            out.put(shortName(section), new ViewDef(shortName(section), label, shape, body));
        }
        return out;
    }

    /**
     * Read a (possibly multi-line) quoted value starting at {@code rest}; advance {@code idx[0]}.
     */
    private static String readQuoted(String[] lines, int[] idx, String rest) {
        if (rest.isEmpty()) return "";
        char quote = rest.charAt(0);
        if (quote != '"' && quote != '\'') return rest;
        StringBuilder sb = new StringBuilder();
        String cur = rest.substring(1);
        int i = idx[0];
        while (true) {
            int q = cur.indexOf(quote);
            if (q >= 0) {
                sb.append(cur, 0, q);
                break;
            }
            sb.append(cur).append('\n');
            i++;
            if (i >= lines.length) break;
            cur = lines[i];
        }
        idx[0] = i;
        return sb.toString();
    }

    private static String unquoteSingleLine(String s) {
        if (s.length() >= 2
                && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String shortName(String section) {
        int dot = section.lastIndexOf('.');
        String name = dot >= 0 ? section.substring(dot + 1) : section;
        return name.toLowerCase(Locale.ROOT);
    }
}
