package me.bechberger.jfr.cli;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.IParameterConsumer;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.ParameterException;

/** Utility class for handling file options in a command-line interface using picocli. */
public class FileOptionConverters {

    @Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @Target(java.lang.annotation.ElementType.TYPE)
    public @interface FileEndingAnnotation {
        String ending() default "";

        boolean allowFolder() default false;

        boolean allowZip() default false;
    }

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
        if (Files.isDirectory(file)) {
            return "File is a directory: " + file;
        }
        if (!file.getFileName().toString().contains(".")) {
            return "File does not have an extension: " + file;
        }
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
        private final boolean allowZipAndFolder;

        public SuggestionFileParameterConsumer(
                String extension, boolean allowEmpty, boolean allowZipAndFolder) {
            this.extension = extension;
            this.allowEmpty = allowEmpty;
            this.allowZipAndFolder = allowZipAndFolder;
        }

        private record FileSuggestion(String name, int distance) {}

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
            String check =
                    allowZipAndFolder
                            ? checkAllowFolderOrZIP(file, extension)
                            : check(file, extension);
            if (check != null) {
                try (var fs = Files.list(file.getParent())) {
                    List<String> suggestions =
                            fs.map(Path::toFile)
                                    .filter(
                                            f -> {
                                                if (f.isFile()) {
                                                    if (f.getName().endsWith(extension)) {
                                                        return true;
                                                    }
                                                    if (allowZipAndFolder
                                                            && f.getName().endsWith(".zip")) {
                                                        try (var zip = new ZipFile(f)) {
                                                            return zip.stream()
                                                                    .anyMatch(
                                                                            entry ->
                                                                                    entry.getName()
                                                                                            .endsWith(
                                                                                                    extension));
                                                        } catch (IOException e) {
                                                            return false;
                                                        }
                                                    }
                                                }
                                                if (f.isDirectory()) {
                                                    if (allowZipAndFolder) {
                                                        try (var stream = Files.list(f.toPath())) {
                                                            return stream.anyMatch(
                                                                    p ->
                                                                            p.toString()
                                                                                    .endsWith(
                                                                                            extension));
                                                        } catch (IOException e) {
                                                            return false;
                                                        }
                                                    }
                                                }
                                                return false;
                                            })
                                    .map(File::getName)
                                    .map(
                                            name ->
                                                    new FileSuggestion(
                                                            name,
                                                            editDistance(
                                                                    name,
                                                                    file.getFileName().toString())))
                                    .filter(s -> s.distance <= 20)
                                    .sorted(Comparator.comparingInt(a -> a.distance))
                                    .limit(5)
                                    .map(s -> s.name)
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

    @FileEndingAnnotation(ending = ".jfr")
    public static class ExistingJFRFileConverter extends ExistingFileWithExtensionConverter {
        public ExistingJFRFileConverter() {
            super(".jfr", true);
        }
    }

    @FileEndingAnnotation(ending = ".cjfr")
    public static class ExistingCJFRFileConverter extends ExistingFileWithExtensionConverter {
        public ExistingCJFRFileConverter() {
            super(".cjfr", true);
        }
    }

    public static class ExistingJFRFileParameterConsumer extends SuggestionFileParameterConsumer {
        public ExistingJFRFileParameterConsumer() {
            super(".jfr", true, false);
        }
    }

    public static class ExistingCJFRFileParameterConsumer extends SuggestionFileParameterConsumer {
        public ExistingCJFRFileParameterConsumer() {
            super(".cjfr", true, false);
        }
    }

    public static class FileWithExtensionConverter implements ITypeConverter<Path> {

        private final String extension;

        public FileWithExtensionConverter() {
            this.extension = getFileEndingAnnotation(getClass());
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

    @FileEndingAnnotation(ending = ".jfr")
    public static class JFRFileConverter extends FileWithExtensionConverter {}

    @FileEndingAnnotation(ending = ".cjfr")
    public static class CJFRFileConverter extends FileWithExtensionConverter {}

    public static @Nullable String getFileEndingAnnotation(Class<?> converterClass) {
        FileEndingAnnotation annotation = converterClass.getAnnotation(FileEndingAnnotation.class);
        if (annotation == null || annotation.ending().isEmpty()) {
            return null;
        }
        return annotation.ending();
    }

    public static boolean isFolderAllowed(Class<?> converterClass) {
        FileEndingAnnotation annotation = converterClass.getAnnotation(FileEndingAnnotation.class);
        return annotation != null && annotation.allowFolder();
    }

    public static boolean isZipAllowed(Class<?> converterClass) {
        FileEndingAnnotation annotation = converterClass.getAnnotation(FileEndingAnnotation.class);
        return annotation != null && annotation.allowZip();
    }

    /** Converter that ensures the file exists and has the specified extension. */
    public static class ExistingFileWithExtensionOrZipOrFolderConverter
            implements ITypeConverter<Path> {
        private final String extension;
        private final boolean allowEmpty;

        public ExistingFileWithExtensionOrZipOrFolderConverter(
                String extension, boolean allowEmpty) {
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
            String check = checkAllowFolderOrZIP(file, extension);
            if (check != null) {
                throw new IllegalArgumentException(check);
            }
            return file;
        }
    }

    private static @Nullable String checkAllowFolderOrZIP(Path file, String expectedExtension) {
        boolean exists = Files.exists(file);
        if (!Files.isDirectory(file) && !file.getFileName().toString().contains(".")) {
            return "File does not have an extension: " + file;
        }
        String extension =
                Files.isDirectory(file)
                        ? ""
                        : file.toString().substring(file.toString().lastIndexOf('.'));
        boolean hasCorrectExtension = extension.equals(expectedExtension);

        boolean isZip = extension.equals(".zip");
        boolean isFolder = Files.isDirectory(file);

        if (!exists) {
            if (!isZip && !hasCorrectExtension && !isFolder) {
                return "File does not exist and does not have the correct extension: expected "
                        + expectedExtension
                        + " or .zip, but got "
                        + file;
            } else if (!isZip) {
                return "File does not exist: " + file;
            }
        }

        if (isZip) {
            // check that the zip file contains a file with the correct extension
            try (var zip = new ZipFile(file.toFile())) {
                boolean hasCorrectFile =
                        zip.stream().anyMatch(entry -> entry.getName().endsWith(expectedExtension));
                if (!hasCorrectFile) {
                    return "ZIP file does not contain a file with the correct extension: expected "
                            + expectedExtension
                            + ", but got "
                            + file;
                }
            } catch (IOException e) {
                return "Error while checking ZIP file: " + e.getMessage();
            }
            return null;
        }

        if (isFolder) {
            // check that the folder contains a file with the correct extension
            try (var stream = Files.list(file)) {
                boolean hasCorrectFile =
                        stream.anyMatch(p -> p.toString().endsWith(expectedExtension));
                if (!hasCorrectFile) {
                    return "Folder does not contain a file with the correct extension: expected "
                            + expectedExtension;
                }
            } catch (IOException e) {
                return "Error while checking folder: " + e.getMessage();
            }
            return null;
        }

        if (!hasCorrectExtension) {
            return "File does not have the correct extension: expected "
                    + expectedExtension
                    + " or .zip or a folder with such files";
        }
        return null;
    }

    @FileEndingAnnotation(ending = ".cjfr", allowZip = true, allowFolder = true)
    public static class ExistingCJFRFileOrZipOrFolderConverter
            extends ExistingFileWithExtensionOrZipOrFolderConverter {
        public ExistingCJFRFileOrZipOrFolderConverter() {
            super(".cjfr", true);
        }
    }

    public static class ExistingCJFRFileOrZipOrFolderParameterConsumer
            extends SuggestionFileParameterConsumer {
        public ExistingCJFRFileOrZipOrFolderParameterConsumer() {
            super(".cjfr", true, true);
        }
    }
}
