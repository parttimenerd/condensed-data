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

    private static int[] countKeptDropped(Path testJfr, Configuration config, String eventType)
            throws Exception {
        var dedup = new JFREventDeduplication(config);
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
        return new int[] {kept, dropped};
    }

    /**
     * The {@code lossless} preset must preserve every periodic time-series observation (each has a
     * distinct timestamp), while {@code default} may still deduplicate unchanged repeats. Static
     * events (flags) must remain deduplicated under both presets since their payload never changes.
     */
    @Test
    public void testLosslessPresetPreservesPeriodicTimeSeries() throws Exception {
        var testJfr = Path.of("benchmark/renaissance-all_gc_details_SerialGC.jfr");
        if (!Files.exists(testJfr)) {
            System.err.println("Skipping: " + testJfr + " not found");
            return;
        }

        // Periodic time-series: deduped under default, fully preserved under lossless.
        var defaultTs =
                countKeptDropped(testJfr, Configuration.DEFAULT, "jdk.DirectBufferStatistics");
        var losslessTs =
                countKeptDropped(testJfr, Configuration.LOSSLESS, "jdk.DirectBufferStatistics");
        assertThat(defaultTs[0]).isGreaterThan(0);
        assertThat(defaultTs[1]).isGreaterThan(0); // default drops some
        assertThat(losslessTs[1]).isEqualTo(0); // lossless drops none
        assertThat(losslessTs[0]).isEqualTo(defaultTs[0] + defaultTs[1]); // all survive

        // Static event (a boolean flag): still deduped under both presets — lossless dedup of
        // constant-valued events loses no per-timestamp information.
        var defaultFlag = countKeptDropped(testJfr, Configuration.DEFAULT, "jdk.BooleanFlag");
        var losslessFlag = countKeptDropped(testJfr, Configuration.LOSSLESS, "jdk.BooleanFlag");
        assertThat(defaultFlag[1]).isGreaterThan(0);
        assertThat(losslessFlag[1]).isEqualTo(defaultFlag[1]);
        assertThat(losslessFlag[0]).isEqualTo(defaultFlag[0]);

        // NativeLibrary (Bug 270): periodic, emitted at start and end of recording —
        // must NOT be deduped under lossless.
        var defaultNL =
                countKeptDropped(testJfr, Configuration.DEFAULT, "jdk.NativeLibrary");
        var losslessNL =
                countKeptDropped(testJfr, Configuration.LOSSLESS, "jdk.NativeLibrary");
        assertThat(defaultNL[1]).isGreaterThan(0); // default drops duplicates
        assertThat(losslessNL[1]).isEqualTo(0); // lossless drops none

        // GCConfiguration (Bug 270): periodic singleton — same fix as NativeLibrary.
        var defaultGC =
                countKeptDropped(testJfr, Configuration.DEFAULT, "jdk.GCConfiguration");
        var losslessGC =
                countKeptDropped(testJfr, Configuration.LOSSLESS, "jdk.GCConfiguration");
        if (defaultGC[0] + defaultGC[1] > 1) {
            assertThat(defaultGC[1]).isGreaterThan(0); // default deduplicates
            assertThat(losslessGC[1]).isEqualTo(0); // lossless keeps all
        }
    }

    /**
     * Bug 273: {@code jdk.DeprecatedInvocation} was keyed by {@code method} alone, collapsing
     * distinct call sites (same deprecated method, different {@code invocationTime}/stackTrace).
     * The correct key is {@code (method, invocationTime)}: same call site re-emitted per chunk
     * boundary gets deduped; different call sites (same method, different time) are preserved.
     *
     * <p>Uses {@code renaissance-neo4j-analytics_default_G1.jfr} (1 chunk, 4 distinct call sites
     * of {@code System.getSecurityManager()}) and {@code renaissance-als_default_G1.jfr} (2
     * chunks, each emitting 23 unique call sites — expect 23 after dedup, not 46 or 6).
     */
    @Test
    public void testDeprecatedInvocationPreservesDistinctCallSites() throws Exception {
        // 1-chunk recording: 4 events with the same method but different invocationTime.
        // All 4 must be kept.
        var neo4jJfr =
                Path.of("benchmark/renaissance-neo4j-analytics_default_G1.jfr");
        if (Files.exists(neo4jJfr)) {
            var result = countKeptDropped(neo4jJfr, Configuration.DEFAULT, "jdk.DeprecatedInvocation");
            assertThat(result[1]).isEqualTo(0); // no drops for single-chunk recording
            assertThat(result[0]).isEqualTo(4); // all 4 distinct call sites preserved
        } else {
            System.err.println("Skipping neo4j test: " + neo4jJfr + " not found");
        }

        // 2-chunk recording: 46 total events, each unique (method, invocationTime) appears in
        // both chunks. Expect 23 after dedup (cross-chunk duplicates removed).
        var alsJfr = Path.of("benchmark/renaissance-als_default_G1.jfr");
        if (Files.exists(alsJfr)) {
            var result = countKeptDropped(alsJfr, Configuration.DEFAULT, "jdk.DeprecatedInvocation");
            assertThat(result[0]).isEqualTo(23); // 23 unique call sites
            assertThat(result[1]).isEqualTo(23); // 23 cross-chunk duplicates dropped
        } else {
            System.err.println("Skipping als test: " + alsJfr + " not found");
        }
    }
}
