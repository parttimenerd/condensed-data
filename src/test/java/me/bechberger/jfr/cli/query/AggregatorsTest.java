package me.bechberger.jfr.cli.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.bechberger.jfr.cli.query.Aggregators.Reducer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Aggregators} reducer semantics, focused on the order-sensitive
 * FIRST/LAST/LAST_BATCH functions. {@link QueryEvaluator} feeds each reducer the group's events in
 * chronological (stable {@code startTime}) order, so the reducer's job is purely to keep the first
 * or last value it sees.
 */
class AggregatorsTest {

    private static Object reduce(String fn, Object... values) {
        Reducer r = Aggregators.reducer(fn).get();
        for (Object v : values) {
            r.accept(v);
        }
        return r.result();
    }

    @Test
    void firstKeepsEarliestValue() {
        assertEquals("a", reduce("FIRST", "a", "b", "c"));
    }

    @Test
    void lastKeepsLatestValue() {
        assertEquals("c", reduce("LAST", "a", "b", "c"));
    }

    @Test
    void lastBatchKeepsLatestValueInBatch() {
        // Regression for Bug 293: within the already-batch-filtered subset, jfr's representative
        // is the LAST event in chronological order, not the first. memory-leaks-by-* reports the
        // most-recently-allocated sample per group.
        assertEquals("last", reduce("LAST_BATCH", "first", "middle", "last"));
    }

    @Test
    void lastBatchSingleValue() {
        assertEquals("only", reduce("LAST_BATCH", "only"));
    }
}
