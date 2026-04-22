package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class TruncateModeTest {

    @Test
    public void testFromCliValueBegin() {
        assertEquals(TruncateMode.BEGIN, TruncateMode.fromCliValue("begin"));
    }

    @Test
    public void testFromCliValueBeginning() {
        assertEquals(TruncateMode.BEGIN, TruncateMode.fromCliValue("beginning"));
    }

    @Test
    public void testFromCliValueBeginingTypo() {
        assertEquals(TruncateMode.BEGIN, TruncateMode.fromCliValue("begining"));
    }

    @Test
    public void testFromCliValueEnd() {
        assertEquals(TruncateMode.END, TruncateMode.fromCliValue("end"));
    }

    @Test
    public void testFromCliValueIsCaseInsensitiveAndTrimmed() {
        assertEquals(TruncateMode.END, TruncateMode.fromCliValue("  EnD  "));
    }

    @Test
    public void testFromCliValueRejectsUnknownValue() {
        var exception =
                assertThrows(
                        IllegalArgumentException.class, () -> TruncateMode.fromCliValue("middle"));
        assertTrue(exception.getMessage().contains("Unknown truncate mode"));
    }
}
