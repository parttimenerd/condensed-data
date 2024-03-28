package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Stream;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.SpecifiedType.NoSuchDefaultTypeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TypeCollectionTest {

    static Stream<Arguments> normalizeArguments() {
        return Stream.of(
                Arguments.of(0L, 0),
                Arguments.of(1L, (byte) 1),
                Arguments.of(1L, (char) 1),
                Arguments.of(true, true),
                Arguments.of("", ""),
                Arguments.of(List.of(1L), new int[] {1}),
                Arguments.of(List.of(1L), new Integer[] {1}),
                Arguments.of(List.of(1L), new long[] {1}),
                Arguments.of(List.of(1L), new Long[] {1L}),
                Arguments.of(List.of(1f), new float[] {1f}),
                Arguments.of(List.of(1f), new double[] {1}),
                Arguments.of(List.of(1L), new char[] {1}),
                Arguments.of(List.of(1L), new Character[] {1}),
                Arguments.of(List.of(1L), new byte[] {1}),
                Arguments.of(List.of(1L), new Byte[] {1}),
                Arguments.of(List.of(1L), new short[] {1}),
                Arguments.of(List.of(true), new boolean[] {true}),
                Arguments.of(1f, 1f),
                Arguments.of(1f, (double) 1));
    }

    @ParameterizedTest
    @MethodSource("normalizeArguments")
    public void testNormalizeInt(Object expected, Object value) {
        assertEquals(expected, TypeCollection.normalize(value));
    }

    @Test
    public void testGetTypes() {
        List<CondensedType<?, ?>> types = new TypeCollection().getTypes();
        assertEquals(5, types.size());
    }

    @Test
    public void testGetTypeOrNull() {
        var col = new TypeCollection();
        assertNull(col.getTypeOrNull("invalid"));
        assertEquals(
                IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id()),
                col.getTypeOrNull("int4"));
    }

    @Test
    @SuppressWarnings({"rawtypes"})
    public void testGetDefaultTypeInstance() {
        assertEquals(
                IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id()),
                TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE));
        assertThrows(
                NoSuchDefaultTypeException.class,
                () ->
                        TypeCollection.getDefaultTypeInstance(
                                (SpecifiedType<? extends CondensedType<Object, Object>>)
                                        (SpecifiedType) StructType.SPECIFIED_TYPE));
    }
}
