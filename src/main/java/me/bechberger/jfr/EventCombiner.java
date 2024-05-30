package me.bechberger.jfr;

import java.util.*;
import java.util.function.BiConsumer;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;

/**
 * Idea: Allows to combine multiple events into one to reduce the amount of data written to the
 * stream
 */
public abstract class EventCombiner {

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

        /** Create the initial state for the event, when no state for the token is present */
        S createInitialState(C token, RecordedEvent event);

        /**
         * Combine the event with the current state, modifying the state
         *
         * @param token token for the event
         * @param state current state associated with the token
         * @param event event to combine
         */
        void combine(C token, S state, RecordedEvent event);

        /**
         * @return true if the new token differs enough from the state to clear it and write the
         *     combined state event
         */
        boolean canClear(S state, C newToken);

        /** Reconstitute the combined state into a list of events */
        List<ReadStruct> reconstitute(
                StructType<ReadStruct, ReadStruct> eventType, ReadStruct combinedReadEvent);
    }

    /** Data and state for a single combiner */
    private static class CombinerData<C, S> {
        private final EventType eventType;
        private final Combiner<C, S> combiner;
        private final Map<C, S> statePerToken = new HashMap<>();
        private StructType<S, ?> combinedStateType;

        private CombinerData(Combiner<C, S> combiner, EventType eventType) {
            this.combiner = combiner;
            this.eventType = eventType;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void write(
                CondensedOutputStream out, BiConsumer<StructType<?, ?>, ?> stateWriter, S state) {
            if (combinedStateType == null) {
                combinedStateType = combiner.createCombinedStateType(out, eventType);
            }
            ((BiConsumer) stateWriter).accept(combinedStateType, state);
        }

        private void write(CondensedOutputStream out, BiConsumer<StructType<?, ?>, ?> stateWriter) {
            statePerToken.forEach(
                    (token, state) -> {
                        write(out, stateWriter, state);
                    });
        }

        private void processEvent(
                CondensedOutputStream out,
                BiConsumer<StructType<?, ?>, ?> stateWriter,
                RecordedEvent event) {
            var token = combiner.createToken(event);
            // if state is present, check whether it should be cleared and written down
            // if not cleared, combine with current event
            // if not present or cleared, create new
            var state = statePerToken.get(token);
            if (state == null) {
                state = combiner.createInitialState(token, event);
                statePerToken.put(token, state);
            } else if (combiner.canClear(state, token)) {
                write(out, stateWriter, state);
                state = combiner.createInitialState(token, event);
                statePerToken.put(token, state);
            } else {
                combiner.combine(token, state, event);
            }
        }
    }

    final CondensedOutputStream out;
    private final Map<String, CombinerData<?, ?>> combinersPerType = new HashMap<>();
    private final Set<String> checkedEventTypes = new HashSet<>();

    /** Create a new event combiner */
    public EventCombiner(CondensedOutputStream out) {
        this.out = out;
    }

    public abstract void stateWriter(StructType<?, ?> type, Object state);

    public void put(EventType eventType, Combiner<?, ?> combiner) {
        if (combinersPerType.containsKey(eventType.getName())) {
            throw new IllegalArgumentException("Combiner for " + eventType + " already exists");
        }
        combinersPerType.put(eventType.getName(), new CombinerData<>(combiner, eventType));
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
        combinerData.processEvent(out, this::stateWriter, event);
        return true;
    }

    /** Close the combiner and write the remaining state to the stream */
    public void close() {
        combinersPerType
                .values()
                .forEach(combinerData -> combinerData.write(out, this::stateWriter));
    }

    /**
     * Read and reconstitute the combined state into a list of events, assumes that the struct type
     * name is equal to the event type name
     */
    public List<ReadStruct> readAndReconstitute(
            StructType<ReadStruct, ReadStruct> eventType, ReadStruct struct) {
        var combinerData = combinersPerType.get(struct.getType().getName());
        if (combinerData == null) {
            throw new IllegalArgumentException("No combiner for " + struct.getType().getName());
        }
        return combinerData.combiner.reconstitute(eventType, struct);
    }

    public abstract boolean isEventStateWrapperType(StructType<?, ?> type);
}
