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
        assertTrue(filter.isInformationGathering());

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
        var ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                EventFilter.EventFilterOptionMixin.createBasicFilter(
                                        start, end, duration, null));
        assertTrue(ex.getMessage().contains("Both start, end and duration are set"));
        assertFalse(ex.getMessage().contains("ignoring duration"));
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
    public void testCreateBasicFilterDurationAloneThrows() {
        // Bug 211: --duration alone (without --start or --end) should throw
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createBasicFilter(
                                null, null, Duration.ofHours(1), null));
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
        assertEquals(0, diff); // event start == long GC start => distance 0
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

        // Long GC (gcId2) is at [2000, 2900]. Event at [2900, 1200].
        // startDiff = 2900 - 2000 = 900, endDiff = 2900 - 1200 = 1700, min = 900
        long diff = context.getTimeDiffToLongGC(2_900, 1_200);
        assertEquals(900, diff);
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

    // ========== GC Percentile validation and robustness tests ==========

    @Test
    public void testGCPercentileRejectsNegativeValue() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                                -1, Duration.ofMinutes(1)));
    }

    @Test
    public void testGCPercentileRejectsAbove100() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                                101, Duration.ofMinutes(1)));
    }

    @Test
    public void testGCPercentile100DoesNotCrash() {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        100, Duration.ofMinutes(1));
        assertNotNull(filter);
        var context = filter.createContext();
        var gcEvent =
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId",
                                1L,
                                "startTime",
                                Instant.ofEpochSecond(100),
                                "duration",
                                Duration.ofMillis(5)),
                        "gcId",
                        "startTime",
                        "duration");
        filter.analyze(gcEvent, context);
        var testEvent =
                makeEvent(
                        "jdk.SomeEvent",
                        Map.of("startTime", Instant.ofEpochSecond(100)),
                        "startTime");
        // Should not throw
        assertDoesNotThrow(() -> filter.test(testEvent, context));
    }

    @Test
    public void testGCPercentile100SelectsLongestGC() {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        100, Duration.ofMillis(1));
        assertNotNull(filter);
        var context = filter.createContext();

        var shortGc =
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId", 1L,
                                "startTime", Instant.ofEpochMilli(0),
                                "duration", Duration.ofMillis(1)),
                        "gcId",
                        "startTime",
                        "duration");
        var longGc =
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId", 2L,
                                "startTime", Instant.ofEpochMilli(10),
                                "duration", Duration.ofMillis(10)),
                        "gcId",
                        "startTime",
                        "duration");
        filter.analyze(shortGc, context);
        filter.analyze(longGc, context);

        var nearLongest =
                makeEvent(
                        "jdk.SomeEvent",
                        Map.of("startTime", Instant.ofEpochMilli(10)),
                        "startTime");
        var nearShortOnly =
                makeEvent(
                        "jdk.SomeEvent", Map.of("startTime", Instant.ofEpochMilli(0)), "startTime");

        assertTrue(filter.test(nearLongest, context));
        assertFalse(filter.test(nearShortOnly, context));
    }

    @Test
    public void testGCPercentileHandlesNoGCEvents() {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofMinutes(1));
        assertNotNull(filter);
        var context = filter.createContext();
        // No GC events analyzed — test should not crash
        var testEvent =
                makeEvent(
                        "jdk.SomeEvent",
                        Map.of("startTime", Instant.ofEpochSecond(100)),
                        "startTime");
        assertDoesNotThrow(() -> filter.test(testEvent, context));
        // With no GC data, all events should be excluded
        assertFalse(filter.test(testEvent, context));
    }

    @Test
    public void testGCPercentileHandlesLongDurationInsteadOfDuration() {
        // In condensed data, duration may be stored as Long (nanos) not Duration
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofMinutes(1));
        var context = filter.createContext();
        var gcEvent =
                makeEvent(
                        "jdk.GarbageCollection",
                        Map.of(
                                "gcId",
                                1L,
                                "startTime",
                                Instant.ofEpochSecond(100),
                                "duration",
                                5_000_000L),
                        "gcId",
                        "startTime",
                        "duration");
        assertDoesNotThrow(() -> filter.analyze(gcEvent, context));
    }

    @Test
    public void testGCPercentileHandlesNullStartTime() {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofMinutes(1));
        var context = filter.createContext();
        var values = new HashMap<String, Object>();
        values.put("startTime", null);
        var testEvent = makeEvent("jdk.SomeEvent", values, "startTime");
        // Should not throw NPE
        assertDoesNotThrow(() -> filter.test(testEvent, context));
    }

    // --- Bug 90/91 time filter tests ---

    private static ReadStruct makeStartOnlyEvent(String typeName, Instant startTime) {
        return makeEvent(typeName, Map.of("startTime", startTime), "startTime");
    }

    private static ReadStruct makeEventWithNullStartTime(String typeName) {
        var values = new HashMap<String, Object>();
        values.put("startTime", null);
        return makeEvent(typeName, values, "startTime");
    }

    @Test
    public void testStartOnlyFilterPassesEventsWithNullStartTime() {
        // Bug 90: events with null startTime should not be filtered out
        var start = Instant.parse("2020-01-01T00:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(start, null, null, null);
        assertNotNull(filter);
        var event = makeEventWithNullStartTime("jdk.BooleanFlag");
        assertTrue(filter.test(event, null), "Events with null startTime should pass through");
    }

    @Test
    public void testStartOnlyFilterPassesAllEventsBeforeRecording() {
        // Bug 90: --start before recording should include all events with startTime
        var start = Instant.parse("2020-01-01T00:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(start, null, null, null);
        var event = makeStartOnlyEvent("jdk.CPULoad", Instant.parse("2025-12-05T12:12:21Z"));
        assertTrue(filter.test(event, null));
    }

    @Test
    public void testEndOnlyFilterFiltersEventsByStartTime() {
        // Bug 91: --end only should filter events whose startTime is after the end
        var end = Instant.parse("2025-01-01T01:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(null, end, null, null);
        assertNotNull(filter);

        // Event before end -> should pass
        var before = makeStartOnlyEvent("test", Instant.parse("2025-01-01T00:30:00Z"));
        assertTrue(filter.test(before, null), "Event before --end should pass");

        // Event after end -> should be filtered
        var after = makeStartOnlyEvent("test", Instant.parse("2025-01-01T02:00:00Z"));
        assertFalse(filter.test(after, null), "Event after --end should be filtered out");
    }

    @Test
    public void testEndOnlyFilterUsesEndTimeWhenAvailable() {
        var end = Instant.parse("2025-01-01T01:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(null, end, null, null);

        // Event with endTime before end -> pass
        var before =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T00:30:00Z"),
                        Instant.parse("2025-01-01T00:50:00Z"));
        assertTrue(filter.test(before, null));

        // Event with endTime after end -> filtered
        var after =
                makeTimedEvent(
                        "test",
                        Instant.parse("2025-01-01T00:30:00Z"),
                        Instant.parse("2025-01-01T01:30:00Z"));
        assertFalse(filter.test(after, null));
    }

    @Test
    public void testEndOnlyFilterPassesEventsWithNullStartTime() {
        // Events with null startTime should pass (can't determine time)
        var end = Instant.parse("2025-01-01T01:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(null, end, null, null);
        var event = makeEventWithNullStartTime("jdk.BooleanFlag");
        assertTrue(filter.test(event, null), "Events with null startTime should pass through");
    }

    @Test
    public void testTimeFilterHandlesLongStartTimeBeforeWindow() {
        // Events where startTime is a Long (nanoseconds) should be properly filtered
        var start = Instant.parse("2025-01-01T00:00:00Z");
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(start, null, null, null);
        // startTime is a Long (nanoseconds from epoch) - before the filter window
        var event = makeEvent("jdk.SomeEvent", Map.of("startTime", 12345L), "startTime");
        assertFalse(
                filter.test(event, null),
                "Events with Long startTime before filter window should be filtered out");
    }

    @Test
    public void testTimeFilterHandlesLongStartTimeInWindow() {
        // Events where startTime is a Long (nanoseconds) within the window should pass
        var start = Instant.parse("2025-01-01T00:00:00Z");
        long nanosIn2025 = start.getEpochSecond() * 1_000_000_000L + 1_000_000_000L;
        var filter = EventFilter.EventFilterOptionMixin.createBasicFilter(start, null, null, null);
        var event = makeEvent("jdk.SomeEvent", Map.of("startTime", nanosIn2025), "startTime");
        assertTrue(
                filter.test(event, null),
                "Events with Long startTime within filter window should pass through");
    }

    // --- GC percentile index selection correctness ---

    /**
     * Verifies that --gc-percentile 90 selects the longest 10% of GCs, not 90%. With 100 GCs of
     * increasing duration (1ns..100ns), the 90th percentile should select the 10 longest (durations
     * 91-100ns), not the 90 longest.
     */
    @Test
    public void testGCPercentileSelectsCorrectNumberOfLongGCs() throws Exception {
        var context = new EventFilter.EventFilterOptionMixin.GCPercentileContext(90);

        // Add 100 GC events with durations 1ns, 2ns, ..., 100ns
        // Each GC starts at a distinct time: 1_000_000 * i nanos
        for (int i = 1; i <= 100; i++) {
            context.add(
                    makeEvent(
                            "jdk.GarbageCollection",
                            Map.of(
                                    "gcId", (long) i,
                                    "startTime", Instant.ofEpochSecond(0, 1_000_000L * i),
                                    "duration", Duration.ofNanos(i)),
                            "gcId",
                            "startTime",
                            "duration"));
        }

        // Force calculation
        context.getTimeDiffToLongGC(0, 0);

        // Access the internal percentileStartTimeNanos set via reflection
        var field = context.getClass().getDeclaredField("percentileStartTimeNanos");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        TreeSet<Long> selectedGCStarts = (TreeSet<Long>) field.get(context);

        // --gc-percentile 90 means "longest 10% of GCs" = 10 out of 100
        assertEquals(
                10,
                selectedGCStarts.size(),
                "With 100 GCs and --gc-percentile 90, should select the 10 longest GCs (top 10%),"
                        + " not "
                        + selectedGCStarts.size());
    }

    /** Complementary test: --gc-percentile 50 should select the longest 50% of GCs. */
    @Test
    public void testGCPercentile50SelectsHalfOfGCs() throws Exception {
        var context = new EventFilter.EventFilterOptionMixin.GCPercentileContext(50);

        for (int i = 1; i <= 20; i++) {
            context.add(
                    makeEvent(
                            "jdk.GarbageCollection",
                            Map.of(
                                    "gcId", (long) i,
                                    "startTime", Instant.ofEpochSecond(0, 1_000_000L * i),
                                    "duration", Duration.ofNanos(i)),
                            "gcId",
                            "startTime",
                            "duration"));
        }

        context.getTimeDiffToLongGC(0, 0);

        var field = context.getClass().getDeclaredField("percentileStartTimeNanos");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        TreeSet<Long> selectedGCStarts = (TreeSet<Long>) field.get(context);

        assertEquals(
                10,
                selectedGCStarts.size(),
                "With 20 GCs and --gc-percentile 50, should select the 10 longest GCs (top 50%)");
    }

    /** Edge case: --gc-percentile 99 with 100 GCs should select just the 1 longest. */
    @Test
    public void testGCPercentile99SelectsOnlyLongestGC() throws Exception {
        var context = new EventFilter.EventFilterOptionMixin.GCPercentileContext(99);

        for (int i = 1; i <= 100; i++) {
            context.add(
                    makeEvent(
                            "jdk.GarbageCollection",
                            Map.of(
                                    "gcId", (long) i,
                                    "startTime", Instant.ofEpochSecond(0, 1_000_000L * i),
                                    "duration", Duration.ofNanos(i)),
                            "gcId",
                            "startTime",
                            "duration"));
        }

        context.getTimeDiffToLongGC(0, 0);

        var field = context.getClass().getDeclaredField("percentileStartTimeNanos");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        TreeSet<Long> selectedGCStarts = (TreeSet<Long>) field.get(context);

        assertEquals(
                1,
                selectedGCStarts.size(),
                "With 100 GCs and --gc-percentile 99, should select just the 1 longest GC (top"
                        + " 1%)");
    }

    // ========== Bug 214 regression: gc-percentile must actually filter events ==========

    @Test
    public void testGCPercentileFilterExcludesDistantEvents() {
        // Create 10 GCs: durations 10, 20, ..., 100 nanos; starts at 1000, 2000, ..., 10000
        var context = new EventFilter.EventFilterOptionMixin.GCPercentileContext(90);
        for (int i = 1; i <= 10; i++) {
            context.add(
                    makeEvent(
                            "jdk.GarbageCollection",
                            Map.of(
                                    "gcId", (long) i,
                                    "startTime", Instant.ofEpochSecond(0, i * 1_000L),
                                    "duration", Duration.ofNanos(i * 10L)),
                            "gcId",
                            "startTime",
                            "duration"));
        }
        // 90th percentile of 10 GCs → percentileIndex = 9, long GC = GC 10 (start=10000, dur=100)

        // Event right at the long GC → distance 0
        assertEquals(0, context.getTimeDiffToLongGC(10_000, 10_000));

        // Event 500ns away from the long GC start → distance 500
        assertEquals(500, context.getTimeDiffToLongGC(10_500, 10_500));

        // Event far away (at nanos=1) → large positive distance
        long farDiff = context.getTimeDiffToLongGC(1, 1);
        assertTrue(farDiff > 0, "Events far from long GCs should have positive distance");
    }

    @Test
    public void testGCPercentileFilterEndToEndExclusion() throws Exception {
        var filter =
                EventFilter.EventFilterOptionMixin.createGCPercentileFilter(
                        90, Duration.ofNanos(50));
        assertNotNull(filter);
        var context = filter.createContext();

        // Add 10 GCs
        for (int i = 1; i <= 10; i++) {
            filter.analyze(
                    makeEvent(
                            "jdk.GarbageCollection",
                            Map.of(
                                    "gcId", (long) i,
                                    "startTime", Instant.ofEpochSecond(0, i * 1_000_000L),
                                    "duration", Duration.ofNanos(i * 1_000L)),
                            "gcId",
                            "startTime",
                            "duration"),
                    context);
        }
        // Long GC (GC 10): start=10_000_000, duration=10_000 → end=10_010_000

        // Event near the long GC (within 50ns context) → included
        var nearEvent =
                makeEvent(
                        "jdk.CPULoad",
                        Map.of("startTime", Instant.ofEpochSecond(0, 10_000_020)),
                        "startTime");
        assertTrue(filter.test(nearEvent, context), "Event near long GC should be included");

        // Event far from the long GC → excluded
        var farEvent =
                makeEvent(
                        "jdk.CPULoad",
                        Map.of("startTime", Instant.ofEpochSecond(0, 1_000)),
                        "startTime");
        assertFalse(filter.test(farEvent, context), "Event far from long GC should be excluded");
    }
}
