package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.bechberger.jfr.cli.FileOptionConverters;
import org.jetbrains.annotations.Nullable;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
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

    private Map<String, List<@Nullable String>> getFileEndingsOfPositionalsPerCommand() {
        return spec.commandLine().getParent().getSubcommands().entrySet().stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    return e.getValue().getCommandSpec().args().stream()
                                            .filter(ArgSpec::isPositional)
                                            .filter(a -> a.type().equals(Path.class))
                                            .map(
                                                    a -> {
                                                        var converters = a.converters();
                                                        if (converters.length == 0) {
                                                            return null;
                                                        }
                                                        if (converters.length > 1) {
                                                            throw new IllegalStateException(
                                                                    "Too many converters");
                                                        }
                                                        var converter = converters[0];
                                                        var ending =
                                                                FileOptionConverters
                                                                        .getFileEndingAnnotation(
                                                                                converter
                                                                                        .getClass());
                                                        if (ending == null) {
                                                            return null;
                                                        }
                                                        return ending;
                                                    })
                                            .toList();
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

    /** Process the script to handle file endings better */
    private String processScript(String script) {
        String currentCommand = null;
        var fileEndings = getFileEndingsOfPositionalsPerCommand();
        List<String> results = new ArrayList<>();
        List<@Nullable String> currentPositionals = List.of();
        int currentPositionalIndex = 0;
        for (var line : script.lines().toList()) {
            var commandName = extractCommandName(line);
            if (commandName != null) {
                currentCommand = commandName;
                currentPositionalIndex = 0;
                currentPositionals = fileEndings.get(currentCommand);
                results.add(line);
                continue;
            }
            if (currentPositionals == null
                    || currentPositionals.isEmpty()
                    || currentPositionalIndex >= currentPositionals.size()) {
                results.add(line);
                continue;
            }
            // check for "positionals=$( compgen -f -- "${curr_word}" ) # files"
            if (line.strip().equals("positionals=$( compgen -f -- \"${curr_word}\" ) # files")) {
                var ending = currentPositionals.get(currentPositionalIndex);
                if (ending != null) {
                    results.add(
                            line.replace(
                                    "positionals=$( compgen -f -- \"${curr_word}\" ) # files",
                                    "positionals=$( compgen -f -- \"${curr_word}\" | grep -E '\\"
                                            + ending
                                            + "$' ) # files"));
                } else {
                    results.add(line);
                }
                currentPositionalIndex++;
                continue;
            }
            results.add(line);
        }
        return String.join("\n", results);
    }

    @Override
    public void run() {
        System.out.println(processScript(AutoComplete.bash("cjfr", spec.parent().commandLine())));
    }
}
