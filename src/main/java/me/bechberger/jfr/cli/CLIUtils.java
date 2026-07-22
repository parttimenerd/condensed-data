package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.parseMemory;
import static me.bechberger.util.TimeUtil.parseDuration;
import static me.bechberger.util.TimeUtil.parseInstant;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.bechberger.femtocli.TypeConverter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.commands.InflateCommand;

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
            Throwable cause = e;
            while (cause instanceof java.lang.reflect.InvocationTargetException
                    || (cause instanceof RuntimeException && cause.getCause() != null)) {
                cause = cause.getCause();
            }
            if (cause instanceof IllegalArgumentException) {
                System.err.println("Error: " + cause.getMessage());
                return 2;
            }
            return printError(e);
        }
        return 0;
    }

    public static int printError(Throwable throwable) {
        if (System.getenv("CJFR_DEBUG") != null) {
            throwable.printStackTrace();
        }
        System.err.println("Error: " + userErrorMessage(throwable));
        return 1;
    }

    public static String userErrorMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.lang.reflect.InvocationTargetException
                        || current instanceof RuntimeException)) {
            current = current.getCause();
        }
        if (current instanceof java.nio.file.NoSuchFileException nsfe) {
            return "No such file or directory: " + nsfe.getFile();
        }
        if (current instanceof java.nio.file.AccessDeniedException ade) {
            return "Permission denied: " + ade.getFile();
        }
        String message = current.getMessage();
        return (message == null || message.isBlank()) ? current.toString() : message;
    }

    /**
     * Check if the output file can be written. Throws IllegalArgumentException if the file exists
     * and is not writable, or if the file exists and force is false.
     */
    public static void checkOutputFileWritable(Path outputFile) {
        checkOutputFileWritable(outputFile, false);
    }

    /**
     * Check if the output file can be written.
     *
     * @param force if true, allow overwriting existing files (but still refuse read-only files)
     */
    public static void checkOutputFileWritable(Path outputFile, boolean force) {
        if (Files.exists(outputFile)) {
            if (!Files.isWritable(outputFile)) {
                throw new IllegalArgumentException(
                        "Cannot write to " + outputFile + " (file is read-only)");
            }
            if (!force) {
                throw new IllegalArgumentException(
                        "Output file already exists: "
                                + outputFile
                                + " (use --force to overwrite)");
            }
        }
    }

    /** Format a byte size into a human-readable string (e.g. "1.2 MB"). */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static class ConfigurationConverter implements TypeConverter<Configuration> {
        public ConfigurationConverter() {}

        @Override
        public Configuration convert(String value) {
            if (!Configuration.configurations.containsKey(value)) {
                throw new IllegalArgumentException(
                        "Unknown configuration: "
                                + value
                                + ", use one of "
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
                if (c == '"') {
                    currentArg.append(c);
                } else {
                    currentArg.append('\\');
                    currentArg.append(c);
                }
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
        if (escapeNext) {
            // trailing backslash
            var last = args.size() - 1;
            if (last >= 0) {
                args.set(last, args.get(last) + "\\");
            } else {
                args.add("\\");
            }
        }
        return args;
    }

    public static String combineArgs(List<String> options) {
        // wrap the options in quotes and join them with spaces
        return options.stream()
                .map(
                        s -> {
                            // Escape quotes (backslashes are literal in splitArgs)
                            String escaped = s.replace("\"", "\\\"");
                            if (s.contains(" ") || s.isEmpty()) {
                                return "\"" + escaped + "\"";
                            }
                            return escaped;
                        })
                .collect(Collectors.joining(" "));
    }

    /**
     * Extract matching entries from a ZIP file into a temp folder while preserving entry paths.
     *
     * <p>Preserving entry paths avoids basename collisions like {@code a/same.jfr} and {@code
     * b/same.jfr} that would otherwise overwrite each other when flattened.
     */
    public static List<Path> extractMatchingZipEntries(
            Path zipPath, Predicate<String> entryMatcher, String tempPrefix)
            throws java.io.IOException {
        var extractedFiles = new ArrayList<Path>();
        var tmpFolder = Files.createTempDirectory(tempPrefix);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        for (var file : extractedFiles) {
                                            Files.deleteIfExists(file);
                                        }
                                        if (Files.exists(tmpFolder)) {
                                            try (var paths = Files.walk(tmpFolder)) {
                                                paths.sorted(Comparator.reverseOrder())
                                                        .forEach(
                                                                path -> {
                                                                    try {
                                                                        Files.deleteIfExists(path);
                                                                    } catch (
                                                                            java.io.IOException e) {
                                                                        throw new RuntimeException(
                                                                                e);
                                                                    }
                                                                });
                                            }
                                        }
                                    } catch (java.io.IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }));

        try (var is = Files.newInputStream(zipPath);
                var zipReader = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zipReader.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                var entryName = entry.getName();
                if (!entryMatcher.test(entryName)) {
                    continue;
                }
                // Keep relative paths to avoid basename collisions across directories.
                var target = tmpFolder.resolve(entryName).normalize();
                if (!target.startsWith(tmpFolder)) {
                    throw new java.io.IOException("Invalid ZIP entry path: " + entryName);
                }
                var parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(zipReader, target, StandardCopyOption.REPLACE_EXISTING);
                extractedFiles.add(target);
            }
        }

        return extractedFiles;
    }
}
