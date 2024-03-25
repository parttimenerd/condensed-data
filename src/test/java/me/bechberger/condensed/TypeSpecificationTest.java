package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.types.*;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
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
public void testFloatTypeRoundTrip(@ForAll String name, @ForAll String description, @ForAll float value) {
        try (var in =
                     new CondensedInputStream(
                             CondensedOutputStream.use(
                                     out ->
                                     {
                                         var type = out.writeAndStoreType(
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
}
