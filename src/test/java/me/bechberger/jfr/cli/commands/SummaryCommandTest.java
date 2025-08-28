package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.jfr.cli.Constants;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SummaryCommandTest {
    @Test
    public void testPrintHelpWithNoArguments() throws Exception {
        var result = new CommandExecuter("summary").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () ->
                        assertThat(result.error())
                                .containsIgnoringNewLines("Usage: cjfr")
                                .contains("Missing required parameter"),
                () -> assertThat(result.output()).isEmpty());
    }

    private static String[] addShort(boolean shortSummary, String... args) {
        if (shortSummary) {
            String[] newArgs = new String[args.length + 1];
            System.arraycopy(args, 0, newArgs, 0, args.length);
            newArgs[args.length] = "-s";
            return newArgs;
        }
        return args;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSingleFile(boolean shortSummary) throws Exception {
        new CommandExecuter(
                        addShort(
                                shortSummary,
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName()))
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) ->
                                checkSummaryResult(
                                        shortSummary,
                                        result.output(),
                                        List.of(CommandTestUtil.getSampleJFRFile())))
                .run();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultipleFiles(boolean shortSummary) throws Exception {
        new CommandExecuter(
                        addShort(
                                shortSummary,
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "-i",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(1),
                                "-i",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(2)))
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(),
                        CommandTestUtil.getSampleCJFRFile(1),
                        CommandTestUtil.getSampleCJFRFile(2))
                .checkNoError()
                .check(
                        (result, files) ->
                                checkSummaryResult(
                                        shortSummary,
                                        result.output(),
                                        List.of(
                                                CommandTestUtil.getSampleJFRFile(),
                                                CommandTestUtil.getSampleJFRFile(1),
                                                CommandTestUtil.getSampleJFRFile(2))))
                .run();
    }

    private void checkSummaryResult(boolean shortSummary, String result, List<Path> jfrFiles) {
        int testEventCount =
                jfrFiles.stream()
                        .mapToInt(
                                f -> {
                                    try {
                                        return (int)
                                                RecordingFile.readAllEvents(f).stream()
                                                        .filter(
                                                                e ->
                                                                        e.getEventType()
                                                                                .getName()
                                                                                .equals(
                                                                                        "TestEvent"))
                                                        .count();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .sum();
        if (shortSummary) {
            checkShortSummaryResult(result, testEventCount);
        } else {
            checkFullSummaryResult(result, testEventCount);
        }
    }

    private void checkShortSummaryResult(String result, int testEventCount) {
        String regexp =
                """
                 Format Version: $VERSION
                 Generator: $GENERATOR
                 Generator Version: $GENERATOR_VERSION
                 Generator Configuration: default
                 Compression: $COMPRESSION
                 Start: .*
                 End: .*
                 Duration: [0-9]+(\\.[0-9]*)?s
                 Events: .*
                """
                        .replace("$VERSION", Constants.FORMAT_VERSION + "")
                        .replace("$GENERATOR_VERSION", Constants.VERSION)
                        .replace("$GENERATOR", "condensed jfr cli")
                        .replace("$COMPRESSION", Compression.DEFAULT.name())
                        .strip();
        assertThat(result.strip().split("\n\n")[0]).matches(regexp);
    }

    private void checkFullSummaryResult(String result, int testEventCount) {
        String regexp =
                 """
                  Format Version: $VERSION
                  Generator: $GENERATOR
                  Generator Version: $GENERATOR_VERSION
                  Generator Configuration: default
                  Compression: $COMPRESSION
                  Start: .*
                  End: .*
                  Duration: [0-9]+(\\.[0-9]*)?s
                  Events: .*

                  Event Type                                Count
                 """
                        .replace("$VERSION", Constants.FORMAT_VERSION + "")
                        .replace("$GENERATOR_VERSION", Constants.VERSION)
                        .replace("$GENERATOR", "condensed jfr cli")
                        .replace("$COMPRESSION", Compression.DEFAULT.name())
                        .strip();
        assertThat(result.strip().split("====")[0].strip()).matches(regexp);
        assertThat(result).contains("TestEvent                                     " + testEventCount);
    }

    @Test
    public void testPrintJSON() throws Exception {
        new CommandExecuter("summary", "T/" + CommandTestUtil.getSampleCJFRFileName(), "--json")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) -> {
                            var json = new JSONObject(result.output());
                            var keys = json.keySet();
                            assertAll(
                                    () -> assertThat(keys).contains("format version"),
                                    () -> assertThat(keys).contains("generator"),
                                    () -> assertThat(keys).contains("generator version"),
                                    () -> assertThat(keys).contains("generator configuration"),
                                    () -> assertThat(keys).contains("compression"),
                                    () -> assertThat(keys).contains("start"),
                                    () -> assertThat(keys).contains("start-epoch"),
                                    () -> assertThat(keys).contains("end"),
                                    () -> assertThat(keys).contains("end-epoch"),
                                    () -> assertThat(keys).contains("duration"),
                                    () -> assertThat(keys).contains("events"));
                        })
                .run();
    }
}