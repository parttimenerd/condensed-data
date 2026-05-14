package me.bechberger.jfr.cli;

import static me.bechberger.jfr.cli.CLIUtils.editDistance;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import me.bechberger.femtocli.TypeConverter;
import org.jetbrains.annotations.Nullable;

/** Utility class for handling file options in a command-line interface. */
public class FileOptionConverters {

    @Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @Target(java.lang.annotation.ElementType.TYPE)
    public @interface FileEndingAnnotation {
        String ending() default "";

        boolean allowFolder() default false;

        boolean allowZip() default false;
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

    private record FileSuggestion(String name, int distance) {}

    /**
     * Builds a suggestion string for similar files in the same directory. Returns null if no
     * suggestions found.
     */
    private static @Nullable String buildSuggestions(
            Path file, String extension, boolean allowZipAndFolder) {
        var parent = file.getParent();
        if (parent == null) {
            return null;
        }
        try (var fs = Files.list(parent)) {
            List<String> suggestions =
                    fs.map(Path::toFile)
                            .filter(
                                    f -> {
                                        if (f.isFile()) {
                                            if (f.getName().endsWith(extension)) {
                                                return true;
                                            }
                                            if (allowZipAndFolder && f.getName().endsWith(".zip")) {
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
                                        if (f.isDirectory() && allowZipAndFolder) {
                                            try (var stream = Files.list(f.toPath())) {
                                                return stream.anyMatch(
                                                        p -> p.toString().endsWith(extension));
                                            } catch (IOException e) {
                                                return false;
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
                                                            name, file.getFileName().toString())))
                            .filter(s -> s.distance <= 20)
                            .sorted(Comparator.comparingInt(a -> a.distance))
                            .limit(5)
                            .map(s -> s.name)
                            .collect(Collectors.toList());
            if (!suggestions.isEmpty()) {
                return ". Did you mean: " + String.join(", ", suggestions);
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    /** Converter that ensures the file exists and has the specified extension, with suggestions. */
    public static class ExistingFileWithExtensionConverter implements TypeConverter<Path> {
        private final String extension;
        private final boolean allowEmpty;

        public ExistingFileWithExtensionConverter(String extension, boolean allowEmpty) {
            this.extension = extension;
            this.allowEmpty = allowEmpty;
        }

        @Override
        public Path convert(String value) {
            if (value.isEmpty()) {
                if (allowEmpty) {
                    return Path.of("");
                } else {
                    throw new IllegalArgumentException("File path cannot be empty");
                }
            }
            Path file = Path.of(value);
            String error = check(file, extension);
            if (error != null) {
                String suggestion = buildSuggestions(file, extension, false);
                throw new IllegalArgumentException(error + (suggestion != null ? suggestion : ""));
            }
            return file;
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

    @FileEndingAnnotation(ending = ".html")
    public static class HTMLFileConverter extends FileWithExtensionConverter {}

    public static class FileWithExtensionConverter implements TypeConverter<Path> {

        private final String extension;

        public FileWithExtensionConverter() {
            this.extension = getFileEndingAnnotation(getClass());
        }

        @Override
        public Path convert(String value) {
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

    /**
     * Converter that ensures the file exists and has the specified extension, or is a zip/folder
     * containing such files, with suggestions.
     */
    public static class ExistingFileWithExtensionOrZipOrFolderConverter
            implements TypeConverter<Path> {
        private final List<String> extensions;
        private final boolean allowEmpty;

        public ExistingFileWithExtensionOrZipOrFolderConverter(
                String extension, boolean allowEmpty) {
            this.extensions = List.of(extension);
            this.allowEmpty = allowEmpty;
        }

        public ExistingFileWithExtensionOrZipOrFolderConverter(
                List<String> extensions, boolean allowEmpty) {
            this.extensions = extensions;
            this.allowEmpty = allowEmpty;
        }

        @Override
        public Path convert(String value) {
            if (value.isEmpty()) {
                if (allowEmpty) {
                    return Path.of("");
                } else {
                    throw new IllegalArgumentException("File path cannot be empty");
                }
            }
            Path file = Path.of(value);
            String error = checkAllowFolderOrZIP(file, extensions);
            if (error != null) {
                String suggestion = buildSuggestions(file, extensions.get(0), true);
                throw new IllegalArgumentException(error + (suggestion != null ? suggestion : ""));
            }
            return file;
        }
    }

    private static @Nullable String checkAllowFolderOrZIP(
            Path file, List<String> expectedExtensions) {
        String extensionList = String.join(" or ", expectedExtensions);
        boolean exists = Files.exists(file);
        if (!Files.isDirectory(file) && !file.getFileName().toString().contains(".")) {
            return "File does not have an extension: " + file;
        }
        String extension =
                Files.isDirectory(file)
                        ? ""
                        : file.toString().substring(file.toString().lastIndexOf('.'));
        boolean hasCorrectExtension = expectedExtensions.contains(extension);

        boolean isZip = extension.equals(".zip");
        boolean isFolder = Files.isDirectory(file);

        if (!exists) {
            if (!isZip && !hasCorrectExtension && !isFolder) {
                return "File does not exist and does not have the correct extension: expected "
                        + extensionList
                        + " or .zip, but got "
                        + file;
            } else if (!isZip) {
                return "File does not exist: " + file;
            }
        }

        if (isZip) {
            try (var zip = new ZipFile(file.toFile())) {
                boolean hasCorrectFile =
                        zip.stream()
                                .anyMatch(
                                        entry ->
                                                expectedExtensions.stream()
                                                        .anyMatch(
                                                                ext ->
                                                                        entry.getName()
                                                                                .endsWith(ext)));
                if (!hasCorrectFile) {
                    return "ZIP file does not contain a file with the correct extension: expected "
                            + extensionList
                            + ", but got "
                            + file;
                }
            } catch (IOException e) {
                return "Error while checking ZIP file: " + e.getMessage();
            }
            return null;
        }

        if (isFolder) {
            try (var stream = Files.list(file)) {
                boolean hasCorrectFile =
                        stream.anyMatch(
                                p ->
                                        expectedExtensions.stream()
                                                .anyMatch(ext -> p.toString().endsWith(ext)));
                if (!hasCorrectFile) {
                    return "Folder does not contain a file with the correct extension: expected "
                            + extensionList;
                }
            } catch (IOException e) {
                return "Error while checking folder: " + e.getMessage();
            }
            return null;
        }

        if (!hasCorrectExtension) {
            // Check for compressed file formats (e.g., .jfr.lz4, .cjfr.gz)
            String fileName = file.getFileName().toString();
            Set<String> compressionExtensions = Set.of(".lz4", ".gz", ".zst", ".bz2", ".xz");
            if (compressionExtensions.contains(extension)) {
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                String baseExtension =
                        baseName.contains(".") ? baseName.substring(baseName.lastIndexOf('.')) : "";
                if (expectedExtensions.contains(baseExtension)) {
                    return "Compressed "
                            + extension.substring(1).toUpperCase()
                            + " files are not supported. Decompress first, e.g.: "
                            + decompressCommand(extension, fileName);
                }
            }
            return "File does not have the correct extension: expected "
                    + extensionList
                    + " or .zip or a folder with such files";
        }
        return null;
    }

    private static String decompressCommand(String compressionExt, String fileName) {
        return switch (compressionExt) {
            case ".lz4" -> "unlz4 " + fileName;
            case ".gz" -> "gunzip " + fileName;
            case ".zst" -> "unzstd " + fileName;
            case ".bz2" -> "bunzip2 " + fileName;
            case ".xz" -> "unxz " + fileName;
            default -> "decompress " + fileName;
        };
    }

    @FileEndingAnnotation(ending = ".cjfr", allowZip = true, allowFolder = true)
    public static class ExistingCJFRFileOrZipOrFolderConverter
            extends ExistingFileWithExtensionOrZipOrFolderConverter {
        public ExistingCJFRFileOrZipOrFolderConverter() {
            super(".cjfr", true);
        }
    }

    @FileEndingAnnotation(ending = ".jfr", allowZip = true, allowFolder = true)
    public static class ExistingJFRFileOrZipOrFolderConverter
            extends ExistingFileWithExtensionOrZipOrFolderConverter {
        public ExistingJFRFileOrZipOrFolderConverter() {
            super(".jfr", true);
        }
    }

    /** Accepts both .cjfr and .jfr files (and zips/folders containing them) */
    public static class ExistingCJFROrJFRFileOrZipOrFolderConverter
            extends ExistingFileWithExtensionOrZipOrFolderConverter {
        public ExistingCJFROrJFRFileOrZipOrFolderConverter() {
            super(List.of(".cjfr", ".jfr"), true);
        }
    }
}
