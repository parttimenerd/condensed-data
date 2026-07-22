package me.bechberger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import org.jetbrains.annotations.Nullable;

public interface JFRReader {

    @Nullable
    ReadStruct readNextEvent();

    default List<ReadStruct> readAll() {
        var result = new ArrayList<ReadStruct>();
        ReadStruct event;
        while ((event = readNextEvent()) != null) {
            result.add(event);
        }
        return result;
    }

    StartMessage getStartMessage();

    default Duration getDuration() {
        var start = getStartTime();
        var end = getEndTime();
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }

    Instant getStartTime();

    Instant getEndTime();

    /**
     * The source recording's {@code gmtOffset} (milliseconds east of UTC) captured at condense
     * time, or {@code Long.MIN_VALUE} when unknown. Used at inflate to restore the recording's
     * original timezone in the JFR metadata region.
     */
    default long getGmtOffsetMillis() {
        return Long.MIN_VALUE;
    }

    CondensedInputStream getInputStream();
}
