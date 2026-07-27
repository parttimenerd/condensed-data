package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the ZStatisticsCounter/Sampler value-residual encoding
 * (ZStatisticsCombiner.createValueDefinition + ZStatisticsReconstitutor). The {@code value} field
 * of these events is (near-)cumulative, so it is stored as a signed residual {@code value -
 * prevValue - increment} and rebuilt on inflate. This must be exactly lossless: the per-id multiset
 * of {@code value} observations after a lossless condense -> inflate round-trip must equal the
 * original.
 *
 * <p>A single round-trip covers both event types (the source ZGC recording contains both), which
 * keeps the test to one small inflate.
 */
public class ZStatisticsValueResidualTest {

    /** Map "eventType\tid\tvalue" -> count, order-independent (event reordering is a non-bug). */
    private static Map<String, Long> perIdValueMultiset(Path jfr, String... eventTypes)
            throws Exception {
        Map<String, Long> counts = new HashMap<>();
        try (var rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                boolean wanted = false;
                for (String t : eventTypes) {
                    if (t.equals(type)) {
                        wanted = true;
                        break;
                    }
                }
                if (!wanted) {
                    continue;
                }
                String key = type + "\t" + e.getString("id") + "\t" + e.getLong("value");
                counts.merge(key, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static long countFor(Map<String, Long> multiset, String eventType) {
        return multiset.entrySet().stream()
                .filter(en -> en.getKey().startsWith(eventType + "\t"))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    @Test
    public void testZStatisticsValuePreservedLossless() throws Exception {
        assertLosslessRoundTrip();
    }

    /**
     * Guards against a reconstitutor-state leak: the {@code ZStatisticsReconstitutor} instances live
     * in a JVM-lifetime static registry, but the residual decode carries a per-id running value that
     * must be FRESH for each recording. If that state were kept on the shared instance, the second
     * inflate in one JVM would decode every value offset by the first recording's last cumulative
     * value. Running the exact same round-trip twice in-process must therefore both be lossless.
     */
    @Test
    public void testResidualStateDoesNotLeakBetweenInflates() throws Exception {
        assertLosslessRoundTrip();
        assertLosslessRoundTrip();
    }

    private static void assertLosslessRoundTrip() throws Exception {
        // Small ZGC recording (~700 KB) that still carries both ZStatisticsCounter (15k) and
        // ZStatisticsSampler (1.7k) events — keeps the round-trip fast and the temp footprint tiny.
        var srcJfr = Path.of("benchmark/renaissance-dotty_gc_ZGC.jfr");
        if (!Files.exists(srcJfr)) {
            System.err.println("Skipping: " + srcJfr + " not found");
            return;
        }
        var tmpDir = Files.createTempDirectory("zstats-residual-test");
        var cjfr = tmpDir.resolve("out.cjfr");
        var inflated = tmpDir.resolve("out.inflated.jfr");
        try {
            int c =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "condense",
                                "--force",
                                "--condenser-config",
                                "lossless",
                                srcJfr.toString(),
                                cjfr.toString()
                            });
            assertEquals(0, c, "condense should succeed");
            int i =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "inflate", "--force", cjfr.toString(), inflated.toString()
                            });
            assertEquals(0, i, "inflate should succeed");

            String counter = "jdk.ZStatisticsCounter";
            String sampler = "jdk.ZStatisticsSampler";
            var original = perIdValueMultiset(srcJfr, counter, sampler);
            var roundTripped = perIdValueMultiset(inflated, counter, sampler);

            assertTrue(countFor(original, counter) > 0, counter + " must be present in source");
            assertTrue(countFor(original, sampler) > 0, sampler + " must be present in source");

            assertEquals(
                    original,
                    roundTripped,
                    "lossless round-trip must preserve the per-id ZStatistics value multiset "
                            + "exactly (residual encoding is lossless)");
        } finally {
            Files.deleteIfExists(cjfr);
            Files.deleteIfExists(inflated);
            Files.deleteIfExists(tmpDir);
        }
    }
}
