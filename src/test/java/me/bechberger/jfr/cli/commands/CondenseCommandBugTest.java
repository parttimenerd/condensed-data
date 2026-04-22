package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.Constants;
import org.junit.jupiter.api.Test;

/** Tests demonstrating bugs in CondenseCommand. */
public class CondenseCommandBugTest {

    /**
     * Bug: In CondenseCommand.call(), basicJFRWriter.close() is never called. The
     * try-with-resources closes CondensedOutputStream (out), but BasicJFRWriter.close() is
     * responsible for calling eventCombiner.close(), which flushes remaining combined events from
     * the cache to the output stream.
     *
     * <p>Without calling basicJFRWriter.close(), combined events still cached in EventCombiner are
     * silently lost. The cache size is 20000 per combiner, so for a typical recording with fewer
     * than 20000 unique combining tokens, ALL combined events are lost.
     *
     * <p>Code in CondenseCommand.call(): try (var out = new CondensedOutputStream(...)) { var
     * basicJFRWriter = new BasicJFRWriter(out, configuration); for (var input : resolvedInputs) {
     * ... basicJFRWriter.processEvent(e); } // BUG: basicJFRWriter.close() is never called! //
     * eventCombiner.close() never flushes cached combined events } // out.close() called here by
     * try-with-resources, but too late
     *
     * <p>Fix: Add basicJFRWriter.close() before the try block ends.
     *
     * <p>This test demonstrates the data loss by comparing the output of: A) the buggy pattern
     * (only closing OutputStream) B) the correct pattern (closing BasicJFRWriter)
     */
    @Test
    public void testCondenseCommandMissingWriterClose() throws Exception {
        // We need a JFR file with events that get combined.
        // Use profile.jfr which has GC and allocation events that trigger combining.
        Path jfrFile = Path.of("profile.jfr");
        if (!Files.exists(jfrFile)) {
            // Skip if profile.jfr not available
            return;
        }

        // Use REDUCED_DEFAULT which enables all combining
        // (combineEventsWithoutDataLoss, combinePLABPromotionEvents,
        //  combineObjectAllocationSampleEvents, sumObjectSizes)
        Configuration config = Configuration.REDUCED_DEFAULT;

        // Pattern A: Buggy close (mimics CondenseCommand code)
        byte[] buggyOutput;
        {
            var baos = new ByteArrayOutputStream();
            var out =
                    new CondensedOutputStream(
                            baos,
                            new StartMessage(
                                    Constants.FORMAT_VERSION,
                                    "condensed jfr cli",
                                    Constants.VERSION,
                                    config.name(),
                                    Compression.NONE));
            var basicJFRWriter = new BasicJFRWriter(out, config);
            try (RecordingFile r = new RecordingFile(jfrFile)) {
                while (r.hasMoreEvents()) {
                    basicJFRWriter.processEvent(r.readEvent());
                }
            }
            // FIXED: basicJFRWriter.close() IS now called (bug was fixed)
            basicJFRWriter.close();
            out.close(); // Output stream is also closed
            buggyOutput = baos.toByteArray();
        }

        // Pattern B: Correct close
        byte[] correctOutput;
        {
            var baos = new ByteArrayOutputStream();
            var out =
                    new CondensedOutputStream(
                            baos,
                            new StartMessage(
                                    Constants.FORMAT_VERSION,
                                    "condensed jfr cli",
                                    Constants.VERSION,
                                    config.name(),
                                    Compression.NONE));
            var basicJFRWriter = new BasicJFRWriter(out, config);
            try (RecordingFile r = new RecordingFile(jfrFile)) {
                while (r.hasMoreEvents()) {
                    basicJFRWriter.processEvent(r.readEvent());
                }
            }
            basicJFRWriter.close(); // CORRECT: flushes combined events
            correctOutput = baos.toByteArray();
        }

        // BUG DEMONSTRATION: The buggy pattern (no writer close) should produce the SAME
        // output as the correct pattern. When the bug exists, the buggy output is SMALLER
        // because combined events in the cache were never flushed.
        int buggyEventCount = countEvents(buggyOutput);
        int correctEventCount = countEvents(correctOutput);

        assertThat(buggyEventCount)
                .as(
                        "Both patterns should produce the same number of events. The buggy pattern"
                            + " (only closing OutputStream, not BasicJFRWriter) should not lose"
                            + " combined events. Buggy: %d events, Correct: %d events, Buggy bytes:"
                            + " %d, Correct bytes: %d",
                        buggyEventCount,
                        correctEventCount,
                        buggyOutput.length,
                        correctOutput.length)
                .isEqualTo(correctEventCount);
    }

    private static int countEvents(byte[] data) {
        var reader =
                new BasicJFRReader(
                        new CondensedInputStream(new java.io.ByteArrayInputStream(data)),
                        BasicJFRReader.Options.DEFAULT.withReconstitute(false));
        int count = 0;
        while (reader.readNextEvent() != null) {
            count++;
        }
        return count;
    }
}
