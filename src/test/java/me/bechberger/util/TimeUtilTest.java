package me.bechberger.util;

import static me.bechberger.util.TimeUtil.formatInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class TimeUtilTest {

    @Property
    public void testInstantRoundtrip(@ForAll @LongRange(min = 0) long seconds) {
        if (seconds > Instant.MAX.getEpochSecond()) {
            return;
        }
        var instant = Instant.ofEpochSecond(seconds);
        var formatted = formatInstant(instant);
        var parsed = TimeUtil.parseInstant(formatted);
        assertEquals(
                instant,
                parsed,
                "Instant roundtrip failed: initial=" + instant + ", formatted=" + formatted);
    }

    @Test
    public void testWithoutDateIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> TimeUtil.parseInstant("12:34:56"));
    }

    @Test
    public void testWithoutDateAndSecondsIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> TimeUtil.parseInstant("12:34"));
    }

    @Test
    public void testWithoutDateAndSeconds2IsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> TimeUtil.parseInstant("2:34"));
    }

    @Property
    public void testDurationRoundtrip(@ForAll @LongRange(min = 0) long seconds) {
        var duration = TimeUtil.parseDuration(seconds + "s");
        var formatted = TimeUtil.formatDuration(duration);
        var parsed = TimeUtil.parseDuration(formatted);
        assertEquals(
                duration,
                parsed,
                "Duration roundtrip failed: initial=" + duration + ", formatted=" + formatted);
    }

    @Test
    public void testDuration() {
        var duration = TimeUtil.parseDuration("3s");
        assertEquals(3, duration.getSeconds());
    }

    @ParameterizedTest
    @CsvSource({
        "3s,3.0s",
        "1.5s,1.5s",
        "100ms,100.0ms",
        "1500ms,1.5s",
        "1m30s,1m 30s",
        "1h,1h",
        "1h30m,1h 30m",
    })
    public void testDurations(String input, String expected) {
        var duration = TimeUtil.parseDuration(input);
        var formatted = TimeUtil.formatDuration(duration);
        assertEquals(expected, formatted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "3", "3x", "1h30x", "1h30m20", "1h30m20s10"})
    public void testInvalidDurations(String input) {
        try {
            TimeUtil.parseDuration(input);
        } catch (IllegalArgumentException e) {
            return; // expected
        }
        throw new AssertionError("Expected IllegalArgumentException for input: " + input);
    }

    /**
     * Test that formatInstant uses local timezone, not UTC
     *
     * <p>Regression test for issue where summary command was showing UTC timestamps instead of
     * local time, causing confusion when the local timezone differs from UTC.
     *
     * <p>Example: If local time is 11:31 in UTC+1, the summary should show 11:31, not 10:31 (which
     * is the UTC time).
     */
    @Test
    public void testFormatInstantUsesLocalTime() {
        // Create an instant for a known UTC time
        // 2025-11-19 10:31:12 UTC
        var instant = Instant.parse("2025-11-19T10:31:12Z");

        // Format the instant
        var formatted = formatInstant(instant);

        // Get what the formatted time should be in the system's local timezone
        var expectedDateTime = ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        var expectedFormatted =
                expectedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"));

        // Verify that the formatted time matches the local time, not UTC
        assertEquals(
                expectedFormatted,
                formatted,
                "formatInstant should use local timezone, not UTC. "
                        + "For instant "
                        + instant
                        + " (UTC: 2025-11-19 10:31:12), "
                        + "expected local time but got: "
                        + formatted);

        // Additional check: if we're in a timezone that's not UTC,
        // the formatted time should NOT be "2025-11-19 10:31:12"
        var systemOffset = ZoneId.systemDefault().getRules().getOffset(instant);
        if (!systemOffset.equals(ZoneOffset.UTC)) {
            // We're not in UTC, so the formatted time should differ from UTC time
            var utcTime = "2025-11-19 10:31:12";
            if (formatted.equals(utcTime)) {
                throw new AssertionError(
                        "formatInstant is using UTC instead of local time. "
                                + "System timezone is "
                                + ZoneId.systemDefault()
                                + " (offset: "
                                + systemOffset
                                + "), "
                                + "but formatted time matches UTC: "
                                + formatted);
            }
        }
    }

    // ========== Bug reproducer tests ==========

    /**
     * Bug: parseDuration uses (long)(value * 1_000_000_000L) which truncates toward zero. For
     * values where the double product is slightly below the true integer (due to IEEE 754
     * representation), this causes an off-by-one nanosecond error.
     *
     * <p>Example: "0.000001005s" → Double.parseDouble("0.000001005") * 1e9 ≈ 1004.9999... → (long)
     * truncates to 1004 instead of the correct 1005.
     *
     * <p>Fix: use Math.round() instead of (long) cast.
     */
    @ParameterizedTest
    @CsvSource({
        "0.000001005s, 1005",
        "0.000001008s, 1008",
        "0.00000192s, 1920",
        "1.001s, 1001000000",
        "1.0007s, 1000700000",
        "1.0009s, 1000900000",
    })
    public void testParseDurationTruncationBug(String input, long expectedNanos) {
        var duration = TimeUtil.parseDuration(input);
        assertEquals(
                expectedNanos,
                duration.toNanos(),
                "parseDuration(\""
                        + input
                        + "\") should give "
                        + expectedNanos
                        + " nanos but got "
                        + duration.toNanos()
                        + " (off by "
                        + (duration.toNanos() - expectedNanos)
                        + " due to (long) truncation)");
    }

    /**
     * Bug: formatDuration → parseDuration roundtrip fails for durations whose formatted
     * decimal-second representation triggers the (long) truncation issue.
     *
     * <p>Example: Duration.ofNanos(1005) → formatDuration → "0.000001005s" → parseDuration →
     * Duration.ofNanos(1004) ≠ original.
     */
    @ParameterizedTest
    @ValueSource(longs = {15, 30, 60, 1005, 1008, 1920, 1001000000})
    public void testDurationRoundtripWithNanosTruncationBug(long nanos) {
        var original = Duration.ofNanos(nanos);
        var formatted = TimeUtil.formatDuration(original);
        var parsed = TimeUtil.parseDuration(formatted);
        assertEquals(
                original,
                parsed,
                "Roundtrip failed for "
                        + nanos
                        + " nanos: formatted='"
                        + formatted
                        + "', parsed="
                        + parsed.toNanos()
                        + " nanos");
    }

    /**
     * Bug: formatDuration of a negative duration produces output like "-3s" which parseDuration
     * cannot parse (its regex requires \\d+ before the unit). This means negative durations cannot
     * roundtrip through format/parse.
     */
    @Test
    public void testNegativeDurationRoundtripBug() {
        var negativeDuration = Duration.ofSeconds(-3);
        var formatted = TimeUtil.formatDuration(negativeDuration);
        // This will throw IllegalArgumentException because "-3s" doesn't match the regex
        var parsed = TimeUtil.parseDuration(formatted);
        assertEquals(negativeDuration, parsed);
    }

    // ========== Additional coverage tests ==========

    /** Parse microsecond and nanosecond units */
    @ParameterizedTest
    @CsvSource({
        "500us, 500000",
        "1us, 1000",
        "1000us, 1000000",
        "100ns, 100",
        "1ns, 1",
        "1000ns, 1000",
    })
    public void testParseDurationSubMicrosecondUnits(String input, long expectedNanos) {
        var duration = TimeUtil.parseDuration(input);
        assertEquals(expectedNanos, duration.toNanos(), "parseDuration(\"" + input + "\") nanos");
    }

    /** Parse combined multi-unit duration strings */
    @ParameterizedTest
    @CsvSource({
        "1h30m20s, 5420000",
        "2h, 7200000",
        "1m500ms, 60500",
        "1h 30m, 5400000",
        "1h 0m 1s, 3601000",
    })
    public void testParseDurationCombinedUnits(String input, long expectedMillis) {
        var duration = TimeUtil.parseDuration(input);
        assertEquals(
                expectedMillis, duration.toMillis(), "parseDuration(\"" + input + "\") millis");
    }

    /** Parse fractional unit values */
    @ParameterizedTest
    @CsvSource({
        "1.5h, 5400",
        "0.5m, 30",
        "2.5s, 2",
    })
    public void testParseDurationFractionalUnits(String input, long expectedSeconds) {
        var duration = TimeUtil.parseDuration(input);
        assertEquals(
                expectedSeconds, duration.getSeconds(), "parseDuration(\"" + input + "\") seconds");
    }

    /** Duration parsing is case-insensitive */
    @ParameterizedTest
    @CsvSource({
        "1H30M, 1h 30m",
        "1H 30M, 1h 30m",
        "3S, 3.0s",
        "500MS, 500.0ms",
    })
    public void testParseDurationCaseInsensitive(String input, String expectedFormatted) {
        var duration = TimeUtil.parseDuration(input);
        assertEquals(expectedFormatted, TimeUtil.formatDuration(duration));
    }

    /** Whitespace handling in duration parsing */
    @Test
    public void testParseDurationWithWhitespace() {
        assertEquals(TimeUtil.parseDuration("1h30m"), TimeUtil.parseDuration("  1h  30m  "));
    }

    /** Duration.ZERO formatting */
    @Test
    public void testFormatDurationZero() {
        assertEquals("0s", TimeUtil.formatDuration(Duration.ZERO));
    }

    /** Verify "1m" is parsed as 1 minute (not confused with "ms") */
    @Test
    public void testParseDurationMinuteVsMillisecond() {
        assertEquals(Duration.ofMinutes(1), TimeUtil.parseDuration("1m"));
        assertEquals(Duration.ofMillis(1), TimeUtil.parseDuration("1ms"));
        assertNotEquals(TimeUtil.parseDuration("1m"), TimeUtil.parseDuration("1ms"));
    }

    /** parseInstant with full date-time and timezone offset roundtrips through formatInstant */
    @Test
    public void testParseInstantWithOffset() {
        var formatted = "2025-06-15 14:30:00+02:00";
        var instant = TimeUtil.parseInstant(formatted);
        // The instant should correspond to 12:30 UTC
        assertEquals(Instant.parse("2025-06-15T12:30:00Z"), instant);
    }

    /** parseInstant with date-time but no offset uses local timezone */
    @Test
    public void testParseInstantWithoutOffset() {
        var input = "2025-06-15 14:30:00";
        var instant = TimeUtil.parseInstant(input);
        // Should be interpreted as local time
        var expected =
                java.time.LocalDateTime.of(2025, 6, 15, 14, 30, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
        assertEquals(expected, instant);
    }

    /** formatDuration output for various Duration values */
    @ParameterizedTest
    @CsvSource({
        "0, 0s",
        "1, 1.0s",
        "60, 1m",
        "61, 1m 1s",
        "3600, 1h",
        "3661, 1h 1m 1s",
        "86400, 24h",
    })
    public void testFormatDurationFromSeconds(long seconds, String expected) {
        assertEquals(expected, TimeUtil.formatDuration(Duration.ofSeconds(seconds)));
    }

    /** Verify clamp behavior for extreme durations */
    @Test
    public void testClampDuration() {
        var twoYears = Duration.ofDays(730);
        var clamped = TimeUtil.clamp(twoYears);
        assertEquals(TimeUtil.MAX_DURATION_SECONDS, clamped.getSeconds());

        var negativeTwoYears = Duration.ofDays(-730);
        var clampedNeg = TimeUtil.clamp(negativeTwoYears);
        assertEquals(-TimeUtil.MAX_DURATION_SECONDS, clampedNeg.getSeconds());

        // Within range: unchanged
        var oneHour = Duration.ofHours(1);
        assertEquals(oneHour, TimeUtil.clamp(oneHour));
    }

    // ========== Bug 42/63/193/208: time-only format rejected ==========

    @ParameterizedTest
    @ValueSource(strings = {"12:34:56", "12:34", "2:34", "0:0:0", "23:59:59"})
    public void testTimeOnlyFormatIsRejected(String input) {
        var ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class, () -> TimeUtil.parseInstant(input));
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("Time-only format is not supported");
    }
}
