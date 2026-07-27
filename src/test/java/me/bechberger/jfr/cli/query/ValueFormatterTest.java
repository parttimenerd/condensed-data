package me.bechberger.jfr.cli.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ValueFormatter}, focused on the bit-rate rendering used by the
 * network-utilization view (a field carrying both {@code jdk.jfr.DataAmount(BITS)} and {@code
 * jdk.jfr.Frequency} is a bits-per-second rate: binary scaling, "bps"/"kbps"/… suffix).
 */
class ValueFormatterTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0 bps",
        "512, 512 bps",
        "1023, 1023 bps",
        "1024, 1.0 kbps",
        "2560, 2.5 kbps",
        "43929, 42.9 kbps",
        "834253, 814.7 kbps",
        "1486168, 1.4 Mbps",
        "1073741824, 1.0 Gbps"
    })
    void formatBitrate(long bits, String expected) {
        assertEquals(expected, ValueFormatter.formatBitrate(bits));
    }

    @Test
    void formatBitrateNegative() {
        assertEquals("-1.0 kbps", ValueFormatter.formatBitrate(-1024));
    }

    @Test
    void bitrateDispatchesThroughFormat() {
        assertEquals("2.5 kbps", ValueFormatter.format(2560L, null, ColumnType.Kind.BITRATE));
    }

    @Test
    void memoryDispatchDistinctFromBitrate() {
        // Same magnitude, MEMORY vs BITRATE must diverge in unit only.
        assertEquals("2.5 kB", ValueFormatter.format(2560L, null, ColumnType.Kind.MEMORY));
        assertEquals("2.5 kbps", ValueFormatter.format(2560L, null, ColumnType.Kind.BITRATE));
    }

    @ParameterizedTest
    @CsvSource({"0, 0 bytes", "512, 512 bytes", "1024, 1.0 kB", "1048576, 1.0 MB"})
    void formatMemory(long bytes, String expected) {
        assertEquals(expected, ValueFormatter.formatMemory(bytes));
    }

    /**
     * jfr view groups plain integer counts with the locale thousands separator; native rendering
     * uses Locale.ROOT so the separator is a comma (the only, documented, locale difference vs a
     * German oracle). Bug 295: cjfr previously emitted no grouping at all (3242 vs jfr's 3.242).
     */
    @ParameterizedTest
    @CsvSource({
        "0, 0",
        "999, 999",
        "1000, '1,000'",
        "3242, '3,242'",
        "10310, '10,310'",
        "1234567, '1,234,567'",
        "-4200, '-4,200'"
    })
    void formatPlainIntegerGroups(long value, String expected) {
        assertEquals(expected, ValueFormatter.format((Object) value, null));
    }

    @Test
    void formatFrequencyHasNoDigitGrouping() {
        // jfr renders Frequency columns without a thousands separator (verified against the
        // cpu-tsc view: "Fast Time Frequency: 1000000000 Hz", not "1,000,000,000 Hz"), unlike
        // plain integer counts which are grouped.
        assertEquals("2600 Hz", ValueFormatter.format(2600L, null, ColumnType.Kind.FREQUENCY));
    }

    @Test
    void formatWholeDoubleGroups() {
        // A whole-valued double (e.g. a summed count) renders as a grouped integer, not "12345.0".
        assertEquals("12,345", ValueFormatter.format(12345.0, null));
    }
}
