package me.bechberger.util;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Positive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MemoryUtilTest {

    @Property
    public void testFormatRoundtrip(@ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long bytes) {
        String formatted = MemoryUtil.formatMemory(bytes, 20);
        long parsed = MemoryUtil.parseMemory(formatted);
        assertEquals(bytes, parsed);
    }

    @Test
    public void testParse10Bytes() {
        assertEquals(10, MemoryUtil.parseMemory("10B"));
        assertEquals(10, MemoryUtil.parseMemory("10"));
    }
}
