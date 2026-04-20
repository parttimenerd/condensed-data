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
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;
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

    @Test
    public void testHelpShowsOptionalOutputAndConfigurations() throws Exception {
        var result = new CommandExecuter("condense", "--help").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.error()).isEmpty(),
                () -> assertThat(result.output()).contains("*.cjfr"),
                () ->
                        assertThat(result.output())
                                .contains("possible values: default")
                                .contains("reasonable-default")
                                .contains("reduced-default"),
                () ->
                        assertThat(result.output())
                                .contains("<inputFile>")
                                .contains("[<outputFile>]"));
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
    public void testInvalidJFRFileShowsFriendlyError() throws Exception {
        Path invalidJfr = Files.createTempFile("invalid", ".jfr");
        var result = new CommandExecuter("condense", invalidJfr.toString(), "T/out.cjfr").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(1),
                () -> assertThat(result.output()).isEmpty(),
                () -> assertThat(result.error()).contains("Not a valid Flight Recorder file"),
                () -> assertThat(result.error()).doesNotContain("\tat "));
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
            var entry = new java.util.zip.ZipEntry(CommandTestUtil.getSampleJFRFileName());
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

    @Test
    public void testCondensedOutputCanBeSummarized() throws Exception {
        new CommandExecuter("condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/out.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("out.cjfr").toString(),
                                                    "--json")
                                            .checkNoError()
                                            .run();
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            assertThat(json.get("generator configuration")).isEqualTo("default");
                            assertThat(Util.asMap(json.get("events"))).containsKey("TestEvent");
                        })
                .run();
    }

    @Test
    public void testCondensedOutputCanBeViewed() throws Exception {
        new CommandExecuter("condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/out.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    map.get("out.cjfr").toString(),
                                                    "TestEvent",
                                                    "--limit",
                                                    "1")
                                            .checkNoError()
                                            .run();
                            assertThat(viewResult.output()).contains("TestEvent");
                        })
                .run();
    }

    @Test
    public void testFolderInputCondensesIntoCombinedSummary() throws Exception {
        int expectedTestEventCount =
                (int)
                        (jdk.jfr.consumer.RecordingFile.readAllEvents(
                                                CommandTestUtil.getSampleJFRFile())
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count()
                                + jdk.jfr.consumer.RecordingFile.readAllEvents(
                                                CommandTestUtil.getSampleJFRFile(1))
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count());

        new CommandExecuter("condense", "T/", "T/combined.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile(), CommandTestUtil.getSampleJFRFile(1))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("combined.cjfr").toString(),
                                                    "--json")
                                            .checkNoError()
                                            .run();
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            Map<String, Object> events = Util.asMap(json.get("events"));
                            assertThat(((Number) events.get("TestEvent")).intValue())
                                    .isEqualTo(expectedTestEventCount);
                        })
                .run();
    }

    @Test
    public void testMultiInputReducedNoCompressionProducesExpectedSummary() throws Exception {
        int expectedTestEventCount =
                (int)
                        (jdk.jfr.consumer.RecordingFile.readAllEvents(
                                                CommandTestUtil.getSampleJFRFile())
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count()
                                + jdk.jfr.consumer.RecordingFile.readAllEvents(
                                                CommandTestUtil.getSampleJFRFile(1))
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count()
                                + jdk.jfr.consumer.RecordingFile.readAllEvents(
                                                CommandTestUtil.getSampleJFRFile(2))
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count());

        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/combined.cjfr",
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(1),
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(2),
                        "-c",
                        "reduced-default",
                        "--no-compression")
                .withFiles(
                        CommandTestUtil.getSampleJFRFile(),
                        CommandTestUtil.getSampleJFRFile(1),
                        CommandTestUtil.getSampleJFRFile(2))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("combined.cjfr").toString(),
                                                    "--json")
                                            .checkNoError()
                                            .run();
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            Map<String, Object> events = Util.asMap(json.get("events"));
                            assertThat(json.get("generator configuration"))
                                    .isEqualTo("reduced-default");
                            assertThat(json.get("compression")).isEqualTo(Compression.NONE.name());
                            assertThat(((Number) events.get("TestEvent")).intValue())
                                    .isEqualTo(expectedTestEventCount);
                        })
                .run();
    }

    /**
     * Bug: When using multiple input files with --statistics, the compression ratio calculation at
     * the end of CondenseCommand.call() only measures Files.size(inputFile) (the first file),
     * ignoring additional files in inputFiles list.
     *
     * <p>Expected: JFR file size should be sum of all input file sizes. Actual: JFR file size is
     * only the first file's size.
     */
    @Test
    public void testMultiFileStatisticsShowsTotalInputSize() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "-i",
                                "T/" + CommandTestUtil.getSampleJFRFileName(1),
                                "T/combined.cjfr",
                                "--statistics")
                        .withFiles(
                                CommandTestUtil.getSampleJFRFile(),
                                CommandTestUtil.getSampleJFRFile(1))
                        .checkNoError()
                        .run();

        // The statistics output should show the combined size of both input JFR files
        long file1Size = Files.size(CommandTestUtil.getSampleJFRFile());
        long file2Size = Files.size(CommandTestUtil.getSampleJFRFile(1));
        long totalSize = file1Size + file2Size;

        // Parse "JFR file size: NNN" from output
        var output = result.output();
        assertThat(output).contains("JFR file size:");
        var match = java.util.regex.Pattern.compile("JFR file size: (\\d+)").matcher(output);
        assertThat(match.find()).isTrue();
        long reportedSize = Long.parseLong(match.group(1));

        assertThat(reportedSize)
                .as("Should report total size of all input files, not just first")
                .isEqualTo(totalSize);
    }

    /**
     * Bug candidate: with folder input and --statistics, input size should represent the sum of all
     * JFR files in that folder, not the folder entry size itself.
     */
    @Test
    public void testFolderStatisticsShowsSumOfContainedJfrSizes() throws Exception {
        long expectedTotalSize =
                Files.size(CommandTestUtil.getSampleJFRFile())
                        + Files.size(CommandTestUtil.getSampleJFRFile(1));

        var result =
                new CommandExecuter("condense", "T/", "T/combined.cjfr", "--statistics")
                        .withFiles(
                                CommandTestUtil.getSampleJFRFile(),
                                CommandTestUtil.getSampleJFRFile(1))
                        .checkNoError()
                        .run();

        var match =
                java.util.regex.Pattern.compile("JFR file size: (\\d+)").matcher(result.output());
        assertThat(match.find()).isTrue();
        long reportedSize = Long.parseLong(match.group(1));

        assertThat(reportedSize)
                .as("Folder statistics should report total size of contained .jfr files")
                .isEqualTo(expectedTotalSize);
    }

    /** Test condensing JFR file with very large string fields */
    @Test
    public void testCondenseWithLargeStringEvents() throws Exception {
        var result =
                new CommandExecuter("condense", "T/" + "large_string.jfr", "T/large_string.cjfr")
                        .withFiles(CommandTestUtil.getLargeStringJFRFile())
                        .checkNoError()
                        .run();

        assertThat(result.output()).isEmpty();
    }

    /** Test condensing JFR file with extreme numeric values */
    @Test
    public void testCondenseWithExtremeNumericEvents() throws Exception {
        var result =
                new CommandExecuter(
                                "condense", "T/" + "extreme_numeric.jfr", "T/extreme_numeric.cjfr")
                        .withFiles(CommandTestUtil.getExtremeNumericJFRFile())
                        .checkNoError()
                        .run();

        assertThat(result.output()).isEmpty();
    }

    /** Test condensing JFR file with unicode and special character strings */
    @Test
    public void testCondenseWithUnicodeStringEvents() throws Exception {
        var result =
                new CommandExecuter(
                                "condense", "T/" + "unicode_string.jfr", "T/unicode_string.cjfr")
                        .withFiles(CommandTestUtil.getUnicodeStringJFRFile())
                        .checkNoError()
                        .run();

        assertThat(result.output()).isEmpty();
    }

    /** Test condensing JFR file with events containing many fields */
    @Test
    public void testCondenseWithManyFieldsEvents() throws Exception {
        var result =
                new CommandExecuter("condense", "T/" + "many_fields.jfr", "T/many_fields.cjfr")
                        .withFiles(CommandTestUtil.getManyFieldsJFRFile())
                        .checkNoError()
                        .run();

        assertThat(result.output()).isEmpty();
    }

    /** Test condensing mixed custom events with all edge cases */
    @Test
    public void testCondenseMixedEdgeCaseEvents() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + "large_string.jfr",
                                "-i",
                                "T/" + "extreme_numeric.jfr",
                                "-i",
                                "T/" + "unicode_string.jfr",
                                "-i",
                                "T/" + "many_fields.jfr",
                                "T/edge_cases_combined.cjfr")
                        .withFiles(
                                CommandTestUtil.getLargeStringJFRFile(),
                                CommandTestUtil.getExtremeNumericJFRFile(),
                                CommandTestUtil.getUnicodeStringJFRFile(),
                                CommandTestUtil.getManyFieldsJFRFile())
                        .checkNoError()
                        .run();

        assertThat(result.output()).isEmpty();
    }
}
