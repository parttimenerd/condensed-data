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
}
