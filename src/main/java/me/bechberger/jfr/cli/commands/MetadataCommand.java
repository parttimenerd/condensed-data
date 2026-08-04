package me.bechberger.jfr.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import me.bechberger.condensed.types.ArrayType;
import me.bechberger.condensed.types.BooleanType;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.FloatType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StringType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.TypeCollection;
import me.bechberger.condensed.types.VarIntType;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.WritingJFRReader;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFROrJFRFileOrZipOrFolderConverter;

@Command(
        name = "metadata",
        description = "Print event type metadata (schemas) in oracle jfr-metadata format",
        mixinStandardHelpOptions = true)
public class MetadataCommand implements Callable<Integer> {

    @Parameters(
            description = "The input .cjfr or .jfr files",
            arity = "1..*",
            converter = ExistingCJFROrJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Option(
            names = {"--events"},
            description =
                    "Comma-separated list of event name patterns to include. Omit for all types.")
    private String eventsFilter;

    @Option(
            names = {"--categories"},
            description = "Comma-separated list of category name patterns to include.")
    private String categoriesFilter;

    @Override
    public Integer call() {
        try {
            Path jfrBin = jfrBinary();

            // Resolve every input to a .jfr path; inflate .cjfr to a temp file.
            List<Path> jfrInputs = new ArrayList<>();
            for (Path p : inputFiles) {
                if (p.toString().endsWith(".jfr")) {
                    jfrInputs.add(p);
                } else {
                    var reader =
                            CombiningJFRReader.fromPaths(
                                    List.of(p),
                                    (me.bechberger.jfr.cli.EventFilter.EventFilterInstance) null,
                                    true);
                    Path tmp = WritingJFRReader.toJFRFile(reader);
                    tmp.toFile().deleteOnExit();
                    jfrInputs.add(tmp);
                }
            }

            if (jfrBin != null) {
                return delegateToJfrMetadata(jfrBin, jfrInputs);
            }

            // Fallback: homegrown renderer (no annotation/content-type fidelity)
            return renderFallback(jfrInputs);
        } catch (Exception e) {
            return CLIUtils.printError(e);
        }
    }

    private int delegateToJfrMetadata(Path jfrBin, List<Path> files) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(jfrBin.toString());
        command.add("metadata");
        if (eventsFilter != null) {
            command.add("--events");
            command.add(eventsFilter);
        }
        if (categoriesFilter != null) {
            command.add("--categories");
            command.add(categoriesFilter);
        }
        for (Path f : files) {
            command.add(f.toString());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String out = new String(process.getInputStream().readAllBytes());
        String err = new String(process.getErrorStream().readAllBytes());
        int exit = process.waitFor();
        System.out.print(out);
        System.err.print(err);
        return exit;
    }

    private static Path jfrBinary() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            String exe =
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "jfr.exe"
                            : "jfr";
            Path candidate = Path.of(javaHome, "bin", exe);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ── Fallback renderer (used when jfr binary is absent) ───────────────────

    private int renderFallback(List<Path> jfrInputs) throws Exception {
        for (Path jfr : jfrInputs) {
            var reader =
                    CombiningJFRReader.fromPaths(
                            List.of(jfr),
                            (me.bechberger.jfr.cli.EventFilter.EventFilterInstance) null,
                            false,
                            false,
                            new me.bechberger.condensed.stats.NoopStatistic());
            while (reader.readNextEvent() != null) {}

            var types = reader.getInputStream().getTypeCollection().getTypes();
            List<String> eventPatterns = parsePatterns(eventsFilter);

            boolean first = true;
            for (var type : types) {
                if (!isJfrType(type)) continue;
                if (!shouldInclude(type, eventPatterns)) continue;
                if (!first) System.out.println();
                first = false;
                printType(type);
            }
        }
        return 0;
    }

    private boolean isJfrType(CondensedType<?, ?> type) {
        int id = type.getId();
        if (id <= TypeCollection.STRUCT_ID) return false;
        String name = type.getName();
        if (name == null) return false;
        if (name.startsWith("me.bechberger.jfr.")) return false;
        if (type instanceof ArrayType<?, ?>
                && (type.getDescription() == null || type.getDescription().isBlank())) {
            return false;
        }
        if (!(type instanceof StructType<?, ?>)) {
            return switch (name) {
                case "boolean",
                        "byte",
                        "char",
                        "double",
                        "float",
                        "int",
                        "long",
                        "short",
                        "java.lang.String",
                        "java.lang.Thread",
                        "java.lang.Class" ->
                        true;
                default -> false;
            };
        }
        return true;
    }

    private List<String> parsePatterns(String filter) {
        if (filter == null || filter.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String p : filter.split(",")) {
            String t = p.strip();
            if (!t.isEmpty()) result.add(t.toLowerCase());
        }
        return result;
    }

    private boolean shouldInclude(CondensedType<?, ?> type, List<String> patterns) {
        if (patterns.isEmpty()) return true;
        String name = type.getName();
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String pat : patterns) {
            if (globMatches(pat, lower)) return true;
        }
        return false;
    }

    private boolean globMatches(String pattern, String text) {
        if (!pattern.contains("*")) return text.equals(pattern);
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                if (!text.startsWith(part)) return false;
                pos = part.length();
            } else if (i == parts.length - 1) {
                if (!text.endsWith(part)) return false;
            } else {
                int idx = text.indexOf(part, pos);
                if (idx < 0) return false;
                pos = idx + part.length();
            }
        }
        return true;
    }

    private void printType(CondensedType<?, ?> type) {
        String name = type.getName();
        if (name == null) return;

        if (!(type instanceof StructType<?, ?> st)) {
            String displayName = shortName(name);
            System.out.println("class " + displayName + " {");
            System.out.println("}");
            return;
        }

        String desc = type.getDescription();
        List<String> typeAnnotations = new ArrayList<>();
        String shortName = shortName(name);

        if (desc != null && !desc.isBlank() && desc.startsWith("[")) {
            List<String> elems = topLevelElements(desc);
            String typeLabel = stringAt(elems, 0);
            String typeDesc = stringAt(elems, 1);
            List<List<Object>> annots = annotationsAt(elems, 2);

            for (var ann : annots) {
                if (ann.isEmpty()) continue;
                String annName = (String) ann.get(0);
                if ("jdk.jfr.Category".equals(annName)) {
                    typeAnnotations.add(formatAnn("Category", ann));
                }
            }
            if (typeLabel != null && !typeLabel.equals(shortName)) {
                typeAnnotations.add("@Label(\"" + escape(typeLabel) + "\")");
            }
            if (typeDesc != null) {
                typeAnnotations.add("@Description(\"" + escape(typeDesc) + "\")");
            }
            for (var ann : annots) {
                if (ann.isEmpty()) continue;
                String annName = (String) ann.get(0);
                switch (annName) {
                    case "jdk.jfr.Category", "jdk.jfr.Label", "jdk.jfr.Description" -> {}
                    default -> typeAnnotations.add(formatAnn(jfrAnnShortName(annName), ann));
                }
            }
        }

        if (!name.equals(shortName)) {
            typeAnnotations.add(0, "@Name(\"" + name + "\")");
        }

        boolean isEvent = st.getFields().stream().anyMatch(f -> "startTime".equals(f.name()));

        for (String ann : typeAnnotations) {
            System.out.println(ann);
        }
        if (isEvent) {
            System.out.println("class " + shortName + " extends jdk.jfr.Event {");
        } else {
            System.out.println("class " + shortName + " {");
        }

        List<? extends StructType.Field<?, ?, ?>> fields = st.getFields();
        for (int i = 0; i < fields.size(); i++) {
            printField(fields.get(i), i < fields.size() - 1);
        }
        System.out.println("}");
    }

    private void printField(StructType.Field<?, ?, ?> field, boolean trailingBlank) {
        String fieldDesc = field.description();
        List<String> fieldAnnotations = new ArrayList<>();
        String javaTypeName = null;
        boolean isArray = false;

        if (fieldDesc != null && !fieldDesc.isBlank() && fieldDesc.startsWith("[")) {
            List<String> elems = topLevelElements(fieldDesc);
            String javaTypeRaw = stringAt(elems, 0);
            List<List<Object>> annots = annotationsAt(elems, 2);
            String fieldLabel = stringAt(elems, 3);
            String fieldDescText = stringAt(elems, 4);
            String isArrayStr = elems.size() > 5 ? elems.get(5).trim() : null;
            isArray = "true".equals(isArrayStr);

            if (javaTypeRaw != null) {
                javaTypeName = shortJavaType(javaTypeRaw);
            }

            for (var ann : annots) {
                if (ann.isEmpty()) continue;
                String annName = (String) ann.get(0);
                switch (annName) {
                    case "jdk.jfr.Label" -> {
                        if (fieldLabel != null)
                            fieldAnnotations.add("@Label(\"" + escape(fieldLabel) + "\")");
                    }
                    case "jdk.jfr.Description" -> {
                        if (fieldDescText != null)
                            fieldAnnotations.add("@Description(\"" + escape(fieldDescText) + "\")");
                    }
                    default -> fieldAnnotations.add(formatAnn(jfrAnnShortName(annName), ann));
                }
            }
        }

        if (javaTypeName == null) {
            javaTypeName = inferJavaType(field.type());
        }
        if (isArray) javaTypeName = javaTypeName + "[]";

        for (String ann : fieldAnnotations) {
            System.out.println("  " + ann);
        }
        System.out.println("  " + javaTypeName + " " + field.name() + ";");
        if (trailingBlank) System.out.println();
    }

    private String inferJavaType(CondensedType<?, ?> type) {
        if (type instanceof BooleanType) return "boolean";
        if (type instanceof IntType) return "int";
        if (type instanceof VarIntType) return "long";
        if (type instanceof FloatType) return "double";
        if (type instanceof StringType) return "String";
        if (type instanceof ArrayType<?, ?> at) return inferJavaType(at.getValueType()) + "[]";
        if (type instanceof StructType<?, ?>) {
            String n = type.getName();
            return n != null ? shortName(n) : "Object";
        }
        return "Object";
    }

    private String shortName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }

    private String shortJavaType(String javaType) {
        return switch (javaType) {
            case "java.lang.String" -> "String";
            default -> {
                int dot = javaType.lastIndexOf('.');
                yield dot >= 0 ? javaType.substring(dot + 1) : javaType;
            }
        };
    }

    private String jfrAnnShortName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return "@" + (dot >= 0 ? fullName.substring(dot + 1) : fullName);
    }

    private String formatAnn(String shortAnn, List<Object> ann) {
        String at = shortAnn.startsWith("@") ? shortAnn : "@" + shortAnn;
        if (ann.size() <= 1) return at;
        @SuppressWarnings("unchecked")
        List<Object> rawArgs = (List<Object>) ann.get(1);
        if (rawArgs == null || rawArgs.isEmpty()) return at;

        if (rawArgs.size() == 1) {
            Object v = rawArgs.get(0);
            if (v instanceof String s && s.trim().startsWith("[")) {
                List<String> items = parseStringArray(s.trim());
                if (items.size() == 1) {
                    return at + "(\"" + escape(items.get(0)) + "\")";
                }
                StringBuilder sb = new StringBuilder(at).append("({");
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(escape(items.get(i))).append('"');
                }
                return sb.append("})").toString();
            }
            if (v instanceof String s) return at + "(\"" + escape(s) + "\")";
            return at + "(" + v + ")";
        }

        StringBuilder sb = new StringBuilder(at).append("({");
        for (int i = 0; i < rawArgs.size(); i++) {
            if (i > 0) sb.append(", ");
            Object v = rawArgs.get(i);
            if (v instanceof String s) sb.append('"').append(escape(s)).append('"');
            else sb.append(v);
        }
        return sb.append("})").toString();
    }

    private static List<String> parseStringArray(String s) {
        List<String> result = new ArrayList<>();
        List<String> elems = topLevelElements(s);
        for (String elem : elems) {
            String v = elem.trim();
            if (v.startsWith("\"")) result.add(jsonUnquote(v));
        }
        return result;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> topLevelElements(String s) {
        List<String> out = new ArrayList<>();
        int open = s.indexOf('[');
        if (open < 0) return out;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        StringBuilder cur = new StringBuilder();
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (inStr) {
                cur.append(c);
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"' -> {
                    inStr = true;
                    cur.append(c);
                }
                case '[' -> {
                    depth++;
                    if (depth > 1) cur.append(c);
                }
                case ']' -> {
                    depth--;
                    if (depth == 0) {
                        if (cur.length() > 0) out.add(cur.toString());
                        return out;
                    }
                    cur.append(c);
                }
                case ',' -> {
                    if (depth == 1) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else {
                        cur.append(c);
                    }
                }
                default -> cur.append(c);
            }
        }
        return out;
    }

    private static String stringAt(List<String> elems, int idx) {
        if (idx >= elems.size()) return null;
        String s = elems.get(idx).trim();
        if (s.equals("null") || s.isEmpty()) return null;
        if (s.length() >= 2 && s.charAt(0) == '"') return jsonUnquote(s);
        return null;
    }

    private static String jsonUnquote(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() - 1) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> {
                        sb.append('\\');
                        sb.append(next);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<List<Object>> annotationsAt(List<String> elems, int idx) {
        List<List<Object>> result = new ArrayList<>();
        if (idx >= elems.size()) return result;
        String raw = elems.get(idx).trim();
        if (raw.isEmpty() || raw.equals("null")) return result;
        List<String> annEntries = topLevelElements(raw);
        for (String entry : annEntries) {
            List<String> parts = topLevelElements(entry);
            if (parts.isEmpty()) continue;
            String annName = stringAt(parts, 0);
            if (annName == null) continue;
            List<Object> argsList = new ArrayList<>();
            argsList.add(annName);
            if (parts.size() > 1) {
                List<String> argElems = topLevelElements(parts.get(1));
                List<Object> args = new ArrayList<>();
                for (String arg : argElems) {
                    String v = arg.trim();
                    if (v.startsWith("\"")) args.add(jsonUnquote(v));
                    else if (!v.isEmpty()) args.add(v);
                }
                argsList.add(args);
            } else {
                argsList.add(new ArrayList<>());
            }
            result.add(argsList);
        }
        return result;
    }
}
