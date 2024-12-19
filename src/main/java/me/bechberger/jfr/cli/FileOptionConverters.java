package me.bechberger.jfr.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.IParameterConsumer;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.ParameterException;

/** Utility class for handling file options in a command-line interface using picocli. */
public class FileOptionConverters {

    /** Converter that ensures the file exists and has the specified extension. */
    public static class ExistingFileWithExtensionConverter implements ITypeConverter<Path> {
        private final String extension;
        private final boolean allowEmpty;

        public ExistingFileWithExtensionConverter(String extension, boolean allowEmpty) {
            this.extension = extension;
            this.allowEmpty = allowEmpty;
        }

        @Override
        public Path convert(String value) throws Exception {
            if (value.isEmpty()) {
                if (allowEmpty) {
                    return Path.of("");
                } else {
                    throw new IllegalArgumentException("File path cannot be empty");
                }
            }
            Path file = Path.of(value);
            String check = check(file, extension);
            if (check != null) {
                throw new IllegalArgumentException(check);
            }
            return file;
        }
    }

    private static @Nullable String check(Path file, String expectedExtension) {
        boolean exists = Files.exists(file);
        String extension = file.toString().substring(file.toString().lastIndexOf('.'));
        boolean hasCorrectExtension = extension.equals(expectedExtension);
        if (!exists && !hasCorrectExtension) {
            return "File does not exist and does not have the correct extension: expected "
                    + expectedExtension;
        } else if (!exists) {
            return "File does not exist: " + file;
        } else if (!hasCorrectExtension) {
            return "File does not have the correct extension: expected " + expectedExtension;
        }
        return null;
    }

    /**
     * Parameter consumer that suggests similar files if the specified file does not exist or has
     * the wrong extension.
     */
    public static class SuggestionFileParameterConsumer implements IParameterConsumer {
        private final String extension;
        private final boolean allowEmpty;

        public SuggestionFileParameterConsumer(String extension, boolean allowEmpty) {
            this.extension = extension;
            this.allowEmpty = allowEmpty;
        }

        @Override
        public void consumeParameters(
                Stack<String> args,
                CommandLine.Model.ArgSpec argSpec,
                CommandLine.Model.CommandSpec commandSpec) {
            if (args.isEmpty() || args.peek().isEmpty()) {
                if (!allowEmpty) {
                    throw new ParameterException(
                            commandSpec.commandLine(),
                            "Missing required parameter: " + argSpec.paramLabel());
                }
                argSpec.setValue(Path.of(""));
                return;
            }
            String value = args.pop();
            Path file = Path.of(value);
            String check = check(file, extension);
            if (check != null) {
                try (var fs = Files.list(file.getParent())) {
                    List<String> suggestions =
                            fs.map(Path::toFile)
                                    .map(File::getName)
                                    .filter(name -> name.endsWith(extension))
                                    .filter(
                                            name ->
                                                    editDistance(
                                                                    name,
                                                                    file.getFileName().toString())
                                                            < 20)
                                    .collect(Collectors.toList());
                    if (suggestions.isEmpty()) {
                        throw new ParameterException(commandSpec.commandLine(), check);
                    }
                    throw new ParameterException(
                            commandSpec.commandLine(),
                            check + ". Did you mean: " + String.join(", ", suggestions));
                } catch (IOException e) {
                    throw new ParameterException(commandSpec.commandLine(), check);
                }
            }
            argSpec.setValue(file);
        }

        private int editDistance(String a, String b) {
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
                                        dp[i - 1][j - 1]
                                                + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1),
                                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
                    }
                }
            }
            return dp[a.length()][b.length()];
        }
    }

    public static class ExistingJFRFileConverter extends ExistingFileWithExtensionConverter {
        public ExistingJFRFileConverter() {
            super(".jfr", true);
        }
    }

    public static class ExistingCJFRFileConverter extends ExistingFileWithExtensionConverter {
        public ExistingCJFRFileConverter() {
            super(".cjfr", true);
        }
    }

    public static class ExistingJFRFileParameterConsumer extends SuggestionFileParameterConsumer {
        public ExistingJFRFileParameterConsumer() {
            super(".jfr", true);
        }
    }

    public static class ExistingCJFRFileParameterConsumer extends SuggestionFileParameterConsumer {
        public ExistingCJFRFileParameterConsumer() {
            super(".cjfr", true);
        }
    }

    public static class FileWithExtensionConverter implements ITypeConverter<Path> {

        private final String extension;

        public FileWithExtensionConverter(String extension) {
            this.extension = extension;
        }

        @Override
        public Path convert(String value) throws Exception {
            if (value.isEmpty()) {
                return Path.of("");
            }
            if (!value.endsWith(extension)) {
                throw new IllegalArgumentException(
                        "File does not have the correct extension: " + extension);
            }
            return Path.of(value);
        }
    }

    public static class JFRFileConverter extends FileWithExtensionConverter {
        public JFRFileConverter() {
            super(".jfr");
        }
    }

    public static class CJFRFileConverter extends FileWithExtensionConverter {
        public CJFRFileConverter() {
            super(".cjfr");
        }
    }
}
