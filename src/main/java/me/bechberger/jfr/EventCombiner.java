package me.bechberger.jfr;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.types.StructType;

/**
 * Idea: Allows to combine multiple events into one to reduce the amount of data written to the
 * stream
 *
 * <p>For the reconstitution of events, we also write a backing event type to the output stream and
 * use this then later to recreate the events
 */
public abstract class EventCombiner {

    private static final String EVENT_COMBINER_TYPE_NAME = "condensed.EventCombiner";

    /**
     * Can have state
     *
     * @param <C> small combination token to identify combinable events
     * @param <S> state combined data of the events
     */
    public interface Combiner<C, S> {

        /**
         * @return the type of the combined state
         */
        StructType<S, ?> createCombinedStateType(CondensedOutputStream out, EventType eventType);

        /** Create a token for the event instance */
        C createToken(RecordedEvent event);

        /**
         * Create the initial state for the event, when no state for the token is present
         *
         * <p>Be sure to add the event
         */
        S createInitialState(C token, RecordedEvent event);

        /**
         * Combine the event with the current state, modifying the state
         *
         * @param token token for the event
         * @param state current state associated with the token
         * @param event event to combine
         */
        void combine(C token, S state, RecordedEvent event);
    }

    /** Data and state for a single combiner */
    private static class CombinerData<C, S> {
        private final Combiner<C, S> combiner;
        private final EventType eventType;
        private final CondensedOutputStream out;
        private final BiConsumer<StructType<?, ?>, ?> stateWriter;
        private final Cache<C, S> statePerToken;
        private StructType<S, ?> combinedStateType;

        private CombinerData(
                Combiner<C, S> combiner,
                EventType eventType,
                int cacheSize,
                CondensedOutputStream out,
                BiConsumer<StructType<?, ?>, ?> stateWriter) {
            this.combiner = combiner;
            this.eventType = eventType;
            this.out = out;
            this.stateWriter = stateWriter;
            this.statePerToken =
                    new Cache<>(cacheSize) {
                        public void onRemove(C key, S value) {
                            write(value);
                        }
                    };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void write(S state) {
            if (combinedStateType == null) {
                combinedStateType = combiner.createCombinedStateType(out, eventType);
            }
            ((BiConsumer) stateWriter).accept(combinedStateType, state);
        }

        private void write() {
            statePerToken.removeAll();
        }

        private void processEvent(RecordedEvent event) {
            var token = combiner.createToken(event);
            // if state is present, check whether it should be cleared and written down
            // if not cleared, combine with current event
            // if not present or cleared, create new
            var state = statePerToken.get(token);
            if (state == null) {
                state = combiner.createInitialState(token, event);
                statePerToken.put(token, state);
            } else {
                combiner.combine(token, state, event);
            }
        }
    }

    final CondensedOutputStream out;
    final int cacheSize;
    private final Map<String, CombinerData<?, ?>> combinersPerType = new HashMap<>();
    private final Set<String> checkedEventTypes = new HashSet<>();

    /**
     * Create a new event combiner
     *
     * @param out output stream to write the combined state to
     * @param cacheSize maximum number of states to keep in memory per combiner before writing them
     *     to the stream
     */
    public EventCombiner(CondensedOutputStream out, int cacheSize) {
        this.out = out;
        this.cacheSize = cacheSize;
    }

    public abstract void stateWriter(StructType<?, ?> type, Object state);

    /**
     * Add a combiner for a specific event type if it is not already present
     *
     * @param eventType event type to combine
     * @param combinerSupplier supplier for the combiner to use
     * @param reconstitutedTypeWriter writer for the reconstituted type (e.g., using {@link
     *     BasicJFRWriter#writeOutEventTypeIfNeeded(EventType)}
     */
    public void putIfNotThere(
            EventType eventType,
            Supplier<Combiner<?, ?>> combinerSupplier,
            Runnable reconstitutedTypeWriter) {
        if (!combinersPerType.containsKey(eventType.getName())) {
            put(eventType, combinerSupplier.get(), reconstitutedTypeWriter);
        }
    }

    /**
     * Add a combiner for a specific event type
     *
     * @param eventType event type to combine
     * @param combiner combiner to use
     * @throws IllegalArgumentException if a combiner for the event type already exists
     */
    public void put(
            EventType eventType, Combiner<?, ?> combiner, Runnable reconstitutedTypeWriter) {
        if (combinersPerType.containsKey(eventType.getName())) {
            throw new IllegalArgumentException("Combiner for " + eventType + " already exists");
        }
        combinersPerType.put(
                eventType.getName(),
                new CombinerData<>(combiner, eventType, cacheSize, out, this::stateWriter));
        reconstitutedTypeWriter.run();
    }

    abstract void processNewEventType(EventType eventType);

    /**
     * Process the event and emit the combined state if necessary
     *
     * @param event event to process
     * @return true if the event was processed and false if it should be written to the stream
     */
    public boolean processEvent(RecordedEvent event) {
        if (!checkedEventTypes.contains(event.getEventType().getName())) {
            processNewEventType(event.getEventType());
            checkedEventTypes.add(event.getEventType().getName());
        }
        var combinerData = combinersPerType.get(event.getEventType().getName());
        if (combinerData == null) {
            return false;
        }
        combinerData.processEvent(event);
        return true;
    }

    /** Close the combiner and write the remaining state to the stream */
    public void close() {
        combinersPerType.values().forEach(CombinerData::write);
    }
}
