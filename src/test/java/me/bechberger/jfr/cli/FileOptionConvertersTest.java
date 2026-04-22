package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileOptionConvertersTest {

    @TempDir Path tempDir;

    // --- ExistingFileWithExtensionConverter tests ---

    @Test
    public void testExistingJFRFileConverterValid() throws IOException {
        Path file = tempDir.resolve("test.jfr");
        Files.createFile(file);
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        assertEquals(file, converter.convert(file.toString()));
    }

    @Test
    public void testExistingJFRFileConverterEmpty() {
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        // allowEmpty is true, so empty should return Path.of("")
        assertEquals(Path.of(""), converter.convert(""));
    }

    @Test
    public void testExistingCJFRFileConverterValid() throws IOException {
        Path file = tempDir.resolve("test.cjfr");
        Files.createFile(file);
        var converter = new FileOptionConverters.ExistingCJFRFileConverter();
        assertEquals(file, converter.convert(file.toString()));
    }

    @Test
    public void testExistingFileConverterNotExist() {
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        Path nonExistent = tempDir.resolve("nonexistent.jfr");
        var ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> converter.convert(nonExistent.toString()));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    public void testExistingFileConverterWrongExtension() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.createFile(file);
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        var ex =
                assertThrows(
                        IllegalArgumentException.class, () -> converter.convert(file.toString()));
        assertTrue(ex.getMessage().contains("extension"));
    }

    @Test
    public void testExistingFileConverterDirectory() throws IOException {
        Path dir = tempDir.resolve("subdir.jfr");
        Files.createDirectory(dir);
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        var ex =
                assertThrows(
                        IllegalArgumentException.class, () -> converter.convert(dir.toString()));
        assertTrue(ex.getMessage().contains("directory"));
    }

    @Test
    public void testExistingFileConverterNoExtension() {
        Path file = tempDir.resolve("noext");
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        var ex =
                assertThrows(
                        IllegalArgumentException.class, () -> converter.convert(file.toString()));
        assertTrue(ex.getMessage().contains("extension"));
    }

    @Test
    public void testExistingFileConverterNotExistWrongExtension() {
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        Path nonExistent = tempDir.resolve("nonexistent.txt");
        var ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> converter.convert(nonExistent.toString()));
        assertTrue(
                ex.getMessage().contains("does not exist")
                        && ex.getMessage().contains("extension"));
    }

    @Test
    public void testExistingFileConverterNotEmptyNotAllowed() {
        var converter = new FileOptionConverters.ExistingFileWithExtensionConverter(".jfr", false);
        var ex = assertThrows(IllegalArgumentException.class, () -> converter.convert(""));
        assertTrue(ex.getMessage().contains("empty"));
    }

    // --- Suggestions test ---

    @Test
    public void testSuggestionsForMisspelledFile() throws IOException {
        // Create some .jfr files that could be suggested
        Files.createFile(tempDir.resolve("myrecording.jfr"));
        Files.createFile(tempDir.resolve("otherfile.jfr"));
        var converter = new FileOptionConverters.ExistingJFRFileConverter();
        Path misspelled = tempDir.resolve("myrecordin.jfr");
        var ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> converter.convert(misspelled.toString()));
        assertTrue(
                ex.getMessage().contains("Did you mean"),
                "Expected suggestions in: " + ex.getMessage());
    }

    // --- FileWithExtensionConverter tests ---

    @Test
    public void testJFRFileConverterValid() {
        var converter = new FileOptionConverters.JFRFileConverter();
        Path result = converter.convert("output.jfr");
        assertEquals(Path.of("output.jfr"), result);
    }

    @Test
    public void testJFRFileConverterEmpty() {
        var converter = new FileOptionConverters.JFRFileConverter();
        assertEquals(Path.of(""), converter.convert(""));
    }

    @Test
    public void testJFRFileConverterWrongExtension() {
        var converter = new FileOptionConverters.JFRFileConverter();
        assertThrows(IllegalArgumentException.class, () -> converter.convert("output.txt"));
    }

    @Test
    public void testCJFRFileConverterValid() {
        var converter = new FileOptionConverters.CJFRFileConverter();
        Path result = converter.convert("output.cjfr");
        assertEquals(Path.of("output.cjfr"), result);
    }

    @Test
    public void testHTMLFileConverterValid() {
        var converter = new FileOptionConverters.HTMLFileConverter();
        assertEquals(Path.of("report.html"), converter.convert("report.html"));
    }

    @Test
    public void testHTMLFileConverterWrongExtension() {
        var converter = new FileOptionConverters.HTMLFileConverter();
        assertThrows(IllegalArgumentException.class, () -> converter.convert("report.txt"));
    }

    // --- Annotation utility methods ---

    @Test
    public void testGetFileEndingAnnotation() {
        assertEquals(
                ".jfr",
                FileOptionConverters.getFileEndingAnnotation(
                        FileOptionConverters.ExistingJFRFileConverter.class));
        assertEquals(
                ".cjfr",
                FileOptionConverters.getFileEndingAnnotation(
                        FileOptionConverters.ExistingCJFRFileConverter.class));
        assertNull(
                FileOptionConverters.getFileEndingAnnotation(
                        FileOptionConverters.ExistingFileWithExtensionConverter.class));
    }

    @Test
    public void testIsFolderAllowed() {
        assertFalse(
                FileOptionConverters.isFolderAllowed(
                        FileOptionConverters.ExistingJFRFileConverter.class));
    }

    @Test
    public void testIsZipAllowed() {
        assertFalse(
                FileOptionConverters.isZipAllowed(
                        FileOptionConverters.ExistingJFRFileConverter.class));
    }

    @Test
    public void testExistingJFRFileOrZipOrFolderConverterAcceptsFolder() throws IOException {
        Path folder = tempDir.resolve("jfr-folder");
        Files.createDirectory(folder);
        Files.createFile(folder.resolve("inside.jfr"));

        var converter = new FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter();

        assertEquals(folder, converter.convert(folder.toString()));
    }

    @Test
    public void testExistingJFRFileOrZipOrFolderConverterRejectsEmptyFolder() throws IOException {
        Path folder = tempDir.resolve("empty-folder");
        Files.createDirectory(folder);

        var converter = new FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter();
        var exception =
                assertThrows(
                        IllegalArgumentException.class, () -> converter.convert(folder.toString()));

        assertTrue(exception.getMessage().contains("Folder does not contain"));
    }

    @Test
    public void testExistingJFRFileOrZipOrFolderConverterAcceptsZipWithMatchingFile()
            throws IOException {
        Path zip = tempDir.resolve("recordings.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("inside.jfr"));
            out.write("hello".getBytes());
            out.closeEntry();
        }

        var converter = new FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter();

        assertEquals(zip, converter.convert(zip.toString()));
    }

    @Test
    public void testExistingJFRFileOrZipOrFolderConverterRejectsZipWithoutMatchingFile()
            throws IOException {
        Path zip = tempDir.resolve("recordings.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("inside.txt"));
            out.write("hello".getBytes());
            out.closeEntry();
        }

        var converter = new FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter();
        var exception =
                assertThrows(
                        IllegalArgumentException.class, () -> converter.convert(zip.toString()));

        assertTrue(exception.getMessage().contains("ZIP file does not contain"));
    }

    @Test
    public void testExistingCJFRFileOrZipOrFolderConverterAcceptsZipWithMatchingFile()
            throws IOException {
        Path zip = tempDir.resolve("recordings.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("inside.cjfr"));
            out.write("hello".getBytes());
            out.closeEntry();
        }

        var converter = new FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter();

        assertEquals(zip, converter.convert(zip.toString()));
    }

    @Test
    public void testExistingJFRFileOrZipOrFolderConverterRejectsMissingPath() {
        var converter = new FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter();
        var exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> converter.convert(tempDir.resolve("missing.jfr").toString()));

        assertTrue(exception.getMessage().contains("does not exist"));
    }

    @Test
    public void testIsFolderAllowedForFolderConverters() {
        assertTrue(
                FileOptionConverters.isFolderAllowed(
                        FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter.class));
        assertTrue(
                FileOptionConverters.isFolderAllowed(
                        FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter.class));
    }

    @Test
    public void testIsZipAllowedForZipConverters() {
        assertTrue(
                FileOptionConverters.isZipAllowed(
                        FileOptionConverters.ExistingJFRFileOrZipOrFolderConverter.class));
        assertTrue(
                FileOptionConverters.isZipAllowed(
                        FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter.class));
    }
}
