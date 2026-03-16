package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.stream.Stream;
import me.bechberger.jfr.cli.JFRCLI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests that the main {@code cjfr} command works (has help and everything, exits as expected) */
public class MainCommandTest {

    /** Reminder to look at the tests whenever a version is changed */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /** Tests main command with no arguments */
    @Test
    public void testNoArguments() throws Exception {
        var result = new CommandExecuter().run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.output()).containsIgnoringNewLines("Usage: cjfr"),
                () -> assertThat(result.error()).isEmpty());
    }

    /** Test {@code cjfr --help} */
    @Test
    public void testHelp() throws Exception {
        var result = new CommandExecuter("--help").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () ->
                        assertThat(result.output())
                                .containsIgnoringNewLines("Usage: cjfr")
                                .containsIgnoringNewLines(" condense  "),
                () -> assertThat(result.error()).isEmpty());
    }

    @Test
    public void testVersion() throws Exception {
        var result = new CommandExecuter("--version").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.output().strip()).isEqualTo(VERSION),
                () -> assertThat(result.error()).isEmpty());
    }

    private static Stream<Arguments> subCommands() {
        return JFRCLI.subCommandNames().stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("subCommands")
    public void testSubCommandHelp(String subCommand) throws Exception {
        var result = new CommandExecuter(subCommand, "--help").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () ->
                        assertThat(result.output())
                                .containsIgnoringNewLines("Usage: cjfr " + subCommand),
                () -> assertThat(result.error()).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("subCommands")
    public void testSubCommandsHaveVersion(String subCommand) throws Exception {
        var result = new CommandExecuter(subCommand, "--version").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.output().strip()).isEqualTo(VERSION),
                () -> assertThat(result.error()).isEmpty());
    }
}
