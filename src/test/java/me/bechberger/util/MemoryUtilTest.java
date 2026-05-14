package me.bechberger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class MemoryUtilTest {

    @Property
    public void testFormatRoundtrip(
            @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long bytes) {
        String formatted = MemoryUtil.formatMemory(bytes, 20);
        long parsed = MemoryUtil.parseMemory(formatted);
        assertEquals(bytes, parsed);
    }

    @Test
    public void testParse10Bytes() {
        assertEquals(10, MemoryUtil.parseMemory("10B"));
        assertEquals(10, MemoryUtil.parseMemory("10"));
    }

    // ========== Bug reproducer tests ==========

    /**
     * Bug: parseMemory("b") crashes with StringIndexOutOfBoundsException. After lowercasing "b" and
     * stripping the trailing "b", the string is empty. Then charAt(memory.length() - 1) →
     * charAt(-1) throws.
     */
    @Test
    public void testParseMemoryBareB() {
        // Should throw IllegalArgumentException, not StringIndexOutOfBoundsException
        assertThrows(IllegalArgumentException.class, () -> MemoryUtil.parseMemory("b"));
    }

    /** Bug: parseMemory("B") also crashes — same issue after lowercasing. */
    @Test
    public void testParseMemoryBareUpperB() {
        assertThrows(IllegalArgumentException.class, () -> MemoryUtil.parseMemory("B"));
    }

    /**
     * Bug: formatMemory with low decimals is lossy for values not exactly on a power-of-1024
     * boundary. formatMemory(1025, 1) -> "1.0KB" -> parseMemory -> 1024 (not 1025).
     *
     * <p>This is effectively a design limitation: the format rounds, but the test
     * testFormatRoundtrip uses decimals=20 to avoid this. With decimals=1 (used in MemoryColumn),
     * precision is lost for most values.
     */
    @ParameterizedTest
    @CsvSource({
        "1025, 1", "1500, 1", "2047, 1",
    })
    public void testFormatMemoryLossyRoundtrip(long bytes, int decimals) {
        String formatted = MemoryUtil.formatMemory(bytes, decimals);
        long parsed = MemoryUtil.parseMemory(formatted);
        assertEquals(
                bytes,
                parsed,
                "Roundtrip lost precision: " + bytes + " -> \"" + formatted + "\" -> " + parsed);
    }

    // ========== Additional coverage tests ==========

    @ParameterizedTest
    @CsvSource({
        "1KB, 1024",
        "1kb, 1024",
        "1.5KB, 1536",
        "1MB, 1048576",
        "1GB, 1073741824",
        "1TB, 1099511627776",
        "0, 0",
        "0B, 0",
        "100, 100",
        "100B, 100",
    })
    public void testParseMemoryUnits(String input, long expected) {
        assertEquals(expected, MemoryUtil.parseMemory(input));
    }

    @Test
    public void testFormatMemoryZero() {
        assertEquals("0B", MemoryUtil.formatMemory(0, 1));
    }

    @Test
    public void testFormatMemoryExactKB() {
        assertEquals("1.0KB", MemoryUtil.formatMemory(1024, 1));
    }

    @Test
    public void testFormatMemoryExactMB() {
        assertEquals("1.0MB", MemoryUtil.formatMemory(1048576, 1));
    }

    @Test
    public void testFormatMemoryExactGB() {
        assertEquals("1.0GB", MemoryUtil.formatMemory(1073741824L, 1));
    }

    @Test
    public void testFormatMemoryNegativeLargeValueScalesUnit() {
        assertEquals("-1.00GB", MemoryUtil.formatMemory(-1073741824L));
    }

    @Test
    public void testFormatMemoryNegativeSmallValueStaysBytes() {
        assertEquals("-10B", MemoryUtil.formatMemory(-10, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "xyz", "KB"})
    public void testParseMemoryInvalidInputs(String input) {
        assertThrows(Exception.class, () -> MemoryUtil.parseMemory(input));
    }

    // ========== Bug reproducer tests (bugs 32-33) ==========

    /**
     * Bug 32: formatMemory crashes with StringIndexOutOfBoundsException for values above ~1024 TB
     * (1024^5). The "KMGT" suffix string only has 4 characters (indices 0-3), but exp can be 5 or 6
     * for petabyte/exabyte values.
     *
     * <p>exp = (int)(Math.log(bytes) / Math.log(1024)) For Long.MAX_VALUE (~9.2 exabytes), exp = 6
     * "KMGT".charAt(6 - 1) → charAt(5) → StringIndexOutOfBoundsException
     */
    @Test
    public void testFormatMemoryLargeValueCrashes() {
        // 1 PB = 1024^5 = 1,125,899,906,842,624
        long onePB = 1024L * 1024 * 1024 * 1024 * 1024;
        // This should not crash — it should either format as PB/EB or cap at TB
        String pbFormatted = MemoryUtil.formatMemory(onePB, 2);
        assertTrue(
                pbFormatted.endsWith("B"),
                "formatMemory should handle petabyte values, got: " + pbFormatted);

        // Long.MAX_VALUE = ~9.2 EB, exp=6
        String maxFormatted = MemoryUtil.formatMemory(Long.MAX_VALUE, 2);
        assertTrue(
                maxFormatted.endsWith("B"),
                "formatMemory should handle Long.MAX_VALUE, got: " + maxFormatted);
    }

    /**
     * Bug 33: formatMemory with MemoryUnit.BITS for sub-1024 values always returns "B" suffix
     * (bytes) instead of "b" (bits). The early return on line 15 is: return bytes + "B"; which is
     * hardcoded to "B" and ignores the unit parameter entirely.
     *
     * <p>Expected: formatMemory(100, 1, BITS) → "100b" Actual: formatMemory(100, 1, BITS) → "100B"
     */
    @Test
    public void testFormatMemoryBitsUnitSub1024() {
        String result = MemoryUtil.formatMemory(100, 1, MemoryUtil.MemoryUnit.BITS);
        assertEquals(
                "100b",
                result,
                "Sub-1024 value with BITS unit should use 'b' suffix, not 'B'. "
                        + "The early return 'bytes + \"B\"' ignores the unit parameter.");
    }

    // ========== Bug 153/154: formatMemory with maxDecimals caps output length ==========

    /**
     * Bug 153/154: formatMemory with unbounded decimals produces very long strings that get
     * truncated by table column widths, cutting off the unit suffix (e.g. "670.590027" instead of
     * "670.59MB"). With maxDecimals=2, output should always include the unit suffix.
     */
    @Test
    public void testFormatMemoryWithMaxDecimalsCapsLength() {
        // 82,403,442 bytes ≈ 78.59 MB — exact representation needs 6+ decimals
        String result = MemoryUtil.formatMemory(82_403_442, 1, 2, MemoryUtil.MemoryUnit.BYTES);
        assertTrue(result.endsWith("MB"), "Should always end with unit suffix, got: " + result);
        assertEquals("78.59MB", result);
    }

    @Test
    public void testFormatMemoryWithMaxDecimalsExactValue() {
        // 1 MB exactly — should match within 1 decimal
        String result = MemoryUtil.formatMemory(1048576, 1, 2, MemoryUtil.MemoryUnit.BYTES);
        assertEquals("1.0MB", result);
    }

    @Test
    public void testFormatMemoryWithMaxDecimalsLargeValue() {
        // ~670 MB — exact would need many decimals, capped at 2
        long bytes = (long) (670.590027 * 1024 * 1024);
        String result = MemoryUtil.formatMemory(bytes, 1, 2, MemoryUtil.MemoryUnit.BYTES);
        assertTrue(result.endsWith("MB"), "Should always end with unit suffix, got: " + result);
        assertTrue(
                result.length() <= 10,
                "With maxDecimals=2, result should fit in 10-char column, got: " + result);
    }

    @Test
    public void testFormatMemoryMaxDecimalsPreservesExactRoundtrip() {
        // Old behavior: 3-arg overload delegates to 4-arg with maxDecimals=20
        long bytes = 82_403_442;
        String exact = MemoryUtil.formatMemory(bytes, 1, MemoryUtil.MemoryUnit.BYTES);
        long parsed = MemoryUtil.parseMemory(exact);
        assertEquals(bytes, parsed, "3-arg overload should still do exact round-trip");
    }
}
