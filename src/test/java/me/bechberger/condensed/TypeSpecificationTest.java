package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.types.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** Testing that type specifications can be written and read back */
public class TypeSpecificationTest {

    @Property
    @SuppressWarnings("rawtypes")
    public void testIntTypeRoundTrip(
            @ForAll @IntRange(min = 1, max = 8) int width,
            @ForAll boolean signed,
            @ForAll OverflowMode overflowMode) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out ->
                                        out.writeAndStoreType(
                                                id -> new IntType(id, width, signed, overflowMode)),
                                true))) {
            IntType result = (IntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new IntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, width, signed, overflowMode),
                    result);
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void testIntType() {
        IntType intType =
                new IntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, 4, true, OverflowMode.ERROR);
        byte[] data =
                CondensedOutputStream.use(
                        out -> {
                            out.writeAndStoreType(
                                    i -> {
                                        assertEquals(i, intType.getId());
                                        return intType;
                                    });
                            intType.writeTo(out, -1L);
                            out.writeMessage(intType, -1000L);
                        },
                        true);
        try (var in = new CondensedInputStream(data)) {
            IntType result = (IntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(intType, result);
            assertEquals(-1, intType.readFrom(in));
            var inst = in.readNextInstance();
            assertNotNull(inst);
            assertEquals(-1000L, inst.value());
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testVarIntTypeRoundTrip(
            @ForAll String name,
            @ForAll String description,
            @ForAll boolean signed,
            @ForAll long value) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out -> {
                                    var type =
                                            out.writeAndStoreType(
                                                    id ->
                                                            new VarIntType(
                                                                    id, name, description, signed));
                                    out.writeMessage(type, value);
                                },
                                true))) {
            VarIntType result = (VarIntType) (CondensedType) in.readNextTypeMessageAndProcess();

            assertEquals(
                    new VarIntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description, signed),
                    result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testFloatTypeRoundTrip(
            @ForAll String name, @ForAll String description, @ForAll float value) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out -> {
                                    var type =
                                            out.writeAndStoreType(
                                                    id -> new FloatType(id, name, description));
                                    out.writeMessage(type, value);
                                },
                                true))) {
            FloatType result = (FloatType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new FloatType(TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description), result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @Provide
    public Arbitrary<String> encodings() {
        return Arbitraries.of(Charset.defaultCharset().toString(), "UTF-8", "UTF-16", "UTF-32");
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testStringRoundTrip(
            @ForAll String name,
            @ForAll String description,
            @ForAll("encodings") String encoding,
            @ForAll String value) {
        byte[] outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var type =
                                    out.writeAndStoreType(
                                            id -> new StringType(id, name, description, encoding));
                            out.writeMessage(type, value);
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            StringType result = (StringType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new StringType(
                            TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description, encoding),
                    result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testIntArrayRoundTrip(
            @ForAll String name,
            @ForAll String description,
            @ForAll boolean useDefaultIntType,
            @ForAll @Size(max = 200) List<Integer> value) {
        AtomicReference<ArrayType<?>> typeRef = new AtomicReference<>();
        byte[] outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var innerType =
                                    useDefaultIntType
                                            ? TypeCollection.getDefaultTypeInstance(
                                                    IntType.SPECIFIED_TYPE)
                                            : out.writeAndStoreType(
                                                    id ->
                                                            new IntType(
                                                                    id,
                                                                    4,
                                                                    true,
                                                                    OverflowMode.ERROR));
                            var type =
                                    out.writeAndStoreType(
                                            id ->
                                                    new ArrayType<>(
                                                            id, name, description, innerType));
                            typeRef.set(type);
                            out.writeMessage(type, value.stream().map(Long::valueOf).toList());
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            if (!useDefaultIntType) {
                assertInstanceOf(IntType.class, in.readNextTypeMessageAndProcess());
            }
            ArrayType result = (ArrayType) in.readNextTypeMessageAndProcess();
            assertEquals(typeRef.get(), result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value.stream().map(i -> (long) i).toList(), msg.value());
        }
    }

    @Test
    public void testNestedIntArray() {
        AtomicReference<ArrayType<List<Long>>> typeRef = new AtomicReference<>();
        List<List<Long>> value = List.of(List.of(1L, 2L), List.of(3L, 4L), List.of(), List.of(-5L));
        var outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var innerInnerType =
                                    TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE);
                            var innerType =
                                    out.writeAndStoreType(
                                            id -> new ArrayType<>(id, innerInnerType));
                            var type = out.writeAndStoreType(id -> new ArrayType<>(id, innerType));
                            typeRef.set(type);
                            out.writeMessage(type, value);
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            assertInstanceOf(ArrayType.class, in.readNextTypeMessageAndProcess());
            var actualType = in.<ArrayType<List<Long>>>readNextTypeMessageAndProcess();
            assertEquals(typeRef.get(), actualType);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }
}
