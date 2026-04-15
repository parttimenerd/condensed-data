package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.commands.CommandExecuter.BiConsumerWithException;
import me.bechberger.jfr.cli.commands.CommandExecuter.CommandResult;
import org.junit.jupiter.api.Test;

/** Test {@link CondenseCommand} */
public class CondenseCommandTest {

    private static BiConsumerWithException<CommandResult, Map<String, Path>> checkFiles(
            boolean checkOutAndErrCode, String... files) {
        return (result, map) -> {
            if (checkOutAndErrCode) {
                result.assertNoErrorOrOutput();
            }
            assertAll(
                    Arrays.stream(files)
                            .map(
                                    file ->
                                            () -> {
                                                var modifiedFile = file.replace(".jfr", ".cjfr");
                                                assertThat(map).containsKey(file);
                                                assertThat(map.get(file)).exists().isNotEmptyFile();
                                                assertThat(map).containsKey(modifiedFile);
                                                assertThat(map.get(modifiedFile))
                                                        .exists()
                                                        .isNotEmptyFile();
                                            }));
        };
    }

    @Test
    public void testPrintHelpWithNoArguments() throws Exception {
        var result = new CommandExecuter("condense").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () ->
                        assertThat(result.error())
                                .containsIgnoringNewLines("Usage: cjfr")
                                .contains("Missing required parameter"),
                () -> assertThat(result.output()).isEmpty());
    }

    /** Check that {@code condense sample.jfr} produces a condensed file {@code sample.cjfr} */
    @Test
    public void testSingleJFRArgumentShouldProduceCondensedFile() throws Exception {
        new CommandExecuter("condense", "T/" + CommandTestUtil.getSampleJFRFileName())
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(checkFiles(true, CommandTestUtil.getSampleJFRFileName()))
                .run();
    }

    /**
     * Check that {@code condense sample.jfr output.cjfr} produces a condensed file {@code
     * output.cjfr}
     */
    @Test
    public void testSingleJFRArgumentWithOutputShouldProduceCondensedFile() throws Exception {
        new CommandExecuter(
                        "condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/output.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(checkFiles(true, "output.cjfr"))
                .run();
    }

    @Test
    public void testWithNonExistantFile() throws Exception {
        var result = new CommandExecuter("condense", "T/nonexistant.jfr").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () -> assertThat(result.error()).contains("does not exist"),
                () -> assertThat(result.output()).isEmpty());
    }

    @Test
    public void testWithNonJFRFile() throws Exception {
        Path tmp = Files.createTempFile("nonjfr", ".txt");
        Files.writeString(tmp, "Hello");
        var result = new CommandExecuter("condense", tmp.toAbsolutePath().toString()).run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () -> assertThat(result.error()).contains("expected .jfr"),
                () -> assertThat(result.output()).isEmpty());
    }

    @Test
    public void testOutputFileDoesNotEndWithCJFR() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "T/output.jfr")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () -> assertThat(result.output()).isEmpty());
    }

    @Test
    public void testFailureOnNonExistentGeneratorConfiguration() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "-c",
                                "nonexistent")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () -> assertThat(result.error()).contains("Invalid value"),
                () -> assertThat(result.output()).isEmpty());
    }

    private static Configuration readConfiguration(Path condensedFile) throws IOException {
        try (var is = Files.newInputStream(condensedFile)) {
            BasicJFRReader reader = new BasicJFRReader(new CondensedInputStream(is));
            reader.readNextEvent();
            return reader.getConfiguration();
        }
    }

    private static StartMessage readStartMessage(Path condensedFile) throws IOException {
        try (var is = Files.newInputStream(condensedFile)) {
            BasicJFRReader reader = new BasicJFRReader(new CondensedInputStream(is));
            reader.readNextEvent();
            return reader.getStartMessage();
        }
    }

    @Test
    public void testUsingGeneratorConfiguration() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/test.cjfr",
                        "-c",
                        "reduced-default")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(
                        (result, files) -> {
                            result.assertNoErrorOrOutput();
                            var testFile = files.get("test.cjfr");
                            assertThat(readConfiguration(testFile).name())
                                    .isEqualTo("reduced-default");
                            assertThat(readStartMessage(testFile).compression())
                                    .isEqualTo(Compression.DEFAULT);
                        })
                .run();
    }

    @Test
    public void testUsingNoCompression() throws Exception {
        AtomicLong nonCompressedSize = new AtomicLong();
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/test.cjfr",
                        "--no-compression")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(
                        (result, files) -> {
                            result.assertNoErrorOrOutput();
                            var testFile = files.get("test.cjfr");
                            assertThat(readStartMessage(testFile).compression())
                                    .isEqualTo(Compression.NONE);
                            assertThat(readConfiguration(testFile))
                                    .isEqualTo(Configuration.DEFAULT);
                            nonCompressedSize.set(Files.size(testFile));
                        })
                .run();
        new CommandExecuter(
                        "condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/test.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(
                        (result, files) -> {
                            result.assertNoErrorOrOutput();
                            var testFile = files.get("test.cjfr");
                            assertThat(Files.size(testFile)).isLessThan(nonCompressedSize.get());
                        })
                .run();
    }

    @Test
    public void testStatistics() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "T/test.cjfr",
                                "-s")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .check(checkFiles(false, "test.cjfr"))
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.error()).isEmpty(),
                () -> assertThat(result.output()).contains("compression ratio"));
    }

    @Test
    public void testEmptyJFRFile() throws Exception {
        new CommandExecuter("condense", "T/empty.jfr")
                .withFiles(CommandTestUtil.getEmptyJFRFile())
                .check(checkFiles(true, "empty.jfr"))
                .run();
    }

    @Test
    public void testMultipleInputFiles() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/combined.cjfr",
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(1))
                .withFiles(CommandTestUtil.getSampleJFRFile(), CommandTestUtil.getSampleJFRFile(1))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("combined.cjfr");
                            assertThat(map.get("combined.cjfr")).exists().isNotEmptyFile();
                        })
                .run();
    }

    @Test
    public void testMultipleInputsWithoutOutputFails() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "-i",
                                "T/" + CommandTestUtil.getSampleJFRFileName(1))
                        .withFiles(
                                CommandTestUtil.getSampleJFRFile(),
                                CommandTestUtil.getSampleJFRFile(1))
                        .run();
        assertThat(result.exitCode()).isNotEqualTo(0);
    }

    @Test
    public void testFolderInput() throws Exception {
        new CommandExecuter("condense", "T/", "T/combined.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile(), CommandTestUtil.getSampleJFRFile(1))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("combined.cjfr");
                            assertThat(map.get("combined.cjfr")).exists().isNotEmptyFile();
                        })
                .run();
    }

    @Test
    public void testZipInput() throws Exception {
        var tmpFolder = org.assertj.core.util.Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("jfrs.zip");
        try (var zipOutputStream = java.nio.file.Files.newOutputStream(zipFile);
                var zip = new java.util.zip.ZipOutputStream(zipOutputStream)) {
            var entry =
                    new java.util.zip.ZipEntry(CommandTestUtil.getSampleJFRFileName());
            zip.putNextEntry(entry);
            java.nio.file.Files.copy(CommandTestUtil.getSampleJFRFile(), zip);
            zip.closeEntry();
        }

        new CommandExecuter("condense", "T/jfrs.zip", "T/combined.cjfr")
                .withFiles(zipFile)
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("combined.cjfr");
                            assertThat(map.get("combined.cjfr")).exists().isNotEmptyFile();
                        })
                .run();
    }
}
