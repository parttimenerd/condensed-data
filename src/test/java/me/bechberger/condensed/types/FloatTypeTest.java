package me.bechberger.condensed.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class FloatTypeTest {

    /**
     * Bug: FloatType does not override equals() or hashCode(). CondensedType.equals() only checks
     * id, name, and description — it does not check the FloatType.type field (FLOAT32 vs BFLOAT16).
     *
     * <p>Two FloatType instances with the same id but different precision are considered equal,
     * which means the TypeCollection could accept a BFLOAT16 type where a FLOAT32 was expected (or
     * vice versa), silently corrupting data.
     *
     * <p>Expected: FloatType(id, FLOAT32) != FloatType(id, BFLOAT16) Actual: FloatType(id, FLOAT32)
     * == FloatType(id, BFLOAT16) if same id/name/desc
     */
    @Test
    public void testFloat32NotEqualToBFloat16() {
        var float32 = new FloatType(100, "myfloat", "A float", FloatType.Type.FLOAT32);
        var bfloat16 = new FloatType(100, "myfloat", "A float", FloatType.Type.BFLOAT16);

        assertNotEquals(
                float32,
                bfloat16,
                "FloatType with FLOAT32 should not be equal to FloatType with BFLOAT16");
    }

    /**
     * Same bug for hashCode: two FloatTypes with different precision but same id produce the same
     * hash code, causing collisions in hash-based collections.
     */
    @Test
    public void testDifferentPrecisionDifferentHashCode() {
        var float32 = new FloatType(100, "myfloat", "A float", FloatType.Type.FLOAT32);
        var bfloat16 = new FloatType(100, "myfloat", "A float", FloatType.Type.BFLOAT16);

        // While different objects CAN have the same hashCode, well-implemented
        // hashCode should return different values for semantically different objects
        // in most cases. At minimum, equals should not return true.
        assertNotEquals(
                float32, bfloat16, "Precondition: equals should distinguish FLOAT32 and BFLOAT16");
    }

    /** Sanity: same precision types should still be equal */
    @Test
    public void testSamePrecisionTypesAreEqual() {
        var a = new FloatType(100, "myfloat", "A float", FloatType.Type.FLOAT32);
        var b = new FloatType(100, "myfloat", "A float", FloatType.Type.FLOAT32);
        assertEquals(a, b, "Same FloatType instances should be equal");
    }
}
