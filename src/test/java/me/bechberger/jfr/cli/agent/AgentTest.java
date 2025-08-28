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
    public void tearDown() throws IOException {
        // delete test-dir folder if it exists
        deleteTestDir();
        if (jvm != null) {
            jvm.close();
        }
        System.out.println("Test completed, cleaned up test directory.");
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