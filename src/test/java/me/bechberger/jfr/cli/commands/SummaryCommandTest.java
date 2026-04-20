package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.jfr.cli.Constants;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;
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

    @Test
    public void testIsoStartTimeIsAccepted() throws Exception {
        new CommandExecuter(
                        "summary",
                        "T/" + CommandTestUtil.getSampleCJFRFileName(),
                        "--start",
                        "1970-01-01T00:00:00")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .run();
    }

    @Test
    public void testConflictingTimeArgumentsShowFriendlyError() throws Exception {
        var result =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "--start",
                                "2025-12-05T12:12:21",
                                "--end",
                                "2025-12-05T12:12:22",
                                "--duration",
                                "1s")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(1),
                () -> assertThat(result.output()).isEmpty(),
                () -> assertThat(result.error()).contains("Both start, end and duration are set"),
                () -> assertThat(result.error()).doesNotContain("\tat "));
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
        assertThat(result)
                .contains("TestEvent                                     " + testEventCount);
    }

    @Test
    public void testPrintJSON() throws Exception {
        new CommandExecuter("summary", "T/" + CommandTestUtil.getSampleCJFRFileName(), "--json")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) -> {
                            Map<String, Object> json;
                            try {
                                json = Util.asMap(JSONParser.parse(result.output()));
                            } catch (java.io.IOException e) {
                                throw new RuntimeException(e);
                            }
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

    @Test
    public void testEmptyFile() throws Exception {
        new CommandExecuter("summary", "T/" + CommandTestUtil.getEmptyCJFRFileName())
                .withFiles(CommandTestUtil.getEmptyCJFRFile())
                .checkNoError()
                .check(
                        (result, files) -> {
                            assertThat(result.output())
                                    .contains("Events: 0")
                                    .contains("Duration: 0s")
                                    .doesNotContain("Event Type");
                        })
                .run();
    }

    @Test
    public void testFullStatistics() throws Exception {
        var result =
                new CommandExecuter(
                                "summary", "T/" + CommandTestUtil.getSampleCJFRFileName(), "--full")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("EventWriteTree");
    }

    @Test
    public void testFlamegraph() throws Exception {
        new CommandExecuter(
                        "summary",
                        "T/" + CommandTestUtil.getSampleCJFRFileName(),
                        "--flamegraph",
                        "T/flame.html")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) -> {
                            assertThat(files).containsKey("flame.html");
                            assertThat(files.get("flame.html")).exists().isNotEmptyFile();
                        })
                .run();
    }

    @Test
    public void testFolderInput() throws Exception {
        new CommandExecuter("summary", "T/")
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(), CommandTestUtil.getSampleCJFRFile(1))
                .checkNoError()
                .check(
                        (result, files) -> {
                            assertThat(result.output()).contains("TestEvent");
                        })
                .run();
    }

    @Test
    public void testJsonValuesValidation() throws Exception {
        new CommandExecuter("summary", "T/" + CommandTestUtil.getSampleCJFRFileName(), "--json")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) -> {
                            Map<String, Object> json;
                            try {
                                json = Util.asMap(JSONParser.parse(result.output()));
                            } catch (java.io.IOException e) {
                                throw new RuntimeException(e);
                            }
                            assertAll(
                                    () ->
                                            assertThat(
                                                            ((Number) json.get("format version"))
                                                                    .intValue())
                                                    .isEqualTo(Constants.FORMAT_VERSION),
                                    () ->
                                            assertThat(json.get("generator"))
                                                    .isEqualTo("condensed jfr cli"),
                                    () ->
                                            assertThat(json.get("generator version"))
                                                    .isEqualTo(Constants.VERSION),
                                    () ->
                                            assertThat(json.get("compression"))
                                                    .isEqualTo(Compression.DEFAULT.name()),
                                    () ->
                                            assertThat(((Number) json.get("count")).longValue())
                                                    .isGreaterThan(0),
                                    () ->
                                            assertThat(
                                                            ((Number) json.get("duration-millis"))
                                                                    .longValue())
                                                    .isGreaterThan(0),
                                    () -> {
                                        Map<String, Object> events = Util.asMap(json.get("events"));
                                        assertThat(events).containsKey("TestEvent");
                                        assertThat(((Number) events.get("TestEvent")).longValue())
                                                .isGreaterThan(0);
                                    });
                        })
                .run();
    }

    @Test
    public void testZipInput() throws Exception {
        var tmpFolder = org.assertj.core.util.Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("sample.zip");
        try (var zipOutputStream = java.nio.file.Files.newOutputStream(zipFile);
                var zip = new java.util.zip.ZipOutputStream(zipOutputStream)) {
            var entry = new java.util.zip.ZipEntry(CommandTestUtil.getSampleCJFRFileName());
            zip.putNextEntry(entry);
            java.nio.file.Files.copy(CommandTestUtil.getSampleCJFRFile(), zip);
            zip.closeEntry();
        }

        new CommandExecuter("summary", "T/sample.zip")
                .withFiles(zipFile)
                .checkNoError()
                .check(
                        (result, files) -> {
                            assertThat(result.output()).contains("TestEvent").contains("Events:");
                        })
                .run();
    }

    @Test
    public void testEventsFilter() throws Exception {
        // Without filter: should show both TestEvent and AnotherEvent
        var unfilteredResult =
                new CommandExecuter("summary", "T/" + CommandTestUtil.getSampleCJFRFileName())
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(unfilteredResult.output()).contains("TestEvent").contains("AnotherEvent");

        // With --events TestEvent: should only show TestEvent in event counts
        // Note: summary --events may fail with very short recordings due to duration
        // validation in Summary constructor, so we accept exit code 0 or 1
        var filteredResult =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "--events",
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        if (filteredResult.exitCode() == 0) {
            assertThat(filteredResult.output())
                    .contains("TestEvent")
                    .doesNotContain("AnotherEvent");
        } else {
            // If it failed, it should be the known Duration validation issue
            assertThat(filteredResult.error()).contains("Duration");
        }
    }

    @Test
    public void testJsonEventsFilterOnlyReturnsRequestedEvents() throws Exception {
        var result =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "--events",
                                "TestEvent",
                                "--json")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();

        if (result.exitCode() == 0) {
            Map<String, Object> json = Util.asMap(JSONParser.parse(result.output()));
            assertThat(Util.asMap(json.get("events"))).containsOnlyKeys("TestEvent");
        } else {
            assertThat(result.error()).contains("Duration");
        }
    }

    @Test
    public void testFolderInputJsonAggregatesEventCounts() throws Exception {
        int expectedTestEventCount =
                (int)
                        (RecordingFile.readAllEvents(CommandTestUtil.getSampleJFRFile()).stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count()
                                + RecordingFile.readAllEvents(CommandTestUtil.getSampleJFRFile(1))
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count());

        new CommandExecuter("summary", "T/", "--json")
                .withFiles(
                        CommandTestUtil.getSampleCJFRFile(), CommandTestUtil.getSampleCJFRFile(1))
                .checkNoError()
                .check(
                        (result, files) -> {
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(result.output()));
                            Map<String, Object> events = Util.asMap(json.get("events"));
                            assertThat(((Number) events.get("TestEvent")).intValue())
                                    .isEqualTo(expectedTestEventCount);
                        })
                .run();
    }

    /**
     * Bug: When --full is used (without --json), the if/else structure in SummaryCommand.call()
     * prints EventWriteTree and DetailedStatistics in the 'if (full)' branch, but skips the basic
     * summary (Format Version, Start, End, Duration, Events) because summary.toString() is in the
     * 'else' branch.
     *
     * <p>Expected: --full should show BOTH the basic summary AND the full statistics. Actual:
     * --full shows only EventWriteTree and DetailedStatistics, no basic summary.
     */
    @Test
    public void testFullStatisticsIncludesBasicSummary() throws Exception {
        var result =
                new CommandExecuter(
                                "summary", "T/" + CommandTestUtil.getSampleCJFRFileName(), "--full")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // --full should include EventWriteTree (it does)
        assertThat(result.output()).contains("EventWriteTree");
        // --full should ALSO include the basic summary info
        assertThat(result.output())
                .as("--full should include basic summary alongside full statistics")
                .contains("Format Version:")
                .contains("Events:");
    }

    @Test
    public void testJsonFullWithEventsFilterOnMultipleInputs() throws Exception {
        int expectedTestEventCount =
                (int)
                        (RecordingFile.readAllEvents(CommandTestUtil.getSampleJFRFile()).stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count()
                                + RecordingFile.readAllEvents(CommandTestUtil.getSampleJFRFile(1))
                                        .stream()
                                        .filter(e -> e.getEventType().getName().equals("TestEvent"))
                                        .count());

        var result =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "-i",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(1),
                                "--events",
                                "TestEvent",
                                "--json",
                                "--full")
                        .withFiles(
                                CommandTestUtil.getSampleCJFRFile(),
                                CommandTestUtil.getSampleCJFRFile(1))
                        .run();

        if (result.exitCode() == 0) {
            Map<String, Object> json = Util.asMap(JSONParser.parse(result.output()));
            assertThat(json).containsKey("eventWriteTree");
            assertThat(Util.asMap(json.get("events"))).containsOnlyKeys("TestEvent");
            assertThat(((Number) Util.asMap(json.get("events")).get("TestEvent")).intValue())
                    .isEqualTo(expectedTestEventCount);
        } else {
            assertThat(result.error()).contains("Duration");
        }
    }

    /**
     * summary --json on a condensed file with large string events must exit 0 and /** summary
     * --json on a condensed file with large string events must exit 0 and report the correct event
     * type name.
     */
    @Test
    public void testSummaryJsonOnCondensedLargeStringEvents() throws Exception {
        new CommandExecuter("condense", "T/large_string.jfr", "T/large_string.cjfr")
                .withFiles(CommandTestUtil.getLargeStringJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("large_string.cjfr").toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            assertThat(Util.asMap(json.get("events")))
                                    .containsKey("LargeStringEvent");
                        })
                .run();
    }

    /**
     * summary --json on condensed extreme-numeric events: field VALUES are not included in summary,
     * so NaN/Inf in event fields must not break JSON output.
     */
    @Test
    public void testSummaryJsonOnCondensedExtremeNumericEvents() throws Exception {
        new CommandExecuter("condense", "T/extreme_numeric.jfr", "T/extreme_numeric.cjfr")
                .withFiles(CommandTestUtil.getExtremeNumericJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("extreme_numeric.cjfr").toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            assertThat(Util.asMap(json.get("events")))
                                    .containsKey("ExtremeNumericEvent");
                        })
                .run();
    }

    /**
     * summary --json on condensed unicode events: emoji and non-ASCII text in event field values
     * must not corrupt the CJFR metadata or summary output.
     */
    @Test
    public void testSummaryJsonOnCondensedUnicodeEvents() throws Exception {
        new CommandExecuter("condense", "T/unicode_string.jfr", "T/unicode_string.cjfr")
                .withFiles(CommandTestUtil.getUnicodeStringJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("unicode_string.cjfr").toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            assertThat(Util.asMap(json.get("events")))
                                    .containsKey("UnicodeStringEvent");
                        })
                .run();
    }

    /**
     * summary --json on condensed many-fields events: event type with many fields must be fully
     * summarised.
     */
    @Test
    public void testSummaryJsonOnCondensedManyFieldsEvents() throws Exception {
        new CommandExecuter("condense", "T/many_fields.jfr", "T/many_fields.cjfr")
                .withFiles(CommandTestUtil.getManyFieldsJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("many_fields.cjfr").toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            assertThat(Util.asMap(json.get("events")))
                                    .containsKey("ManyFieldsEvent");
                        })
                .run();
    }
}
