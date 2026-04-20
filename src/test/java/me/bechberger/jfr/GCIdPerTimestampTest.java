package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
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

    /**
     * Bug: When the same timestamp maps to different GC IDs, gcIdPerTimestamp.put() overwrites the
     * TreeMap entry, but the old GC ID is never removed from the reverse map (timestampPerGCId).
     * This causes the two maps to become inconsistent.
     *
     * <p>Consequence: The orphaned gcId in timestampPerGCId prevents re-insertion of that gcId at a
     * later timestamp (the containsKey check returns true and the method returns early). Also a
     * memory leak as orphaned entries accumulate.
     *
     * <p>Fix: Before putting into gcIdPerTimestamp, check if the timestamp already exists and
     * remove the old gcId from timestampPerGCId.
     */
    @Test
    void testDuplicateTimestampCausesDualMapInconsistency() throws Exception {
        GCIdPerTimestamp gcIdPerTimestamp = new GCIdPerTimestamp();

        // Put gc1 at timestamp T1
        gcIdPerTimestamp.put(Instant.ofEpochMilli(1), 1);
        assertEquals(1, gcIdPerTimestamp.size());

        // Put gc2 at the SAME timestamp T1 (e.g., two GC events at same instant)
        gcIdPerTimestamp.put(Instant.ofEpochMilli(1), 2);
        // gcIdPerTimestamp has {T1→2}, size is 1 — correct
        assertEquals(1, gcIdPerTimestamp.size());

        // Access internal maps via reflection to verify consistency
        Field reverseMapField = GCIdPerTimestamp.class.getDeclaredField("timestampPerGCId");
        reverseMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, Instant> reverseMap = (Map<Long, Instant>) reverseMapField.get(gcIdPerTimestamp);

        // BUG: timestampPerGCId still has {1→T1, 2→T1} — should only have {2→T1}
        // gc1 is orphaned in the reverse map
        assertEquals(
                1,
                reverseMap.size(),
                "Reverse map should have 1 entry (gc2→T1) but has orphaned gc1→T1");
    }

    /**
     * Bug: Same as above, but demonstrates the functional consequence. After overwriting gc1→T1
     * with gc2→T1, putting gc1 at a different timestamp is silently dropped because
     * timestampPerGCId.containsKey(gc1) returns true.
     */
    @Test
    void testOrphanedGCIdBlocksReinsertion() {
        GCIdPerTimestamp gcIdPerTimestamp = new GCIdPerTimestamp();

        // Put gc1 at timestamp T1=1ms
        gcIdPerTimestamp.put(Instant.ofEpochMilli(1), 1);

        // Put gc2 at the SAME timestamp T1=1ms — overwrites gc1 in TreeMap
        gcIdPerTimestamp.put(Instant.ofEpochMilli(1), 2);

        // Now try to add gc1 at a DIFFERENT timestamp T2=100ms
        // This should succeed since gc1 is no longer actively mapped
        gcIdPerTimestamp.put(Instant.ofEpochMilli(100), 1);

        // BUG: The put above is silently dropped because timestampPerGCId.containsKey(1)
        // still returns true (orphaned entry). So gc1 is NOT at T2=100ms.
        // getClosestGCId(100) should return 1, but it won't because the entry was dropped.
        assertEquals(
                1,
                gcIdPerTimestamp.getClosestGCId(Instant.ofEpochMilli(100)),
                "GC ID 1 should be associated with timestamp 100ms after re-insertion");
    }
}
