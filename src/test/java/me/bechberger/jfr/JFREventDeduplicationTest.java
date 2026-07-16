package me.bechberger.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

public class JFREventDeduplicationTest {

    private static void assertEventTypeIsNotDeduplicated(Path testJfr, String eventType)
            throws Exception {
        if (!Files.exists(testJfr)) {
            System.err.println("Skipping: " + testJfr + " not found");
            return;
        }
        var dedup = new JFREventDeduplication(Configuration.DEFAULT);

        int kept = 0;
        int dropped = 0;
        try (var recording = new RecordingFile(testJfr)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (!event.getEventType().getName().equals(eventType)) {
                    continue;
                }
                if (dedup.recordAndCheckIfDuplicate(event)) {
                    dropped++;
                } else {
                    kept++;
                }
            }
        }

        assertThat(kept).isGreaterThan(0);
        assertThat(dropped).isEqualTo(0);
    }

    @Test
    public void testFinalizerStatisticsIsNotDeduplicated() throws Exception {
        assertEventTypeIsNotDeduplicated(
                Path.of("benchmark/renaissance-all_gc_ZGC.jfr"), "jdk.FinalizerStatistics");
    }

    @Test
    public void testClassLoaderStatisticsIsNotDeduplicated() throws Exception {
        assertEventTypeIsNotDeduplicated(
                Path.of("benchmark/renaissance-als_default_G1.jfr"), "jdk.ClassLoaderStatistics");
    }

    @Test
    public void testThreadAllocationStatisticsDeduplicatesUnchangedEntries() throws Exception {
        var testJfr = Path.of("benchmark/renaissance-movie-lens_default_G1.jfr");
        if (!Files.exists(testJfr)) {
            System.err.println("Skipping: " + testJfr + " not found");
            return;
        }
        var dedup = new JFREventDeduplication(Configuration.DEFAULT);

        int kept = 0;
        int dropped = 0;
        try (var recording = new RecordingFile(testJfr)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ThreadAllocationStatistics")) {
                    continue;
                }
                if (dedup.recordAndCheckIfDuplicate(event)) {
                    dropped++;
                } else {
                    kept++;
                }
            }
        }

        assertThat(kept).isGreaterThan(0);
        // Idle threads emit repeated unchanged values — expect some deduplication
        assertThat(dropped).isGreaterThan(0);
    }
}
