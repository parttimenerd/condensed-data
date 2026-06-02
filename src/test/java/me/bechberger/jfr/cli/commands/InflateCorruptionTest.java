package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

@InflaterRelated
public class InflateCorruptionTest {

    /**
     * Bug 16: Inflate produces corrupt JFR files (StackTrace constant pool). Round-trip: condense
     * profile.jfr → inflate → re-condense should work.
     */
    @Test
    public void testInflateRoundTripWithProfileJfr() throws Exception {
        Path profileJfr = Path.of("profile.jfr");
        if (!Files.exists(profileJfr)) {
            System.err.println("Skipping: profile.jfr not found");
            return;
        }

        // Steps: condense → inflate → read with RecordingFile → re-condense
        new CommandExecuter("condense", "T/profile.jfr", "T/test.cjfr")
                .withFiles(profileJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("test.cjfr");
                            // Inflate
                            new CommandExecuter("inflate", "T/test.cjfr", "T/inflated.jfr")
                                    .withFiles(map.get("test.cjfr"))
                                    .checkNoError()
                                    .check(
                                            (r2, m2) -> {
                                                Path inflatedFile = m2.get("inflated.jfr");
                                                assertThat(inflatedFile).exists();

                                                // Read with JDK RecordingFile
                                                int[] count = {0};
                                                try (RecordingFile rf =
                                                        new RecordingFile(inflatedFile)) {
                                                    while (rf.hasMoreEvents()) {
                                                        rf.readEvent();
                                                        count[0]++;
                                                    }
                                                } catch (Exception e) {
                                                    throw new RuntimeException(
                                                            "Failed to read inflated JFR: "
                                                                    + e.getMessage(),
                                                            e);
                                                }
                                                assertThat(count[0])
                                                        .describedAs("events in inflated file")
                                                        .isGreaterThan(0);

                                                // Re-condense
                                                var r3 =
                                                        new CommandExecuter(
                                                                        "condense",
                                                                        "T/inflated.jfr",
                                                                        "T/recondensed.cjfr")
                                                                .withFiles(inflatedFile)
                                                                .run();
                                                assertThat(r3.exitCode())
                                                        .describedAs(
                                                                "Re-condense error: %s", r3.error())
                                                        .isEqualTo(0);
                                            })
                                    .run();
                        })
                .run();
    }

    /**
     * Bug 250: Inflate produces corrupt JFR when chunk rotation triggers (>100K events).
     *
     * <p>WritingJFRReader rotates JMC recording chunks every 100K events. The inflated multi-chunk
     * JFR file is corrupt — Java's RecordingFile parser throws "Pool byte must contain at least one
     * element" or "Error parsing constant pool" when trying to read the second chunk.
     *
     * <p>This test generates enough events to trigger at least one chunk rotation during inflate,
     * then verifies the inflated JFR can be fully read by RecordingFile.
     */
    @Test
    public void testInflateWithChunkRotationProducesReadableJFR() throws Exception {
        // Use HighFreqEvent to generate >100K events (triggers chunk rotation in
        // WritingJFRReader)
        int eventCount = 150_000;
        Path highFreqJfr = CommandTestUtil.getHighFreqJFRFile(eventCount);
        String jfrName = highFreqJfr.getFileName().toString();
        String cjfrName = jfrName.replace(".jfr", ".cjfr");
        String inflatedName = jfrName.replace(".jfr", "_inflated.jfr");

        new CommandExecuter("condense", "T/" + jfrName, "T/" + cjfrName)
                .withFiles(highFreqJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey(cjfrName);
                            new CommandExecuter("inflate", "T/" + cjfrName, "T/" + inflatedName)
                                    .withFiles(map.get(cjfrName))
                                    .checkNoError()
                                    .check(
                                            (r2, m2) -> {
                                                Path inflatedFile = m2.get(inflatedName);
                                                assertThat(inflatedFile).exists();

                                                // Read ALL events with JDK RecordingFile
                                                // This crosses chunk boundaries and will fail
                                                // if the second chunk is corrupt
                                                int readCount = 0;
                                                try (RecordingFile rf =
                                                        new RecordingFile(inflatedFile)) {
                                                    while (rf.hasMoreEvents()) {
                                                        rf.readEvent();
                                                        readCount++;
                                                    }
                                                } catch (IOException e) {
                                                    throw new AssertionError(
                                                            "Inflated JFR is corrupt (chunk"
                                                                    + " rotation produced invalid"
                                                                    + " multi-chunk file): "
                                                                    + e.getMessage(),
                                                            e);
                                                }
                                                assertThat(readCount)
                                                        .describedAs(
                                                                "events readable from inflated"
                                                                        + " JFR")
                                                        .isGreaterThan(0);
                                            })
                                    .run();
                        })
                .run();
    }

    /**
     * Bug 250 (variant): Inflate of benchmark file with many event types also produces corrupt JFR.
     *
     * <p>Tests with renaissance-dotty_gc_details_G1.jfr which has ~216K events across 88 event
     * types, exercising more type registrations across chunk boundaries.
     */
    @Test
    public void testInflateWithBenchmarkDottyFileProducesReadableJFR() throws Exception {
        Path dottyJfr = Path.of("benchmark/renaissance-dotty_gc_details_G1.jfr");
        if (!Files.exists(dottyJfr)) {
            System.err.println("Skipping: benchmark file not found");
            return;
        }
        String jfrName = dottyJfr.getFileName().toString();
        String cjfrName = jfrName.replace(".jfr", ".cjfr");
        String inflatedName = jfrName.replace(".jfr", "_inflated.jfr");

        new CommandExecuter("condense", "T/" + jfrName, "T/" + cjfrName)
                .withFiles(dottyJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey(cjfrName);
                            new CommandExecuter("inflate", "T/" + cjfrName, "T/" + inflatedName)
                                    .withFiles(map.get(cjfrName))
                                    .checkNoError()
                                    .check(
                                            (r2, m2) -> {
                                                Path inflatedFile = m2.get(inflatedName);
                                                assertThat(inflatedFile).exists();

                                                int readCount = 0;
                                                try (RecordingFile rf =
                                                        new RecordingFile(inflatedFile)) {
                                                    while (rf.hasMoreEvents()) {
                                                        rf.readEvent();
                                                        readCount++;
                                                    }
                                                } catch (IOException e) {
                                                    throw new AssertionError(
                                                            "Inflated benchmark JFR is corrupt: "
                                                                    + e.getMessage(),
                                                            e);
                                                }
                                                assertThat(readCount)
                                                        .describedAs(
                                                                "events readable from inflated"
                                                                        + " benchmark JFR")
                                                        .isGreaterThan(0);
                                            })
                                    .run();
                        })
                .run();
    }
}
