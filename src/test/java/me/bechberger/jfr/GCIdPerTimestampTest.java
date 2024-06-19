package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GCIdPerTimestampTest {

    @Test
    void testGetAndPut() {
        GCIdPerTimestamp gcIdPerTimestamp = new GCIdPerTimestamp();
        assertEquals(0, gcIdPerTimestamp.size());
        assertEquals(1, gcIdPerTimestamp.getClosestGCId(Instant.ofEpochMilli(0)));
        gcIdPerTimestamp.put(Instant.ofEpochMilli(1), 1);
        assertEquals(1, gcIdPerTimestamp.getClosestGCId(Instant.ofEpochMilli(0)));
        assertEquals(1, gcIdPerTimestamp.getClosestGCId(Instant.ofEpochMilli(1)));
        assertEquals(2, gcIdPerTimestamp.getClosestGCId(Instant.ofEpochMilli(2)));
        assertEquals(2, gcIdPerTimestamp.getClosestGCId(Instant.ofEpochMilli(3)));
    }

    @Test
    void testFull() {
        GCIdPerTimestamp gcIdPerTimestamp = new GCIdPerTimestamp();
        for (int i = 0; i < GCIdPerTimestamp.MAX_SIZE + 100; i++) {
            gcIdPerTimestamp.put(Instant.ofEpochMilli(i), i);
            assertEquals(Math.min(GCIdPerTimestamp.MAX_SIZE, i + 1), gcIdPerTimestamp.size());
        }
    }
}
