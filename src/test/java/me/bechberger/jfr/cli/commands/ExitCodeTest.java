package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verify that CLI parameter errors return exit code 2 (consistent with picocli convention), while
 * runtime errors return exit code 1.
 */
public class ExitCodeTest {

    private static boolean isInflaterless() {
        return "true".equals(System.getProperty("cjfr.test.inflaterless"));
    }

    private CommandExecuter.CommandResult run(String... args) throws Exception {
        return new CommandExecuter(args).run();
    }

    // --- Unknown options should return exit code 2 ---

    static Stream<String> unknownOptionArgs() {
        var args =
                Stream.of(
                        "--definitely-unknown",
                        "condense --unknown-opt",
                        "inflate --unknown-opt",
                        "summary --unknown-opt",
                        "view --unknown-opt");
        return isInflaterless() ? args.filter(s -> !s.startsWith("inflate")) : args;
    }

    @ParameterizedTest
    @MethodSource("unknownOptionArgs")
    public void testUnknownOptionExitsWithCode2(String argString) throws Exception {
        var result = run(argString.split(" "));
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("Unknown option");
    }

    // --- Missing required parameters should return exit code 2 ---

    static Stream<String> missingRequiredParamArgs() {
        var args = Stream.of("condense", "inflate", "summary");
        return isInflaterless() ? args.filter(s -> !s.equals("inflate")) : args;
    }

    @ParameterizedTest
    @MethodSource("missingRequiredParamArgs")
    public void testMissingRequiredParamExitsWithCode2(String argString) throws Exception {
        var result = run(argString.split(" "));
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("Missing required parameter");
    }

    // --- Invalid option values should return exit code 2 ---

    @Test
    public void testInvalidCondenserConfigExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "condense",
                                "T/" + CommandTestUtil.getSampleJFRFileName(),
                                "--condenser-config",
                                "doesnotexist")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("Unknown configuration");
    }

    @Test
    public void testInvalidWidthExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--width",
                                "5")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--width must be between 10 and 1000");
    }

    @Test
    public void testInvalidCellHeightExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--cell-height",
                                "0")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--cell-height must be >= 1");
    }

    @Test
    public void testInvalidLimitExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--limit",
                                "-2")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--limit must be >= 0");
    }

    @InflaterRelated
    @Test
    public void testGcPercentileOutOfRangeExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "inflate",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "T/out.jfr",
                                "--gc-percentile",
                                "101")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--gc-percentile must be between 0 and 100");
    }

    @InflaterRelated
    @Test
    public void testNegativeGcPercentileExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "inflate",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "T/out.jfr",
                                "--gc-percentile",
                                "-1")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--gc-percentile must be between 0 and 100");
    }

    @Test
    public void testConflictingOptionsExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "--short",
                                "--full")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--short and --full are mutually exclusive");
    }

    // --- Time filter validation should return exit code 2 ---

    static Stream<String> durationCommands() {
        var commands = Stream.of("view", "summary", "inflate");
        return isInflaterless() ? commands.filter(c -> !c.equals("inflate")) : commands;
    }

    @ParameterizedTest
    @MethodSource("durationCommands")
    public void testZeroDurationExitsWithCode2(String command) throws Exception {
        String cjfr = "T/" + CommandTestUtil.getSampleCJFRFileName();
        var builder =
                command.equals("view")
                        ? new CommandExecuter(command, cjfr, "TestEvent", "--duration", "0s")
                        : command.equals("inflate")
                                ? new CommandExecuter(
                                        command, cjfr, "T/out.jfr", "--duration", "0s")
                                : new CommandExecuter(command, cjfr, "--duration", "0s");
        var result = builder.withFiles(CommandTestUtil.getSampleCJFRFile()).run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--duration must be positive");
    }

    @ParameterizedTest
    @MethodSource("durationCommands")
    public void testNegativeDurationExitsWithCode2(String command) throws Exception {
        String cjfr = "T/" + CommandTestUtil.getSampleCJFRFileName();
        var builder =
                command.equals("view")
                        ? new CommandExecuter(command, cjfr, "TestEvent", "--duration", "-1s")
                        : command.equals("inflate")
                                ? new CommandExecuter(
                                        command, cjfr, "T/out.jfr", "--duration", "-1s")
                                : new CommandExecuter(command, cjfr, "--duration", "-1s");
        var result = builder.withFiles(CommandTestUtil.getSampleCJFRFile()).run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--duration must be positive");
    }

    @Test
    public void testStartAfterEndExitsWithCode2() throws Exception {
        var result =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "--start",
                                "2025-12-05T12:12:23",
                                "--end",
                                "2025-12-05T12:12:21")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("Start time");
        assertThat(result.error()).contains("after end time");
    }

    // --- Help should return exit code 0 ---

    static Stream<String> helpArgs() {
        var args =
                Stream.of(
                        "--help",
                        "condense --help",
                        "inflate --help",
                        "summary --help",
                        "view --help");
        return isInflaterless() ? args.filter(s -> !s.startsWith("inflate")) : args;
    }

    @ParameterizedTest
    @MethodSource("helpArgs")
    public void testHelpExitsWithCode0(String argString) throws Exception {
        var result = run(argString.split(" "));
        assertThat(result.exitCode()).isEqualTo(0);
    }

    /**
     * Bug 163: Viewing event types with many columns (e.g., jdk.ActiveRecording) at default width
     * crashed with "count is negative" because column headers exceeded computed widths.
     */
    @Test
    public void testViewManyColumnEventDoesNotCrash() throws Exception {
        var cjfr = "T/" + CommandTestUtil.getSampleCJFRFileName();
        var result =
                new CommandExecuter("view", cjfr, "jdk.ActiveRecording", "--limit", "1")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(0);
    }
}
