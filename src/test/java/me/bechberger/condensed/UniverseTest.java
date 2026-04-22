package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.types.IntType;
import org.junit.jupiter.api.Test;

public class UniverseTest {

    // --- EmbeddingType tests ---

    @Test
    public void testEmbeddingTypeIsNullableInline() {
        assertFalse(EmbeddingType.INLINE.isNullable());
    }

    @Test
    public void testEmbeddingTypeIsNullableNullableInline() {
        assertTrue(EmbeddingType.NULLABLE_INLINE.isNullable());
    }

    @Test
    public void testEmbeddingTypeIsNullableReference() {
        assertFalse(EmbeddingType.REFERENCE.isNullable());
    }

    @Test
    public void testEmbeddingTypeIsNullableReferencePerType() {
        assertFalse(EmbeddingType.REFERENCE_PER_TYPE.isNullable());
    }

    @Test
    public void testEmbeddingTypeValueOf() {
        assertEquals(EmbeddingType.INLINE, EmbeddingType.valueOf(0));
        assertEquals(EmbeddingType.NULLABLE_INLINE, EmbeddingType.valueOf(1));
        assertEquals(EmbeddingType.REFERENCE, EmbeddingType.valueOf(2));
        assertEquals(EmbeddingType.REFERENCE_PER_TYPE, EmbeddingType.valueOf(3));
    }

    @Test
    public void testEmbeddingTypeValues() {
        assertEquals(4, EmbeddingType.values().length);
    }

    // --- HashAndEqualsConfig tests ---

    @Test
    public void testHashAndEqualsConfigNone() {
        assertNotNull(HashAndEqualsConfig.NONE);
    }

    @Test
    public void testHashAndEqualsConfigPutAndGet() {
        var config = new HashAndEqualsConfig();
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        config.<Long>put(
                intType.getName(),
                v ->
                        new Universe.HashAndEqualsWrapper<Long>() {
                            @Override
                            public int hashCode() {
                                return Long.hashCode(v);
                            }

                            @Override
                            public boolean equals(Object other) {
                                return other instanceof Universe.HashAndEqualsWrapper;
                            }

                            @Override
                            public Long value() {
                                return v;
                            }
                        });
        var factory = config.getWrapperFactory(intType);
        assertTrue(factory.isPresent());
    }

    @Test
    public void testHashAndEqualsConfigGetMissing() {
        var config = new HashAndEqualsConfig();
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        var factory = config.getWrapperFactory(intType);
        assertTrue(factory.isEmpty());
    }
}
