package me.bechberger.jfr.cli;

import java.util.Iterator;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import org.jetbrains.annotations.NotNull;

public class EventCompletionCandidates implements Iterable<String> {
    private EventCompletionCandidates() {}

    @Override
    public @NotNull Iterator<String> iterator() {
        return FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .map(EventType::getName)
                .sorted()
                .iterator();
    }
}
