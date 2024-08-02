package me.bechberger.util;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static me.bechberger.util.TimeUtil.formatInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TimeUtilTest {

    @Property
    public void testInstantRoundtrip(@ForAll @LongRange(min = 0) long seconds) {
        if (seconds > Instant.MAX.getEpochSecond()) {
            return;
        }
        var instant = Instant.ofEpochSecond(seconds);
        var formatted = formatInstant(instant);
        var parsed = TimeUtil.parseInstant(formatted);
        assertEquals(instant, parsed, "Instant roundtrip failed: initial=" + instant + ", formatted=" + formatted);
    }

    @Property
    public void testDurationRoundtrip(@ForAll @LongRange(min = 0) long seconds) {
        var duration = TimeUtil.parseDuration(seconds + "s");
        var formatted = TimeUtil.formatDuration(duration);
        var parsed = TimeUtil.parseDuration(formatted);
        assertEquals(duration, parsed, "Duration roundtrip failed: initial=" + duration + ", formatted=" + formatted);
    }

    @Test
    public void testDuration() {
        var duration = TimeUtil.parseDuration("3s");
        assertEquals(3, duration.getSeconds());
    }
}
