package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.CombiningJFRReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for time-window filtering in EventFilter, including events with Long startTimes
 * (reconstituted combined events).
 */
public class TimeFilterDiagTest {

    /**
     * Verify that --end before recording start filters out ALL events, including those with Long
     * startTimes
     */
    @Test
    public void endFilterExcludesAllEventsIncludingLongTimestamps() {
        Path cjfr = Path.of("profile.cjfr");
        if (!cjfr.toFile().exists()) return;
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(
                        null, Instant.parse("2000-01-01T00:00:00Z"), null, null);
        var reader = CombiningJFRReader.fromPaths(List.of(cjfr), filter, true);
        int count = 0;
        ReadStruct event;
        while ((event = reader.readNextEvent()) != null) {
            count++;
        }
        assertEquals(
                0, count, "All events should be filtered out when --end is before recording start");
    }

    /**
     * Verify that --start before recording start returns ALL events (including Long-timestamped
     * ones)
     */
    @Test
    public void startFilterBeforeRecordingReturnsAllEvents() {
        Path cjfr = Path.of("profile.cjfr");
        if (!cjfr.toFile().exists()) return;
        // Count unfiltered events
        var readerAll = CombiningJFRReader.fromPaths(List.of(cjfr));
        int totalCount = 0;
        while (readerAll.readNextEvent() != null) totalCount++;

        // Now filter with permissive --start
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(
                        Instant.parse("2000-01-01T00:00:00Z"), null, null, null);
        var reader = CombiningJFRReader.fromPaths(List.of(cjfr), filter, true);
        int filteredCount = 0;
        while (reader.readNextEvent() != null) filteredCount++;

        assertEquals(
                totalCount,
                filteredCount,
                "Permissive --start before recording should return all events");
    }

    /** Verify that --duration filters correctly (Bug 183) */
    @Test
    public void durationFilterReducesEventCount() {
        Path cjfr = Path.of("profile.cjfr");
        if (!cjfr.toFile().exists()) return;
        // Count unfiltered events
        var readerAll = CombiningJFRReader.fromPaths(List.of(cjfr));
        int totalCount = 0;
        while (readerAll.readNextEvent() != null) totalCount++;

        // Filter with short duration from start of recording
        Instant recordingStart = Instant.parse("2025-12-05T11:12:21Z");
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(
                        recordingStart, null, Duration.ofMillis(100), null);
        var reader = CombiningJFRReader.fromPaths(List.of(cjfr), filter, true);
        int filteredCount = 0;
        while (reader.readNextEvent() != null) filteredCount++;

        assertTrue(
                filteredCount < totalCount,
                "100ms duration window should return fewer events than the full ~2s recording. "
                        + "Got "
                        + filteredCount
                        + " of "
                        + totalCount);
    }

    /** Zero duration should be rejected (Bug 184) */
    @Test
    public void zeroDurationIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createBasicFilter(
                                null, null, Duration.ZERO, null));
    }

    /** Negative duration should be rejected (Bug 185) */
    @Test
    public void negativeDurationIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createBasicFilter(
                                null, null, Duration.ofSeconds(-1), null));
    }

    /** Start after end should be rejected (Bug 186) */
    @Test
    public void startAfterEndIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createBasicFilter(
                                Instant.parse("2025-12-05T12:12:23Z"),
                                Instant.parse("2025-12-05T12:12:21Z"),
                                null,
                                null));
    }
}
