package me.bechberger.jfr;

import java.util.List;
import java.util.stream.Collectors;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.EventReconstitutor.Reconstitutor;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;

/**
 * JMC-dependent reconstitution of combined JFR events into TypedValue objects. This class can be
 * dropped when building a JMC-free JAR.
 */
@JMCDependent
public class JFREventTypedValueCombiner {

    /** Construct a TypedValue event from its fields */
    public static class TypedValueEventBuilder
            extends JFREventCombiner.EventBuilder<TypedValue, TypedValueEventBuilder> {

        private final WritingJFRReader jfrWriter;

        public TypedValueEventBuilder(
                ReadStruct combinedReadEvent, String eventTypeName, WritingJFRReader jfrWriter) {
            super(combinedReadEvent, eventTypeName);
            this.jfrWriter = jfrWriter;
        }

        @Override
        public TypedValue build() {
            var condensedType = jfrWriter.getCondensedType(getEventTypeName());
            ReadStruct reconstructed = new ReadStruct(checkType(condensedType), getMap());
            return jfrWriter.fromReadStruct(reconstructed);
        }
    }

    /**
     * Creates a TypedValue reconstitutor from an AbstractReconstitutor by wrapping its generic
     * reconstitute method with a TypedValueEventBuilder.
     */
    public static <C extends JFREventCombiner.AbstractCombiner<?, ?>>
            Reconstitutor<C, TypedValue> createTypedValueReconstitutor(
                    JFREventCombiner.AbstractReconstitutor<C> reconstitutor,
                    WritingJFRReader jfrWriter) {
        return new Reconstitutor<>() {
            @Override
            public String getEventTypeName() {
                return reconstitutor.getEventTypeName();
            }

            @Override
            public List<TypedValue> reconstitute(
                    StructType<?, ?> resultEventType, ReadStruct combinedReadEvent) {
                return reconstitutor.reconstitute(
                        resultEventType,
                        combinedReadEvent,
                        new TypedValueEventBuilder(
                                combinedReadEvent, reconstitutor.getEventTypeName(), jfrWriter));
            }
        };
    }

    /** Reconstitutes combined events back into individual TypedValue events for JFR writing */
    public static class JFREventTypedValuedReconstitutor extends EventReconstitutor<TypedValue> {

        public JFREventTypedValuedReconstitutor(WritingJFRReader jfrWriter) {
            super(
                    JFREventCombiner.getRecons().entrySet().stream()
                            .collect(
                                    Collectors.toMap(
                                            e -> e.getKey().getCombinedTypeName(),
                                            e ->
                                                    createTypedValueReconstitutor(
                                                            e.getValue(), jfrWriter))));
        }
    }
}
