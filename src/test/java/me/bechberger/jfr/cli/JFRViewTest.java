package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests the underlying JFRView works */
public class JFRViewTest {

    @Name("TestEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace
    static class TestEvent extends Event {
        @Label("Label")
        int number;

        @Label("Memory")
        @DataAmount
        long memory = Runtime.getRuntime().freeMemory();

        @Label("String")
        String string = "Hello" + memory;

        TestEvent(int number) {
            this.number = number;
        }
    }

    private static ReadStruct testEventStruct;
    private static ReadStruct gcEventStruct;

    @BeforeAll
    static void initJFRStructs() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out);
            AtomicBoolean hadGC = new AtomicBoolean(false);
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            rs.close();
                        });
                rs.enable("jdk.GarbageCollection");
                rs.onEvent(
                        "jdk.GarbageCollection",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            hadGC.set(true);
                        });
                rs.startAsync();
                while (!hadGC.get()) {
                    @SuppressWarnings("unused") // force allocation for GC
                    var x = new byte[1024 * 1024 * 1024];
                    System.gc();
                }
                TestEvent testEvent = new TestEvent(1);
                testEvent.commit();
                rs.awaitTermination();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try (CondensedInputStream in = new CondensedInputStream(outputStream.toByteArray())) {
            var reader = new BasicJFRReader(in);
            var events = reader.readAll();
            for (var event : events) {
                switch (event.getType().getName()) {
                    case "TestEvent" -> testEventStruct = event;
                    case "jdk.GarbageCollection" -> gcEventStruct = event;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testHeader() {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        for (var line : view.header()) {
            if (line.length() > config.width()) {
                fail();
            }
        }
        assertEquals(
                """
                TestEvent

                Start Time Duration   Event Thread    Stack Trace                                       Number     Memory     String                                          \s
                ---------- ---------- --------------- ------------------------------------------------- ---------- ---------- -------------------------------------------------
                """
                        .strip(),
                String.join("\n", view.header()).strip());
    }

    @Test
    public void testNotEnoughSpace() {
        var config = new PrintConfig(10, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        assertEquals(
                """
                TestEvent

                Start Time Duration   Event Thread     Number     Memory    \s
                ---------- ---------- ---------------  ---------- ----------
                """
                        .strip(),
                String.join("\n", view.header()).strip());
    }

    /**
     * Bug: JFRView.rows() throws IndexOutOfBoundsException when some columns have width <= 0. The
     * first loop in rows() conditionally adds to rowsPerColumn (only if width > 0), but the second
     * loop indexes into rowsPerColumn using the original column index j, which is larger than
     * rowsPerColumn.size() when columns were skipped.
     */
    @Test
    public void testNotEnoughSpaceRows() {
        // Using width=10 causes some variable-width columns to get width <= 0
        var config = new PrintConfig(10, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        // This should not throw IndexOutOfBoundsException
        var rows = view.rows(testEventStruct);
        assertNotNull(rows);
        assertFalse(rows.isEmpty());
    }

    @Test
    public void testRows() {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        var content = view.rows(testEventStruct);
        assertEquals(1, content.size());
        var line = content.get(0);
        System.err.println(line);
        assertTrue(line.contains(".bechberger.jfr.cli.JFRViewTest.initJFRStructs "));
        assertTrue(line.contains(" Hello"));
        assertTrue(line.contains("main"));
    }

    @Test
    public void testMultiLineCells() {
        var config = new PrintConfig(160, 2, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        var content = view.rows(testEventStruct);
        System.err.println(content);
        assertEquals(2, content.size());
    }

    @Test
    public void testGCEvent() {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(gcEventStruct.getType()), config);
        var content = view.rows(gcEventStruct);
        assertEquals(1, content.size());
        System.err.println(String.join("\n", view.header()));
        System.err.println(content.get(0));
        System.out.println(gcEventStruct);
    }

    // ========== Bug reproducer tests ==========

    /**
     * Bug: TruncateMode.BEGIN keeps the beginning of the string, but the option description says
     * "--truncate beginning" means "truncate the beginning" (i.e., keep the end). The modes are
     * swapped relative to the documented semantics.
     *
     * <p>With input "ABCDEFGHIJ" and width 5: --truncate begin → should truncate beginning → keep
     * "FGHIJ" but actually keeps "ABCDE" (the beginning)
     *
     * <p>--truncate end → should truncate end → keep "ABCDE" but actually keeps "FGHIJ" (the end)
     */
    @Test
    public void testTruncateModeBeginKeepsEnd() throws Exception {
        // Access the private truncate method via reflection
        var config = new PrintConfig(160, 1, TruncateMode.BEGIN);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);

        Method truncateMethod =
                JFRView.class.getDeclaredMethod("truncate", String.class, int.class);
        truncateMethod.setAccessible(true);

        // --truncate begin should mean "truncate the beginning" → keep the end
        String result = (String) truncateMethod.invoke(view, "ABCDEFGHIJ", 5);
        assertEquals(
                "FGHIJ",
                result,
                "TruncateMode.BEGIN should truncate the beginning and keep the end");
    }

    @Test
    public void testTruncateModeEndKeepsBeginning() throws Exception {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);

        Method truncateMethod =
                JFRView.class.getDeclaredMethod("truncate", String.class, int.class);
        truncateMethod.setAccessible(true);

        // --truncate end should mean "truncate the end" → keep the beginning
        String result = (String) truncateMethod.invoke(view, "ABCDEFGHIJ", 5);
        assertEquals(
                "ABCDE", result, "TruncateMode.END should truncate the end and keep the beginning");
    }

    private static StructType<?, ReadStruct> createType(String typeName, String... fieldNames) {
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        List<Field<Object, ?, ?>> fields = new java.util.ArrayList<>();
        for (String name : fieldNames) {
            fields.add(new Field<>(name, "", intType, o -> null, EmbeddingType.INLINE));
        }
        return new StructType<>(1000, typeName, fields);
    }

    @Test
    public void testStructColumnFormatReturnsDashForNullStruct() {
        var eventType = createType("outer", "nested");
        var values = new java.util.HashMap<String, Object>();
        values.put("nested", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.StructColumn("nested", List.of(new JFRView.StringColumn("name")));

        assertEquals(List.of("-"), column.format(event, 2));
    }

    @Test
    public void testStructColumnRowsUsesNestedStructSize() {
        var nestedType = createType("nested", "a", "b", "c");
        var nested = new ReadStruct(nestedType, Map.of("a", 1L, "b", 2L, "c", 3L));
        var outerType = createType("outer", "nested");
        var event = new ReadStruct(outerType, Map.of("nested", nested));
        var column = new JFRView.StructColumn("nested", List.of(new JFRView.StringColumn("a")));

        assertEquals(3, column.rows(event));
    }

    @Test
    public void testStructColumnFormatSingleRowShowsAllParts() {
        var nestedType = createType("nested", "name", "count");
        var nested = new ReadStruct(nestedType, Map.of("name", "alpha", "count", 7L));
        var outerType = createType("outer", "nested");
        var event = new ReadStruct(outerType, Map.of("nested", nested));
        var column =
                new JFRView.StructColumn(
                        "nested",
                        List.of(
                                new JFRView.StringColumn("name"),
                                new JFRView.IntegerColumn("count", 10)));

        var rows = column.format(event, 1);
        assertEquals(1, rows.size());
        assertEquals("alpha, 7", rows.get(0));
    }

    @Test
    public void testStructColumnFormatMultiRowIncludesHeaders() {
        var nestedType = createType("nested", "name", "count");
        var nested = new ReadStruct(nestedType, Map.of("name", "alpha", "count", 7L));
        var outerType = createType("outer", "nested");
        var event = new ReadStruct(outerType, Map.of("nested", nested));
        var column =
                new JFRView.StructColumn(
                        "nested",
                        List.of(
                                new JFRView.StringColumn("name"),
                                new JFRView.IntegerColumn("count", 10)));

        var rows = column.format(event, 2);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).startsWith("Name: "));
        assertTrue(rows.get(1).startsWith("Count: "));
    }

    @Test
    public void testStructColumnOfWithDepthZeroCreatesFallbackFormatter() {
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        var column = JFRView.StructColumn.of("other", intType, 0);
        var eventType = createType("outer", "other");
        var event = new ReadStruct(eventType, Map.of("other", 42L));

        assertEquals(-1, column.width());
        assertEquals(List.of("42"), column.format(event, 1));
    }

    @Test
    public void testBooleanColumnFormatsTrueAndFalse() {
        var eventType = createType("flags", "enabled");
        var trueEvent = new ReadStruct(eventType, Map.of("enabled", true));
        var falseEvent = new ReadStruct(eventType, Map.of("enabled", false));
        var column = new JFRView.BooleanColumn("enabled");

        assertEquals(7, column.width());
        assertEquals(List.of("true"), column.format(trueEvent, 1));
        assertEquals(List.of("false"), column.format(falseEvent, 1));
        assertEquals(JFRView.Alignment.LEFT, column.alignment());
    }

    @Test
    public void testClassLoaderColumnFormatsMissingAndPresentLoader() {
        var classLoaderType = createType("loader", "name");
        var eventType = createType("event", "classLoader");
        var loader = new ReadStruct(classLoaderType, Map.of("name", "app-loader"));
        var eventWithLoader = new ReadStruct(eventType, Map.of("classLoader", loader));
        var withoutLoaderValues = new java.util.HashMap<String, Object>();
        withoutLoaderValues.put("classLoader", null);
        var eventWithoutLoader = new ReadStruct(eventType, withoutLoaderValues);
        var column = new JFRView.ClassLoaderColumn("classLoader");

        assertEquals(12, column.width());
        assertEquals(List.of("app-loader"), column.format(eventWithLoader, 1));
        assertEquals(List.of("-"), column.format(eventWithoutLoader, 1));
        assertEquals(JFRView.Alignment.LEFT, column.alignment());
    }

    @Test
    public void testClassColumnUsesNestedPackageStruct() {
        var packageType = createType("pkg", "name");
        var classType = createType("klass", "name", "package");
        var eventType = createType("event", "klass");

        var pkg = new ReadStruct(packageType, Map.of("name", "java.lang"));
        var klass = new ReadStruct(classType, Map.of("name", "Class", "package", pkg));
        var event = new ReadStruct(eventType, Map.of("klass", klass));

        var column = new JFRView.ClassColumn("klass");
        assertEquals(List.of("java.lang.Class"), column.format(event, 1));
    }

    @Test
    public void testClassColumnDoesNotDuplicatePackagePrefix() {
        var packageType = createType("pkg", "name");
        var classType = createType("klass", "name", "package");
        var eventType = createType("event", "klass");

        var pkg = new ReadStruct(packageType, Map.of("name", "java.lang"));
        var klass = new ReadStruct(classType, Map.of("name", "java/lang/Class", "package", pkg));
        var event = new ReadStruct(eventType, Map.of("klass", klass));

        var column = new JFRView.ClassColumn("klass");
        assertEquals(List.of("java.lang.Class"), column.format(event, 1));
    }

    // ========== Null-safety tests (Bugs 161, 162, 165, 173-176) ==========

    @Test
    public void testStringColumnReturnsHyphenForNull() {
        var eventType = createType("event", "name");
        var values = new java.util.HashMap<String, Object>();
        values.put("name", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.StringColumn("name");
        assertEquals(List.of("-"), column.format(event, 1));
    }

    @Test
    public void testIntegerColumnReturnsHyphenForNull() {
        var eventType = createType("event", "count");
        var values = new java.util.HashMap<String, Object>();
        values.put("count", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.IntegerColumn("count", 10);
        assertEquals(List.of("-"), column.format(event, 1));
    }

    @Test
    public void testFloatColumnReturnsHyphenForNull() {
        var eventType = createType("event", "rate");
        var values = new java.util.HashMap<String, Object>();
        values.put("rate", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.FloatColumn("rate", 10, 2);
        assertEquals(List.of("-"), column.format(event, 1));
    }

    @Test
    public void testBooleanColumnReturnsHyphenForNull() {
        var eventType = createType("event", "active");
        var values = new java.util.HashMap<String, Object>();
        values.put("active", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.BooleanColumn("active");
        assertEquals(List.of("-"), column.format(event, 1));
    }

    @Test
    public void testMemoryColumnReturnsHyphenForNull() {
        var eventType = createType("event", "size");
        var values = new java.util.HashMap<String, Object>();
        values.put("size", null);
        var event = new ReadStruct(eventType, values);
        var column =
                new JFRView.MemoryColumn("size", me.bechberger.util.MemoryUtil.MemoryUnit.BYTES);
        assertEquals(List.of("-"), column.format(event, 1));
    }

    @Test
    public void testMemoryColumnHandlesDoubleValue() {
        var eventType = createType("event", "size");
        var event = new ReadStruct(eventType, Map.of("size", 1024.5d));
        var column =
                new JFRView.MemoryColumn("size", me.bechberger.util.MemoryUtil.MemoryUnit.BYTES);
        var formatted = column.format(event, 1);
        assertEquals(1, formatted.size());
        assertFalse(formatted.get(0).equals("-"));
    }

    @Test
    public void testMemoryColumnHandlesIntegerValue() {
        var eventType = createType("event", "size");
        var event = new ReadStruct(eventType, Map.of("size", 2048));
        var column =
                new JFRView.MemoryColumn("size", me.bechberger.util.MemoryUtil.MemoryUnit.BYTES);
        var formatted = column.format(event, 1);
        assertEquals(1, formatted.size());
        assertFalse(formatted.get(0).equals("-"));
    }

    @Test
    public void testDurationColumnReturnsHyphenForNull() {
        var eventType = createType("event", "duration");
        var values = new java.util.HashMap<String, Object>();
        values.put("duration", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.DurationColumn("duration");
        assertEquals(List.of("-"), column.format(event, 1));
    }

    @Test
    public void testInstantColumnReturnsHyphenForNull() {
        var eventType = createType("event", "startTime");
        var values = new java.util.HashMap<String, Object>();
        values.put("startTime", null);
        var event = new ReadStruct(eventType, values);
        var column = new JFRView.InstantColumn("startTime");
        assertEquals(List.of("-"), column.format(event, 1));
    }

    // ========== Long value handling (Bug 18 — combined events) ==========

    @Test
    public void testDurationColumnHandlesLongNanos() {
        var eventType = createType("event", "duration");
        var event = new ReadStruct(eventType, Map.of("duration", 4_102_250L));
        var column = new JFRView.DurationColumn("duration");
        var result = column.format(event, 1);
        assertEquals(1, result.size());
        // Should be formatted as a duration, not a raw number
        assertNotEquals("4102250", result.get(0));
        assertFalse(result.get(0).matches("\\d+"), "Should format as duration, not raw number");
    }

    @Test
    public void testInstantColumnHandlesLongNanos() {
        var eventType = createType("event", "startTime");
        long nanos = java.time.Instant.now().getEpochSecond() * 1_000_000_000L;
        var event = new ReadStruct(eventType, Map.of("startTime", nanos));
        var column = new JFRView.InstantColumn("startTime");
        var result = column.format(event, 1);
        assertEquals(1, result.size());
        assertFalse(result.get(0).equals("-"), "Should not be null marker");
        // Should contain time format like HH:mm:ss
        assertTrue(
                result.get(0).matches("\\d{2}:\\d{2}:\\d{2}"),
                "Expected time format HH:mm:ss, got: " + result.get(0));
    }

    // ========== Annotation shadowing (Bugs 3, 4) ==========

    private static StructType<?, ReadStruct> createTypeWithFieldDesc(
            String typeName, String fieldName, String fieldTypeName, String fieldDesc) {
        // Create a custom VarIntType-like type with the specified name
        var fieldType = new me.bechberger.condensed.types.VarIntType(999, fieldTypeName, "", false);
        List<Field<Object, ?, ?>> fields =
                List.of(
                        new Field<>(
                                fieldName, fieldDesc, fieldType, o -> null, EmbeddingType.INLINE));
        return new StructType<>(1000, typeName, fields);
    }

    @Test
    public void testFieldToColumnDispatchesUnsignedTimespanToDurationColumn() {
        // Simulate a field typed as "jdk.jfr.Unsigned" but with @Timespan annotation in description
        String desc =
                "[\"long\",\"jdk.jfr.Unsigned\",[[\"jdk.jfr.Unsigned\",[]],[\"jdk.jfr.Timespan\",[\"TICKS\"]]],\"Sum"
                    + " of Pauses\",null,false]";
        var type = createTypeWithFieldDesc("event", "sumOfPauses", "jdk.jfr.Unsigned", desc);
        var field = type.getFields().get(0);
        var column = JFRView.fieldToColumn(field);
        assertInstanceOf(
                JFRView.DurationColumn.class,
                column,
                "Field with @Unsigned + @Timespan should dispatch to DurationColumn");
    }

    @Test
    public void testFieldToColumnDispatchesPlainUnsignedToIntegerColumn() {
        // Simulate a plain @Unsigned field without @Timespan
        String desc =
                "[\"int\",\"jdk.jfr.Unsigned\",[[\"jdk.jfr.Unsigned\",[]]],\"GC"
                        + " Identifier\",null,false]";
        var type = createTypeWithFieldDesc("event", "gcId", "jdk.jfr.Unsigned", desc);
        var field = type.getFields().get(0);
        var column = JFRView.fieldToColumn(field);
        assertInstanceOf(
                JFRView.IntegerColumn.class,
                column,
                "Field with only @Unsigned (no @Timespan) should dispatch to IntegerColumn");
    }

    @Test
    public void testUnsignedTimespanFieldRendersAsDuration() {
        // End-to-end test: create event with @Unsigned+@Timespan field, verify it renders as
        // duration
        String desc =
                "[\"long\",\"jdk.jfr.Unsigned\",[[\"jdk.jfr.Unsigned\",[]],[\"jdk.jfr.Timespan\",[\"TICKS\"]]],\"Sum"
                    + " of Pauses\",null,false]";
        var type = createTypeWithFieldDesc("event", "sumOfPauses", "jdk.jfr.Unsigned", desc);
        var field = type.getFields().get(0);
        var column = JFRView.fieldToColumn(field);
        var event = new ReadStruct(type, Map.of("sumOfPauses", 4_102_250L));
        var result = column.format(event, 1);
        assertFalse(
                result.get(0).matches("\\d+"),
                "Unsigned timespan should render as duration, not raw number: " + result.get(0));
    }

    /**
     * Bug 163/164/177: When an event type has many fixed-width columns, variable-width columns can
     * get widths smaller than their header text. This caused header() to call
     * String.repeat(negative), throwing IllegalArgumentException "count is negative".
     */
    @Test
    public void testNarrowWidthDoesNotCrashWithManyColumns() {
        // Create a type with many fields to exhaust the available width at width=160
        var stringType =
                new me.bechberger.condensed.types.StringType(998, "java.lang.String", "", "UTF-8");
        List<Field<Object, ?, ?>> fields = new java.util.ArrayList<>();
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        // 12 integer fields (each 10 chars wide) = 120 chars of fixed width
        for (int i = 0; i < 12; i++) {
            fields.add(new Field<>("field" + i, "", intType, o -> null, EmbeddingType.INLINE));
        }
        // 3 string fields with long names (variable width, will be squeezed)
        for (String name : List.of("longFieldName", "anotherLongName", "stackTraceLike")) {
            fields.add(new Field<>(name, "", stringType, o -> null, EmbeddingType.INLINE));
        }
        var type = new StructType<>(1001, "ManyColumnEvent", fields);
        var config = new PrintConfig(160, 1, TruncateMode.END);
        // This previously threw IllegalArgumentException: count is negative
        @SuppressWarnings("rawtypes")
        JFRView view = new JFRView(new JFRViewConfig((StructType) type), config);
        assertDoesNotThrow(() -> view.header());
        // Also verify rows work
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < 12; i++) {
            values.put("field" + i, i);
        }
        values.put("longFieldName", "val1");
        values.put("anotherLongName", "val2");
        values.put("stackTraceLike", "val3");
        @SuppressWarnings({"unchecked", "rawtypes"})
        var event = new ReadStruct((StructType) type, values);
        assertDoesNotThrow(() -> view.rows(event));
    }

    /**
     * Bug 168: When a JFR file from a newer JDK has missing annotations (contentType=null), the
     * startTime field is stored as a plain 'long' instead of 'timestamp'. JFRView should still
     * display it as a formatted time, not raw nanosecond ticks.
     *
     * <p>This test verifies that InstantColumn handles Long values correctly (converting
     * nanoseconds from epoch to formatted time).
     */
    @Test
    public void testInstantColumnFormatsLongNanosAsTime() {
        // Simulate what happens when startTime is stored as Long (missing @Timestamp annotation)
        var col = new JFRView.InstantColumn("startTime");
        // 2024-05-24T14:06:58Z as nanoseconds from epoch
        long nanosFromEpoch = 1716559618353857958L;

        var intType =
                new IntType(
                        999,
                        "long",
                        "",
                        8,
                        true,
                        me.bechberger.condensed.CondensedOutputStream.OverflowMode.ERROR);
        var type =
                new StructType<>(
                        1000,
                        "TestEvent",
                        List.of(
                                new Field<>(
                                        "startTime",
                                        "",
                                        intType,
                                        o -> null,
                                        EmbeddingType.INLINE)));
        @SuppressWarnings({"unchecked", "rawtypes"})
        var event = new ReadStruct((StructType) type, Map.of("startTime", nanosFromEpoch));

        var result = col.format(event, 1);
        assertNotNull(result);
        assertEquals(1, result.size());
        // Should be a formatted time, not raw ticks
        assertFalse(
                result.get(0).contains("1716559618"),
                "Should not show raw nanosecond ticks: " + result.get(0));
        assertTrue(
                result.get(0).matches("\\d{2}:\\d{2}:\\d{2}"),
                "Should show HH:mm:ss format: " + result.get(0));
    }

    /**
     * Bug 168: fieldToColumn should map 'timestamp' type name to InstantColumn. This verifies the
     * existing behavior works.
     */
    @Test
    public void testFieldToColumnMapsTimestampType() {
        var timestampType =
                new me.bechberger.condensed.types.VarIntType(100, "timestamp", "", false, 1);
        var field = new Field<>("startTime", "", timestampType, o -> null, EmbeddingType.INLINE, 1);
        var column = JFRView.fieldToColumn(field);
        assertInstanceOf(JFRView.InstantColumn.class, column);
    }

    /**
     * Bug 59/212: StructColumn single-row rendering should show all sub-fields, not just the first
     * one. This prevents raw address numbers from hiding useful memory-formatted values in
     * VirtualSpace structs.
     */
    @Test
    public void testStructColumnSingleRowShowsAllFields() {
        // Create a struct type with two fields: a raw long and a memory-typed field
        var longType =
                new me.bechberger.condensed.types.IntType(
                        900,
                        "long",
                        "",
                        8,
                        true,
                        me.bechberger.condensed.CondensedOutputStream.OverflowMode.ERROR);
        var memType =
                new me.bechberger.condensed.types.VarIntType(
                        901, "memory varint BYTES", "", false, 1);

        var innerStructType =
                new StructType<>(
                        902,
                        "jdk.types.VirtualSpace",
                        List.of(
                                new Field<>("start", "", longType, o -> null, EmbeddingType.INLINE),
                                new Field<>(
                                        "committedSize",
                                        "",
                                        memType,
                                        o -> null,
                                        EmbeddingType.INLINE)));

        var outerField =
                new Field<>("heapSpace", "", innerStructType, o -> null, EmbeddingType.INLINE);

        var column = JFRView.fieldToColumn(outerField);

        // Create test data: struct with a raw address and a formatted memory value
        @SuppressWarnings({"unchecked", "rawtypes"})
        var innerStruct =
                new ReadStruct(
                        (StructType) innerStructType,
                        Map.of("start", 21474836480L, "committedSize", 822083584L));

        var outerType =
                new StructType<>(
                        903,
                        "TestEvent",
                        List.of(
                                new Field<>(
                                        "heapSpace",
                                        "",
                                        innerStructType,
                                        o -> null,
                                        EmbeddingType.INLINE)));
        @SuppressWarnings({"unchecked", "rawtypes"})
        var event = new ReadStruct((StructType) outerType, Map.of("heapSpace", innerStruct));

        var result = column.format(event, 1);
        assertNotNull(result);
        assertEquals(1, result.size());
        // Should contain comma-separated values, showing both fields
        assertTrue(
                result.get(0).contains(","),
                "Single-row struct should show comma-separated fields: " + result.get(0));
        // Should contain a formatted memory value (not just raw numbers)
        assertTrue(
                result.get(0).contains("MB"),
                "Should include formatted memory value: " + result.get(0));
    }
}
