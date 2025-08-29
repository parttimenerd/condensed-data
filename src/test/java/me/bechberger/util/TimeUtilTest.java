package me.bechberger.util;

import static me.bechberger.util.TimeUtil.formatInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
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
}
