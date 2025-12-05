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
}
