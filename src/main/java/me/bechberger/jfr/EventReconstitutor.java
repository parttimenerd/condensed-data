package me.bechberger.jfr;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.EventCombiner.Combiner;

/**
 * Reconstitute combined JFR events
 *
 * <p>Important: You need to have a reconstitutor for each combined event type
 *
 * @param <E> type of the reconstituted event
 */
public class EventReconstitutor<E> {

    /**
     * Reconstitutes events from the read struct of the combined state
     *
     * @param <C> combiner for which to reconstitute the events
     */
    public interface Reconstitutor<C extends Combiner<?, ?>, E> {

        /** Get the name of the result event type */
        String getEventTypeName();

        /**
         * Reconstitute the combined state into a list of events
         *
         * @param resultEventType result event type, for which instances should be created
         */
        List<E> reconstitute(StructType<?, ?> resultEventType, ReadStruct combinedReadEvent);
    }

    private final Map<String, Reconstitutor<?, E>> reconstitutorPerCombinedType;

    public EventReconstitutor(Map<String, Reconstitutor<?, E>> reconstitutorPerCombinedType) {
        this.reconstitutorPerCombinedType = reconstitutorPerCombinedType;
    }

    public void put(String combinedEventType, Reconstitutor<?, E> reconstitutor) {
        if (reconstitutorPerCombinedType.containsKey(combinedEventType)) {
            throw new IllegalArgumentException(
                    "Reconstitutor for combined event type "
                            + combinedEventType
                            + " already exists");
        }
        reconstitutorPerCombinedType.put(combinedEventType, reconstitutor);
    }

    private boolean isCombinedEvent(String typeName) {
        return reconstitutorPerCombinedType.containsKey(typeName);
    }

    /** Is the passed event a combined backing event? */
    public boolean isCombinedEvent(ReadStruct event) {
        return isCombinedEvent(event.getType().getName());
    }

    private List<E> reconstitute(
            ReadStruct combinedReadEvent, Function<String, StructType<?, ?>> typeProvider) {
        var typeName = combinedReadEvent.getType().getName();
        if (!isCombinedEvent(typeName)) {
            throw new IllegalArgumentException(
                    "Event type " + typeName + " is not a combined event");
        }
        var reconstitutor = reconstitutorPerCombinedType.get(typeName);
        return reconstitutor.reconstitute(
                typeProvider.apply(reconstitutor.getEventTypeName()), combinedReadEvent);
    }

    /** Reconstitute the combined event */
    public List<E> reconstitute(CondensedInputStream in, ReadStruct combinedReadEvent) {
        return reconstitute(
                combinedReadEvent,
                n -> {
                    var typeOrNull = in.getTypeCollection().getTypeOrNull(n);
                    if (typeOrNull == null) {
                        throw new IllegalArgumentException("Type " + n + " not found");
                    }
                    if (!(typeOrNull instanceof StructType)) {
                        throw new IllegalArgumentException("Type " + n + " is not a struct type");
                    }
                    return (StructType<?, ?>) typeOrNull;
                });
    }
}
