package me.bechberger.jfr.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests for the JFR CLI, regarding multiple files */
public class JFRMultiFileTest {

    // custom JFR event
    @Name("TestEvent")
    @StackTrace(false)
    static class TestEvent extends Event {
        int number;

        TestEvent(int number) {
            this.number = number;
        }
    }

    private static Path testFileFolder;

    /** contains 4 files, each file contains a single TestEvent with the index as its number */
    private static List<Path> testFiles;

    private static List<Path> createTestFiles(Path folder) {
        List<Path> files = new ArrayList<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("TestEvent");
            rs.onEvent(
                    "TestEvent",
                    t -> {
                        var number = (int) t.getValue("number");
                        var file = folder.resolve("test" + number + ".cjfr");
                        try {
                            var jfrWriter =
                                    new BasicJFRWriter(
                                            new CondensedOutputStream(
                                                    Files.newOutputStream(file),
                                                    StartMessage.DEFAULT));
                            jfrWriter.processEvent(t);
                            files.add(file);
                            jfrWriter.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (number == 3) {
                            rs.close();
                        }
                    });
            rs.startAsync();
            for (int i = 0; i < 4; i++) {
                var event = new TestEvent(i);
                event.commit();
            }
            rs.awaitTermination();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return files;
    }

    @BeforeAll
    public static void createTestFiles() throws IOException {
        testFileFolder = Files.createTempDirectory("jfr-cli-test");
        testFiles = createTestFiles(testFileFolder);
    }

    /** Tests passing all files directly as arguments */
    @Test
    public void testFileList() {
        check(testFiles);
    }

    /** Tests passing the folder containing the files as argument */
    @Test
    public void testFolder() {
        check(List.of(testFileFolder));
    }

    @Test
    public void testReversedFileList() {
        check(testFiles.stream().sorted(Comparator.reverseOrder()).toList());
    }

    @Test
    public void testZip() throws IOException {
        var zip = testFileFolder.resolve("test.zip");
        // create zip of all files in Java
        try (var zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var file : testFiles) {
                zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
        assertAll(() -> check(List.of(zip)), () -> check(List.of(testFileFolder)));
    }

    @Test
    public void testDuplicates() throws IOException {
        var duplicates = testFiles.stream().map(Path::toAbsolutePath).collect(Collectors.toList());
        duplicates.addAll(testFiles);
        check(duplicates);
    }

    private void check(List<Path> paths) {
        var args = paths.stream().map(Path::toString).toList();
        var properArgs = new ArrayList<String>();
        properArgs.add(args.get(0));
        for (int i = 1; i < args.size(); i++) {
            properArgs.add("-i");
            properArgs.add(args.get(i));
        }
        assertAll(
                () -> checkSummaryResult(captureStdout("summary", properArgs)),
                () -> checkViewResult(captureStdout("view", combine("TestEvent", args))),
                () -> checkInflateResult(properArgs));
    }

    private List<String> combine(String val, List<String> args) {
        var combined = new ArrayList<String>();
        combined.add(val);
        combined.addAll(args);
        return combined;
    }

    private void checkSummaryResult(String result) {
        var ds = StartMessage.DEFAULT;
        String regexp =
                """
                 Format Version: $VERSION
                 Generator: $GENERATOR
                 Generator Version: $GENERATOR_VERSION
                 Generator Configuration: \\(default\\)
                 Compression: $COMPRESSION
                 Start: .*
                 End: .*
                 Duration: [0-9](\\.[0-9]*)?s
                 Events: 4

                 Event Type                                Count
                =================================================
                 TestEvent                                     4

                """
                        .replace("$VERSION", ds.version() + "")
                        .replace("$GENERATOR_VERSION", ds.generatorVersion())
                        .replace("$GENERATOR", ds.generatorName())
                        .replace("$COMPRESSION", ds.compression().toString())
                        .strip();
        assertThat(result.strip()).matches(regexp);
    }

    private void checkViewResult(String result) {
        String regexp =
                """

                                                             TestEvent

                Start Time Duration   Event Thread    Stack Trace                                        Number   \s
                ---------- ---------- --------------- -------------------------------------------------- ----------
                  $TIME_RE         0s main            -                                                           0
                  $TIME_RE         0s main            -                                                           1
                  $TIME_RE         0s main            -                                                           2
                  $TIME_RE         0s main            -                                                           3
                """
                        .replace("$TIME_RE", "[0-9]{1,2}:[0-9]{1,2}:[0-9]{1,2}")
                        .strip();
        assertThat(result.strip()).matches(regexp);
    }

    private void checkInflateResult(List<String> args) throws IOException {
        Path tmpJfrFile = Files.createTempFile("jfr-cli-test", ".jfr");
        args = new ArrayList<>(args);
        args.addAll(1, List.of(tmpJfrFile.toString()));
        args.add(0, "inflate");
        JFRCLI.execute(args.toArray(String[]::new));
        var events = RecordingFile.readAllEvents(tmpJfrFile);
        for (int i = 0; i < 4; i++) {
            assertThat(events.get(i).getStartTime().getEpochSecond()).isNotEqualTo(0);
            assertThat(events.get(i).getInt("number")).isEqualTo(i);
        }
    }

    private String captureStdout(String command, List<String> args) {
        var combined = new ArrayList<String>();
        combined.add(command);
        combined.addAll(args);
        return captureStdout(() -> JFRCLI.execute(combined.toArray(String[]::new)));
    }

    private String captureStdout(Runnable runnable) {
        var old = System.out;
        var out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        runnable.run();
        System.setOut(old);
        return out.toString();
    }
}
