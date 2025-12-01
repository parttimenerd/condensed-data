package me.bechberger.jfr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A registry of JFR types for which a reduced version exists,
 * so that missing fields can be added
 * <p>
 * The types need to be common JFR types
 * <p>
 * Used by {@link me.bechberger.jfr.BasicJFRWriter} during writing
 * and by {@link me.bechberger.jfr.WritingJFRReader} during writing JFR files
 * <p>
 * <b>For now it only supports primitive fields</b>
 */
public class ReducedJFRTypes {

    /**
     * A field that has been removed from a JFR type
     */
    public sealed interface RemovedField {
        String fieldName();

        /**
         * Condition under which the field should be removed when writing the reduced type
         */
        Predicate<Configuration> conditionForRemoval();
    }

    public record RemovedPrimitiveField(String fieldName, Predicate<Configuration> conditionForRemoval) implements RemovedField {
    }

    public static class ReducedTypeDefinition {
        private final String typeName;
        private final List<RemovedField> removedFields;

        public ReducedTypeDefinition(String typeName, List<RemovedField> removedFields) {
            this.typeName = typeName;
            this.removedFields = removedFields;
        }

        public List<RemovedField> getRemovedFields(Configuration configuration, boolean ignoreJFRHandledFields) {
            return removedFields.stream()
                    .filter(f -> f.conditionForRemoval().test(configuration))
                    .filter(f -> !ignoreJFRHandledFields)
                    .toList();
        }
    }

    public static final Map<String, ReducedTypeDefinition> REDUCED_JFR_TYPES = Map.of("jdk.types.StackFrame",
            new ReducedTypeDefinition(
                    "jdk.types.StackFrame",
                    List.of(
                            new RemovedPrimitiveField(
                                    "bytecodeIndex",
                                    Configuration::removeBCIAndLineNumberFromStackFrames),
                            new RemovedPrimitiveField(
                                    "lineNumber",
                                    Configuration::removeBCIAndLineNumberFromStackFrames),
                            new RemovedPrimitiveField(
                                    "type",
                                    Configuration::removeTypeInformationFromStackFrames
                            )
                    )
            )
    );

    public static Set<String> getRemovedFields(String typeName, Configuration configuration, boolean ignoreJFRHandledFields) {
        ReducedTypeDefinition def = REDUCED_JFR_TYPES.get(typeName);
        if (def == null) {
            return Set.of();
        }
        return def.getRemovedFields(configuration, ignoreJFRHandledFields).stream()
                .map(RemovedField::fieldName)
                .collect(java.util.stream.Collectors.toSet());
    }
}