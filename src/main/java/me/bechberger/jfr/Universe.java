package me.bechberger.jfr;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.TypeCollection;

/** Universe that contains the required state like the start time */
public class Universe {

    /**
     * Sentinel for {@link #gmtOffsetSeconds} meaning "the source recording's timezone offset was
     * not captured" (e.g. an old CJFR file written before timezone preservation existed, or a
     * source JFR without a region gmtOffset). Distinct from a genuine 0 offset (UTC).
     */
    public static final long GMT_OFFSET_UNSET = Long.MIN_VALUE;

    private StructType<Universe, Universe> _type;

    private long startTimeNanos = -1;

    private long lastStartTimeNanos = -1;

    /**
     * Raw {@code gmtOffset} value (milliseconds east of UTC, as JFR stores it in the metadata
     * region) of the source recording, captured at condense time and re-injected at inflate so
     * {@code jfr print} renders the recording's original local zone. {@link #GMT_OFFSET_UNSET} when
     * unknown.
     */
    private long gmtOffsetMillis = GMT_OFFSET_UNSET;

    public Universe() {}

    public StructType<Universe, Universe> getStructType(TypeCollection typeCollection) {
        if (_type == null) {
            _type =
                    StructReflectionUtil.createStructWithPrimitiveFields(
                            typeCollection, Universe.class);
        }
        return _type;
    }

    public Universe(long startTimeNanos, long lastStartTimeNanos) {
        this.startTimeNanos = startTimeNanos;
        this.lastStartTimeNanos = lastStartTimeNanos;
    }

    public Universe(long startTimeNanos, long lastStartTimeNanos, long gmtOffsetMillis) {
        this.startTimeNanos = startTimeNanos;
        this.lastStartTimeNanos = lastStartTimeNanos;
        this.gmtOffsetMillis = gmtOffsetMillis;
    }

    public void setStartTimeNanos(long startTimeNanos) {
        assert this.startTimeNanos == -1;
        this.startTimeNanos = startTimeNanos;
    }

    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    public void update(Universe other) {
        if (other.startTimeNanos != -1) {
            startTimeNanos = other.startTimeNanos;
        }
        if (other.lastStartTimeNanos != -1) {
            if (lastStartTimeNanos == -1) {
                lastStartTimeNanos = other.lastStartTimeNanos;
            } else {
                lastStartTimeNanos = Math.max(lastStartTimeNanos, other.lastStartTimeNanos);
            }
        }
        if (other.gmtOffsetMillis != GMT_OFFSET_UNSET) {
            gmtOffsetMillis = other.gmtOffsetMillis;
        }
    }

    public void setGmtOffsetMillis(long gmtOffsetMillis) {
        this.gmtOffsetMillis = gmtOffsetMillis;
    }

    public long getGmtOffsetMillis() {
        return gmtOffsetMillis;
    }

    public void setLastStartTimeNanos(long lastStartTimeNanos) {
        if (this.lastStartTimeNanos == -1) {
            this.lastStartTimeNanos = lastStartTimeNanos;
        } else {
            this.lastStartTimeNanos = Math.max(this.lastStartTimeNanos, lastStartTimeNanos);
        }
    }

    public long getLastStartTimeNanos() {
        return lastStartTimeNanos;
    }

    public Duration getDuration() {
        if (startTimeNanos == -1) {
            return Duration.ZERO;
        }
        return Duration.of(lastStartTimeNanos - startTimeNanos, ChronoUnit.NANOS);
    }
}
