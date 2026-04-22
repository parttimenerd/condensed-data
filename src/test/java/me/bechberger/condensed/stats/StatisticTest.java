package me.bechberger.condensed.stats;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class StatisticTest {

    /**
     * Bug: Statistic.bytes is an int field, so it overflows for files larger than 2 GB. This is the
     * same class of bug as CountingOutputStream.count (previously fixed).
     *
     * <p>The record(int bytes) method does: this.bytes += bytes After recording more than
     * Integer.MAX_VALUE bytes, the count wraps negative.
     *
     * <p>Expected: getBytes() returns correct count for >2GB Actual: getBytes() wraps negative due
     * to int overflow
     */
    @Test
    public void testBytesDoNotOverflow() {
        var statistic = new Statistic();

        // Simulate recording slightly more than Integer.MAX_VALUE bytes
        // We can't actually record 2GB, but we can call record() with large values
        int bigChunk = Integer.MAX_VALUE / 2 + 1; // ~1.07 GB
        statistic.record(bigChunk);
        statistic.record(bigChunk);

        // Total should be > Integer.MAX_VALUE (about 2.15 GB)
        long expectedTotal = 2L * bigChunk;
        assertTrue(
                statistic.getBytes() > 0,
                "Statistic.getBytes() should not overflow to negative, but got: "
                        + statistic.getBytes());
        assertEquals(
                expectedTotal,
                statistic.getBytes(),
                "Statistic.getBytes() should accurately track bytes beyond 2GB");
    }

    /**
     * Bug: SubStatistic fields (count, bytes, strings, stringBytes, etc.) are also int, with the
     * same overflow risk for large files.
     */
    @Test
    public void testSubStatisticBytesDoNotOverflow() {
        var statistic = new Statistic();
        statistic.setModeAndCount(WriteMode.INSTANCE);

        int bigChunk = Integer.MAX_VALUE / 2 + 1;
        statistic.record(bigChunk);
        statistic.record(bigChunk);

        // The toPrettyString() uses %10d format, which can print negative values
        String pretty = statistic.toPrettyString();
        assertFalse(
                pretty.contains("-"),
                "toPrettyString() should not contain negative values from overflow:\n" + pretty);
    }
}
