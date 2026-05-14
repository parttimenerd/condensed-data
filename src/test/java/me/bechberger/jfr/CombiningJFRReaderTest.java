package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

public class CombiningJFRReaderTest {

    @Test
    public void testGetInputStreamAfterAllEventsReadDoesNotThrow() throws Exception {
        var tmpDir = Files.createTempDirectory("combining-reader-test");
        var cjfr1 = tmpDir.resolve("one.cjfr");
        var cjfr2 = tmpDir.resolve("two.cjfr");
        try {
            var jfr1 = me.bechberger.jfr.cli.commands.CommandTestUtil.getSampleJFRFile(0);
            var jfr2 = me.bechberger.jfr.cli.commands.CommandTestUtil.getSampleJFRFile(1);
            me.bechberger.jfr.cli.JFRCLI.execute(
                    new String[] {"condense", "--force", jfr1.toString(), cjfr1.toString()});
            me.bechberger.jfr.cli.JFRCLI.execute(
                    new String[] {"condense", "--force", jfr2.toString(), cjfr2.toString()});

            var reader = CombiningJFRReader.fromPaths(List.of(cjfr1, cjfr2));

            while (reader.readNextEvent() != null) {
                // exhaust reader
            }

            assertDoesNotThrow(reader::getInputStream);
        } finally {
            Files.deleteIfExists(cjfr1);
            Files.deleteIfExists(cjfr2);
            Files.deleteIfExists(tmpDir);
        }
    }

    /**
     * Bug 247: On-the-fly re-condensation of JFR files via CombiningJFRReader.readerForJFRFile()
     * used Configuration.DEFAULT which has ignoreUnnecessaryEvents=true (dedup). When a JFR file
     * has been through lossy compression (e.g. reasonable-default with BFloat16), two events with
     * different original values can collapse to the same BFloat16 representation. Re-condensation
     * then incorrectly drops one as a "duplicate".
     *
     * <p>Reproduction: condense a JFR file with reasonable-default → inflate → view/summary the
     * inflated JFR. The view/summary re-condenses with DEFAULT config and the dedup drops events
     * whose field values were made identical by BFloat16.
     *
     * <p>This test reads a sample JFR file via CombiningJFRReader (which re-condenses on the fly)
     * and verifies all events from the original JFR file are preserved.
     */
    @Test
    public void testOnTheFlyCondensationDoesNotDeduplicateEvents() throws Exception {
        var jfrFile = me.bechberger.jfr.cli.commands.CommandTestUtil.getSampleJFRFile();

        // Read all events from the original JFR file directly
        List<RecordedEvent> originalEvents = new ArrayList<>();
        try (var rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                originalEvents.add(rf.readEvent());
            }
        }

        // Count events via CombiningJFRReader reading the original JFR
        // (on-the-fly re-condensation path)
        var combiningReader = CombiningJFRReader.fromPaths(List.of(jfrFile));
        int combiningEventCount = 0;
        while (combiningReader.readNextEvent() != null) {
            combiningEventCount++;
        }

        // The on-the-fly path should preserve all events from the JFR file.
        // Before the fix, dedup (ignoreUnnecessaryEvents) in on-the-fly condensation
        // could drop events whose field values looked identical after lossy compression.
        assertEquals(
                originalEvents.size(),
                combiningEventCount,
                "CombiningJFRReader on-the-fly condensation should preserve all events "
                        + "from the JFR file");
    }
}
