package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import org.junit.jupiter.api.Test;

public class EventFilterTest {

    private static void setPrivateField(Object instance, String fieldName, Object value)
            throws Exception {
        var field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(instance, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StructType<?, ReadStruct> createEventType(
            String typeName, String... fieldNames) {
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        List<Field<Object, ?, ?>> fields = new ArrayList<>();
        for (String name : fieldNames) {
            fields.add(new Field<>(name, "", intType, o -> null, EmbeddingType.INLINE));
        }
        return new StructType<>(200, typeName, List.copyOf(fields));
    }

    private static ReadStruct makeEvent(
            String typeName, Map<String, Object> values, String... fieldNames) {
        var type = createEventType(typeName, fieldNames);
        return new ReadStruct(type, new HashMap<>(values));
    }

    private static ReadStruct makeTimedEvent(String typeName, Instant startTime, Instant endTime) {
        return makeEvent(
                typeName,
                Map.of("startTime", startTime, "endTime", endTime),
                "startTime",
                "endTime");
    }

    // --- EventFilter.getInstant tests ---

    @Test
    public void testGetInstantWithInstant() {
        Instant now = Instant.now();
        var event = makeEvent("test", Map.of("startTime", now), "startTime");
        assertEquals(now, EventFilter.getInstant(event, "startTime"));
    }

    @Test
    public void testGetInstantWithLong() {
        long nanos = 1_000_000_000L; // 1 second in nanos
        var event = makeEvent("test", Map.of("startTime", nanos), "startTime");
        Instant result = EventFilter.getInstant(event, "startTime");
        assertEquals(Instant.ofEpochSecond(0, nanos), result);
    }

    @Test
    public void testGetInstantWithNull() {
        // Field exists but accessor returns null
        var type = createEventType("test", "startTime");
        var ids = new HashMap<String, Integer>();
        ids.put("startTime", null);
        var event = new ReadStruct(type, new HashMap<>(), ids, (f, id) -> null);
        assertNull(EventFilter.getInstant(event, "startTime"));
    }

    @Test
    public void testGetInstantWithWrongType() {
        var event = makeEvent("test", Map.of("startTime", "not-an-instant"), "startTime");
        assertThrows(ClassCastException.class, () -> EventFilter.getInstant(event, "startTime"));
    }

    // --- createBasicFilter tests ---

    @Test
    public void testCreateBasicFilterNull() {
        assertNull(EventFilter.EventFilterOptionMixin.createBasicFilter(null, null, null, null));
    }

    @Test
    public void testCreateBasicFilterByEventType() {
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(
                        null, null, null, List.of("jdk.CPULoad"));
        assertNotNull(filter);
        assertFalse(filter.isInformationGathering());

        var matchingEvent =
                makeTimedEvent(
                        "jdk.CPULoad",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-01-01T00:01:00Z"));
        assertTrue(filter.test(matchingEvent, null));

        var nonMatchingEvent =
                makeTimedEvent(
                        "jdk.OtherEvent",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-01-01T00:01:00Z"));
        assertFalse(filter.test(nonMatchingEvent, null));
    }

    @Test
    public void testCreateBasicFilterByStartAndEnd() {
        var start = Instant.parse("2025-01-01T00:00:00Z");
        var end = Instant.parse("2025-01-01T01:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(start, end, null, null);
        assertNotNull(filter);

        // Event within range
        var inRange =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T00:30:00Z"),
                        Instant.parse("2025-01-01T00:31:00Z"));
        assertTrue(filter.test(inRange, null));

        // Event before range
        var beforeRange =
                makeTimedEvent(
                        "test",
                        Instant.parse("2024-12-31T23:00:00Z"),
                        Instant.parse("2024-12-31T23:01:00Z"));
        assertFalse(filter.test(beforeRange, null));

        // Event after range
        var afterRange =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T02:00:00Z"),
                        Instant.parse("2025-01-01T03:00:00Z"));
        assertFalse(filter.test(afterRange, null));
    }

    @Test
    public void testCreateBasicFilterByStartAndDuration() {
        var start = Instant.parse("2025-01-01T00:00:00Z");
        var duration = Duration.ofHours(1);
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(start, null, duration, null);
        assertNotNull(filter);

        var inRange =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T00:30:00Z"),
                        Instant.parse("2025-01-01T00:31:00Z"));
        assertTrue(filter.test(inRange, null));
    }

    @Test
    public void testCreateBasicFilterByEndAndDuration() {
        var end = Instant.parse("2025-01-01T01:00:00Z");
        var duration = Duration.ofHours(1);
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(null, end, duration, null);
        assertNotNull(filter);

        var inRange =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T00:30:00Z"),
                        Instant.parse("2025-01-01T00:31:00Z"));
        assertTrue(filter.test(inRange, null));
    }

    @Test
    public void testCreateBasicFilterWithAllThreeThrows() {
        var start = Instant.parse("2025-01-01T00:00:00Z");
        var end = Instant.parse("2025-01-01T01:00:00Z");
        var duration = Duration.ofHours(1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createBasicFilter(
                                start, end, duration, null));
    }

    @Test
    public void testCreateBasicFilterByEventTypesOnly() {
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(
                        null, null, null, List.of("jdk.CPULoad", "jdk.GCPausePhase"));
        assertNotNull(filter);

        var cpuLoad =
                makeTimedEvent(
                        "jdk.CPULoad",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-01-01T00:01:00Z"));
        assertTrue(filter.test(cpuLoad, null));

        var gcPause =
                makeTimedEvent(
                        "jdk.GCPausePhase",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-01-01T00:01:00Z"));
        assertTrue(filter.test(gcPause, null));
    }

    @Test
    public void testCreateBasicFilterDurationOnlyDefaultsMinMax() {
        // Only duration is set, no start/end => start=MIN, end=MAX
        var filter =
                EventFilter.EventFilterOptionMixin.createBasicFilter(
                        null, null, Duration.ofHours(1), null);
        assertNotNull(filter);
        // Should pass - no time restriction effective
        var event =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-01-01T00:01:00Z"));
        assertTrue(filter.test(event, null));
    }

    // --- CombinedFilter tests ---

    @Test
    public void testCombinedFilterAnd() {
        EventFilter.SinglePhaseEventFilter<Void> filterType =
                (event, ctx) -> event.getType().getName().equals("jdk.CPULoad");
        EventFilter.SinglePhaseEventFilter<Void> filterTime =
                (event, ctx) -> {
                    var st = EventFilter.getInstant(event, "startTime");
                    return st != null && st.isAfter(Instant.parse("2025-01-01T00:00:00Z"));
                };

        var combined =
                new EventFilter.CombinedFilter(
                        EventFilter.CombinedFilter.Operator.AND, filterType, filterTime);

        // Matches both
        var good =
                makeTimedEvent(
                        "jdk.CPULoad",
                        Instant.parse("2025-06-01T00:00:00Z"),
                        Instant.parse("2025-06-01T00:01:00Z"));
        var context = combined.createContext();
        assertTrue(combined.test(good, context));

        // Wrong type
        var wrongType =
                makeTimedEvent(
                        "jdk.Other",
                        Instant.parse("2025-06-01T00:00:00Z"),
                        Instant.parse("2025-06-01T00:01:00Z"));
        assertFalse(combined.test(wrongType, context));

        // Wrong time
        var wrongTime =
                makeTimedEvent(
                        "jdk.CPULoad",
                        Instant.parse("2024-01-01T00:00:00Z"),
                        Instant.parse("2024-01-01T00:01:00Z"));
        assertFalse(combined.test(wrongTime, context));
    }

    @Test
    public void testCombinedFilterOr() {
        EventFilter.SinglePhaseEventFilter<Void> filterType1 =
                (event, ctx) -> event.getType().getName().equals("jdk.CPULoad");
        EventFilter.SinglePhaseEventFilter<Void> filterType2 =
                (event, ctx) -> event.getType().getName().equals("jdk.GCPausePhase");

        var combined =
                new EventFilter.CombinedFilter(
                        EventFilter.CombinedFilter.Operator.OR, filterType1, filterType2);
        var context = combined.createContext();

        var cpu = makeTimedEvent("jdk.CPULoad", Instant.now(), Instant.now());
        assertTrue(combined.test(cpu, context));

        var gc = makeTimedEvent("jdk.GCPausePhase", Instant.now(), Instant.now());
        assertTrue(combined.test(gc, context));

        var other = makeTimedEvent("jdk.Other", Instant.now(), Instant.now());
        assertFalse(combined.test(other, context));
    }

    @Test
    public void testCombinedFilterIsInformationGathering() {
        EventFilter.SinglePhaseEventFilter<Void> singlePhase = (event, ctx) -> true;
        assertFalse(singlePhase.isInformationGathering());

        var combined =
                new EventFilter.CombinedFilter(
                        EventFilter.CombinedFilter.Operator.AND, singlePhase);
        assertFalse(combined.isInformationGathering());
    }

    @Test
    public void testCombinedFilterAnalyze() {
        int[] analyzeCount = {0};
        EventFilter.SinglePhaseEventFilter<Void> filter =
                new EventFilter.SinglePhaseEventFilter<>() {
                    @Override
                    public boolean test(ReadStruct struct, Void context) {
                        return true;
                    }

                    @Override
                    public void analyze(ReadStruct struct) {
                        analyzeCount[0]++;
                    }
                };

        var combined =
                new EventFilter.CombinedFilter(EventFilter.CombinedFilter.Operator.AND, filter);
        var context = combined.createContext();
        var event = makeTimedEvent("test", Instant.now(), Instant.now());
        combined.analyze(event, context);
        assertEquals(1, analyzeCount[0]);
    }

    // --- and() / or() convenience methods ---

    @Test
    public void testAndConvenience() {
        EventFilter.SinglePhaseEventFilter<Void> f1 = (event, ctx) -> true;
        EventFilter.SinglePhaseEventFilter<Void> f2 = (event, ctx) -> false;
        var andFilter = f1.and(f2);
        assertNotNull(andFilter);

        var event = makeTimedEvent("test", Instant.now(), Instant.now());
        assertFalse(andFilter.test(event, andFilter.createContext()));
    }

    @Test
    public void testOrConvenience() {
        EventFilter.SinglePhaseEventFilter<Void> f1 = (event, ctx) -> true;
        EventFilter.SinglePhaseEventFilter<Void> f2 = (event, ctx) -> false;
        var orFilter = f1.or(f2);
        assertNotNull(orFilter);

        var event = makeTimedEvent("test", Instant.now(), Instant.now());
        assertTrue(orFilter.test(event, orFilter.createContext()));
    }

    // --- createAnalyzeFilter / createTestFilter convenience ---

    @Test
    public void testCreateTestFilterInstance() {
        EventFilter.SinglePhaseEventFilter<Void> filter =
                (event, ctx) -> event.getType().getName().equals("match");
        var instance = filter.createTestFilter(null);

        var good = makeTimedEvent("match", Instant.now(), Instant.now());
        assertTrue(instance.test(good));

        var bad = makeTimedEvent("no-match", Instant.now(), Instant.now());
        assertFalse(instance.test(bad));
    }

    @Test
    public void testCreateAnalyzeFilterInstance() {
        int[] count = {0};
        EventFilter.SinglePhaseEventFilter<Void> filter =
                new EventFilter.SinglePhaseEventFilter<>() {
                    @Override
                    public boolean test(ReadStruct struct, Void context) {
                        return true;
                    }

                    @Override
                    public void analyze(ReadStruct struct) {
                        count[0]++;
                    }
                };
        var instance = filter.createAnalyzeFilter(null);
        var event = makeTimedEvent("test", Instant.now(), Instant.now());
        // analyzeFilter always returns false but calls analyze
        assertFalse(instance.test(event));
        assertEquals(1, count[0]);
    }

    @Test
    public void testCreateGCPercentileFilterReturnsNullForZeroPercentile() {
        assertNull(
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        0, Duration.ofSeconds(1)));
    }

    @Test
    public void testGCPercentileContextWithGarbageCollectionEvents() {
        var context = new EventFilter.EventFilterOptionMixin.GCPercentileContext(90);
        context.add(
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId", 1L,
                                "startTime", Instant.ofEpochSecond(0, 1_000),
                                "duration", Duration.ofNanos(10)),
                        "gcId",
                        "startTime",
                        "duration"));
        context.add(
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId", 2L,
                                "startTime", Instant.ofEpochSecond(0, 2_000),
                                "duration", Duration.ofNanos(20)),
                        "gcId",
                        "startTime",
                        "duration"));
        context.add(
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId", 3L,
                                "startTime", Instant.ofEpochSecond(0, 3_000),
                                "duration", Duration.ofNanos(30)),
                        "gcId",
                        "startTime",
                        "duration"));

        long diff = context.getTimeDiffToLongGC(3_000, 1_000);
        assertTrue(diff <= 0);
    }

    @Test
    public void testGCPercentileContextFallsBackToHeapSummaryEvents() {
        var context = new EventFilter.EventFilterOptionMixin.GCPercentileContext(90);
        context.add(
                makeEvent(
                        "jdk.GCHeapSummary",
                        Map.of("gcId", 1L, "startTime", Instant.ofEpochSecond(0, 1_000)),
                        "gcId",
                        "startTime"));
        context.add(
                makeEvent(
                        "jdk.G1HeapSummary",
                        Map.of("gcId", 1L, "startTime", Instant.ofEpochSecond(0, 1_400)),
                        "gcId",
                        "startTime"));
        context.add(
                makeEvent(
                        "jdk.PSHeapSummary",
                        Map.of("gcId", 2L, "startTime", Instant.ofEpochSecond(0, 2_000)),
                        "gcId",
                        "startTime"));
        context.add(
                makeEvent(
                        "jdk.PSHeapSummary",
                        Map.of("gcId", 2L, "startTime", Instant.ofEpochSecond(0, 2_900)),
                        "gcId",
                        "startTime"));

        long diff = context.getTimeDiffToLongGC(2_900, 1_200);
        assertTrue(diff <= 0);
    }

    @Test
    public void testCreateFilterCombinesBasicAndGCPercentileFilters() {
        var mixin = new EventFilter.EventFilterOptionMixin();
        mixin.start = Instant.parse("2025-01-01T00:00:00Z");
        mixin.duration = Duration.ofMinutes(10);
        mixin.eventTypes = List.of("jdk.CPULoad");
        mixin.gcPercentile = 90;
        mixin.gcPercentileContext = Duration.ofSeconds(1);

        var filter = mixin.createFilter();
        assertNotNull(filter);
        assertTrue(filter.isInformationGathering());
    }

    @Test
    public void testCreateFilterReturnsNullWhenNoOptionsSet() {
        var mixin = new EventFilter.EventFilterOptionMixin();
        assertNull(mixin.createFilter());
    }

    @Test
    public void testGCPercentileFilterTestBranchWithAndWithoutEndTime() throws Exception {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofSeconds(1));
        assertNotNull(filter);

        var context = filter.createContext();
        var starts = new TreeSet<Long>(List.of(1_000L, 2_000L, 3_000L));
        var ends = new TreeSet<Long>(List.of(1_000L, 2_000L, 3_000L));
        setPrivateField(context, "percentileStartTimeNanos", starts);
        setPrivateField(context, "percentileEndTimeNanos", ends);

        var withEndTime =
                makeEvent(
                        "jdk.CPULoad",
                        Map.of(
                                "startTime", Instant.ofEpochSecond(0, 2_500),
                                "endTime", Instant.ofEpochSecond(0, 2_600)),
                        "startTime",
                        "endTime");
        assertTrue(filter.test(withEndTime, context));

        var withoutEndTime =
                makeEvent(
                        "jdk.CPULoad",
                        Map.of("startTime", Instant.ofEpochSecond(0, 2_500)),
                        "startTime");
        assertTrue(filter.test(withoutEndTime, context));
    }

    @Test
    public void testGCPercentileFilterCanReturnFalseForNegativeContextThreshold() throws Exception {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofNanos(-1));
        assertNotNull(filter);

        var context = filter.createContext();
        var starts = new TreeSet<Long>(List.of(2_000L));
        var ends = new TreeSet<Long>(List.of(2_000L));
        setPrivateField(context, "percentileStartTimeNanos", starts);
        setPrivateField(context, "percentileEndTimeNanos", ends);

        var exactBoundary =
                makeEvent(
                        "jdk.CPULoad",
                        Map.of(
                                "startTime", Instant.ofEpochSecond(0, 2_000),
                                "endTime", Instant.ofEpochSecond(0, 2_000)),
                        "startTime",
                        "endTime");
        assertFalse(filter.test(exactBoundary, context));
    }

    @Test
    public void testGCPercentileFilterAnalyzeDelegatesToContextAdd() {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofSeconds(1));
        assertNotNull(filter);

        var context = filter.createContext();
        var gcEvent =
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId", 42L,
                                "startTime", Instant.ofEpochSecond(0, 1_000),
                                "duration", Duration.ofNanos(15)),
                        "gcId",
                        "startTime",
                        "duration");
        assertDoesNotThrow(() -> filter.analyze(gcEvent, context));
    }

    @Test
    public void testNoReconstitutionDefaultAndFlagValue() {
        var mixin = new EventFilter.EventFilterOptionMixin();
        assertFalse(mixin.noReconstitution());
        mixin.noReconstitution = true;
        assertTrue(mixin.noReconstitution());
    }
}
