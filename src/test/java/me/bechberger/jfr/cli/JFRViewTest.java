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

    @SuppressWarnings({"unchecked", "rawtypes"})
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
    public void testStructColumnFormatSingleRowUsesOnlyFirstPart() {
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
        assertEquals("alpha", rows.get(0));
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
}
