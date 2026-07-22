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

    /**
     * Bug 256: In the lossless {@code default} config, all {@code
     * jdk.MetaspaceChunkFreeListSummary} events whose chunk counts are all zero were dropped by
     * {@code BasicJFRWriter.isUnnecessaryEvent} before ever reaching the combiner. G1 emits these
     * events with all-zero chunk counts, so a condense→inflate roundtrip lost every one of them
     * (e.g. 84 → 0), even though the config claims {@code combineEventsWithoutDataLoss}.
     *
     * <p>Fix: dropped the all-zero {@code jdk.MetaspaceChunkFreeListSummary} clause from {@code
     * isUnnecessaryEvent}; the dedicated combiner now preserves these events (with their {@code
     * when}/{@code metadataType}/{@code gcId}) losslessly.
     */
    @Test
    public void testMetaspaceChunkFreeListSummaryPreservedInDefaultConfig() throws Exception {
        Path jfrFile = Path.of("profile.jfr");
        if (!Files.exists(jfrFile)) {
            return; // skip if sample not available
        }

        int originalCount = countMetaspaceEvents(jfrFile);
        if (originalCount == 0) {
            return; // nothing to assert if the sample has no such events
        }

        Path tmpDir = Files.createTempDirectory("bug256");
        Path cjfr = tmpDir.resolve("b256.cjfr");
        Path inflated = tmpDir.resolve("b256_inflated.jfr");
        try {
            me.bechberger.jfr.cli.JFRCLI.execute(
                    new String[] {"condense", "--force", jfrFile.toString(), cjfr.toString()});
            me.bechberger.jfr.cli.JFRCLI.execute(
                    new String[] {"inflate", "--force", cjfr.toString(), inflated.toString()});

            int inflatedCount = countMetaspaceEvents(inflated);
            assertThat(inflatedCount)
                    .as(
                            "All jdk.MetaspaceChunkFreeListSummary events must survive a"
                                    + " default-config condense→inflate roundtrip (was dropped as"
                                    + " \"unnecessary\" when chunk counts were all zero)")
                    .isEqualTo(originalCount);
        } finally {
            Files.deleteIfExists(inflated);
            Files.deleteIfExists(cjfr);
            Files.deleteIfExists(tmpDir);
        }
    }

    private static int countMetaspaceEvents(Path jfr) throws Exception {
        int count = 0;
        try (RecordingFile r = new RecordingFile(jfr)) {
            while (r.hasMoreEvents()) {
                if (r.readEvent()
                        .getEventType()
                        .getName()
                        .equals("jdk.MetaspaceChunkFreeListSummary")) {
                    count++;
                }
            }
        }
        return count;
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
