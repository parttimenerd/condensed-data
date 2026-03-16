package me.bechberger.jfr;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Enum linking combined event types to their original event type names. Used by both combiners and
 * reconstitutors so neither needs a direct reference to the other.
 */
public enum CombinedEventType {
    OBJECT_ALLOCATION_SAMPLE("jdk.combined.ObjectAllocationSample", "jdk.ObjectAllocationSample"),
    PROMOTE_OBJECT_IN_NEW_PLAB("jdk.combined.PromoteObjectInNewPLAB", "jdk.PromoteObjectInNewPLAB"),
    PROMOTE_OBJECT_OUTSIDE_PLAB(
            "jdk.combined.PromoteObjectOutsidePLAB", "jdk.PromoteObjectOutsidePLAB"),
    TENURING_DISTRIBUTION("jdk.combined.TenuringDistribution", "jdk.TenuringDistribution"),
    GC_PHASE_PAUSE_LEVEL1("jdk.combined.GCPhasePauseLevel1", "jdk.GCPhasePauseLevel1"),
    GC_PHASE_PAUSE_LEVEL2("jdk.combined.GCPhasePauseLevel2", "jdk.GCPhasePauseLevel2"),
    GC_PHASE_PARALLEL("jdk.combined.GCPhaseParallel", "jdk.GCPhaseParallel");

    private final String combinedTypeName;
    private final String originalTypeName;

    private static final Map<String, CombinedEventType> BY_COMBINED_NAME =
            Stream.of(values())
                    .collect(
                            Collectors.toMap(
                                    CombinedEventType::getCombinedTypeName, Function.identity()));

    CombinedEventType(String combinedTypeName, String originalTypeName) {
        this.combinedTypeName = combinedTypeName;
        this.originalTypeName = originalTypeName;
    }

    public String getCombinedTypeName() {
        return combinedTypeName;
    }

    public String getOriginalTypeName() {
        return originalTypeName;
    }

    public static CombinedEventType byCombinedName(String name) {
        return BY_COMBINED_NAME.get(name);
    }
}
