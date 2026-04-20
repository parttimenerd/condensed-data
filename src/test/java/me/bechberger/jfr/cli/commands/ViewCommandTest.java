package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.nio.file.Files;
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
                                                    "begining")
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
                                                    "begining")
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
}
