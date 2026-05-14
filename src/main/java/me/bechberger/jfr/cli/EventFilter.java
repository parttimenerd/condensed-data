package me.bechberger.jfr.cli;

import static me.bechberger.condensed.Util.toNanoSeconds;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import me.bechberger.jfr.cli.CLIUtils.InstantConverter;
import org.jetbrains.annotations.Nullable;

public interface EventFilter<C> {

    Logger LOGGER = Logger.getLogger(EventFilter.class.getName());

    /**
     * Gets startTime/endTime as Instant, handling both Instant and Long (nanoseconds) storage
     * (combined events store timestamps as Long).
     */
    static @Nullable Instant getInstant(ReadStruct event, String field) {
        Object val = event.get(field);
        if (val == null) {
            return null;
        }
        if (val instanceof Instant inst) {
            return inst;
        }
        if (val instanceof Long nanos) {
            return Instant.ofEpochSecond(0, nanos);
        }
        throw new ClassCastException("Cannot convert " + val.getClass().getName() + " to Instant");
    }

    /**
     * Checks if the filter matches the event.
     *
     * @param struct event to check
     * @return matched filter?
     */
    boolean test(ReadStruct struct, C context);

    void analyze(ReadStruct struct, C context);

    boolean isInformationGathering();

    C createContext();

    interface EventFilterInstance {
        boolean test(ReadStruct struct);
    }

    default EventFilterInstance createAnalyzeFilter(C context) {
        return struct -> {
            EventFilter.this.analyze(struct, context);
            return false;
        };
    }

    default EventFilterInstance createTestFilter(C context) {
        return struct -> EventFilter.this.test(struct, context);
    }

    interface SinglePhaseEventFilter<C> extends EventFilter<C> {
        @Override
        default void analyze(ReadStruct struct, C context) {
            analyze(struct);
        }

        default void analyze(ReadStruct struct) {}

        default C createContext() {
            return null;
        }

        @Override
        default boolean isInformationGathering() {
            return false;
        }
    }

    interface TwoPhaseEventFilter<C> extends EventFilter<C> {
        @Override
        default boolean isInformationGathering() {
            return true;
        }
    }

    default CombinedFilter and(EventFilter<?> other) {
        return new CombinedFilter(CombinedFilter.Operator.AND, this, other);
    }

    default CombinedFilter or(EventFilter<?> other) {
        return new CombinedFilter(CombinedFilter.Operator.OR, this, other);
    }

    /** Combines multiple filters with an operator */
    class CombinedFilter implements EventFilter<List<?>> {

        public enum Operator {
            AND,
            OR
        }

        private final Operator operator;
        private final EventFilter<?>[] filters;

        public CombinedFilter(Operator operator, EventFilter<?>... filters) {
            this.operator = operator;
            this.filters = filters;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public boolean test(ReadStruct struct, List<?> context) {
            for (int i = 0; i < filters.length; i++) {
                var testResult = ((EventFilter) filters[i]).test(struct, context.get(i));
                if (operator == Operator.AND && !testResult) {
                    return false;
                }
                if (operator == Operator.OR && testResult) {
                    return true;
                }
            }
            return operator == Operator.AND;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void analyze(ReadStruct struct, List<?> context) {
            for (int i = 0; i < filters.length; i++) {
                ((EventFilter) filters[i]).analyze(struct, context.get(i));
            }
        }

        @Override
        public boolean isInformationGathering() {
            for (EventFilter<?> filter : filters) {
                if (filter.isInformationGathering()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<?> createContext() {
            return Stream.of(filters).map(EventFilter::createContext).collect(Collectors.toList());
        }
    }

    class EventFilterOptionMixin {

        @Option(
                names = {"--start"},
                description = "The start time",
                converter = InstantConverter.class)
        Instant start;

        @Option(
                names = {"--end"},
                description = "The end time",
                converter = InstantConverter.class)
        Instant end;

        @Option(
                names = {"--duration"},
                description = "The duration, don't pass it if both start and end are passed",
                converter = DurationConverter.class)
        Duration duration;

        @Option(
                names = {"--events"},
                description = "The event types to include (repeatable, comma-separated)",
                split = ",")
        List<String> eventTypes;

        @Option(
                names = "--no-reconstitution",
                description = "Don't reconstitute the events, just read the combined events")
        boolean noReconstitution;

        @Option(
                names = "--gc-percentile",
                description =
                        "Filter out events that happened in the seconds before and after the GC's"
                            + " with >= n-th percentile duration, e.g. --gc-percentile 90 gives you"
                            + " all events before, during and after the longest 10% of GC's")
        int gcPercentile = 0;

        @Option(
                names = "--gc-percentile-context",
                description =
                        "The context to use for the GC percentile filter, e.g."
                                + " --gc-percentile-context 1m",
                converter = DurationConverter.class,
                defaultValue = "1m")
        Duration gcPercentileContext;

        /** Create a basic event filter that checks a time window and the event types */
        static @Nullable EventFilter<?> createBasicFilter(
                Instant start, Instant end, Duration duration, List<String> eventTypes) {
            if (start == null && end == null && duration == null && eventTypes == null) {
                return null;
            }
            if (duration != null && (duration.isNegative() || duration.isZero())) {
                throw new IllegalArgumentException("--duration must be positive, got: " + duration);
            }
            if (start != null && end != null && duration != null) {
                throw new IllegalArgumentException(
                        "Both start, end and duration are set; cannot specify all three together");
            }
            if (start != null && end != null && start.isAfter(end)) {
                throw new IllegalArgumentException(
                        "Start time " + start + " is after end time " + end);
            }
            if (start == null) {
                if (end == null) {
                    if (duration != null) {
                        throw new IllegalArgumentException("--duration requires --start or --end");
                    }
                    start = Instant.MIN;
                    end = Instant.MAX;
                } else if (duration != null) {
                    start = end.minus(duration);
                }
            } else if (end == null) {
                if (duration != null) {
                    end = start.plus(duration);
                }
            }
            Instant fixedStart = start;
            Instant fixedEnd = end;
            if (eventTypes == null) {
                return (SinglePhaseEventFilter<Void>)
                        (event, context) -> matchesTimeWindow(event, fixedStart, fixedEnd);
            }

            // Warn about wildcard characters in event type names
            for (String eventType : eventTypes) {
                if (eventType.contains("*") || eventType.contains("?")) {
                    System.err.println(
                            "Warning: Wildcard patterns are not supported in --events."
                                    + " '"
                                    + eventType
                                    + "' will be treated as a literal name.");
                }
            }

            record BasicFilterContext(Set<String> knownEventTypes) {}

            // Build a lowercase set for case-insensitive matching
            Set<String> eventTypesLower =
                    eventTypes.stream().map(String::toLowerCase).collect(Collectors.toSet());

            return new TwoPhaseEventFilter<BasicFilterContext>() {
                @Override
                public boolean test(ReadStruct struct, BasicFilterContext context) {
                    return matchesTimeWindow(struct, fixedStart, fixedEnd)
                            && (eventTypes.contains(struct.getType().getName())
                                    || eventTypesLower.contains(
                                            struct.getType().getName().toLowerCase()));
                }

                @Override
                public void analyze(ReadStruct struct, BasicFilterContext context) {
                    context.knownEventTypes().add(struct.getType().getName());
                }

                @Override
                public BasicFilterContext createContext() {
                    return new BasicFilterContext(new HashSet<>());
                }

                @Override
                public EventFilterInstance createTestFilter(BasicFilterContext context) {
                    List<String> unknownEventTypes =
                            eventTypes.stream()
                                    .filter(
                                            eventType ->
                                                    !context.knownEventTypes().contains(eventType)
                                                            && context.knownEventTypes().stream()
                                                                    .noneMatch(
                                                                            k ->
                                                                                    k
                                                                                            .equalsIgnoreCase(
                                                                                                    eventType)))
                                    .distinct()
                                    .sorted()
                                    .toList();
                    if (!unknownEventTypes.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Unknown event type(s): "
                                        + String.join(", ", unknownEventTypes)
                                        + ". Known event types include: "
                                        + context.knownEventTypes().stream()
                                                .sorted()
                                                .limit(10)
                                                .collect(Collectors.joining(", ")));
                    }
                    return struct -> test(struct, context);
                }
            };
        }

        private static boolean matchesTimeWindow(
                ReadStruct event, Instant fixedStart, Instant fixedEnd) {
            if (fixedStart == null && fixedEnd == null) {
                return true;
            }
            Instant startTime = getInstant(event, "startTime");
            if (startTime == null) {
                return true;
            }
            if (fixedStart != null && startTime.isBefore(fixedStart)) {
                return false;
            }
            if (fixedEnd != null) {
                Instant endTime = getInstant(event, "endTime");
                Instant effectiveEnd = endTime != null ? endTime : startTime;
                if (effectiveEnd.isAfter(fixedEnd)) {
                    return false;
                }
            }
            return true;
        }

        static class GCPercentileContext {
            record GCInfo(long id, long startTimeNanos, long durationNanos) {}

            private static final Set<String> HEAP_SUMMARY_EVENTS =
                    Set.of("jdk.GCHeapSummary", "jdk.PSHeapSummary", "jdk.G1HeapSummary");

            private final int percentile;
            private final List<GCInfo> gcInfos;

            /** gcId -> [first startTime, last startTime] for heap summary fallback */
            private final Map<Long, long[]> heapSummaryBounds;

            // start time and end time of the longest GCs
            private TreeSet<Long> percentileStartTimeNanos;
            private TreeSet<Long> percentileEndTimeNanos;

            GCPercentileContext(int percentile) {
                this.percentile = percentile;
                gcInfos = new ArrayList<>();
                heapSummaryBounds = new HashMap<>();
            }

            void add(ReadStruct struct) {
                String name = struct.getType().getName();
                if (name.equals("jdk.GarbageCollection")) {
                    var startInstant = getInstant(struct, "startTime");
                    if (startInstant == null) return;
                    var rawDuration = struct.get("duration");
                    long durationNanos;
                    if (rawDuration instanceof Duration d) {
                        durationNanos = d.toNanos();
                    } else if (rawDuration instanceof Long nanos) {
                        durationNanos = nanos;
                    } else {
                        return;
                    }
                    gcInfos.add(
                            new GCInfo(
                                    struct.get("gcId", Long.class),
                                    toNanoSeconds(startInstant),
                                    durationNanos));
                } else if (HEAP_SUMMARY_EVENTS.contains(name) && struct.containsKey("gcId")) {
                    var startInstant = getInstant(struct, "startTime");
                    if (startInstant == null) return;
                    long gcId = struct.get("gcId", Long.class);
                    long startNanos = toNanoSeconds(startInstant);
                    heapSummaryBounds.compute(
                            gcId,
                            (k, bounds) -> {
                                if (bounds == null) {
                                    return new long[] {startNanos, startNanos};
                                }
                                bounds[0] = Math.min(bounds[0], startNanos);
                                bounds[1] = Math.max(bounds[1], startNanos);
                                return bounds;
                            });
                }
            }

            void calculatePercentileIfNeeded() {
                if (percentileStartTimeNanos != null) {
                    return;
                }
                // Fall back to heap summary events if no jdk.GarbageCollection events
                if (gcInfos.isEmpty()) {
                    for (var entry : heapSummaryBounds.entrySet()) {
                        long[] bounds = entry.getValue();
                        long duration = bounds[1] - bounds[0];
                        gcInfos.add(new GCInfo(entry.getKey(), bounds[0], duration));
                    }
                }
                if (gcInfos.isEmpty()) {
                    // No GC data at all — create empty sets, filter will exclude everything
                    percentileStartTimeNanos = new TreeSet<>();
                    percentileEndTimeNanos = new TreeSet<>();
                    return;
                }
                int percentileIndex;
                if (percentile == 100) {
                    percentileIndex = gcInfos.size() - 1;
                } else {
                    percentileIndex =
                            Math.max(
                                    0,
                                    Math.min(
                                            gcInfos.size() - 1,
                                            (int) Math.floor(gcInfos.size() * percentile / 100.0)));
                }
                gcInfos.sort(Comparator.comparingLong(GCInfo::durationNanos));
                percentileStartTimeNanos =
                        gcInfos.subList(percentileIndex, gcInfos.size()).stream()
                                .map(GCInfo::startTimeNanos)
                                .collect(Collectors.toCollection(TreeSet::new));
                percentileEndTimeNanos =
                        gcInfos.subList(percentileIndex, gcInfos.size()).stream()
                                .map(gc -> gc.startTimeNanos() + gc.durationNanos())
                                .collect(Collectors.toCollection(TreeSet::new));
            }

            long getTimeDiffToLongGC(long startTimeNanos, long endTimeNanos) {
                calculatePercentileIfNeeded();
                if (percentileStartTimeNanos.isEmpty()) {
                    return Long.MAX_VALUE; // no long GCs found, exclude everything
                }
                Long floorVal = percentileStartTimeNanos.floor(startTimeNanos);
                Long ceilVal = percentileEndTimeNanos.ceiling(endTimeNanos);
                long startDiff = floorVal != null ? startTimeNanos - floorVal : Long.MAX_VALUE;
                long endDiff = ceilVal != null ? ceilVal - endTimeNanos : Long.MAX_VALUE;
                return Math.min(startDiff, endDiff);
            }
        }

        /**
         * Creates a filter that filters out events that happened in the seconds before and after
         * the GC's with >= n-th percentile duration
         */
        static @Nullable TwoPhaseEventFilter<GCPercentileContext> createGCPercentileFilter(
                int gcPercentile, Duration gcPercentileContext) {
            if (gcPercentile == 0) {
                return null;
            }
            if (gcPercentile < 0 || gcPercentile > 100) {
                throw new IllegalArgumentException(
                        "--gc-percentile must be between 0 and 100, got: " + gcPercentile);
            }
            return new TwoPhaseEventFilter<>() {
                @Override
                public boolean test(ReadStruct struct, GCPercentileContext context) {
                    var startInstant = getInstant(struct, "startTime");
                    if (startInstant == null) return false;
                    var startTimeNanos = toNanoSeconds(startInstant);
                    var endInstant =
                            struct.containsKey("endTime") ? getInstant(struct, "endTime") : null;
                    var endTimeNanos =
                            endInstant != null ? toNanoSeconds(endInstant) : startTimeNanos;
                    return context.getTimeDiffToLongGC(startTimeNanos, endTimeNanos)
                            <= gcPercentileContext.toNanos();
                }

                @Override
                public void analyze(ReadStruct struct, GCPercentileContext context) {
                    context.add(struct);
                }

                @Override
                public GCPercentileContext createContext() {
                    return new GCPercentileContext(gcPercentile);
                }
            };
        }

        private EventFilter<?> combine(@Nullable EventFilter<?>... filters) {
            List<EventFilter<?>> nonNull = Stream.of(filters).filter(Objects::nonNull).toList();
            if (nonNull.isEmpty()) {
                return null;
            }
            if (nonNull.size() == 1) {
                return nonNull.get(0);
            }
            return new CombinedFilter(
                    CombinedFilter.Operator.AND, nonNull.toArray(new EventFilter<?>[0]));
        }

        /** Create a filter from the options */
        public EventFilter<?> createFilter() {
            return combine(
                    createBasicFilter(start, end, duration, eventTypes),
                    createGCPercentileFilter(gcPercentile, gcPercentileContext));
        }

        public boolean noReconstitution() {
            return noReconstitution;
        }

        public @Nullable List<String> getEventTypes() {
            return eventTypes;
        }

        /**
         * Ensures the given event type is included in the --events filter list. If --events is set
         * but doesn't contain the given type (case-insensitive), adds it so it won't be filtered
         * out at the reader level.
         */
        public void ensureEventTypeIncluded(String eventType) {
            if (eventTypes != null
                    && !eventTypes.isEmpty()
                    && eventTypes.stream().noneMatch(t -> t.equalsIgnoreCase(eventType))) {
                eventTypes = new ArrayList<>(eventTypes);
                eventTypes.add(eventType);
            }
        }
    }
}
