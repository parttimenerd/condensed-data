package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Test;

public class ViewCommandTest {
    @Test
    public void testPrintHelpWithNoArguments() throws Exception {
        var result = new CommandExecuter("view").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () ->
                        assertThat(result.error())
                                .containsIgnoringNewLines("Usage: cjfr")
                                .contains("Missing required parameter"),
                () -> assertThat(result.output()).isEmpty());
    }

    @Test
    public void testPrintHelpWithHelpArgument() throws Exception {
        var result = new CommandExecuter("view", "--help").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.output()).containsIgnoringNewLines("Usage: cjfr"),
                () -> assertThat(result.error()).isEmpty());
    }

    @Test
    public void testBasicView() throws Exception {
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output())
                .contains("TestEvent")
                .contains(
                        """
                        Start Time Duration   Event Thread    Stack Trace                                       Number     Memory     String                                          \s
                        ---------- ---------- --------------- ------------------------------------------------- ---------- ---------- -------------------------------------------------
                        """);
    }

    @Test
    public void testTypoInEventName() throws Exception {
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TstEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(1),
                () -> assertThat(result.error()).contains("Did you mean one of these events:"),
                () -> assertThat(result.error()).contains("TestEvent"));
    }

    @Test
    public void testEmptyFile() throws Exception {
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getEmptyCJFRFileName(), "TestEvent")
                        .withFiles(CommandTestUtil.getEmptyCJFRFile())
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(1),
                () -> assertThat(result.error()).contains("No event of type TestEvent found."),
                () -> assertThat(result.output()).isEmpty());
    }

    @Test
    public void testLimitOption() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--limit",
                                "1")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        var unlimited =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // Limited output should have fewer lines than unlimited
        assertThat(result.output().strip().split("\n").length)
                .isLessThan(unlimited.output().strip().split("\n").length);
    }

    @Test
    public void testWidthOption() throws Exception {
        var defaultResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        var narrowResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--width",
                                "120")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // Narrower width should produce shorter lines
        int defaultMaxLen =
                defaultResult.output().lines().mapToInt(String::length).max().orElse(0);
        int narrowMaxLen =
                narrowResult.output().lines().mapToInt(String::length).max().orElse(0);
        assertThat(narrowMaxLen).isLessThan(defaultMaxLen);
    }

    @Test
    public void testViewAnotherEvent() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "AnotherEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("AnotherEvent");
    }

    @Test
    public void testMultipleInputFiles() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "-i",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(1))
                        .withFiles(
                                CommandTestUtil.getSampleCJFRFile(),
                                CommandTestUtil.getSampleCJFRFile(1))
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testTruncateBeginning() throws Exception {
        var defaultResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        var beginResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--truncate",
                                "begin")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // Both should produce output with the same number of lines
        assertThat(beginResult.output().strip().split("\n").length)
                .isEqualTo(defaultResult.output().strip().split("\n").length);
    }

    @Test
    public void testCellHeight() throws Exception {
        var defaultResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        var tallResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--cell-height",
                                "2")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // Taller cells should produce more output lines
        assertThat(tallResult.output().strip().split("\n").length)
                .isGreaterThan(defaultResult.output().strip().split("\n").length);
    }

    @Test
    public void testFolderInput() throws Exception {
        var result =
                new CommandExecuter("view", "T/", "TestEvent")
                        .withFiles(
                                CommandTestUtil.getSampleCJFRFile(),
                                CommandTestUtil.getSampleCJFRFile(1))
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testEventsFilter() throws Exception {
        // With --events TestEvent, viewing AnotherEvent should find nothing
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "AnotherEvent",
                                "--events",
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(1),
                () -> assertThat(result.error()).contains("No event of type AnotherEvent found."));
    }
}
