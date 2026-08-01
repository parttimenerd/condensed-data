package me.bechberger.jfr.cli.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
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

    /**
     * Bug 363: a sub-second duration that 3-sig-fig-rounds to ≥ 1 s must use the seconds unit, not
     * the milliseconds unit. Previously, a 999.5 ms duration would produce "1000 ms" because the ms
     * branch was taken (nanos < 1_000_000_000), then threeSigFigs(999.5) rounded to "1000". Oracle
     * renders this as "1.00 s".
     */
    @Test
    void timespanNearOneSSecondRoundsToSeconds() {
        // 999_500_000 ns = 999.5 ms → 3-sig-fig rounds to 1.00 s
        assertEquals("1.00 s", ValueFormatter.formatTimespan(Duration.ofNanos(999_500_000)));
        // 999_000_000 ns = 999 ms → 3 sig figs = "999 ms", stays in ms
        assertEquals("999 ms", ValueFormatter.formatTimespan(Duration.ofNanos(999_000_000)));
        // Exactly 1 s
        assertEquals("1.00 s", ValueFormatter.formatTimespan(Duration.ofNanos(1_000_000_000)));
    }

    /**
     * Bug 364: FREQUENCY ColumnType in views used n.longValue() which truncated float frequencies.
     * Bug 361 fixed the print path; this test covers the view/ValueFormatter path.
     */
    @Test
    void formatFrequencyFloatPreservesDecimal() {
        // Float frequency with decimal part must not be truncated
        assertEquals(
                "8975.555 Hz", ValueFormatter.format(8975.555f, null, ColumnType.Kind.FREQUENCY));
        // Integer-valued float still renders without decimal
        assertEquals("1000 Hz", ValueFormatter.format(1000.0f, null, ColumnType.Kind.FREQUENCY));
        // Double frequency
        assertEquals("2600.5 Hz", ValueFormatter.format(2600.5, null, ColumnType.Kind.FREQUENCY));
    }

    /**
     * Bug 365: jfr view abbreviates lambda method parameters to "(...)"; cjfr previously showed the
     * full decoded parameter list. The lambda$ check is in formatMethod (view path only; the print
     * path shows full params in stack traces per oracle behaviour).
     *
     * <p>decodeParams itself is tested here to verify the underlying decoder still works for
     * non-lambda methods.
     */
    @ParameterizedTest
    @CsvSource({
        "(ILjava/lang/Object;ZZ)V, 'int, Object, boolean, boolean'",
        "(Ljava/lang/String;)V, String",
        "()V, ''",
        "([I)V, int[]",
        "([Ljava/lang/Object;)V, Object[]"
    })
    void decodeParamsNonLambda(String descriptor, String expected) {
        assertEquals(expected, ValueFormatter.decodeParams(descriptor));
    }

    /**
     * Bug 366: jfr view uses HALF_EVEN rounding for double flag values; Java String.format uses
     * HALF_UP. The divergence is observable for halfway values like InitialRAMPercentage=1.5625
     * (HALF_UP→1.563, HALF_EVEN→1.562). Fixed by switching formatDouble to BigDecimal HALF_EVEN.
     */
    @Test
    void formatDoubleHalfEvenRounding() {
        // 1.5625 is exactly halfway between 1.562 and 1.563; oracle shows 1.562 (HALF_EVEN)
        assertEquals("1.562", ValueFormatter.format(1.5625, null));
        // Verify common whole-valued doubles still render as integers
        assertEquals("1", ValueFormatter.format(1.0, null));
        assertEquals("0.5", ValueFormatter.format(0.5, null));
    }
}
