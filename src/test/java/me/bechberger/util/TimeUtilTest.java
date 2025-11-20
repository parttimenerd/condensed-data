package me.bechberger.util;

import static me.bechberger.util.TimeUtil.formatInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    public void testWithoutDate() {
        var instant = TimeUtil.parseInstant("12:34:56");
        assertEquals(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd 12:34:56")),
                TimeUtil.formatInstant(instant));
    }

    @Test
    public void testWithoutDateAndSeconds() {
        var instant = TimeUtil.parseInstant("12:34");
        assertEquals(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd 12:34:00")),
                TimeUtil.formatInstant(instant));
    }

    @Test
    public void testWithoutDateAndSeconds2() {
        var instant = TimeUtil.parseInstant("2:34");
        assertEquals(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd 02:34:00")),
                TimeUtil.formatInstant(instant));
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
        "3s,3s",
        "1.5s,1.5s",
        "100ms,0.1s",
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
        var expectedDateTime = LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        var expectedFormatted =
                expectedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

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
}
