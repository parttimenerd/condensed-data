package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Test;

@InflaterRelated
public class BenchmarkCommandTest {

    @Test
    public void testPrintHelp() throws Exception {
        var result = new CommandExecuter("benchmark", "--help").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () ->
                        assertThat(result.output())
                                .containsIgnoringNewLines("Usage: cjfr benchmark"),
                () -> assertThat(result.error()).isEmpty());
    }

    @Test
    public void testVersion() throws Exception {
        var result = new CommandExecuter("benchmark", "--version").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () ->
                        assertThat(result.output().strip())
                                .isEqualTo(MainCommandTest.VERSION),
                () -> assertThat(result.error()).isEmpty());
    }
}
