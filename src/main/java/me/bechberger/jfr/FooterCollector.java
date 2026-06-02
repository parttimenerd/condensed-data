package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;

import java.util.*;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import me.bechberger.condensed.CJFRFooter;
import me.bechberger.condensed.CJFRFooter.AllocStats;
import me.bechberger.condensed.CJFRFooter.CpuStats;
import me.bechberger.condensed.CJFRFooter.GcStats;
import me.bechberger.condensed.CJFRFooter.GcStats.GcBucket;

/**
 * Side-channel accumulator for {@link CJFRFooter} statistics. {@link #collect(RecordedEvent)} is
 * called from {@link BasicJFRWriter#processEvent} after the ignore-filter, adding negligible
 * overhead. Call {@link #build} after all events to get the footer.
 */
public final class FooterCollector {

    private final long bucketSeconds;
    private final Map<String, Long> eventCounts = new HashMap<>();
    private long totalEvents;
    private long firstStartMicros = Long.MAX_VALUE;
    private long lastEndMicros = Long.MIN_VALUE;

    // GC aggregate
    private final List<Long> gcDurationsMicros = new ArrayList<>();
    private long youngGcCount = 0;
    private long oldGcCount = 0;
    private final Map<String, Long> gcCauseCounts = new HashMap<>();
    private long maxHeapUsedBeforeGcBytes = 0;
    private long maxHeapUsedAfterGcBytes = 0;
    private long maxMetaspaceUsedBytes = 0;
    private long totalGcCpuUserMicros = 0;
    private long totalGcCpuSystemMicros = 0;
    private long maxGcPhasePauseMicros = 0;
    private String collectorName = null;

    // GC bucketing: key = bucket index, value = long[5]{count, totalMicros, maxMicros,
    // heapAfterBytes, heapBeforeBytes}
    private long firstGcMicros = Long.MIN_VALUE;
    private final Map<Long, long[]> gcBuckets = new HashMap<>();

    // CPU bucketing: key = bucket index, value = float[4]{sumUser, sumSys, sumMachine, count}
    private long firstCpuMicros = Long.MIN_VALUE;
    private final Map<Long, float[]> cpuBuckets = new HashMap<>();

    // Allocation/promotion tracking
    private long totalAllocationBytes = 0;
    private long totalPromotionBytes = 0;
    private long firstAllocMicros = Long.MIN_VALUE;
    private final Map<Long, long[]> allocBuckets =
            new HashMap<>(); // value = long[2]{allocBytes, promoBytes}

    public FooterCollector(long bucketSeconds) {
        this.bucketSeconds = bucketSeconds;
    }

    public void collect(RecordedEvent event) {
        String name = event.getEventType().getName();
        eventCounts.merge(name, 1L, Long::sum);
        totalEvents++;

        long startMicros = toNanoSeconds(event.getStartTime()) / 1000;
        long endMicros = startMicros + event.getDuration().toNanos() / 1000;
        if (startMicros < firstStartMicros) firstStartMicros = startMicros;
        if (endMicros > lastEndMicros) lastEndMicros = endMicros;

        switch (name) {
            case "jdk.GarbageCollection" -> collectGc(event, startMicros);
            case "jdk.YoungGarbageCollection" -> youngGcCount++;
            case "jdk.OldGarbageCollection" -> oldGcCount++;
            case "jdk.GCHeapSummary", "jdk.G1HeapSummary", "jdk.PSHeapSummary" ->
                    collectHeap(event, startMicros);
            case "jdk.MetaspaceSummary" -> collectMetaspace(event);
            case "jdk.GCCPUTime" -> collectGcCpu(event);
            case "jdk.GCPhasePause" -> collectGcPhasePause(event);
            case "jdk.GCConfiguration" -> collectGcConfiguration(event);
            case "jdk.CPULoad" -> collectCpu(event, startMicros);
            case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" ->
                    collectAllocation(event, startMicros);
            case "jdk.PromoteObjectInNewPLAB", "jdk.PromoteObjectOutsidePLAB" ->
                    collectPromotion(event, startMicros);
            default -> {}
        }
    }

    private void collectGc(RecordedEvent e, long startMicros) {
        long micros = e.getDuration().toNanos() / 1000;
        gcDurationsMicros.add(micros);
        if (e.hasField("cause")) {
            try {
                gcCauseCounts.merge(e.getString("cause"), 1L, Long::sum);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (firstGcMicros == Long.MIN_VALUE) firstGcMicros = startMicros;
        long bucketIdx = (startMicros - firstGcMicros) / ((long) bucketSeconds * 1_000_000L);
        long[] slot = gcBuckets.computeIfAbsent(bucketIdx, k -> new long[5]);
        slot[0] += 1;
        slot[1] += micros;
        slot[2] = Math.max(slot[2], micros);
        // slot[3] = heapUsedAfterGcBytes, slot[4] = heapUsedBeforeGcBytes — updated by collectHeap
    }

    private void collectHeap(RecordedEvent e, long startMicros) {
        if (!e.hasField("when")) return;
        String when;
        try {
            when = e.getString("when");
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!e.hasField("heapSpace")) return;
        RecordedObject heapSpace;
        try {
            heapSpace = e.getValue("heapSpace");
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (heapSpace == null || !heapSpace.hasField("usedSize")) return;
        long usedBytes;
        try {
            usedBytes = heapSpace.getLong("usedSize");
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if ("Before GC".equals(when)) {
            if (usedBytes > maxHeapUsedBeforeGcBytes) maxHeapUsedBeforeGcBytes = usedBytes;
            if (firstGcMicros != Long.MIN_VALUE) {
                long bucketIdx =
                        (startMicros - firstGcMicros) / ((long) bucketSeconds * 1_000_000L);
                long[] slot = gcBuckets.get(bucketIdx);
                if (slot != null) slot[4] = Math.max(slot[4], usedBytes);
            }
        } else if ("After GC".equals(when)) {
            if (usedBytes > maxHeapUsedAfterGcBytes) maxHeapUsedAfterGcBytes = usedBytes;
            if (firstGcMicros != Long.MIN_VALUE) {
                long bucketIdx =
                        (startMicros - firstGcMicros) / ((long) bucketSeconds * 1_000_000L);
                long[] slot = gcBuckets.get(bucketIdx);
                if (slot != null) slot[3] = Math.max(slot[3], usedBytes);
            }
        }
    }

    private void collectMetaspace(RecordedEvent e) {
        if (!e.hasField("when")) return;
        try {
            if (!"After GC".equals(e.getString("when"))) return;
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!e.hasField("metaspace")) return;
        RecordedObject ms;
        try {
            ms = e.getValue("metaspace");
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (ms == null || !ms.hasField("used")) return;
        try {
            long bytes = ms.getLong("used");
            if (bytes > maxMetaspaceUsedBytes) maxMetaspaceUsedBytes = bytes;
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void collectGcCpu(RecordedEvent e) {
        try {
            if (e.hasField("userTime"))
                totalGcCpuUserMicros += e.getDuration("userTime").toNanos() / 1000;
        } catch (IllegalArgumentException ignored) {
        }
        try {
            if (e.hasField("systemTime"))
                totalGcCpuSystemMicros += e.getDuration("systemTime").toNanos() / 1000;
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void collectGcPhasePause(RecordedEvent e) {
        long micros = e.getDuration().toNanos() / 1000;
        if (micros > maxGcPhasePauseMicros) maxGcPhasePauseMicros = micros;
    }

    private void collectGcConfiguration(RecordedEvent e) {
        if (collectorName != null) return; // only need the first one
        if (!e.hasField("youngCollector")) return;
        try {
            String name = e.getString("youngCollector");
            if (name != null && !name.isEmpty()) collectorName = name;
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void collectCpu(RecordedEvent e, long startMicros) {
        if (firstCpuMicros == Long.MIN_VALUE) firstCpuMicros = startMicros;
        long bucketIdx = (startMicros - firstCpuMicros) / ((long) bucketSeconds * 1_000_000L);
        float[] slot = cpuBuckets.computeIfAbsent(bucketIdx, k -> new float[4]);
        try {
            slot[0] += e.getFloat("jvmUser");
            slot[1] += e.getFloat("jvmSystem");
            slot[2] += e.getFloat("machineTotal");
            slot[3] += 1f;
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void collectAllocation(RecordedEvent e, long startMicros) {
        long bytes = 0;
        if (e.hasField("allocationSize")) {
            try {
                bytes = e.getLong("allocationSize");
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (bytes <= 0) return;
        totalAllocationBytes += bytes;
        if (firstAllocMicros == Long.MIN_VALUE) firstAllocMicros = startMicros;
        long bucketIdx = (startMicros - firstAllocMicros) / ((long) bucketSeconds * 1_000_000L);
        long[] slot = allocBuckets.computeIfAbsent(bucketIdx, k -> new long[2]);
        slot[0] += bytes;
    }

    private void collectPromotion(RecordedEvent e, long startMicros) {
        long bytes = 0;
        if (e.hasField("promotionSize")) {
            try {
                bytes = e.getLong("promotionSize");
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (bytes <= 0) return;
        totalPromotionBytes += bytes;
        if (firstAllocMicros == Long.MIN_VALUE) firstAllocMicros = startMicros;
        long bucketIdx = (startMicros - firstAllocMicros) / ((long) bucketSeconds * 1_000_000L);
        long[] slot = allocBuckets.computeIfAbsent(bucketIdx, k -> new long[2]);
        slot[1] += bytes;
    }

    public CJFRFooter build(long fallbackStartMicros, long fallbackDurationMicros) {
        // Always use the chunk-header start (fallbackStartMicros) as the canonical start time.
        // firstStartMicros is the first event's start, which may be later than the chunk header.
        long start = fallbackStartMicros;
        long duration =
                (lastEndMicros == Long.MIN_VALUE) ? fallbackDurationMicros : lastEndMicros - start;

        return new CJFRFooter(
                CJFRFooter.CURRENT_VERSION,
                totalEvents,
                start,
                duration,
                Map.copyOf(eventCounts),
                buildGcStatsOrNull(),
                buildCpuStatsOrNull(),
                buildAllocStatsOrNull());
    }

    private GcStats buildGcStatsOrNull() {
        if (gcDurationsMicros.isEmpty()) return null;

        long[] sorted = gcDurationsMicros.stream().mapToLong(Long::longValue).sorted().toArray();
        long total = 0;
        long max = 0;
        for (long v : sorted) {
            total += v;
            if (v > max) max = v;
        }
        long median = sorted[sorted.length / 2];
        long p95 = sorted[(int) Math.min(sorted.length - 1, sorted.length * 95L / 100)];

        List<Map.Entry<Long, long[]>> bucketEntries = new ArrayList<>(gcBuckets.entrySet());
        bucketEntries.sort(Map.Entry.comparingByKey());
        GcBucket[] buckets = new GcBucket[bucketEntries.size()];
        for (int i = 0; i < bucketEntries.size(); i++) {
            long[] s = bucketEntries.get(i).getValue();
            buckets[i] = new GcBucket(s[0], s[1], s[2], s[3], s[4]);
        }

        return new GcStats(
                sorted.length,
                youngGcCount,
                oldGcCount,
                total,
                max,
                median,
                p95,
                maxGcPhasePauseMicros,
                Map.copyOf(gcCauseCounts),
                collectorName,
                maxHeapUsedBeforeGcBytes,
                maxHeapUsedAfterGcBytes,
                maxMetaspaceUsedBytes,
                totalGcCpuUserMicros,
                totalGcCpuSystemMicros,
                bucketSeconds,
                buckets);
    }

    private CpuStats buildCpuStatsOrNull() {
        if (cpuBuckets.isEmpty()) return null;

        List<Map.Entry<Long, float[]>> entries = new ArrayList<>(cpuBuckets.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        int n = entries.size();
        float[] jvmUser = new float[n];
        float[] jvmSystem = new float[n];
        float[] machineTotal = new float[n];
        for (int i = 0; i < n; i++) {
            float[] s = entries.get(i).getValue();
            float count = s[3] > 0 ? s[3] : 1f;
            jvmUser[i] = s[0] / count;
            jvmSystem[i] = s[1] / count;
            machineTotal[i] = s[2] / count;
        }
        return new CpuStats(bucketSeconds, jvmUser, jvmSystem, machineTotal);
    }

    private AllocStats buildAllocStatsOrNull() {
        if (totalAllocationBytes == 0 && totalPromotionBytes == 0) return null;

        List<Map.Entry<Long, long[]>> entries = new ArrayList<>(allocBuckets.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        int n = entries.size();
        long[] allocPerBucket = new long[n];
        long[] promoPerBucket = new long[n];
        for (int i = 0; i < n; i++) {
            long[] s = entries.get(i).getValue();
            allocPerBucket[i] = s[0];
            promoPerBucket[i] = s[1];
        }
        return new AllocStats(
                totalAllocationBytes,
                totalPromotionBytes,
                bucketSeconds,
                allocPerBucket,
                promoPerBucket);
    }
}
