package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class ReadStructUtilTest {

    // --- getDefaultValue tests ---

    @Test
    public void testGetDefaultValueBoolean() {
        assertEquals(false, ReadStructUtil.getDefaultValue(boolean.class));
    }

    @Test
    public void testGetDefaultValueInt() {
        assertEquals(0, ReadStructUtil.getDefaultValue(int.class));
    }

    @Test
    public void testGetDefaultValueLong() {
        assertEquals(0L, ReadStructUtil.getDefaultValue(long.class));
    }

    @Test
    public void testGetDefaultValueFloat() {
        assertEquals(0.0f, ReadStructUtil.getDefaultValue(float.class));
    }

    @Test
    public void testGetDefaultValueDouble() {
        assertEquals(0.0, ReadStructUtil.getDefaultValue(double.class));
    }

    @Test
    public void testGetDefaultValueByte() {
        assertEquals((byte) 0, ReadStructUtil.getDefaultValue(byte.class));
    }

    @Test
    public void testGetDefaultValueShort() {
        assertEquals((short) 0, ReadStructUtil.getDefaultValue(short.class));
    }

    @Test
    public void testGetDefaultValueChar() {
        assertEquals('\0', ReadStructUtil.getDefaultValue(char.class));
    }

    @Test
    public void testGetDefaultValueObject() {
        assertNull(ReadStructUtil.getDefaultValue(String.class));
    }

    // --- createInstanceFromReadStruct tests ---

    public record SimpleRecord(String name, int value) {}

    @Test
    public void testCreateInstanceFromReadStructRecord() {
        var result =
                ReadStructUtil.createInstanceFromReadStruct(
                        SimpleRecord.class, Map.of("name", "hello", "value", 42));
        assertEquals("hello", result.name());
        assertEquals(42, result.value());
    }

    @Test
    public void testCreateInstanceFromReadStructPartialFields() {
        // Only provide "name", "value" should default to 0
        var result =
                ReadStructUtil.createInstanceFromReadStruct(
                        SimpleRecord.class, Map.of("name", "test"));
        assertEquals("test", result.name());
        assertEquals(0, result.value());
    }

    public record AllPrimitivesRecord(
            boolean b, int i, long l, float f, double d, byte by, short s, char c) {}

    @Test
    public void testCreateInstanceWithAllPrimitiveDefaults() {
        var result =
                ReadStructUtil.createInstanceFromReadStruct(AllPrimitivesRecord.class, Map.of());
        assertFalse(result.b());
        assertEquals(0, result.i());
        assertEquals(0L, result.l());
        assertEquals(0.0f, result.f());
        assertEquals(0.0, result.d());
        assertEquals((byte) 0, result.by());
        assertEquals((short) 0, result.s());
        assertEquals('\0', result.c());
    }

    public record StringRecord(String value) {}

    @Test
    public void testCreateInstanceNullDefault() {
        var result = ReadStructUtil.createInstanceFromReadStruct(StringRecord.class, Map.of());
        assertNull(result.value());
    }

    // --- getFieldsForClass tests ---

    @Test
    public void testGetFieldsForClass() {
        var fields = ReadStructUtil.getFieldsForClass(SimpleRecord.class).toList();
        assertEquals(2, fields.size());
        assertEquals("name", fields.get(0).getName());
        assertEquals("value", fields.get(1).getName());
    }

    public static class ClassWithStaticField {
        public static int staticField = 0;
        public int instanceField;
        public String _privateishField;
    }

    @Test
    public void testGetFieldsForClassFiltersStaticAndUnderscore() {
        var fields = ReadStructUtil.getFieldsForClass(ClassWithStaticField.class).toList();
        // Should only include instanceField, not staticField or _privateishField
        assertEquals(1, fields.size());
        assertEquals("instanceField", fields.get(0).getName());
    }
}
