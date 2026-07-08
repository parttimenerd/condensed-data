package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import jdk.jfr.consumer.RecordingFile;
import org.assertj.core.data.Percentage;
import org.assertj.core.data.TemporalUnitLessThanOffset;
import org.assertj.core.util.Files;
import org.junit.jupiter.api.Test;

@InflaterRelated
public class InflateCommandTest {

    @Test
    public void testPrintHelpWithNoArguments() throws Exception {
        var result = new CommandExecuter("inflate").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () -> assertThat(result.error()).contains("Missing required parameter"),
                () -> assertThat(result.output()).isEmpty());
    }

    private static void checkSampleFile(Path condensedFile) throws Exception {
        checkSampleFile(condensedFile, List.of(CommandTestUtil.getSampleJFRFile()));
    }

    private static void checkSampleFile(Path condensedFile, List<Path> origJFRFiles)
            throws Exception {
        assertThat(condensedFile).exists();
        var events = RecordingFile.readAllEvents(condensedFile);
        var origEvents =
                origJFRFiles.stream()
                        .flatMap(
                                f -> {
                                    try {
                                        return RecordingFile.readAllEvents(f).stream();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .toList();
        assertThat(events).size().isCloseTo(origEvents.size(), Percentage.withPercentage(90));

        var testEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                        .sorted(Comparator.comparingLong(e -> e.getStartTime().toEpochMilli()))
                        .toList();
        var origTestEvents =
                origEvents.stream()
                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                        .sorted(Comparator.comparingLong(e -> e.getStartTime().toEpochMilli()))
                        .toList();
        assertThat(testEvents).size().isEqualTo(origTestEvents.size());

        for (int i = 0; i < testEvents.size(); i++) {
            var event = testEvents.get(i);
            var origEvent = origTestEvents.get(i);
            assertThat(event.getStartTime())
                    .isCloseTo(
                            origEvent.getStartTime(),
                            new TemporalUnitLessThanOffset(1, ChronoUnit.MILLIS));
            assertThat(event.getDuration())
                    .isCloseTo(origEvent.getDuration(), Duration.of(1, ChronoUnit.MILLIS));
            assertThat(event.getEventType().getName())
                    .isEqualTo(origEvent.getEventType().getName());
            assertThat(event.getLong("memory")).isEqualTo(origEvent.getLong("memory"));
            assertThat(event.getString("string")).isEqualTo(origEvent.getString("string"));
        }
    }

    @Test
    public void testSingleCJFRArgumentShouldProduceCondensedFile() throws Exception {
        new CommandExecuter("inflate", "T/" + CommandTestUtil.getSampleCJFRFileName())
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var inflatedFile =
                                    CommandTestUtil.getSampleCJFRFileName()
                                            .replace(".cjfr", ".inflated.jfr");
                            assertThat(map).containsKey(inflatedFile);
                            checkSampleFile(map.get(inflatedFile));
                        })
                .run();
    }

    @Test
    public void testSingleCJFRArgumentWithOutputShouldProduceCondensedFile() throws Exception {
        new CommandExecuter("inflate", "T/" + CommandTestUtil.getSampleCJFRFileName(), "T/out.jfr")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var inflatedFile = "out.jfr";
                            assertThat(map).containsKey(inflatedFile);
                            checkSampleFile(map.get(inflatedFile));
                        })
                .run();
    }

    @Test
    public void testWithNonExistantFile() throws Exception {
        new CommandExecuter("inflate", "T/non_existant.cjfr")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .check(
                        (result, map) -> {
                            assertThat(result.exitCode()).isEqualTo(2);
                            assertThat(result.error()).contains("File does not exist");
                        })
                .run();
    }

    @Test
    public void testWithNonCJFRFile() throws Exception {
        new CommandExecuter("inflate", "T/" + CommandTestUtil.getSampleJFRFileName())
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(
                        (result, map) -> {
                            assertThat(result.exitCode()).isEqualTo(2);
                            assertThat(result.error()).contains("File does not");
                        })
                .run();
    }

    @Test
    public void testWithFolder() throws Exception {
        new CommandExecuter("inflate", "T/", "T/out.jfr")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            checkSampleFile(map.get("out.jfr"));
                        })
                .run();
    }

    @Test
    public void testWithZIPFile() throws Exception {
        // create temporary ZIP file that contains the sample CJFR file
        var tmpFolder = Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("sample.zip");
        try (var zipOutputStream = java.nio.file.Files.newOutputStream(zipFile);
                var zip = new java.util.zip.ZipOutputStream(zipOutputStream)) {
            var entry = new java.util.zip.ZipEntry("sample.cjfr");
            zip.putNextEntry(entry);
            java.nio.file.Files.copy(CommandTestUtil.getSampleCJFRFile(), zip);
            zip.closeEntry();
        }

        new CommandExecuter("inflate", "T/sample.zip", "T/out.jfr")
                .withFiles(zipFile)
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            checkSampleFile(map.get("out.jfr"));
                        })
                .run();
    }

    @Test
    public void testDataAmountBytesOddValuesPreservedAfterCondenseAndInflate() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/byte_count.jfr",
                        "T/byte_count.cjfr",
                        "--condenser-config",
                        "reasonable-default")
                .withFiles(CommandTestUtil.getByteCountJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (condenseResult, files) -> {
                            new CommandExecuter(
                                            "inflate",
                                            files.get("byte_count.cjfr").toString(),
                                            "T/out.jfr")
                                    .checkNoError()
                                    .checkNoOutput()
                                    .check(
                                            (inflateResult, inflateFiles) -> {
                                                var events =
                                                        RecordingFile.readAllEvents(
                                                                inflateFiles.get("out.jfr"));
                                                var event =
                                                        events.stream()
                                                                .filter(
                                                                        e ->
                                                                                e.getEventType()
                                                                                        .getName()
                                                                                        .equals(
                                                                                                "ByteCountEvent"))
                                                                .findFirst()
                                                                .orElseThrow();
                                                assertThat(event.getLong("bytesReadAligned"))
                                                        .isEqualTo(16L);
                                                assertThat(event.getLong("bytesReadOdd"))
                                                        .isEqualTo(15L);
                                                assertThat(event.getLong("bytesReadTiny"))
                                                        .isEqualTo(3L);
                                            })
                                    .run();
                        })
                .run();
    }

    @Test
    public void testWithZIPFileDuplicateBasenamesInDifferentFolders() throws Exception {
        var tmpFolder = Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("sample-dup.zip");
        CommandTestUtil.createZipWithFiles(
                zipFile,
                java.util.Map.of(
                        "a/same.cjfr", CommandTestUtil.getSampleCJFRFile(),
                        "b/same.cjfr", CommandTestUtil.getSampleCJFRFile(1)));

        new CommandExecuter("inflate", "T/sample-dup.zip", "T/out.jfr")
                .withFiles(zipFile)
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) ->
                                checkSampleFile(
                                        map.get("out.jfr"),
                                        List.of(
                                                CommandTestUtil.getSampleJFRFile(),
                                                CommandTestUtil.getSampleJFRFile(1))))
                .run();
    }

    /**
     * Bug candidate: when input is a zip and output is omitted, default output path must not
     * overwrite the zip input file.
     */
    @Test
    public void testZipInputWithoutOutputDoesNotOverwriteZip() throws Exception {
        var tmpFolder = Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("sample.zip");
        try (var zipOutputStream = java.nio.file.Files.newOutputStream(zipFile);
                var zip = new java.util.zip.ZipOutputStream(zipOutputStream)) {
            var entry = new java.util.zip.ZipEntry("sample.cjfr");
            zip.putNextEntry(entry);
            java.nio.file.Files.copy(CommandTestUtil.getSampleCJFRFile(), zip);
            zip.closeEntry();
        }

        byte[] originalZipBytes = java.nio.file.Files.readAllBytes(zipFile);

        var result =
                new CommandExecuter("inflate", "T/sample.zip")
                        .withFiles(zipFile)
                        .checkNoError()
                        .check(
                                (r, files) -> {
                                    assertThat(files).containsKey("sample.zip");
                                    // input zip should remain intact and must not be replaced by
                                    // JFR bytes
                                    assertThat(
                                                    java.nio.file.Files.readAllBytes(
                                                            files.get("sample.zip")))
                                            .isEqualTo(originalZipBytes);
                                    // default output should be generated as a distinct inflated JFR
                                    // file
                                    assertThat(files.keySet())
                                            .anyMatch(name -> name.endsWith(".inflated.jfr"));
                                })
                        .run();

        assertThat(result.error()).isEmpty();
    }

    @Test
    public void testWithMultipleInputFiles() throws Exception {
        var cjfrZero = CommandTestUtil.getSampleCJFRFileName();
        var cjfrOne = CommandTestUtil.getSampleCJFRFileName(1);
        new CommandExecuter("inflate", "T/" + cjfrZero, "T/" + cjfrOne, "T/out.jfr")
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(), CommandTestUtil.getSampleCJFRFile(1))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            checkSampleFile(
                                    map.get("out.jfr"),
                                    List.of(
                                            CommandTestUtil.getSampleJFRFile(),
                                            CommandTestUtil.getSampleJFRFile(1)));
                        })
                .run();
    }

    @Test
    public void testWithThreeInputFiles() throws Exception {
        var cjfrZero = CommandTestUtil.getSampleCJFRFileName();
        var cjfrOne = CommandTestUtil.getSampleCJFRFileName(1);
        var cjfrTwo = CommandTestUtil.getSampleCJFRFileName(2);
        new CommandExecuter(
                        "inflate",
                        "T/" + cjfrZero,
                        "T/" + cjfrOne,
                        "T/" + cjfrTwo,
                        "T/out.jfr")
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(),
                        CommandTestUtil.getSampleCJFRFile(1),
                        CommandTestUtil.getSampleCJFRFile(2))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            checkSampleFile(
                                    map.get("out.jfr"),
                                    List.of(
                                            CommandTestUtil.getSampleJFRFile(),
                                            CommandTestUtil.getSampleJFRFile(1),
                                            CommandTestUtil.getSampleJFRFile(2)));
                        })
                .run();
    }

    @Test
    public void testWithMultipleInputFilesThatAreTheSameFails() throws Exception {
        var cjfrZero = CommandTestUtil.getSampleCJFRFile(0);
        new CommandExecuter("inflate", "T/" + cjfrZero, "T/" + cjfrZero, "T/out.jfr")
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(), CommandTestUtil.getSampleCJFRFile(1))
                .check(
                        (result, map) -> {
                            assertThat(result.exitCode()).isEqualTo(2);
                            assertThat(result.error()).isNotEmpty();
                        })
                .run();
    }

    @Test
    public void testWithMultipleInputFilesInFolder() throws Exception {
        new CommandExecuter("inflate", "T/", "T/out.jfr")
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(), CommandTestUtil.getSampleCJFRFile(1))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            checkSampleFile(
                                    map.get("out.jfr"),
                                    List.of(
                                            CommandTestUtil.getSampleJFRFile(),
                                            CommandTestUtil.getSampleJFRFile(1)));
                        })
                .run();
    }

    @Test
    public void testWithMultipleFilesInZip() throws Exception {
        var tmpFolder = Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("sample.zip");
        try (var zipOutputStream = java.nio.file.Files.newOutputStream(zipFile);
                var zip = new java.util.zip.ZipOutputStream(zipOutputStream)) {
            for (int i = 0; i < 3; i++) {
                var entry = new java.util.zip.ZipEntry(CommandTestUtil.getSampleCJFRFileName(i));
                zip.putNextEntry(entry);
                java.nio.file.Files.copy(CommandTestUtil.getSampleCJFRFile(i), zip);
                zip.closeEntry();
            }
        }

        new CommandExecuter("inflate", "T/sample.zip", "T/out.jfr")
                .withFiles(zipFile)
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            checkSampleFile(
                                    map.get("out.jfr"),
                                    List.of(
                                            CommandTestUtil.getSampleJFRFile(),
                                            CommandTestUtil.getSampleJFRFile(1),
                                            CommandTestUtil.getSampleJFRFile(2)));
                        })
                .run();
    }

    @Test
    public void testEmptyFile() throws Exception {
        new CommandExecuter("inflate", "T/" + CommandTestUtil.getEmptyCJFRFileName())
                .withFiles(CommandTestUtil.getEmptyCJFRFile())
                .check(
                        (result, map) -> {
                            assertThat(result.exitCode()).isEqualTo(0);
                            assertThat(result.error()).isEmpty();
                        })
                .run();
    }

    @Test
    public void testEventsFilter() throws Exception {
        // Inflate with --events TestEvent should only include TestEvent, not AnotherEvent
        new CommandExecuter(
                        "inflate",
                        "T/" + CommandTestUtil.getSampleCJFRFileName(),
                        "T/out.jfr",
                        "--events",
                        "TestEvent")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("out.jfr");
                            var events = RecordingFile.readAllEvents(map.get("out.jfr"));
                            assertThat(
                                            events.stream()
                                                    .filter(
                                                            e ->
                                                                    e.getEventType()
                                                                            .getName()
                                                                            .equals("TestEvent")))
                                    .isNotEmpty();
                            assertThat(
                                            events.stream()
                                                    .filter(
                                                            e ->
                                                                    e.getEventType()
                                                                            .getName()
                                                                            .equals(
                                                                                    "AnotherEvent")))
                                    .isEmpty();
                        })
                .run();
    }

    @Test
    public void testUnknownEventsFilterFailsWithClearError() throws Exception {
        var result =
                new CommandExecuter(
                                "inflate",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "T/out.jfr",
                                "--events",
                                "does.not.exist")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("Unknown event type(s): does.not.exist");
        assertThat(result.output()).isEmpty();
    }

    @Test
    public void testNoReconstitution() throws Exception {
        // --no-reconstitution should still produce a JFR file
        new CommandExecuter(
                        "inflate",
                        "T/" + CommandTestUtil.getSampleCJFRFileName(),
                        "T/out.jfr",
                        "--no-reconstitution")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("out.jfr");
                            assertThat(map.get("out.jfr")).exists();
                            var events = RecordingFile.readAllEvents(map.get("out.jfr"));
                            assertThat(events).isNotEmpty();
                        })
                .run();
    }

    @Test
    public void testCondenseThenInflateRoundTrip() throws Exception {
        new CommandExecuter("condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/out.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var condensedFile = map.get("out.cjfr");
                            var roundTripFile = condensedFile.resolveSibling("roundtrip.jfr");
                            var inflateResult =
                                    new CommandExecuter(
                                                    "inflate",
                                                    condensedFile.toString(),
                                                    roundTripFile.toString())
                                            .checkNoError()
                                            .run();
                            assertThat(inflateResult.output()).isEmpty();
                            assertThat(inflateResult.error()).isEmpty();
                            checkSampleFile(roundTripFile);
                        })
                .run();
    }

    @Test
    public void testCombinedCondenseThenFilteredInflateWithoutReconstitution() throws Exception {
        long expectedTestEvents =
                Stream.of(
                                CommandTestUtil.getSampleJFRFile(),
                                CommandTestUtil.getSampleJFRFile(1),
                                CommandTestUtil.getSampleJFRFile(2))
                        .mapToLong(
                                path -> {
                                    try {
                                        return RecordingFile.readAllEvents(path).stream()
                                                .filter(
                                                        e ->
                                                                e.getEventType()
                                                                        .getName()
                                                                        .equals("TestEvent"))
                                                .count();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .sum();

        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/combined.cjfr",
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(1),
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(2))
                .withFiles(
                        CommandTestUtil.getSampleJFRFile(),
                        CommandTestUtil.getSampleJFRFile(1),
                        CommandTestUtil.getSampleJFRFile(2))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) ->
                                new CommandExecuter(
                                                "inflate",
                                                "T/combined.cjfr",
                                                "T/filtered.jfr",
                                                "--events",
                                                "TestEvent",
                                                "--no-reconstitution")
                                        .withFiles(map.get("combined.cjfr"))
                                        .checkNoError()
                                        .checkNoOutput()
                                        .check(
                                                (inflateResult, inflateMap) -> {
                                                    var events =
                                                            RecordingFile.readAllEvents(
                                                                    inflateMap.get("filtered.jfr"));
                                                    assertThat(events)
                                                            .allMatch(
                                                                    e ->
                                                                            e.getEventType()
                                                                                    .getName()
                                                                                    .equals(
                                                                                            "TestEvent"));
                                                    assertThat(events.size())
                                                            .isEqualTo((int) expectedTestEvents);
                                                })
                                        .run())
                .run();
    }

    /** Test inflate with condensed large string custom events */
    @Test
    public void testInflateWithCondensedLargeStringEvents() throws Exception {
        new CommandExecuter("condense", "T/" + "large_string.jfr", "T/large_string.cjfr")
                .withFiles(CommandTestUtil.getLargeStringJFRFile())
                .check(
                        (condenseResult, map) -> {
                            var inflateResult =
                                    new CommandExecuter(
                                                    "inflate",
                                                    map.get("large_string.cjfr").toString())
                                            .run();
                            assertThat(inflateResult.exitCode()).isEqualTo(0);
                        })
                .run();
    }

    /** Test inflate with condensed extreme numeric custom events */
    @Test
    public void testInflateWithCondensedExtremeNumericEvents() throws Exception {
        new CommandExecuter("condense", "T/" + "extreme_numeric.jfr", "T/extreme_numeric.cjfr")
                .withFiles(CommandTestUtil.getExtremeNumericJFRFile())
                .check(
                        (condenseResult, map) -> {
                            var inflateResult =
                                    new CommandExecuter(
                                                    "inflate",
                                                    map.get("extreme_numeric.cjfr").toString())
                                            .run();
                            assertThat(inflateResult.exitCode()).isEqualTo(0);
                        })
                .run();
    }

    /** Test inflate with condensed unicode string custom events */
    @Test
    public void testInflateWithCondensedUnicodeStringEvents() throws Exception {
        new CommandExecuter("condense", "T/" + "unicode_string.jfr", "T/unicode_string.cjfr")
                .withFiles(CommandTestUtil.getUnicodeStringJFRFile())
                .check(
                        (condenseResult, map) -> {
                            var inflateResult =
                                    new CommandExecuter(
                                                    "inflate",
                                                    map.get("unicode_string.cjfr").toString())
                                            .run();
                            assertThat(inflateResult.exitCode()).isEqualTo(0);
                        })
                .run();
    }

    /** Test inflate with condensed many fields custom events */
    @Test
    public void testInflateWithCondensedManyFieldsEvents() throws Exception {
        new CommandExecuter("condense", "T/" + "many_fields.jfr", "T/many_fields.cjfr")
                .withFiles(CommandTestUtil.getManyFieldsJFRFile())
                .check(
                        (condenseResult, map) -> {
                            var inflateResult =
                                    new CommandExecuter(
                                                    "inflate",
                                                    map.get("many_fields.cjfr").toString())
                                            .run();
                            assertThat(inflateResult.exitCode()).isEqualTo(0);
                        })
                .run();
    }

    @Test
    public void testInflateCreatesParentDirectories() throws Exception {
        new CommandExecuter(
                        "condense", "T/" + CommandTestUtil.getSampleJFRFileName(), "T/sample.cjfr")
                .withFiles(CommandTestUtil.getSampleJFRFile())
                .check(
                        (condenseResult, map) -> {
                            var nestedOutput =
                                    map.get("sample.cjfr")
                                            .getParent()
                                            .resolve("deeply")
                                            .resolve("nested")
                                            .resolve("out.jfr");
                            var inflateResult =
                                    new CommandExecuter(
                                                    "inflate",
                                                    map.get("sample.cjfr").toString(),
                                                    nestedOutput.toString())
                                            .run();
                            assertThat(inflateResult.exitCode()).isEqualTo(0);
                            assertThat(nestedOutput.toFile()).exists();
                        })
                .run();
    }

    @Test
    public void testInflateRefusesReadOnlyOutputFile() throws Exception {
        new CommandExecuter(
                        "inflate", "T/" + CommandTestUtil.getSampleCJFRFileName(), "T/readonly.jfr")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .check(
                        (result, files) -> {
                            // First create the output file as read-only
                            files.get("readonly.jfr");
                            // This is the first run, it should succeed
                        })
                .run();

        // Now do a second run with a pre-created read-only file
        var tempDir = java.nio.file.Files.createTempDirectory("jfr-cli-test");
        var cjfrPath = tempDir.resolve(CommandTestUtil.getSampleCJFRFileName());
        java.nio.file.Files.copy(CommandTestUtil.getSampleCJFRFile(), cjfrPath);
        var readOnlyOut = tempDir.resolve("readonly.jfr");
        java.nio.file.Files.writeString(readOnlyOut, "dummy");
        readOnlyOut.toFile().setReadOnly();
        try {
            var result =
                    new CommandExecuter("inflate", cjfrPath.toString(), readOnlyOut.toString())
                            .run();
            assertThat(result.exitCode()).isNotEqualTo(0);
            assertThat(result.error()).contains("read-only");
        } finally {
            readOnlyOut.toFile().setWritable(true);
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
