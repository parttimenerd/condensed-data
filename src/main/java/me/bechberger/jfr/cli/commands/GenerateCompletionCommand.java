package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import me.bechberger.jfr.cli.FileOptionConverters;
import org.jetbrains.annotations.Nullable;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Generate auto-completion script
 *
 * <p>Can handle {@link
 * me.bechberger.jfr.cli.FileOptionConverters.ExistingJFRFileParameterConsumer}, {@link
 * me.bechberger.jfr.cli.FileOptionConverters.CJFRFileConverter}, {@link
 * me.bechberger.jfr.cli.FileOptionConverters.JFRFileConverter}, and {@link
 * me.bechberger.jfr.cli.FileOptionConverters.ExistingJFRFileConverter} properly
 */
@Command(
        name = "generate-completion",
        description = "Generate an auto-completion script for bash and zsh for ./cjfr",
        mixinStandardHelpOptions = true)
public class GenerateCompletionCommand implements Runnable {

    @Spec CommandSpec spec;

    @Option(
            names = "--fail-on-missing",
            description = "Fail if a file option doesn't have a specific ending",
            defaultValue = "false")
    boolean failOnMissing;

    record FileEnding(String ending, boolean isZipAllowed) {}

    record FileEndingList(List<@Nullable FileEnding> parameters, Map<String, FileEnding> options) {}

    private final Map<String, String> LINE_REPLACEMENTS =
            Map.of(
                    "if [[ \"${curr_word}\" == -* ]]; then",
                    "if [[ \"${curr_word}\" == -* ]] || [[ \"${prev_word}\" == -* ]] || [["
                            + " \"${COMP_WORDS[COMP_CWORD-2]}\" == -* ]]; then");

    private Map<String, FileEndingList> getFileEndingsOfPositionalsPerCommand() {
        Function<ArgSpec, @Nullable FileEnding> conv =
                a -> {
                    var converters = a.converters();
                    if (converters.length == 0) {
                        return null;
                    }
                    if (converters.length > 1) {
                        throw new IllegalStateException("Too many converters");
                    }
                    var converter = converters[0];
                    var ending = FileOptionConverters.getFileEndingAnnotation(converter.getClass());
                    var isZipAllowed = FileOptionConverters.isZipAllowed(converter.getClass());
                    if (ending == null) {
                        return null;
                    }
                    return new FileEnding(ending, isZipAllowed);
                };
        return spec.commandLine().getParent().getSubcommands().entrySet().stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    var parameters =
                                            e.getValue().getCommandSpec().args().stream()
                                                    .filter(ArgSpec::isPositional)
                                                    .filter(a -> a.type().equals(Path.class))
                                                    .map(conv)
                                                    .toList();
                                    var options =
                                            e.getValue().getCommandSpec().options().stream()
                                                    .filter(
                                                            a ->
                                                                    a.converters().length > 0
                                                                            && FileOptionConverters
                                                                                            .getFileEndingAnnotation(
                                                                                                    a
                                                                                                            .converters()[
                                                                                                            0]
                                                                                                            .getClass())
                                                                                    != null)
                                                    .collect(
                                                            Collectors.toMap(
                                                                    a ->
                                                                            String.join(
                                                                                    "|", a.names()),
                                                                    a ->
                                                                            Objects.requireNonNull(
                                                                                    conv.apply(
                                                                                            a))));
                                    return new FileEndingList(parameters, options);
                                }));
    }

    public static String extractCommandName(String line) {
        Pattern pattern = Pattern.compile("function _picocli_cjfr_(\\w+)\\(\\) \\{");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String createFileEndingCode(FileEnding ending, String line, String variable) {
        var misc =
                ending.isZipAllowed
                        ? "; find . -type f -name \"*.zip\" -exec sh -c 'unzip -l"
                                + " \"{}\" | grep -q \"\\"
                                + ending
                                + "$\" && echo \"{}\"' \\; | grep \"^${curr_word}\""
                        : "";
        var needles =
                List.of(
                        variable + "=$( compgen -f -- \"${curr_word}\" ) # files",
                        variable + "=( $( compgen -f -- \"${curr_word}\" ) ) # files");
        if (!needles.contains(line.strip())) {
            if (failOnMissing) {
                throw new IllegalStateException(
                        "Could not find any needle: " + needles + " in line: " + line);
            }
            return line;
        }
        for (var needle : needles) {
            if (line.strip().equals(needle)) {
                return line.replace(
                        needle,
                        variable
                                + "=$( compgen -f -- \"${curr_word}\" | grep -E '\\"
                                + ending.ending
                                + "$'; compgen -d -- \"${curr_word}\""
                                + misc
                                + ") # files");
            }
        }
        return line;
    }

    /** Process the script to handle file endings better */
    private String processScript(String script) {

        var eventNames =
                FlightRecorder.getFlightRecorder().getEventTypes().stream()
                        .map(EventType::getName)
                        .toList();

        String currentCommand;
        var fileEndings = getFileEndingsOfPositionalsPerCommand();
        List<String> results = new ArrayList<>();
        FileEndingList currentEndingList = new FileEndingList(List.of(), new HashMap<>());
        int currentPositionalIndex = 0;
        for (var line : script.lines().toList()) {
            var sline = line.strip();
            var commandName = extractCommandName(line);
            if (commandName != null) {
                currentCommand = commandName;
                currentPositionalIndex = 0;
                currentEndingList = fileEndings.get(currentCommand);
                results.add(line);
                continue;
            }
            if (currentEndingList == null
                    || currentEndingList.parameters.isEmpty()
                    || currentPositionalIndex >= currentEndingList.parameters.size()) {
                if (sline.equals(
                        "COMPREPLY=( $(compgen -W \"${commands// /$'\\n"
                                + "'}${IFS}${positionals}\" -- \"${curr_word}\") )")) {
                    results.add(
                            "    COMPREPLY=( $(compgen -W \"${commands// /$'\\n"
                                    + "'}${IFS}${positionals}${IFS}${arg_opts// /$'\\n"
                                    + "'}${IFS}${flag_opts// /$'\\n"
                                    + "'}\" -- \"${curr_word}\") )");
                } else {
                    results.add(line);
                }
                continue;
            }
            // check for "positionals=$( compgen -f -- "${curr_word}" ) # files"
            if (line.strip().equals("positionals=$( compgen -f -- \"${curr_word}\" ) # files")) {
                var ending = currentEndingList.parameters.get(currentPositionalIndex);
                if (ending != null) {
                    results.add(createFileEndingCode(ending, line, "positionals"));
                    if (currentPositionalIndex == 0) {
                        results.add(
                                "      COMPREPLY=( $(compgen -W \"${commands// /$'\\n"
                                        + "'}${IFS}${positionals}\" -- \"${curr_word}\") )");
                        results.add("      return");
                    }
                } else {
                    if (failOnMissing) {
                        throw new IllegalStateException(
                                "Could not find ending for line: "
                                        + line
                                        + " and index "
                                        + currentPositionalIndex);
                    }
                    results.add(line);
                }
                currentPositionalIndex++;
                continue;
            } else if (line.strip()
                    .equals("COMPREPLY=( $( compgen -f -- \"${curr_word}\" ) ) # files")) {
                // walk back at most 4 lines to find the option name
                // format: -[-a-zA-Z0-9]+(|-[-a-zA-Z0-9]+)+\)
                String optionName = null;
                for (int i = results.size() - 1; i >= Math.max(0, results.size() - 4); i--) {
                    var l = results.get(i);
                    if (l.strip().startsWith("-")) {
                        optionName = l.strip().substring(0, l.strip().length() - 1);
                        break;
                    }
                }
                if (optionName != null && currentEndingList.options.containsKey(optionName)) {
                    var ending = currentEndingList.options.get(optionName);
                    results.add(createFileEndingCode(ending, line, "COMPREPLY"));
                } else {
                    if (failOnMissing) {
                        throw new IllegalStateException(
                                "Could not find option name for line: "
                                        + line
                                        + " and option "
                                        + optionName);
                    }
                    results.add(line);
                }
            } else if (LINE_REPLACEMENTS.containsKey(sline)) {
                results.add(line.replace(sline, LINE_REPLACEMENTS.get(sline)));
            } else if (!results.isEmpty()
                    && results.get(results.size() - 1)
                            .contains(
                                    "if [[ \"${curr_word}\" == -* ]] || [[ \"${prev_word}\" == -*"
                                            + " ]] || [[ \"${COMP_WORDS[COMP_CWORD-2]}\" == -* ]];"
                                            + " then")
                    && sline.equals(
                            "COMPREPLY=( $(compgen -W \"${flag_opts} ${arg_opts}\" --"
                                    + " \"${curr_word}\") )")) {
                // modify comp
                results.add(
                        "    COMPREPLY=( $(compgen -W \"${flag_opts// /$'\\n"
                                + "'}${IFS}${arg_opts// /$'\\n"
                                + "'}\" -- \"${curr_word}\") )");
            } else {
                results.add(line);
            }
        }
        return String.join("\n", results);
    }

    @Override
    public void run() {
        System.out.println(processScript(AutoComplete.bash("cjfr", spec.parent().commandLine())));
    }
}
