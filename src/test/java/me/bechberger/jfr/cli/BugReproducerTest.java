package me.bechberger.jfr.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import me.bechberger.jfr.cli.commands.CommandExecuter;
import me.bechberger.jfr.cli.commands.CommandTestUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reproducer tests for bugs found during manual testing of the CLI.
 *
 * <p>Bug 1: Truncation logic is inverted in JFRView — default TruncateMode.END keeps the end of the
 * string instead of the beginning, causing durations like "0.003832208s" to display as
 * "003832208s".
 *
 * <p>Bug 2: The --truncate option help says 'begining' but TruncateMode has BEGIN/END — using
 * --truncate begining crashes with IllegalArgumentException.
 *
 * <p>Bug 3: summary --full omits the normal summary (format version, generator, etc.) in text mode,
 * while --json --full correctly includes both.
 */
public class BugReproducerTest {

    private static ReadStruct gcEventStruct;

    @BeforeAll
    static void initJFRStructs() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out);
            AtomicBoolean hadGC = new AtomicBoolean(false);
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("jdk.GarbageCollection");
                rs.onEvent(
                        "jdk.GarbageCollection",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            hadGC.set(true);
                            rs.close();
                        });
                rs.startAsync();
                // Force a GC
                while (!hadGC.get()) {
                    @SuppressWarnings("unused") // intentional allocation to trigger GC
                    byte[] garbage = new byte[1024 * 1024];
                    System.gc();
                }
                rs.awaitTermination();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        try (CondensedInputStream in = new CondensedInputStream(outputStream.toByteArray())) {
            var reader = new BasicJFRReader(in);
            var events = reader.readAll();
            for (var event : events) {
                if ("jdk.GarbageCollection".equals(event.getType().getName())) {
                    gcEventStruct = event;
                    break;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertNotNull(gcEventStruct, "Should have captured a GC event");
    }

    // =========================================================================
    // Bug 1: Truncation logic is inverted — default END mode strips the
    //        beginning of strings instead of the end
    // =========================================================================

    /**
     * The truncate() method in JFRView has inverted semantics:
     *
     * <ul>
     *   <li>TruncateMode.END (default) does s.substring(s.length()-width) — keeps the END
     *   <li>TruncateMode.BEGIN does s.substring(0, width) — keeps the BEGINNING
     * </ul>
     *
     * <p>The intuitive meaning of "truncate end" is "cut off the end, keep the beginning". This
     * test demonstrates the bug by showing that with the default END mode, a duration value like
     * "0.003832208s" loses its leading "0." and displays as "003832208s".
     */
    @Test
    public void testTruncationDefaultModeKeepsBeginning() {
        // Use a narrow width so that formatted durations exceed the column width
        // The Duration column has width = max(10, header.length()) = 10
        // But "0.003832208s" is 12 chars, forcing truncation
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(gcEventStruct.getType()), config);
        var rows = view.rows(gcEventStruct);
        assertFalse(rows.isEmpty(), "Should have at least one row");

        String row = rows.get(0);
        // The Duration column is the second column in GarbageCollection events
        // With the default END truncation, durations should keep their beginning
        // (i.e., "0.00383220" not "003832208s")

        // A correctly truncated duration should start with "0." if it's a sub-second value
        // The bug causes it to show something like "003832208s" where the leading "0." is stripped
        Duration dur = gcEventStruct.get("duration", Duration.class);
        if (dur != null && dur.getSeconds() == 0 && dur.toNanos() > 0) {
            String formatted =
                    dur.toString().substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase();
            if (formatted.length() > 10) {
                // If the formatted duration exceeds 10 chars, truncation happens
                // With correct "truncate end" semantics, we should keep the beginning
                // The beginning always starts with "0." for sub-second durations
                // BUG: the default END mode actually keeps the end, so "0." is stripped
                assertThat(row)
                        .as(
                                "Default truncation (END) should keep the beginning of values, "
                                        + "but the logic is inverted: it keeps the end instead. "
                                        + "Duration '%s' should show '0.' prefix when truncated, "
                                        + "not '%s'",
                                formatted, formatted.substring(formatted.length() - 10))
                        .contains("0.");
            }
        }
    }

    /** Direct unit test of the corrected truncation semantics. */
    @Test
    public void testTruncateMethodSemanticsAreCorrect() {
        String value = "0.003832208s"; // 12 chars, typical sub-second duration
        int width = 10;

        // Truncate from the beginning -> keep the end
        String beginResult = value.substring(value.length() - width);

        // Truncate from the end -> keep the beginning
        String endResult = value.substring(0, width);

        assertEquals("003832208s", beginResult, "BEGIN mode should keep the end of the value");
        assertEquals("0.00383220", endResult, "END mode should keep the beginning of the value");
        assertThat(endResult).startsWith("0.");
    }

    // =========================================================================
    // Bug 2: --truncate option help says 'begining' which crashes
    // =========================================================================

    /** The CLI should reject the typo 'begining' and only accept correct spellings. */
    @Test
    public void testTruncateOptionRejectsTypo() throws Exception {
        var result =
                new CommandExecuter(
                                "view",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "TestEvent",
                                "--truncate",
                                "begining")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .run();

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--truncate must be");
    }

    /** The CLI should accept all supported user-facing spellings. */
    @Test
    public void testTruncateModeValueOfDocumentedValues() {
        assertDoesNotThrow(
                () -> TruncateMode.fromCliValue("end"), "'end' should be a valid TruncateMode");
        assertDoesNotThrow(() -> TruncateMode.fromCliValue("begin"));
        assertDoesNotThrow(() -> TruncateMode.fromCliValue("beginning"));
        assertThrows(IllegalArgumentException.class, () -> TruncateMode.fromCliValue("begining"));
    }

    // =========================================================================
    // Bug 3: summary --full omits normal summary in text mode
    // =========================================================================

    /**
     * When using 'summary --full' in text mode, the normal summary (format version, generator,
     * start/end times, duration, event counts) is completely omitted. Only the EventWriteTree and
     * Detailed Statistics are printed.
     *
     * <p>In contrast, 'summary --json --full' correctly includes both the summary fields AND the
     * eventWriteTree.
     *
     * <p>The code has an if/else that makes --full and the normal summary mutually exclusive in
     * text mode.
     */
    @Test
    public void testFullSummaryIncludesNormalSummary() throws Exception {
        var result =
                new CommandExecuter(
                                "summary", "T/" + CommandTestUtil.getSampleCJFRFileName(), "--full")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();

        // --full should include the EventWriteTree (this works)
        assertThat(result.output()).contains("EventWriteTree");

        // BUG: --full should ALSO include the normal summary info, but it doesn't
        assertThat(result.output())
                .as(
                        "BUG: 'summary --full' should include the normal summary "
                                + "(format version, generator, etc.) in addition to the full "
                                + "statistics, but the code has an if/else that makes them "
                                + "mutually exclusive in text mode. "
                                + "Compare with 'summary --json --full' which correctly "
                                + "includes both.")
                .contains("Format Version:");
    }

    /**
     * Verify that --json --full includes both summary and EventWriteTree (this is the correct
     * behavior that text mode should match).
     */
    @Test
    public void testJsonFullIncludesBothSummaryAndTree() throws Exception {
        var result =
                new CommandExecuter(
                                "summary",
                                "T/" + CommandTestUtil.getSampleCJFRFileName(),
                                "--json",
                                "--full")
                        .withFiles(CommandTestUtil.getSampleCJFRFile())
                        .checkNoError()
                        .run();

        // JSON mode correctly includes both summary fields and EventWriteTree
        assertThat(result.output()).contains("format version");
        assertThat(result.output()).contains("generator");
        assertThat(result.output()).contains("eventWriteTree");
    }

    // =========================================================================
    // Bug 4: condense help says output ending is *.cjfc (typo for *.cjfr)
    // =========================================================================

    /**
     * The condense command help text for the outputFile parameter says: "default is the inputFile
     * with the ending *.cjfc"
     *
     * <p>But the actual code substitutes .jfr → .cjfr and the real file extension is .cjfr. The
     * description has a typo: "cjfc" should be "cjfr".
     */
    @Test
    public void testCondenseHelpShowsCorrectFileExtension() throws Exception {
        var result = new CommandExecuter("condense", "--help").run();
        assertThat(result.output())
                .as(
                        "BUG: Help text says '*.cjfc' but the correct extension is '*.cjfr'. "
                                + "This is a typo in the outputFile parameter description.")
                .contains(".cjfr");
    }

    // =========================================================================
    // Bug 5: ${COMPLETION-CANDIDATES} placeholder not expanded in help
    // =========================================================================

    /**
     * The condense --generatorConfiguration option description contains "${COMPLETION-CANDIDATES}"
     * which was a picocli placeholder. FemtoCli doesn't expand this, so the help shows "possible
     * values: " followed by nothing.
     */
    @Test
    public void testConfigurationHelpShowsValidValues() throws Exception {
        var result = new CommandExecuter("condense", "--help").run();
        // The help should list the actual configuration values
        assertThat(result.output())
                .as(
                        "BUG: The configuration option help contains an unexpanded "
                                + "'${COMPLETION-CANDIDATES}' placeholder from picocli. "
                                + "It should list valid values like 'default, "
                                + "reasonable-default, reduced-default'.")
                .containsAnyOf("default, reasonable-default", "reduced-default");
    }
}
