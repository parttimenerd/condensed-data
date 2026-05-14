package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

public class JFRReductionTest {

    @Test
    public void testTimestampReductionDefaultConfigPreservesSub256nsDelta() {
        long baseNanos = 1_764_933_141_345_000_000L;
        var universe = new Universe(baseNanos, baseNanos);
        var instant = Instant.ofEpochSecond(0, baseNanos + 100);

        long reduced =
                (Long)
                        JFRReduction.TIMESTAMP_REDUCTION.reduce(
                                Configuration.DEFAULT, universe, instant);

        assertEquals(100L, reduced);
    }

    @Test
    public void testTimestampInflationDefaultConfigPreservesSub256nsDelta() {
        long baseNanos = 1_764_933_141_345_000_000L;
        var universe = new Universe(baseNanos, baseNanos);

        Instant inflated =
                (Instant)
                        JFRReduction.TIMESTAMP_REDUCTION.inflate(
                                Configuration.DEFAULT, universe, 100L);

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
                                    Configuration.DEFAULT, universe, value);
            long inflated =
                    (Long)
                            JFRReduction.DATA_AMOUNT_BYTES_REDUCTION.inflate(
                                    Configuration.DEFAULT, universe, reduced);
            assertEquals(value, inflated, "roundtrip mismatch for value=" + value);
        }
    }
}
