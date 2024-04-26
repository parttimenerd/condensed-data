package me.bechberger.jfr;

import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.TypeCollection;

/** Universe that contains the required state like the start time */
class Universe {

    private StructType<Universe, Universe> _type;

    private long startTimeNanos = -1;

    public Universe() {}

    public StructType<Universe, Universe> getStructType(TypeCollection typeCollection) {
        if (_type == null) {
            _type = TypeUtil.createStructWithPrimitiveFields(typeCollection, Universe.class);
        }
        return _type;
    }

    public Universe(long startTimeNanos) {
        this.startTimeNanos = startTimeNanos;
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
    }
}
