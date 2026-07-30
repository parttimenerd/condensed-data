package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

public class JFRReductionTest {

    @Test
    public void testStateFieldStrippedFromExecutionSampleUnconditionally() {
        // state is always STATE_RUNNABLE (JFR only samples runnable threads), so it is
        // stripped unconditionally regardless of removeTypeInformationFromStackFrames.
        var configWithStrip = Configuration.LOSSLESS.withRemoveTypeInformationFromStackFrames(true);
        var configWithout = Configuration.LOSSLESS.withRemoveTypeInformationFromStackFrames(false);

        var strippedWithFlag =
                ReducedJFRTypes.getRemovedFields("jdk.ExecutionSample", configWithStrip, false);
        assertTrue(strippedWithFlag.contains("state"), "state should be stripped when flag on");

        var strippedWithoutFlag =
                ReducedJFRTypes.getRemovedFields("jdk.ExecutionSample", configWithout, false);
        assertTrue(
                strippedWithoutFlag.contains("state"),
                "state should be stripped unconditionally (always STATE_RUNNABLE)");
    }

    @Test
    public void testStateFieldStrippedFromNativeMethodSampleUnconditionally() {
        // state is always STATE_RUNNABLE — stripped unconditionally.
        var configWithStrip = Configuration.LOSSLESS.withRemoveTypeInformationFromStackFrames(true);
        var configWithout = Configuration.LOSSLESS.withRemoveTypeInformationFromStackFrames(false);

        var strippedWithFlag =
                ReducedJFRTypes.getRemovedFields("jdk.NativeMethodSample", configWithStrip, false);
        assertTrue(
                strippedWithFlag.contains("state"),
                "state should be stripped from NativeMethodSample when flag on");

        var strippedWithoutFlag =
                ReducedJFRTypes.getRemovedFields("jdk.NativeMethodSample", configWithout, false);
        assertTrue(
                strippedWithoutFlag.contains("state"),
                "state should be stripped from NativeMethodSample unconditionally");
    }

    @Test
    public void testTimestampReductionDefaultConfigPreservesSub256nsDelta() {
        long baseNanos = 1_764_933_141_345_000_000L;
        var universe = new Universe(baseNanos, baseNanos);
        var instant = Instant.ofEpochSecond(0, baseNanos + 100);

        long reduced =
                (Long)
                        JFRReduction.TIMESTAMP_REDUCTION.reduce(
                                Configuration.LOSSLESS, universe, instant);

        assertEquals(100L, reduced);
    }

    @Test
    public void testTimestampInflationDefaultConfigPreservesSub256nsDelta() {
        long baseNanos = 1_764_933_141_345_000_000L;
        var universe = new Universe(baseNanos, baseNanos);

        Instant inflated =
                (Instant)
                        JFRReduction.TIMESTAMP_REDUCTION.inflate(
                                Configuration.LOSSLESS, universe, 100L);

        assertEquals(
                baseNanos + 100L, inflated.getEpochSecond() * 1_000_000_000L + inflated.getNano());
    }

    @Test
    public void testDataAmountBytesReductionRoundtripHandlesNegativeAndBoundaries() {
        long[] values = {0L, 1L, 3L, 8L, 15L, 16L, -1L, -8L, -9L, Long.MIN_VALUE, Long.MAX_VALUE};

        for (long value : values) {
            var universe = new Universe(0L, 0L);
            long reduced =
                    (Long)
                            JFRReduction.DATA_AMOUNT_BYTES_REDUCTION.reduce(
                                    Configuration.LOSSLESS, universe, value);
            long inflated =
                    (Long)
                            JFRReduction.DATA_AMOUNT_BYTES_REDUCTION.inflate(
                                    Configuration.LOSSLESS, universe, reduced);
            assertEquals(value, inflated, "roundtrip mismatch for value=" + value);
        }
    }
}
