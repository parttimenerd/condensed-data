package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.parseMemory;
import static me.bechberger.util.TimeUtil.parseDuration;
import static me.bechberger.util.TimeUtil.parseInstant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import me.bechberger.jfr.Configuration;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;

public class CLIUtils {
    public static class ConfigurationIterable implements Iterable<String> {
        @NotNull
        @Override
        public Iterator<String> iterator() {
            return Configuration.configurations.values().stream()
                    .map(Configuration::name)
                    .sorted()
                    .iterator();
        }
    }

    public static class ConfigurationConverter implements ITypeConverter<Configuration> {
        public ConfigurationConverter() {}

        @Override
        public Configuration convert(String value) {
            if (!Configuration.configurations.containsKey(value)) {
                throw new IllegalArgumentException(
                        "Unknown generatorConfiguration: "
                                + value
                                + " use one of "
                                + Configuration.configurations.keySet());
            }
            return Configuration.configurations.get(value);
        }
    }

    public static class ByteSizeConverter implements ITypeConverter<Long> {
        @Override
        public Long convert(String value) {
            return parseMemory(value);
        }
    }

    public static class DurationConverter implements ITypeConverter<Duration> {
        @Override
        public Duration convert(String value) {
            return parseDuration(value);
        }
    }

    public static class InstantConverter implements ITypeConverter<Instant> {
        @Override
        public Instant convert(String value) {
            return parseInstant(value);
        }
    }

    public static int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] =
                            Math.min(
                                    dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1),
                                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
                }
            }
        }
        return dp[a.length()][b.length()];
    }

    /** Simple code to parse a command line string into a list of arguments */
    public static List<String> parseCommandLine(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '\0';

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false; // Close the quoted section
                } else {
                    currentArg.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (!currentArg.isEmpty()) {
                        args.add(currentArg.toString());
                        currentArg.setLength(0); // Reset the builder
                    }
                } else {
                    currentArg.append(c);
                }
            }
        }

        // Add the last argument if any
        if (!currentArg.isEmpty()) {
            args.add(currentArg.toString());
        }

        return args;
    }

    public static List<String> splitArgs(String agentArgs) {
        if (agentArgs.strip().isBlank()) {
            return List.of();
        }
        var args = new ArrayList<String>();
        var inQuotes = false;
        var escapeNext = false;
        var currentArg = new StringBuilder();
        for (var c : agentArgs.toCharArray()) {
            if (escapeNext) {
                currentArg.append(c);
                escapeNext = false;
            } else if (c == '\\') {
                escapeNext = true;
            } else if (c == ' ' && !inQuotes) {
                if (!currentArg.isEmpty()) {
                    args.add(currentArg.toString());
                    currentArg = new StringBuilder();
                }
            } else if (c == '"') {
                inQuotes = !inQuotes;
                if (!inQuotes) {
                    args.add(currentArg.toString());
                    currentArg = new StringBuilder();
                }
            } else {
                currentArg.append(c);
            }
        }
        if (!currentArg.isEmpty() || inQuotes) {
            args.add(currentArg.toString());
        }
        return args.stream().map(s -> s.replace("\\\"", "\"")).toList();
    }

    public static String combineArgs(List<String> options) {
        // wrap the options in quotes and join them with spaces
        return options.stream()
                .map(
                        s -> {
                            if (s.contains(" ") || s.isEmpty()) {
                                return "\"" + s.replace("\"", "\\\"") + "\"";
                            }
                            return s.replace("\"", "\\\"");
                        })
                .collect(Collectors.joining(" "));
    }

    public static void removeVersionOptionFromSubCommands(CommandLine cli) {
        for (var sub : cli.getSubcommands().values()) {
            sub.getCommandSpec().args().stream()
                    .filter(
                            a ->
                                    a.isOption()
                                            && ((CommandLine.Model.OptionSpec) a)
                                                    .longestName()
                                                    .equals("--version"))
                    .findFirst()
                    .ifPresent(a -> sub.getCommandSpec().remove(a));
        }
    }
}