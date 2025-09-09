package me.bechberger.jfr.cli.agent;

import static me.bechberger.util.MemoryUtil.parseMemory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.bechberger.condensed.Util;
import me.bechberger.jfr.cli.commands.WithRunningJVM;
import one.profiler.AsyncProfilerLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** <b>Tests assume the condensed-data.jar to be built first</b> */
public class AgentTest {

    /**
     * If true, then the CLI for {@link #runAgentViaCLI(String...)} will open the debugger port 5005
     */
    private static final boolean DEBUG_CLI = false;

    private static WithRunningJVM jvm;

    private static void deleteTestDir() throws IOException {
        var testDir = Path.of("test-dir");
        if (Files.exists(testDir)) {
            Files.walk(testDir)
                    .sorted(Comparator.reverseOrder()) // delete files before directories
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }
    }

    @BeforeEach
    public void setup() throws Exception {
        // delete test-dir folder if it exists
        deleteTestDir();
        jvm = new WithRunningJVM();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException {
        // delete test-dir folder if it exists
        deleteTestDir();
        if (jvm != null) {
            jvm.close();
        }
        System.out.println("Test completed, cleaned up test directory.");
        runAgent(AgentRunMode.JATTACH, "stop"); // just to be sure
    }

    public enum AgentRunMode {
        CLI_JAR,
        JATTACH,
    }

    public static Stream<Arguments> runModes() {
        return Arrays.stream(AgentRunMode.values()).map(Arguments::of);
    }

    @ParameterizedTest(name = "Run {index}: mode={0}")
    @MethodSource("runModes")
    public void testHelp(AgentRunMode runMode) throws IOException, InterruptedException {
        var output = runAgent(runMode, "help");
        System.out.println(output);
        assertThat(output).contains("Usage: ");
    }

    /**
     * Tests the basic run of the agent
     *
     * <p>
     *
     * <ul>
     *   <li>Starts the recording
     *   <li>Checks the status
     *   <li>Stops the recording
     *   <li>Checks the file size
     * </ul>
     */
    @ParameterizedTest(name = "Run {index}: mode={0}")
    @MethodSource("runModes")
    // set test label / name
    public void testBasicRun(AgentRunMode runMode) throws InterruptedException, IOException {
        assertTrue(jvm.isAlive());
        var output = runAgent(runMode, "start", "test-dir/recording.cjfr", "--verbose");
        assertTrue(jvm.isAlive());
        assertThat(output).startsWith("Condensed recording to ");
        System.out.println(output);

        // check status
        var status = runAgent(runMode, "status");
        var lines = status.split("\n");
        assertEquals(lines[0], "Recording running");
        System.out.println("Status: " + status);
        for (int i = 1; i < lines.length; i++) {
            assertThat(lines[i]).contains(": ");
        }
        Thread.sleep(1000);
        status = runAgent(runMode, "status");
        System.out.println(status);
        // check that the current-size-uncompressed property is memory and larger than 1000 bytes
        assertThat(status).contains("current-size-uncompressed: ");
        var bytes = parseMemory(status.split("current-size-uncompressed: ")[1].split("\n")[0]);
        assertThat(bytes).isGreaterThan(1000);

        output = runAgent(runMode, "stop");
        System.out.println(output);
        assertThat(Path.of("test-dir/recording.cjfr"))
                .exists()
                .satisfies(
                        (Consumer<Path>)
                                path -> {
                                    try {
                                        assertThat(Files.size(path)).isGreaterThan(1000);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
    }

    /**
     * Run the agent and rotate the file after 1 second
     *
     * <ul>
     *   <li>Starts the recording with a max duration of 1 second
     *   <li>Checks the status
     *   <li>Sets the max size to 1MB
     *   <li>Checks that the status reflects the change
     *   <li>Stops the recording
     *   <li>Checks that the files are created and that the second one is larger than 1000 bytes
     * </ul>
     */
    @Test
    public void testRotate() throws InterruptedException, IOException {
        var output =
                runAgent(
                        AgentRunMode.JATTACH,
                        "start test-dir/recording.cjfr --rotating --max-duration 1s");
        assertThat(output).startsWith("Condensed recording to ");
        System.out.println(output);

        Thread.sleep(1000);
        output = runAgent(AgentRunMode.JATTACH, "status");
        assertThat(output).contains("rotating");

        Thread.sleep(1000);
        // set max-size
        output = runAgent(AgentRunMode.JATTACH, "set-max-size 1m");
        // check that status contains the new max-size
        output = runAgent(AgentRunMode.JATTACH, "status");
        assertThat(output).contains("max-size: 1.00MB");

        runAgent(AgentRunMode.JATTACH, "stop");

        assertThat(Path.of("test-dir/recording_0.cjfr")).exists();
        assertThat(Path.of("test-dir/recording_1.cjfr")).exists();
        // check that the file is larger than 1000 bytes
        assertThat(Files.size(Path.of("test-dir/recording_1.cjfr"))).isGreaterThan(1000);
    }

    /**
     * Tests that the rotation overrides files
     *
     * <ul>
     *   <li>Starts the recording with a max duration of 1 second and max files of 3
     *   <li>Waits for 5 seconds to ensure multiple rotations
     *   <li>Stops the recording
     *   <li>Checks that only three files exist
     *   <li>Checks that both files are sensible
     *   <li>Checks that the third file is older than the first one
     * </ul>
     */
    @Test
    public void testRotateWithMaxFiles() throws InterruptedException, IOException {
        var output =
                runAgent(
                        AgentRunMode.JATTACH,
                        "start test-dir/recording.cjfr --rotating --max-duration 2s --max-files 3"
                                + " --duration 6s");
        assertThat(output).startsWith("Condensed recording to ").doesNotContain("Exception");
        System.out.println(output);

        Thread.sleep(7000);

        runAgent(AgentRunMode.JATTACH, "stop");

        var files = Files.list(Path.of("test-dir")).toList();
        assertThat(files).hasSize(3);
        // check that the files are named recording_0.cjfr, recording_1.cjfr, recording_2.cjfr
        assertThat(files)
                .map(Path::getFileName)
                .map(Path::toString)
                .containsOnly("recording_0.cjfr", "recording_1.cjfr", "recording_2.cjfr");
        // sort files by name
        for (var file : files) {
            assertThat(Files.size(file)).isGreaterThan(1000);
        }
        System.out.println("Files: " + files);

        // print creation and modification time of the files as a table
        for (var file : files) {
            var creationTime = Util.getCreationTimeOfFile(file).toMillis();
            var modificationTime = Files.getLastModifiedTime(file).toMillis();
            System.out.printf(
                    "File: %s, Creation time: %d, Modification time: %d%n",
                    file.getFileName(), creationTime, modificationTime);
        }

        var changeTimeFirstFile = Files.getLastModifiedTime(files.getFirst()).toMillis();
        var changeTimeLastFile = Files.getLastModifiedTime(files.getLast()).toMillis();
        var creationTimeFirstFile = Util.getCreationTimeOfFile(files.getFirst()).toMillis();
        var creationTimeLastFile = Util.getCreationTimeOfFile(files.getLast()).toMillis();
        // check that the first file is newer than the last file
        // this is because the first file is overwritten last
        assertThat(changeTimeFirstFile)
                .withFailMessage(
                        "The first file should be newer than the last file, as it was overwritten"
                                + " last")
                .isGreaterThan(changeTimeLastFile);
        // also check that the modification and creation time of the last file less than 2 seconds
        // apart
        assertThat(changeTimeLastFile)
                .withFailMessage(
                        "Creation and modification time of the last file should be very close")
                .isLessThan(
                        changeTimeFirstFile
                                + 2000); // allow 2 seconds difference (because the file is flushed
        // after stopping)
        // the same for the first file, as we're deleting files
        assertThat(changeTimeFirstFile)
                .withFailMessage(
                        "The modification time of the first file should be at least 500ms after the"
                                + " creation time, as it was overwritten multiple times:"
                                + " first-file-creation="
                                + creationTimeFirstFile
                                + ", first-file-modification="
                                + changeTimeFirstFile)
                .isGreaterThan(creationTimeFirstFile + 500);
    }

    /** Tests that the rotation uses new names when configured */
    @Test
    public void testRotateWithMaxFilesAndNewFiles() throws InterruptedException, IOException {
        var output =
                runAgent(
                        AgentRunMode.JATTACH,
                        "start test-dir/recording.cjfr --rotating --max-duration 300ms --max-files"
                                + " 2 --new-names");
        assertThat(output).startsWith("Condensed recording to ").doesNotContain("Exception");
        System.out.println(output);

        Thread.sleep(3000);

        runAgent(AgentRunMode.JATTACH, "stop");

        var files = Files.list(Path.of("test-dir")).toList();
        assertThat(files).hasSize(2);
        // check that the files are named recording_0.cjfr, recording_1.cjfr, recording_2.cjfr
        assertThat(files)
                .map(Path::getFileName)
                .map(Path::toString)
                .containsOnly("recording_2.cjfr", "recording_3.cjfr");
        // sort files by name
        for (var file : files) {
            assertThat(Files.size(file)).isGreaterThan(1000);
        }
    }

    @Test
    public void testSetInvalidMaxSize() throws IOException, InterruptedException {
        var output =
                runAgent(
                        AgentRunMode.JATTACH,
                        "start test-dir/recording.cjfr --rotating --max-duration 1s --max-files 2");
        assertThat(output).startsWith("Condensed recording to ");
        System.out.println(output);

        output = runAgent(AgentRunMode.JATTACH, "set-max-size 1b");
        assertThat(output).contains("Max size must be at least 1kB or 0 (no limit)");

        output = runAgent(AgentRunMode.JATTACH, "set-max-size -1m");
        assertThat(output)
                .contains("Missing required parameter"); // -1m is not parsed as a number, but as an
        // option

        output = runAgent(AgentRunMode.JATTACH, "set-max-size 512");
        assertThat(output).contains("Severe Error: Max size must be at least 1kB or 0 (no limit)");
    }

    @Test
    public void testInvalidOption() throws IOException, InterruptedException {
        var output =
                runAgent(AgentRunMode.CLI_JAR, "start test-dir/recording.cjfr --unknown-option");
        assertThat(output).contains("Unmatched argument at index 2");
    }

    String runAgent(AgentRunMode runMode, String... args) throws IOException, InterruptedException {
        return switch (runMode) {
            case CLI_JAR -> runAgentViaCLI(args);
            case JATTACH -> runAgentViaJattach(args);
        };
    }

    /**
     * Attaches an agent to the current JVM and records the output, uses a slightly faster jattach
     * based approach
     */
    String runAgentViaJattach(String... args) {
        var out = System.out;
        var newOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(newOut));
        try {
            var pid = ProcessHandle.current().pid();
            AsyncProfilerLoader.executeJattach(
                    pid + "",
                    "load",
                    "instrument",
                    "false",
                    "target/condensed-data.jar=" + String.join(" ", args));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.setOut(out);
        return newOut.toString();
    }

    /** Uses {@code java -jar target/condensed-data.jar attach PID args} to run the agent */
    String runAgentViaCLI(String... args) throws IOException, InterruptedException {
        var bas = new ByteArrayOutputStream();
        var processArgs = new ArrayList<String>();
        // get currently used JVM
        var javaHome = Path.of(System.getProperty("java.home"));
        var javaBin = javaHome.resolve("bin").resolve("java").toString();
        processArgs.add(javaBin);
        if (DEBUG_CLI) {
            System.out.println("Starting with debugger at port 5005");
            processArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
        }
        processArgs.addAll(List.of("-jar", "target/condensed-data.jar", "agent", jvm.pid() + ""));
        processArgs.addAll(Arrays.asList(args));
        var process = new ProcessBuilder(processArgs).start();
        process.getInputStream().transferTo(bas);
        process.getErrorStream().transferTo(bas);
        process.waitFor();
        return bas.toString();
    }
}
