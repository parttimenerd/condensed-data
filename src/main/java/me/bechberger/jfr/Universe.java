package me.bechberger.jfr;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.TypeCollection;

/** Universe that contains the required state like the start time */
public class Universe {

    private StructType<Universe, Universe> _type;

    private long startTimeNanos = -1;

    private long lastStartTimeNanos = -1;

    public Universe() {}

    public StructType<Universe, Universe> getStructType(TypeCollection typeCollection) {
        if (_type == null) {
            _type = TypeUtil.createStructWithPrimitiveFields(typeCollection, Universe.class);
        }
        return _type;
    }

    public Universe(long startTimeNanos, long lastStartTimeNanos) {
        this.startTimeNanos = startTimeNanos;
        this.lastStartTimeNanos = lastStartTimeNanos;
    }

    public void setStartTimeNanos(long startTimeNanos) {
        assert this.startTimeNanos == -1;
        this.startTimeNanos = startTimeNanos;
    }

    public long getStartTimeNanos() {
        if (startTimeNanos == -1) {
            throw new IllegalStateException("Start time is not set");
        }
        return startTimeNanos;
    }

    public void update(Universe other) {
        if (other.startTimeNanos != -1) {
            startTimeNanos = other.startTimeNanos;
        }
        if (other.lastStartTimeNanos != -1) {
            lastStartTimeNanos = other.lastStartTimeNanos;
        }
    }

    public void setLastStartTimeNanos(long lastStartTimeNanos) {
        this.lastStartTimeNanos = lastStartTimeNanos;
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