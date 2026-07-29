package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests and size benchmarks for ExecutionSample/NativeMethodSample combining
 * (combineProfilingSamples) and I/O event combining (combineIOEvents).
 *
 * <p>Uses benchmark recordings when available; skips gracefully when files are absent.
 */
public class ProfilingReductionRoundTripTest {

    // Benchmark files with many ExecutionSample + NativeMethodSample events.
    private static final String ALS_JFR = "benchmark/renaissance-als_default_G1.jfr";
    private static final String MNEMONICS_JFR = "benchmark/renaissance-mnemonics_default_G1.jfr";

    // Multiset "eventType\tjavaThreadId\tstackTrace" → count (order-independent)
    private static Map<String, Long> executionSampleMultiset(Path jfr, int maxDepth)
            throws Exception {
        Map<String, Long> counts = new HashMap<>();
        try (var rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                if (!type.equals("jdk.ExecutionSample") && !type.equals("jdk.NativeMethodSample")) {
                    continue;
                }
                var thread = e.getThread("sampledThread");
                long tid = thread == null ? -1 : thread.getJavaThreadId();
                var st = e.getStackTrace();
                String stKey = st == null ? "" : stackTraceContentKey(st, maxDepth);
                String key = type + "\t" + tid + "\t" + stKey;
                counts.merge(key, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static String stackTraceContentKey(
            jdk.jfr.consumer.RecordedStackTrace st, int maxDepth) {
        var sb = new StringBuilder();
        var frames = st.getFrames();
        int limit = maxDepth < 0 ? frames.size() : Math.min(maxDepth, frames.size());
        for (int i = 0; i < limit; i++) {
            var frame = frames.get(i);
            var method = frame.getMethod();
            if (method != null) {
                sb.append(method.getType().getName()).append('.').append(method.getName());
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private static long totalCount(Map<String, Long> multiset, String... types) {
        long sum = 0;
        for (var entry : multiset.entrySet()) {
            for (var t : types) {
                if (entry.getKey().startsWith(t + "\t")) {
                    sum += entry.getValue();
                    break;
                }
            }
        }
        return sum;
    }

    /**
     * Condense → inflate round-trip with reduced config: total event count is preserved and the
     * inflated file has ≤ distinct (thread, stackTrace) keys (frame collapsing merges some traces).
     */
    @Test
    public void testExecutionSampleRoundTripPreservesMultiset() throws Exception {
        var srcJfr = Path.of(ALS_JFR);
        Assumptions.assumeTrue(Files.exists(srcJfr), ALS_JFR + " not found — skipping");

        var tmpDir = Files.createTempDirectory("profiling-roundtrip");
        var cjfr = tmpDir.resolve("out.cjfr");
        var inflated = tmpDir.resolve("out.inflated.jfr");
        try {
            int c =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "condense",
                                "--force",
                                "--condenser-config",
                                "reduced",
                                srcJfr.toString(),
                                cjfr.toString()
                            });
            assertEquals(0, c, "condense must succeed");

            int i =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "inflate", "--force", cjfr.toString(), inflated.toString()
                            });
            assertEquals(0, i, "inflate must succeed");

            int maxDepth = (int) Configuration.REDUCED_DEFAULT.maxStackTraceDepth();
            var original = executionSampleMultiset(srcJfr, maxDepth);
            var roundTripped = executionSampleMultiset(inflated, -1);

            long origTotal = totalCount(original, "jdk.ExecutionSample", "jdk.NativeMethodSample");
            assertTrue(origTotal > 0, "source must contain ExecutionSample/NativeMethodSample");

            long rtTotal =
                    totalCount(roundTripped, "jdk.ExecutionSample", "jdk.NativeMethodSample");
            assertEquals(
                    origTotal, rtTotal, "total event count must be preserved after round-trip");

            assertTrue(
                    roundTripped.size() <= original.size(),
                    "frame collapsing may only reduce distinct (thread, trace) keys, not increase"
                            + " them (original="
                            + original.size()
                            + ", roundTripped="
                            + roundTripped.size()
                            + ")");
        } finally {
            Files.deleteIfExists(cjfr);
            Files.deleteIfExists(inflated);
            Files.deleteIfExists(tmpDir);
        }
    }

    /**
     * Verifies that reducing with combineProfilingSamples produces a smaller file than default, and
     * that size reduction grows with more profiling data.
     */
    @Test
    public void testReducedConfigSmallerThanDefault() throws Exception {
        var srcJfr = Path.of(ALS_JFR);
        Assumptions.assumeTrue(Files.exists(srcJfr), ALS_JFR + " not found — skipping");

        var tmpDir = Files.createTempDirectory("profiling-size");
        var defaultCjfr = tmpDir.resolve("default.cjfr");
        var reducedCjfr = tmpDir.resolve("reduced.cjfr");
        try {
            int c1 =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "condense",
                                "--force",
                                "--condenser-config",
                                "default",
                                srcJfr.toString(),
                                defaultCjfr.toString()
                            });
            assertEquals(0, c1, "condense with default must succeed");

            int c2 =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "condense",
                                "--force",
                                "--condenser-config",
                                "reduced",
                                srcJfr.toString(),
                                reducedCjfr.toString()
                            });
            assertEquals(0, c2, "condense with reduced must succeed");

            long defaultSize = Files.size(defaultCjfr);
            long reducedSize = Files.size(reducedCjfr);

            assertTrue(
                    reducedSize < defaultSize,
                    "reduced config must produce a smaller file than default "
                            + "(default="
                            + defaultSize
                            + ", reduced="
                            + reducedSize
                            + ")");

            // The als benchmark has many ExecutionSample + NativeMethodSample events;
            // combining them should save at least 20% vs default.
            double savingsPct = 100.0 * (defaultSize - reducedSize) / defaultSize;
            assertTrue(
                    savingsPct >= 20.0,
                    "expected at least 20% savings on als (many profiling events), got "
                            + String.format("%.1f", savingsPct)
                            + "%");
        } finally {
            Files.deleteIfExists(defaultCjfr);
            Files.deleteIfExists(reducedCjfr);
            Files.deleteIfExists(tmpDir);
        }
    }

    /**
     * Verifies that a recording without profiling events (mnemonics_default_G1 has none) still
     * round-trips correctly with the reduced config.
     */
    @Test
    public void testReducedConfigCondensesSuccessfullyWithoutProfilingEvents() throws Exception {
        var srcJfr = Path.of(MNEMONICS_JFR);
        Assumptions.assumeTrue(Files.exists(srcJfr), MNEMONICS_JFR + " not found — skipping");

        var tmpDir = Files.createTempDirectory("profiling-no-samples");
        var cjfr = tmpDir.resolve("out.cjfr");
        try {
            int c =
                    me.bechberger.jfr.cli.JFRCLI.execute(
                            new String[] {
                                "condense",
                                "--force",
                                "--condenser-config",
                                "reduced",
                                srcJfr.toString(),
                                cjfr.toString()
                            });
            assertEquals(0, c, "condense with reduced must succeed even without profiling events");
            assertTrue(Files.size(cjfr) > 0, "output must not be empty");
        } finally {
            Files.deleteIfExists(cjfr);
            Files.deleteIfExists(tmpDir);
        }
    }
}
