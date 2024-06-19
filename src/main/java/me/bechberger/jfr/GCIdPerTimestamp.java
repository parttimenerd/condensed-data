package me.bechberger.jfr;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Maps a GC ID to a timestamp, removes oldest entries to keep the size to {@value #MAX_SIZE} */
public class GCIdPerTimestamp {
    private final TreeMap<Instant, Long> gcIdPerTimestamp = new TreeMap<>();
    private final Map<Long, Instant> timestampPerGCId = new HashMap<>();

    /** should be larger than the caches of other types */
    public static final int MAX_SIZE = 100;

    public void put(Instant startTimestamp, long gcId) {
        if (timestampPerGCId.containsKey(gcId)) {
            return;
        }
        if (gcIdPerTimestamp.size() >= MAX_SIZE) {
            var first = gcIdPerTimestamp.firstEntry();
            gcIdPerTimestamp.remove(first.getKey());
            timestampPerGCId.remove(first.getValue());
        }
        gcIdPerTimestamp.put(startTimestamp, gcId);
        timestampPerGCId.put(gcId, startTimestamp);
    }

    /** Get the GC ID for the given timestamp, that comes <b>after</b> the instant */
    public long getClosestGCId(Instant timestamp) {
        var entry = gcIdPerTimestamp.ceilingEntry(timestamp);
        if (entry == null) {
            var floor = gcIdPerTimestamp.floorEntry(timestamp);
            if (floor == null) {
                return 1; // initial GC
            }
            return floor.getValue() + 1;
        }
        return entry.getValue();
    }

    public int size() {
        return gcIdPerTimestamp.size();
    }
}
