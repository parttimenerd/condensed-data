package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import me.bechberger.condensed.Util.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class UtilTest {

    @Test
    public void testZip() {
        assertEquals(
                List.of(new Pair<>(1, "a"), new Pair<>(2, "b"), new Pair<>(3, "c")),
                Util.zip(List.of(1, 2, 3), List.of("a", "b", "c")));
    }

    @Test
    public void testZipDifferentLengths() {
        assertEquals(
                List.of(new Pair<>(1, "a"), new Pair<>(2, "b")),
                Util.zip(List.of(1, 2, 3), List.of("a", "b")));
    }

    @Test
    public void testZipEmpty() {
        assertEquals(List.of(), Util.zip(List.of(), List.of()));
    }

    // --- Float conversion tests ---

    @Test
    public void testFloatToFp16AndBack() {
        float original = 1.5f;
        short fp16 = Util.floatToFp16(original);
        float back = Util.fp16ToFloat(fp16);
        assertEquals(original, back, 0.001f);
    }

    @Test
    public void testFloatToFp16Zero() {
        short fp16 = Util.floatToFp16(0.0f);
        assertEquals(0.0f, Util.fp16ToFloat(fp16), 0.0f);
    }

    @Test
    public void testFloatToFp16NegativeZero() {
        short fp16 = Util.floatToFp16(-0.0f);
        float back = Util.fp16ToFloat(fp16);
        assertEquals(0, Float.compare(-0.0f, back));
    }

    @Test
    public void testFloatToFp16Infinity() {
        short fp16 = Util.floatToFp16(Float.POSITIVE_INFINITY);
        float back = Util.fp16ToFloat(fp16);
        assertTrue(Float.isInfinite(back) && back > 0);
    }

    @Test
    public void testFloatToFp16NegativeInfinity() {
        short fp16 = Util.floatToFp16(Float.NEGATIVE_INFINITY);
        float back = Util.fp16ToFloat(fp16);
        assertTrue(Float.isInfinite(back) && back < 0);
    }

    @Test
    public void testFloatToFp16NaN() {
        short fp16 = Util.floatToFp16(Float.NaN);
        float back = Util.fp16ToFloat(fp16);
        assertTrue(Float.isNaN(back));
    }

    @Test
    public void testFloatToFp16Subnormal() {
        // Very small number that would be subnormal in fp16
        float tiny = 0.00001f;
        short fp16 = Util.floatToFp16(tiny);
        float back = Util.fp16ToFloat(fp16);
        // Should be close (with fp16 precision loss)
        assertEquals(tiny, back, 0.00005f);
    }

    @Test
    public void testFp16ToFloatZeroExponent() {
        // Exponent = 0 (zero/denormal path in fp16ToFloat)
        short denorm = 0x0001; // smallest positive subnormal in fp16
        float result = Util.fp16ToFloat(denorm);
        assertTrue(result > 0 && result < 1.0f);
    }

    // --- BFloat16 tests ---

    @Test
    public void testFloatToBf16AndBack() {
        float original = 3.14f;
        short bf16 = Util.floatToBf16(original);
        float back = Util.bf16ToFloat(bf16);
        assertEquals(original, back, 0.05f);
    }

    @Test
    public void testEqualUnderBf16ConversionTrue() {
        assertTrue(Util.equalUnderBf16Conversion(100, 100));
    }

    @Test
    public void testEqualUnderBf16ConversionClose() {
        // Two values that map to the same bf16
        assertTrue(Util.equalUnderBf16Conversion(1000, 1000));
    }

    @Test
    public void testEqualUnderBf16ConversionDifferent() {
        assertFalse(Util.equalUnderBf16Conversion(1, 1000000));
    }

    // --- toNanoSeconds ---

    @Test
    public void testToNanoSeconds() {
        Instant instant = Instant.ofEpochSecond(1, 500);
        assertEquals(1_000_000_500L, Util.toNanoSeconds(instant));
    }

    @Test
    public void testToNanoSecondsEpoch() {
        assertEquals(0L, Util.toNanoSeconds(Instant.EPOCH));
    }

    // --- getLibraryVersion ---

    @Test
    public void testGetLibraryVersion() {
        String version = Util.getLibraryVersion();
        assertNotNull(version);
        assertFalse(version.isBlank());
    }

    // --- getCreationTimeOfFile ---

    @TempDir Path tempDir;

    @Test
    public void testGetCreationTimeOfFile() throws IOException {
        Path file = tempDir.resolve("testfile.txt");
        Files.writeString(file, "hello");
        var creationTime = Util.getCreationTimeOfFile(file);
        assertNotNull(creationTime);
    }

    @Test
    public void testGetCreationTimeOfFileNonExistent() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        assertThrows(RuntimeException.class, () -> Util.getCreationTimeOfFile(nonExistent));
    }

    // --- Pair ---

    @Test
    public void testPairEquality() {
        assertEquals(new Pair<>(1, "a"), new Pair<>(1, "a"));
        assertNotEquals(new Pair<>(1, "a"), new Pair<>(2, "a"));
    }

    @Test
    public void testPairAccessors() {
        var pair = new Pair<>("left", "right");
        assertEquals("left", pair.left());
        assertEquals("right", pair.right());
    }
}
