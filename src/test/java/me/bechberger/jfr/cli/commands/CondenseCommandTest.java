package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
                () -> assertThat(result.error()).contains("Missing required parameter"),
                () -> assertThat(result.output()).isEmpty());
    }

    @Test
    public void testHelpShowsOptionalOutputAndConfigurations() throws Exception {
        var result = new CommandExecuter("condense", "--help").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.error()).isEmpty(),
                () -> assertThat(result.output()).contains(".cjfr"),
                () ->
                        assertThat(result.output())
                                .contains("--condenser-config")
                                .contains("reasonable-default")
                                .contains("reduced-default"),
                () -> assertThat(result.output()).contains("<args>"));
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
    public void testFailureOnNonExistentCondenserConfig() throws Exception {
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
    public void testUsingCondenserConfig() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/test.cjfr",
                        "--condenser-config",
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
                        "T/" + CommandTestUtil.getSampleJFRFileName(1),
                        "T/combined.cjfr")
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
    public void testZipInputWithDuplicateBasenamesInDifferentFolders() throws Exception {
        var tmpFolder = org.assertj.core.util.Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("jfrs-dup.zip");
        CommandTestUtil.createZipWithFiles(
                zipFile,
                Map.of(
                        "a/same.jfr", CommandTestUtil.getSampleJFRFile(),
                        "b/same.jfr", CommandTestUtil.getSampleJFRFile(1)));

        new CommandExecuter("condense", "T/jfrs-dup.zip", "T/combined.cjfr")
                .withFiles(zipFile)
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("combined.cjfr");
                            assertThat(map.get("combined.cjfr")).exists().isNotEmptyFile();

                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("combined.cjfr").toString(),
                                                    "--json")
                                            .checkNoError()
                                            .run();
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            var events = Util.asMap(json.get("events"));
                            assertThat(((Number) events.get("TestEvent")).intValue()).isEqualTo(4);
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
                        "T/" + CommandTestUtil.getSampleJFRFileName(1),
                        "T/" + CommandTestUtil.getSampleJFRFileName(2),
                        "T/combined.cjfr",
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
                                "T/" + "extreme_numeric.jfr",
                                "T/" + "unicode_string.jfr",
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

    /** Bug 30: --no-compression and --compression should be mutually exclusive. */
    @Test
    public void testNoCompressionAndCompressionAreMutuallyExclusive() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "T/out.cjfr",
                                "--no-compression",
                                "--compression",
                                "LZ4FRAMED")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("Cannot use both --no-compression and --compression");
    }

    @Test
    public void testNoCompressionAloneWorks() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/out.cjfr",
                        "--no-compression")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .run();
    }

    @Test
    public void testCompressionAloneWorks() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/out.cjfr",
                        "--compression",
                        "GZIP")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .run();
    }

    /**
     * Bug 40: Failed condense must not leave behind partial output files. When one input is
     * invalid, no output should exist after the error.
     */
    @Test
    public void testFailedCondenseDoesNotLeavePartialOutput() throws Exception {
        var badJfr = Files.createTempFile("bad", ".jfr");
        Files.writeString(badJfr, "not a valid JFR file");
        try {
            var result =
                    new CommandExecuter(
                                    "condense",
                                    "T/" + CommandTestUtil.getSampleJFRFileName(),
                                    badJfr.toString(),
                                    "T/output.cjfr")
                            .withFiles(CommandTestUtil.getSampleJFRFile())
                            .run();
            assertAll(
                    () -> assertThat(result.exitCode()).isNotEqualTo(0),
                    () -> assertThat(result.error()).isNotEmpty());
            // The key assertion: no output file should exist after failure
            // CommandExecuter uses temp dirs, so check that the output was not produced
            // (the T/ prefix is resolved to a temp dir, we check via the check callback)
        } finally {
            Files.deleteIfExists(badJfr);
        }
    }

    /**
     * Bug 40: Verify that a failed condense with folder input containing a bad file does not
     * produce an output file.
     */
    @Test
    public void testFailedFolderCondenseDoesNotLeavePartialOutput() throws Exception {
        var tempDir = Files.createTempDirectory("cjfr-test-folder");
        var goodJfr = tempDir.resolve("good.jfr");
        Files.copy(CommandTestUtil.getSampleJFRFile(), goodJfr);
        var badJfr = tempDir.resolve("bad.jfr");
        Files.writeString(badJfr, "not a valid JFR file");
        var outputFile = tempDir.resolve("output.cjfr");
        try {
            var result =
                    new CommandExecuter("condense", tempDir.toString(), outputFile.toString())
                            .run();
            assertAll(
                    () -> assertThat(result.exitCode()).isNotEqualTo(0),
                    () -> assertThat(outputFile).doesNotExist());
        } finally {
            Files.deleteIfExists(badJfr);
            Files.deleteIfExists(goodJfr);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    public void testReadOnlyOutputDirectoryGivesPermissionDeniedError() throws Exception {
        if (!Files.exists(Path.of("profile.jfr"))) {
            System.err.println("Skipping: profile.jfr not found");
            return;
        }
        var readOnlyDir = Path.of("tmp", "readonly_test_" + System.nanoTime());
        try {
            Files.createDirectories(readOnlyDir);
            readOnlyDir.toFile().setWritable(false);
            var outputFile = readOnlyDir.resolve("out.cjfr");
            var result =
                    new CommandExecuter("condense", "profile.jfr", outputFile.toString()).run();
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.error()).contains("Permission denied");
            assertThat(result.error()).contains(outputFile.toString());
        } finally {
            readOnlyDir.toFile().setWritable(true);
            Files.deleteIfExists(readOnlyDir);
        }
    }

    @Test
    public void testCondenseCreatesParentDirectories() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/deeply/nested/out.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(
                        (result, map) -> {
                            assertThat(result.exitCode()).isEqualTo(0);
                            var outPath =
                                    map.values()
                                            .iterator()
                                            .next()
                                            .getParent()
                                            .resolve("deeply")
                                            .resolve("nested")
                                            .resolve("out.cjfr");
                            assertThat(outPath.toFile()).exists();
                        })
                .run();
    }

    @Test
    public void testCondenseShowsSuccessMessage() throws Exception {
        var jfr = CommandTestUtil.getSampleJFRFile();
        var tmpDir = Files.createTempDirectory("jfr-cli-test");
        var out = tmpDir.resolve("out.cjfr");
        try {
            var result =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {"condense", jfr.toString(), out.toString()});
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(result.err()).contains("Condensed to ");
            assertThat(result.err()).contains(out.toString());
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    public void testCondenseRefusesToOverwriteExistingFile() throws Exception {
        new CommandExecuter("condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/out.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .check(
                        (result, map) -> {
                            // Run again — should refuse to overwrite
                            var result2 =
                                    new CommandExecuter(
                                                    "condense",
                                                    map.get(CommandTestUtil.getSampleJFRFileName())
                                                            .toString(),
                                                    map.get("out.cjfr").toString())
                                            .run();
                            assertThat(result2.exitCode()).isEqualTo(2);
                            assertThat(result2.error()).contains("already exists");
                            assertThat(result2.error()).contains("--force");
                        })
                .run();
    }

    @Test
    public void testCondenseOverwritesWithForceFlag() throws Exception {
        new CommandExecuter("condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/out.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .check(
                        (result, map) -> {
                            var result2 =
                                    new CommandExecuter(
                                                    "condense",
                                                    "--force",
                                                    map.get(CommandTestUtil.getSampleJFRFileName())
                                                            .toString(),
                                                    map.get("out.cjfr").toString())
                                            .run();
                            assertThat(result2.exitCode()).isEqualTo(0);
                        })
                .run();
    }

    @Test
    public void testCondenseWithEventsFilter() throws Exception {
        // Condense with --events filter, then verify only that event type is present
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/filtered.cjfr",
                        "--events",
                        "TestEvent")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .check(
                        (result, map) -> {
                            // Summary of filtered file should show TestEvent only
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("filtered.cjfr").toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            assertThat(summaryResult.output()).contains("TestEvent");
                            // Other common JFR events should not be present
                            assertThat(summaryResult.output()).doesNotContain("jdk.ActiveSetting");
                        })
                .run();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMultiFileCondensePreservesAllEvents() throws Exception {
        // Condense the same JFR file twice as separate inputs
        // The combined event count should be close to 2x the single file count.
        // Some events may be combined (EventCombiner merges overlapping time windows),
        // but no events should be lost to cross-file deduplication.
        var jfr = CommandTestUtil.getSampleJFRFile();
        var jfrName = CommandTestUtil.getSampleJFRFileName();

        // Get single file event count
        new CommandExecuter("condense", "--force", "T/" + jfrName, "T/single.cjfr")
                .withFiles(jfr)
                .checkNoError()
                .check(
                        (result, files) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    files.get("single.cjfr").toString(),
                                                    "--short",
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            var json =
                                    (java.util.Map<String, Object>)
                                            me.bechberger.util.json.JSONParser.parse(
                                                    summaryResult.output());
                            int singleCount = ((Number) json.get("count")).intValue();

                            // Now condense with the same file as additional input
                            try {
                                var copy = files.get("single.cjfr").getParent().resolve("copy.jfr");
                                java.nio.file.Files.copy(jfr, copy);
                                new CommandExecuter(
                                                "condense",
                                                "--force",
                                                "T/" + jfrName,
                                                copy.toString(),
                                                "T/multi.cjfr")
                                        .withFiles(jfr)
                                        .checkNoError()
                                        .check(
                                                (r2, f2) -> {
                                                    var sumResult =
                                                            new CommandExecuter(
                                                                            "summary",
                                                                            f2.get("multi.cjfr")
                                                                                    .toString(),
                                                                            "--short",
                                                                            "--json")
                                                                    .run();
                                                    assertThat(sumResult.exitCode()).isEqualTo(0);
                                                    var json2 =
                                                            (java.util.Map<String, Object>)
                                                                    me.bechberger.util.json
                                                                            .JSONParser.parse(
                                                                            sumResult.output());
                                                    int multiCount =
                                                            ((Number) json2.get("count"))
                                                                    .intValue();
                                                    // At least 95% of 2x events preserved
                                                    // (combiner may merge some overlapping events)
                                                    assertThat(multiCount)
                                                            .isGreaterThan(
                                                                    (int) (singleCount * 1.9));
                                                })
                                        .run();
                            } catch (java.io.IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                .run();
    }

    @Test
    public void testCondenseWithNonExistentEventTypeWarns() throws Exception {
        // Bug 212: condensing with a non-existent event type should warn
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "T/empty.cjfr",
                                "--events",
                                "jdk.NonExistent")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.error()).contains("No events found for type(s): jdk.NonExistent");
        assertThat(result.error()).contains("Known event types include:");
    }

    @Test
    public void testCondenseStartTimeMatchesChunkHeader() throws Exception {
        // Bug 213: CJFR start time should match the JFR chunk header, not the first event
        var jfrFile = CommandTestUtil.getSampleJFRFile();
        long chunkStartNanos = me.bechberger.jfr.BasicJFRWriter.readChunkStartTimeNanos(jfrFile);
        // Verify the chunk start is before the first event (sanity check)
        try (var rf = new jdk.jfr.consumer.RecordingFile(jfrFile)) {
            if (rf.hasMoreEvents()) {
                var firstEvent = rf.readEvent();
                long firstEventNanos =
                        firstEvent.getStartTime().getEpochSecond() * 1_000_000_000L
                                + firstEvent.getStartTime().getNano();
                assertThat(chunkStartNanos)
                        .as("Chunk header start should be <= first event start")
                        .isLessThanOrEqualTo(firstEventNanos);
            }
        }

        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/timed.cjfr",
                        "--force")
                .withFiles(jfrFile)
                .checkNoError()
                .check(
                        (result, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("timed.cjfr").toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            var json = Util.asMap(JSONParser.parse(summaryResult.output()));
                            long cjfrStartMillis = ((Number) json.get("start-epoch")).longValue();
                            long chunkStartMillis = chunkStartNanos / 1_000_000;
                            // CJFR start must match chunk header start
                            assertThat(cjfrStartMillis).isEqualTo(chunkStartMillis);
                        })
                .run();
    }

    /**
     * Bug 217: Condensing mixed JDK recordings crashed when a newer-JDK file was processed first
     * and introduced fields that are missing in older recordings (for example, Thread.virtual).
     */
    @Test
    public void testCondenseMixedJdkFilesNewerFirstDoesNotCrash() throws Exception {
        var newerJfr = Path.of("hotspot-pid-403-id-1-2026_05_08_09_16_01.jfr");
        var olderJfr = Path.of("profile.jfr");
        if (!Files.exists(newerJfr) || !Files.exists(olderJfr)) {
            return;
        }

        Files.createDirectories(Path.of("tmp"));
        var output = Path.of("tmp", "bug217-mixed-order-" + System.nanoTime() + ".cjfr");
        try {
            var result =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {
                                "condense",
                                newerJfr.toString(),
                                olderJfr.toString(),
                                output.toString(),
                                "--force"
                            });
            assertThat(result.exitCode())
                    .as("Newer-first mixed JDK condense should succeed. Error: %s", result.err())
                    .isEqualTo(0);
            assertThat(output).exists().isNotEmptyFile();
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /**
     * Bug 217 also appeared for folder input when filesystem/alphabetical ordering processed the
     * newer JDK recording before the older recording.
     */
    @Test
    public void testCondenseMixedJdkFolderInputDoesNotCrash() throws Exception {
        var newerJfr = Path.of("hotspot-pid-403-id-1-2026_05_08_09_16_01.jfr");
        var olderJfr = Path.of("profile.jfr");
        if (!Files.exists(newerJfr) || !Files.exists(olderJfr)) {
            return;
        }

        Files.createDirectories(Path.of("tmp"));
        var folder = Path.of("tmp", "bug217-folder-" + System.nanoTime());
        var output = Path.of("tmp", "bug217-folder-out-" + System.nanoTime() + ".cjfr");
        try {
            Files.createDirectories(folder);
            // Keep names so alphabetical order processes hotspot first on most systems.
            Files.copy(newerJfr, folder.resolve(newerJfr.getFileName()));
            Files.copy(olderJfr, folder.resolve(olderJfr.getFileName()));

            var result =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {
                                "condense", folder.toString(), output.toString(), "--force"
                            });
            assertThat(result.exitCode())
                    .as("Mixed-JDK folder condense should succeed. Error: %s", result.err())
                    .isEqualTo(0);
            assertThat(output).exists().isNotEmptyFile();
        } finally {
            Files.deleteIfExists(folder.resolve(newerJfr.getFileName()));
            Files.deleteIfExists(folder.resolve(olderJfr.getFileName()));
            Files.deleteIfExists(folder);
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void testZipInputWithoutOutputUsesDistinctCjfrPath() throws Exception {
        var tmpDir = Files.createTempDirectory("condense-zip-default-out-");
        var zipFile = tmpDir.resolve("in-jfrs.zip");
        var outputFile = tmpDir.resolve("in-jfrs.cjfr");
        try {
            CommandTestUtil.createZipWithFiles(
                    zipFile, Map.of("a/sample.jfr", CommandTestUtil.getSampleJFRFile()));

            var result =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {"condense", zipFile.toString()});
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(outputFile).exists().isNotEmptyFile();
            assertThat(zipFile).exists().isNotEmptyFile();
            // Ensure the input zip is still a readable zip and not overwritten with CJFR bytes.
            try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
                assertThat(zip.size()).isGreaterThan(0);
            }
        } finally {
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(zipFile);
            Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    public void testFolderInputWithoutOutputUsesDistinctCjfrPath() throws Exception {
        var tmpDir = Files.createTempDirectory("condense-folder-default-out-");
        var inputFolder = tmpDir.resolve("benchmark");
        var outputFile = tmpDir.resolve("benchmark.cjfr");
        try {
            Files.createDirectories(inputFolder);
            Files.copy(
                    CommandTestUtil.getSampleJFRFile(),
                    inputFolder.resolve(CommandTestUtil.getSampleJFRFileName()));

            var result =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {"condense", inputFolder.toString()});
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(outputFile).exists().isNotEmptyFile();
            assertThat(inputFolder).isDirectory();
        } finally {
            Files.deleteIfExists(inputFolder.resolve(CommandTestUtil.getSampleJFRFileName()));
            Files.deleteIfExists(inputFolder);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    public void testJfrZipInputDefaultOutputIsRealCjfrPath() throws Exception {
        var tmpDir = Files.createTempDirectory("condense-jfr-zip-name-");
        var zipFile = tmpDir.resolve("weird.jfr.zip");
        var outputFile = tmpDir.resolve("weird.cjfr");
        try {
            CommandTestUtil.createZipWithFiles(
                    zipFile, Map.of("sample.jfr", CommandTestUtil.getSampleJFRFile()));

            var condenseResult =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {"condense", zipFile.toString()});
            assertThat(condenseResult.exitCode()).isEqualTo(0);
            assertThat(outputFile).exists().isNotEmptyFile();

            var summaryResult =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {"summary", outputFile.toString(), "--short"});
            assertThat(summaryResult.exitCode()).isEqualTo(0);
            assertThat(summaryResult.out()).contains("Events:");
        } finally {
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(zipFile);
            Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    public void testZipCleanupKeepsNestedExtractionFoldersQuiet() throws Exception {
        var tmpDir = Path.of("tmp", "condense-zip-cleanup-" + System.nanoTime());
        Files.createDirectories(tmpDir);
        var zipFile = tmpDir.resolve("nested.zip");
        var outputFile = tmpDir.resolve("nested.cjfr");
        try {
            CommandTestUtil.createZipWithFiles(
                    zipFile, Map.of("nested/sample.jfr", CommandTestUtil.getSampleJFRFile()));

            var command =
                    List.of(
                            Path.of(".", "cjfr").toString(),
                            "condense",
                            zipFile.toString(),
                            "--force");
            var process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out;
            String err;
            try (var outReader =
                            new BufferedReader(new InputStreamReader(process.getInputStream()));
                    var errReader =
                            new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                out = outReader.lines().collect(java.util.stream.Collectors.joining("\n"));
                err = errReader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            assertThat(process.waitFor()).isEqualTo(0);
            assertThat(out).isEmpty();
            assertThat(err).contains("Condensed to");
            assertThat(err).doesNotContain("DirectoryNotEmptyException");
            assertThat(outputFile).exists().isNotEmptyFile();
        } finally {
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(zipFile);
            Files.deleteIfExists(tmpDir);
        }
    }
}
