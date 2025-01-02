package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import jdk.jfr.consumer.RecordingFile;
import org.assertj.core.data.Percentage;
import org.assertj.core.data.TemporalUnitLessThanOffset;
import org.assertj.core.util.Files;
import org.junit.jupiter.api.Test;

public class InflateCommandTest {

    @Test
    public void testPrintHelpWithNoArguments() throws Exception {
        var result = new CommandExecuter("inflate").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () ->
                        assertThat(result.error())
                                .containsIgnoringNewLines("Usage: cjfr")
                                .contains("Missing required parameters"),
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
                        .toList();
        var origTestEvents =
                origEvents.stream()
                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
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
    public void testWithMultipleInputFiles() throws Exception {
        var cjfrZero = CommandTestUtil.getSampleCJFRFileName();
        var cjfrOne = CommandTestUtil.getSampleCJFRFileName(1);
        new CommandExecuter("inflate", "T/" + cjfrZero, "T/out.jfr", "--inputs", "T/" + cjfrOne)
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
    public void testWithMultipleInputFilesThatAreTheSameFails() throws Exception {
        var cjfrZero = CommandTestUtil.getSampleCJFRFile(0);
        new CommandExecuter("inflate", "T/" + cjfrZero, "T/out.jfr", "--inputs", "T/" + cjfrZero)
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
        var cjfrZero = CommandTestUtil.getSampleCJFRFile();
        var cjfrOne = CommandTestUtil.getSampleCJFRFile(1);
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
}
