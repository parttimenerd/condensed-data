package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.parseMemory;
import static me.bechberger.util.TimeUtil.parseDuration;
import static me.bechberger.util.TimeUtil.parseInstant;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import me.bechberger.femtocli.TypeConverter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.commands.InflateCommand;
import org.jetbrains.annotations.NotNull;

public class CLIUtils {

    public static boolean hasInflaterRelatedClasses() {
        try {
            Class.forName(InflateCommand.class.getName() + "$Impl");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Call the {@code run} method of the {@code $Impl} inner class via reflection, printing the
     * result if it returns a String.
     */
    public static int callImpl(Object command, String commandName) {
        try {
            Class<?> implClass = Class.forName(command.getClass().getName() + "$Impl");
            Method run = implClass.getMethod("run", command.getClass());
            Object result = run.invoke(null, command);
            if (result instanceof String s) {
                System.out.println(s);
            }
        } catch (ClassNotFoundException e) {
            System.err.println(
                    "Error: "
                            + commandName
                            + " is not supported in this minimal build"
                            + " (built without JMC support).");
            return 1;
        } catch (Exception e) {
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            } else {
                e.printStackTrace();
            }
            return 1;
        }
        return 0;
    }

    public static class ConfigurationConverter implements TypeConverter<Configuration> {
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

    public static class ByteSizeConverter implements TypeConverter<Long> {
        @Override
        public Long convert(String value) {
            return parseMemory(value);
        }
    }

    public static class DurationConverter implements TypeConverter<Duration> {
        @Override
        public Duration convert(String value) {
            return parseDuration(value);
        }
    }

    public static class InstantConverter implements TypeConverter<Instant> {
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
}