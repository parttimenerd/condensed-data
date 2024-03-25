package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.TypeCollection;
import me.bechberger.condensed.types.VarIntType;
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
                                false))) {
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
                            out.writeAndStoreType(i -> intType);
                            out.writeMessage(intType, -1L);
                            out.writeMessage(intType, -1000L);
                        },
                        false);
        try (var in = new CondensedInputStream(data)) {
            IntType result = (IntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(intType, result);
            assertEquals(-1, intType.readFrom(in));
            assertEquals(-1000, intType.readFrom(in));
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testVarIntTypeRoundTrip(
            @ForAll String name, @ForAll String description, @ForAll boolean signed) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out ->
                                        out.writeAndStoreType(
                                                id ->
                                                        new VarIntType(
                                                                id, name, description, signed)),
                                false))) {
            VarIntType result = (VarIntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new VarIntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description, signed),
                    result);
        }
    }
}
