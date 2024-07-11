package me.bechberger.jfr;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;

/**
 * Don't output events if this event has already been written
 *
 * <p>Useful, because e.g. {@code jdk.BooleanFlag} events are emitted every chunk, when the first
 * event would be sufficient.
 */
public class EventDeduplication {

    @FunctionalInterface
    public interface IsDuplicate {
        boolean check(RecordedEvent lastEvent, RecordedEvent newEvent);
    }

    public record Deduplicator(
            Function<RecordedEvent, Object> obtainToken, IsDuplicate isDuplicate) {
        public Deduplicator(IsDuplicate isDuplicate) {
            this(e -> true, isDuplicate);
        }
    }

    static class DeduplicationForEvent {
        private final String eventType;
        private final Deduplicator deduplicator;
        private final Map<Object, RecordedEvent> tokenToLastEvent = new HashMap<>();

        DeduplicationForEvent(String eventType, Deduplicator deduplicator) {
            this.eventType = eventType;
            this.deduplicator = deduplicator;
        }

        boolean isDuplicate(RecordedEvent event) {
            var token = deduplicator.obtainToken.apply(event);
            if (tokenToLastEvent.containsKey(token)) {
                var lastEvent = tokenToLastEvent.get(token);
                return deduplicator.isDuplicate.check(lastEvent, event);
            }
            return false;
        }

        void store(RecordedEvent event) {
            tokenToLastEvent.put(deduplicator.obtainToken().apply(event), event);
        }
    }

    private final Map<String, DeduplicationForEvent> deduplicators;

    public EventDeduplication(Map<String, Deduplicator> deduplicators) {
        this.deduplicators =
                deduplicators.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        Entry::getKey,
                                        e -> new DeduplicationForEvent(e.getKey(), e.getValue())));
    }

    public EventDeduplication() {
        this(new HashMap<>());
    }

    public void put(String eventType, Deduplicator deduplicator) {
        deduplicators.put(eventType, new DeduplicationForEvent(eventType, deduplicator));
    }

    public void put(
            String eventType,
            Function<RecordedEvent, Object> obtainToken,
            IsDuplicate isDuplicate) {
        put(eventType, new Deduplicator(obtainToken, isDuplicate));
    }

    public void put(
            String eventType, Function<RecordedEvent, Object> obtainToken, String comparedField) {
        put(
                eventType,
                obtainToken,
                (a, b) -> Objects.equals(a.getValue(comparedField), b.getValue(comparedField)));
    }

    public void put(String eventType, String tokenField, String... comparedFields) {
        put(
                eventType,
                e -> e.getValue(tokenField),
                (a, b) ->
                        Arrays.stream(comparedFields)
                                .allMatch(
                                        f -> {
                                            try {
                                                return Objects.equals(a.getValue(f), b.getValue(f));
                                            } catch (IllegalArgumentException e) {
                                                System.exit(1);
                                                throw e;
                                            }
                                        }));
    }

    public void putAll(Map<String, Deduplicator> deduplicators) {
        deduplicators.forEach(this::put);
    }

    /** Check whether the passed event is a duplicate and store it if it is not */
    public boolean recordAndCheckIfDuplicate(RecordedEvent newEvent) {
        String type = newEvent.getEventType().getName();
        var dedup = deduplicators.get(type);
        if (dedup == null) {
            return false;
        }
        if (dedup.isDuplicate(newEvent)) {
            return true;
        }
        dedup.store(newEvent);
        return false;
    }
}
