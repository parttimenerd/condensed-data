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
                () -> assertThat(result.output()).containsIgnoringNewLines("Usage: cjfr benchmark"),
                () -> assertThat(result.error()).isEmpty());
    }

    @Test
    public void testVersion() throws Exception {
        var result = new CommandExecuter("benchmark", "--version").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(0),
                () -> assertThat(result.output().strip()).isEqualTo(MainCommandTest.VERSION),
                () -> assertThat(result.error()).isEmpty());
    }

    @Test
    public void testCsvWithNoMatchingFilesProducesHeaderOnly() throws Exception {
        var result =
                new CommandExecuter("benchmark", "--csv", "--regexp", "does-not-match")
                        .checkNoError()
                        .run();

        assertAll(
                () -> assertThat(result.output().strip()).startsWith("JFR file,"),
                () -> assertThat(result.output().strip()).doesNotContain("sample.jfr"));
    }

    @Test
    public void testInvalidConfigurationFailsBeforeBenchmarkRuns() throws Exception {
        var result = new CommandExecuter("benchmark", "--configuration", "does-not-exist").run();

        assertAll(
                () -> assertThat(result.exitCode()).isNotEqualTo(0),
                () -> assertThat(result.error()).contains("Unknown generatorConfiguration"));
    }

    @Test
    public void testOnlyPerHourCsvReducesHeaderColumns() throws Exception {
        var defaultCsv =
                new CommandExecuter("benchmark", "--csv", "--regexp", "does-not-match")
                        .checkNoError()
                        .run();
        var onlyPerHourCsv =
                new CommandExecuter(
                                "benchmark",
                                "--csv",
                                "--only-per-hour",
                                "--regexp",
                                "does-not-match")
                        .checkNoError()
                        .run();

        int defaultColumns =
                defaultCsv.output().lines().findFirst().orElseThrow().split(",").length;
        int onlyPerHourColumns =
                onlyPerHourCsv.output().lines().findFirst().orElseThrow().split(",").length;

        assertThat(onlyPerHourColumns).isLessThan(defaultColumns);
    }

    @Test
    public void testInvalidCompressionFailsFast() throws Exception {
        var result = new CommandExecuter("benchmark", "--compression", "NOT_A_COMPRESSION").run();

        assertAll(
                () -> assertThat(result.exitCode()).isNotEqualTo(0),
                () -> assertThat(result.error()).contains("Invalid value"));
    }
}
