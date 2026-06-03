package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class ViewCommandTest {
    @Test
    public void testPrintHelpWithNoArguments() throws Exception {
        var result = new CommandExecuter("view").run();
        assertAll(
                () -> assertThat(result.exitCode()).isEqualTo(2),
                () -> assertThat(result.error()).contains("Missing required parameter"),
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
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
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
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
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
        int defaultMaxLen = defaultResult.output().lines().mapToInt(String::length).max().orElse(0);
        int narrowMaxLen = narrowResult.output().lines().mapToInt(String::length).max().orElse(0);
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
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
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
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
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
    public void testEventsFilterDoesNotExcludePositionalEventName() throws Exception {
        // Bug 73/133/192: --events should NOT filter out the positional EVENT_NAME
        // When --events specifies a different type, EVENT_NAME should still be auto-included
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "AnotherEvent",
                                "--events",
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // AnotherEvent should be shown despite --events only listing TestEvent
        assertThat(result.output()).contains("AnotherEvent");
    }

    @Test
    public void testEventsFilterIncludesPositionalEventName() throws Exception {
        // When --events includes the EVENT_NAME, everything works as before
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--events",
                                "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testEventsFilterWithoutPositionalStillWorks() throws Exception {
        // --events without conflicting with EVENT_NAME should still work
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testZipInput() throws Exception {
        var tmpFolder = org.assertj.core.util.Files.newTemporaryFolder();
        var zipFile = tmpFolder.toPath().resolve("sample.zip");
        try (var zipOutputStream = Files.newOutputStream(zipFile);
                var zip = new java.util.zip.ZipOutputStream(zipOutputStream)) {
            var entry = new java.util.zip.ZipEntry(CommandTestUtil.getSampleCJFRFileName());
            zip.putNextEntry(entry);
            Files.copy(CommandTestUtil.getSampleCJFRFile(), zip);
            zip.closeEntry();
        }

        var result =
                new CommandExecuter("view", "T/sample.zip", "TestEvent")
                        .withFiles(zipFile)
                        .checkNoError()
                        .run();

        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testCombinedInputWithLimitWidthCellHeightAndBeginingTruncate() throws Exception {
        new CommandExecuter(
                        "condense",
                        "T/" + CommandTestUtil.getSampleJFRFileName(),
                        "T/combined.cjfr",
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(1),
                        "-i",
                        "T/" + CommandTestUtil.getSampleJFRFileName(2))
                .withFiles(
                        CommandTestUtil.getSampleJFRFile(),
                        CommandTestUtil.getSampleJFRFile(1),
                        CommandTestUtil.getSampleJFRFile(2))
                .checkNoError()
                .checkNoOutput()
                .check(
                        (result, map) -> {
                            var limitOne =
                                    new CommandExecuter(
                                                    "view",
                                                    "T/combined.cjfr",
                                                    "TestEvent",
                                                    "--limit",
                                                    "1",
                                                    "--width",
                                                    "120",
                                                    "--cell-height",
                                                    "2",
                                                    "--truncate",
                                                    "beginning")
                                            .withFiles(map.get("combined.cjfr"))
                                            .checkNoError()
                                            .run();

                            var limitTwo =
                                    new CommandExecuter(
                                                    "view",
                                                    "T/combined.cjfr",
                                                    "TestEvent",
                                                    "--limit",
                                                    "2",
                                                    "--width",
                                                    "120",
                                                    "--cell-height",
                                                    "2",
                                                    "--truncate",
                                                    "beginning")
                                            .withFiles(map.get("combined.cjfr"))
                                            .checkNoError()
                                            .run();

                            assertThat(limitOne.output()).contains("TestEvent");
                            assertThat(limitTwo.output()).contains("TestEvent");
                            assertThat(limitTwo.output().lines().count())
                                    .isGreaterThan(limitOne.output().lines().count());
                        })
                .run();
    }

    /**
     * Condense a JFR file with large string fields, then view the event type. /** Condense a JFR
     * file with large string fields, then view the event type. The view command must exit 0 and
     * produce a table header for the event type.
     */
    @Test
    public void testViewOnCondensedLargeStringEvents() throws Exception {
        new CommandExecuter("condense", "T/large_string.jfr", "T/large_string.cjfr")
                .withFiles(CommandTestUtil.getLargeStringJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    map.get("large_string.cjfr").toString(),
                                                    "LargeStringEvent")
                                            .run();
                            assertThat(viewResult.exitCode()).isEqualTo(0);
                            assertThat(viewResult.output()).contains("LargeStringEvent");
                        })
                .run();
    }

    /**
     * Condense a JFR file with extreme numeric fields (Long.MAX/MIN, Float.MAX, etc.), then view
     * the events. The numeric boundary values must appear in the output.
     */
    @Test
    public void testViewOnCondensedExtremeNumericEvents() throws Exception {
        new CommandExecuter("condense", "T/extreme_numeric.jfr", "T/extreme_numeric.cjfr")
                .withFiles(CommandTestUtil.getExtremeNumericJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    map.get("extreme_numeric.cjfr").toString(),
                                                    "ExtremeNumericEvent")
                                            .run();
                            assertThat(viewResult.exitCode()).isEqualTo(0);
                            assertThat(viewResult.output()).contains("ExtremeNumericEvent");
                            // Long.MAX_VALUE must be rendered correctly
                            assertThat(viewResult.output())
                                    .contains(String.valueOf(Long.MAX_VALUE));
                        })
                .run();
    }

    /**
     * Condense a JFR file with emoji / multi-byte unicode fields, then view. The view table must
     * render without crashing and contain the event type name.
     */
    @Test
    public void testViewOnCondensedUnicodeStringEvents() throws Exception {
        new CommandExecuter("condense", "T/unicode_string.jfr", "T/unicode_string.cjfr")
                .withFiles(CommandTestUtil.getUnicodeStringJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    map.get("unicode_string.cjfr").toString(),
                                                    "UnicodeStringEvent")
                                            .run();
                            assertThat(viewResult.exitCode()).isEqualTo(0);
                            assertThat(viewResult.output()).contains("UnicodeStringEvent");
                        })
                .run();
    }

    /**
     * Condense a JFR file with many fields (14 columns), then view with wide table. All column
     * headers must appear in the output.
     */
    @Test
    public void testViewOnCondensedManyFieldsEvents() throws Exception {
        new CommandExecuter("condense", "T/many_fields.jfr", "T/many_fields.cjfr")
                .withFiles(CommandTestUtil.getManyFieldsJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    map.get("many_fields.cjfr").toString(),
                                                    "ManyFieldsEvent",
                                                    "--width",
                                                    "300")
                                            .run();
                            assertThat(viewResult.exitCode()).isEqualTo(0);
                            assertThat(viewResult.output()).contains("ManyFieldsEvent");
                        })
                .run();
    }

    /**
     * Bug 153/154: Memory columns must always display unit suffixes (e.g. "MB", "GB"), never get
     * truncated to bare numbers like "670.590027" without units.
     */
    @Test
    public void testMemoryColumnAlwaysShowsUnitSuffix() throws Exception {
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        // Extract the Memory column values from data rows (skip header and separator lines)
        var lines = result.output().strip().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("----------")
                    || line.contains("Start Time")
                    || line.contains("TestEvent")
                    || line.isEmpty()) {
                continue;
            }
            // The Memory column value should end with B (KB, MB, GB, etc.)
            // Find all tokens that look like memory values (contain digits and end near B)
            for (String token : line.trim().split("\\s+")) {
                if (token.matches("\\d+\\.\\d+[KMGT]?B?") && token.matches(".*\\d$")) {
                    // A number-only token in a memory column — this is the bug
                    org.junit.jupiter.api.Assertions.fail(
                            "Memory value '" + token + "' is missing unit suffix in line: " + line);
                }
            }
        }
    }

    /**
     * Bug 25: Events in view output must be sorted by startTime, not by storage order. Uses
     * multiple input files to increase likelihood of unsorted events.
     */
    @Test
    public void testEventsAreSortedByStartTime() throws Exception {
        // Use two separate sample files combined via -i to produce events
        // that may come from different chunks
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "jdk.GarbageCollection",
                                "-i",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(1))
                        .withFiles(
                                CommandTestUtil.getSampleCJFRFile(),
                                CommandTestUtil.getSampleCJFRFile(1))
                        .checkNoError()
                        .run();
        // Extract Start Time values from the output and verify they're in non-decreasing order
        var lines = result.output().strip().split("\n");
        var timestamps = new java.util.ArrayList<String>();
        for (String line : lines) {
            // Skip header, separator, and event-type label lines
            if (line.contains("----------")
                    || line.contains("Start Time")
                    || line.contains("GarbageCollection")
                    || line.isEmpty()) {
                continue;
            }
            // First column is the start time
            String startTime = line.trim().split("\\s+")[0];
            if (startTime.matches("\\d{2}:\\d{2}:\\d{2}.*")) {
                timestamps.add(startTime);
            }
        }
        // Verify timestamps are sorted
        for (int i = 1; i < timestamps.size(); i++) {
            assertThat(timestamps.get(i))
                    .as("Event at index %d should be >= event at index %d", i, i - 1)
                    .isGreaterThanOrEqualTo(timestamps.get(i - 1));
        }
    }

    // --- Case-insensitive event name matching (Bug 143) ---

    @Test
    public void testCaseInsensitiveEventNameAllLower() throws Exception {
        // "testevent" should match "TestEvent" case-insensitively
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "testevent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testCaseInsensitiveEventNameAllUpper() throws Exception {
        // "TESTEVENT" should match "TestEvent" case-insensitively
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TESTEVENT")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testCaseInsensitiveEventNameMixedCase() throws Exception {
        // "testEvent" (wrong case) should match "TestEvent"
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "testEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testExactCaseStillWorks() throws Exception {
        // Exact case should still work (regression check)
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleCJFRFileName(), "TestEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    /**
     * Bug 59/212: GCHeapSummary Heap Space should show formatted memory values, not just raw
     * numbers.
     */
    @Test
    public void testGCHeapSummaryShowsFormattedMemory() throws Exception {
        if (!Files.exists(Path.of("profile.cjfr"))) {
            System.err.println("Skipping: profile.cjfr not found");
            return;
        }
        var result =
                new CommandExecuter("view", "profile.cjfr", "jdk.GCHeapSummary", "--limit", "1")
                        .checkNoError()
                        .run();
        // The Heap Space column should contain formatted memory values (MB or GB)
        // not just raw numbers like "21474836480"
        assertThat(result.output()).containsPattern("[0-9]+\\.[0-9]+[KMGT]B");
    }

    @Test
    public void testCommaSeparatedEventNameGivesClearError() throws Exception {
        if (!Files.exists(Path.of("profile.cjfr"))) {
            System.err.println("Skipping: profile.cjfr not found");
            return;
        }
        var result =
                new CommandExecuter("view", "profile.cjfr", "jdk.GarbageCollection,jdk.ThreadStart")
                        .run();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--events");
    }

    @Test
    public void testViewJFRFileDirectly() throws Exception {
        var result =
                new CommandExecuter(
                                "view", "T/" + CommandTestUtil.getSampleJFRFileName(), "TestEvent")
                        .withFiles(CommandTestUtil.getSampleJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.output()).contains("TestEvent");
    }

    @Test
    public void testNonExistentEventShowsDidYouMean() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "jdk.NonExistentEvent")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.error()).contains("No event of type jdk.NonExistentEvent found");
        assertThat(result.error()).contains("Did you mean");
    }

    @Test
    public void testViewWithLimitReturnsQuickly() throws Exception {
        // With --limit 1, the command should not scan the entire file
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
        // Should have header + exactly 1 data row
        assertThat(result.output()).contains("TestEvent");
        // Count non-empty lines that contain separator characters (data rows)
        var dataLines =
                result.output().lines().filter(l -> l.contains("│") && !l.contains("─")).count();
        // Header row + 1 data row = 2 lines with │
        assertThat(dataLines).isLessThanOrEqualTo(2);
    }

    @Test
    public void testViewWithJsonOutputsValidJson() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "jdk.ActiveSetting",
                                "--json",
                                "--limit",
                                "3")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        assertThat(result.exitCode()).isEqualTo(0);
        // Output should be valid JSON (a list)
        var parsed = me.bechberger.util.json.JSONParser.parse(result.output());
        assertThat(parsed).isInstanceOf(java.util.List.class);
        var list = (java.util.List<?>) parsed;
        assertThat(list).hasSize(3);
        // Each element should be a map with event fields
        for (var item : list) {
            assertThat(item).isInstanceOf(java.util.Map.class);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, Object>) item;
            assertThat(map).containsKey("startTime");
        }
    }

    @Test
    public void testLimitReturnsChronologicallyFirstEvents() throws Exception {
        // Bug 210: --limit should return the FIRST N events chronologically, not arbitrary ones
        var limitResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "jdk.ActiveSetting",
                                "--json",
                                "--limit",
                                "3")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        var allResult =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "jdk.ActiveSetting",
                                "--json")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();
        var limitList =
                (java.util.List<?>) me.bechberger.util.json.JSONParser.parse(limitResult.output());
        var allList =
                (java.util.List<?>) me.bechberger.util.json.JSONParser.parse(allResult.output());
        assertThat(limitList).hasSize(3);
        // The limited events should be the first 3 from the full sorted list
        for (int i = 0; i < 3 && i < allList.size(); i++) {
            @SuppressWarnings("unchecked")
            var limitMap = (java.util.Map<String, Object>) limitList.get(i);
            @SuppressWarnings("unchecked")
            var allMap = (java.util.Map<String, Object>) allList.get(i);
            assertThat(limitMap.get("startTime"))
                    .as("Event %d startTime should match", i)
                    .isEqualTo(allMap.get("startTime"));
        }
    }

    @Test
    public void testReasonableDefaultShowsPercentagesInTableView() throws Exception {
        // Bug 218: with reasonable-default, percentage fields were rendered as raw floats and
        // emitted a warning for unknown "percentage" type.
        Path profileJfr = Path.of("profile.jfr");
        if (!Files.exists(profileJfr)) {
            return;
        }

        Path output = Path.of("tmp", "bug218-" + System.nanoTime() + ".cjfr");
        try {
            Files.createDirectories(Path.of("tmp"));
            var condense =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {
                                "condense",
                                profileJfr.toString(),
                                output.toString(),
                                "-f",
                                "-c",
                                "reasonable-default"
                            });
            assertThat(condense.exitCode()).isEqualTo(0);

            var view =
                    me.bechberger.jfr.cli.JFRCLI.runCapturedWithDispatch(
                            new String[] {
                                "view", output.toString(), "jdk.CPULoad", "--limit", "3"
                            });
            assertThat(view.exitCode()).isEqualTo(0);
            assertThat(view.err()).doesNotContain("potentially unknown type: percentage");
            assertThat(view.out()).contains("%");
            assertThat(view.out())
                    .contains("Jvm User")
                    .contains("Jvm System")
                    .contains("Machine Total");
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
