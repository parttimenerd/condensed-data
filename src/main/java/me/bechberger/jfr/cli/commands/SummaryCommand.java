package me.bechberger.jfr.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.Locale;
import me.bechberger.JFRReader;
import me.bechberger.condensed.CJFRFooter;
import me.bechberger.condensed.CJFRFooter.AllocStats;
import me.bechberger.condensed.CJFRFooter.CpuStats;
import me.bechberger.condensed.CJFRFooter.GcStats;
import me.bechberger.condensed.CJFRFooter.GcStats.GcBucket;
import me.bechberger.condensed.CJFRFooterReader;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.stats.FlamegraphGenerator;
import me.bechberger.condensed.stats.Statistic;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Mixin;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFROrJFRFileOrZipOrFolderConverter;
import me.bechberger.util.TimeUtil;
import me.bechberger.util.json.PrettyPrinter;
import org.jetbrains.annotations.Nullable;

@Command(
        name = "summary",
        description = "Print a summary of the condensed JFR file",
        mixinStandardHelpOptions = true)
public class SummaryCommand implements Callable<Integer> {

    @Parameters(
            description = "The input .cjfr or .jfr file, can be a folder, or a zip",
            converter = ExistingCJFROrJFRFileOrZipOrFolderConverter.class)
    private Path inputFile;

    @Option(
            names = {"-i", "--inputs"},
            description = "Additional input files",
            converter = ExistingCJFROrJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Option(
            names = {"-s", "--short"},
            description = "Print a short summary without the event counts",
            defaultValue = "false")
    private boolean shortSummary;

    @Option(names = "--json", description = "Output as JSON", defaultValue = "false")
    private boolean json;

    @Option(
            names = {"--full"},
            description =
                    "Print full statistics including EventWriteTree table and detailed statistics",
            defaultValue = "false")
    private boolean full;

    @Option(
            names = {"--flamegraph"},
            description =
                    "Write storage flamegraph HTML to the specified file"
                            + " (shows byte distribution across event types, not CPU profiling)",
            converter = FileOptionConverters.HTMLFileConverter.class)
    private Path flamegraphPath;

    @Mixin private EventFilterOptionMixin eventFilterOptionMixin;

    @Option(
            names = "--limit",
            description = "Limit the number of event types shown (table and JSON), -1 for all",
            defaultValue = "-1")
    private int limit;

    public Integer call() {
        if (shortSummary && full) {
            System.err.println("Error: --short and --full are mutually exclusive");
            return 2;
        }
        if (limit < -1) {
            System.err.println("Error: --limit must be >= 0 (or -1 for no limit), got: " + limit);
            return 2;
        }
        inputFiles.add(0, inputFile);
        try {
            // Fast path: single .cjfr file with footer present and no special flags
            if (canUseFastPath()) {
                var footer = CJFRFooterReader.tryRead(inputFile);
                if (footer.isPresent()) {
                    var summary = summaryFromFooter(footer.get());
                    if (json) {
                        System.out.println(PrettyPrinter.prettyPrint(summary.toJSON(shortSummary, limit)));
                    } else {
                        System.out.println(summary.toString(shortSummary, limit, full));
                    }
                    return 0;
                }
            }

            // Full-scan path
            Statistic statistic = new Statistic();
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
                            eventFilterOptionMixin.createFilter(),
                            !eventFilterOptionMixin.noReconstitution(),
                            true,
                            statistic);

            var summary = computeSummary(jfrReader);

            var contextRoot = statistic.getContextRoot();
            if (eventFilterOptionMixin.getEventTypes() != null
                    && !eventFilterOptionMixin.getEventTypes().isEmpty()) {
                contextRoot =
                        contextRoot.filtered(
                                new java.util.HashSet<>(eventFilterOptionMixin.getEventTypes()));
            }

            if (json) {
                Map<String, Object> output = summary.toJSON(shortSummary, limit);
                if (full) {
                    var flamegraphGenerator = new FlamegraphGenerator(contextRoot);
                    output.put("eventWriteTree", flamegraphGenerator.toJSON());
                }
                System.out.println(PrettyPrinter.prettyPrint(output));
            } else {
                System.out.println(summary.toString(shortSummary, limit, full));
                if (full) {
                    System.out.println("\nEventWriteTree:");
                    System.out.println("===============");
                    var flamegraphGenerator = new FlamegraphGenerator(contextRoot);
                    flamegraphGenerator.writeTable(System.out);
                    System.out.println("\nDetailed Statistics:");
                    System.out.println("===================");
                    System.out.println(statistic.toPrettyString());
                }
            }

            if (flamegraphPath != null) {
                if (contextRoot.getCount() == 0) {
                    System.err.println("Warning: No events found, flamegraph will be empty");
                }
                var flamegraphGenerator = new FlamegraphGenerator(contextRoot);
                flamegraphGenerator.writeHTML(flamegraphPath);
                var flamegraphMessage = "Storage flamegraph written to: " + flamegraphPath;
                if (json) {
                    System.err.println(flamegraphMessage);
                } else {
                    System.out.println("\n" + flamegraphMessage);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            return CLIUtils.printError(e);
        }
        return 0;
    }

    private boolean canUseFastPath() {
        // Only a single .cjfr file (not .jfr, not folder, not zip)
        if (inputFiles.size() != 1) return false;
        Path f = inputFiles.get(0);
        if (!Files.isRegularFile(f) || !f.toString().endsWith(".cjfr")) return false;
        // No event filter / time filter
        if (eventFilterOptionMixin.createFilter() != null) return false;
        // --full requires EventWriteTree scan; --flamegraph requires per-event stats
        if (full || flamegraphPath != null) return false;
        return true;
    }

    private Summary summaryFromFooter(CJFRFooter footer) {
        // Read just the StartMessage header from the file (no events consumed)
        Path f = inputFiles.get(0);
        var startMessage = readStartMessage(f);
        Instant start = Instant.ofEpochSecond(0, footer.startTimeMicros() * 1000);
        Instant end = Instant.ofEpochSecond(0, (footer.startTimeMicros() + footer.durationMicros()) * 1000);
        return new Summary(
                footer.totalEvents(),
                Duration.ofNanos(footer.durationMicros() * 1000),
                startMessage.version(),
                startMessage.generatorName(),
                startMessage.generatorVersion(),
                startMessage.generatorConfiguration(),
                startMessage.compression(),
                start,
                end,
                footer.eventCounts(),
                footer.gcStats(),
                footer.cpuStats(),
                footer.allocStats());
    }

    private static me.bechberger.condensed.Message.StartMessage readStartMessage(Path path) {
        try (var in = new CondensedInputStream(
                new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path), 4096))) {
            in.readNextMessageAndProcess(); // triggers start-string read; result may be null (footer sentinel)
            return in.getUniverse().getStartMessage();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read header from " + path, e);
        }
    }

    record Summary(
            long eventCount,
            Duration duration,
            int version,
            String generatorName,
            String generatorVersion,
            String generatorConfiguration,
            Compression compression,
            Instant start,
            Instant end,
            Map<String, Long> eventCounts,
            @Nullable GcStats gcStats,
            @Nullable CpuStats cpuStats,
            @Nullable AllocStats allocStats) {

        public Summary {
            if (eventCount < 0) {
                throw new IllegalArgumentException("Event count must be non-negative");
            }
            if (duration.isNegative() || duration.getSeconds() > 100L * 365 * 24 * 3600) {
                throw new IllegalArgumentException("Duration must be positive");
            }
            if (version < 0) {
                throw new IllegalArgumentException("Version must be non-negative");
            }
            if (start == null || end == null || start.isAfter(end)) {
                throw new IllegalArgumentException("Start time must be before end time");
            }
            if (eventCounts == null) {
                throw new IllegalArgumentException("Event counts must not be null");
            }
        }

        public String toString(boolean shortSummary) {
            return toString(shortSummary, -1, false);
        }

        public String toString(boolean shortSummary, int limit, boolean showBucketTable) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append(" Format Version: ").append(version()).append("\n");
            sb.append(" Generator: ").append(generatorName()).append("\n");
            sb.append(" Generator Version: ").append(this.generatorVersion()).append("\n");
            sb.append(" Generator Configuration: ")
                    .append(
                            this.generatorConfiguration().isEmpty()
                                    ? "(default)"
                                    : this.generatorConfiguration())
                    .append("\n");
            sb.append(" Compression: ").append(this.compression()).append("\n");
            sb.append(" Start: ").append(TimeUtil.formatInstant(this.start())).append("\n");
            sb.append(" End: ").append(TimeUtil.formatInstant(this.end())).append("\n");
            sb.append(" Duration: ")
                    .append(TimeUtil.formatDuration(this.duration().truncatedTo(ChronoUnit.MILLIS)))
                    .append("\n");
            sb.append(" Events: ").append(this.eventCount()).append("\n");
            if (!shortSummary && !this.eventCounts().isEmpty()) {
                sb.append("\n");
                sb.append(" Event Type                                Count\n");
                sb.append("=================================================\n");
                var sorted =
                        this.eventCounts().entrySet().stream()
                                .sorted(Comparator.comparing(e -> -e.getValue()));
                if (limit >= 0) {
                    sorted = sorted.limit(limit);
                }
                for (var entry : sorted.toList()) {
                    sb.append(String.format(" %-40s %6d%n", entry.getKey(), entry.getValue()));
                }
            }
            // GC summary (shown whenever gcStats is available, regardless of --full)
            if (gcStats != null) {
                sb.append("\n");
                appendGcSummary(sb, gcStats);
            }
            // Allocation summary (shown whenever allocStats is available)
            if (allocStats != null) {
                sb.append("\n");
                appendAllocSummary(sb, allocStats, duration);
            }
            // Combined GC+CPU+alloc bucket table (shown only with --full)
            if (showBucketTable && (gcStats != null || cpuStats != null || allocStats != null)) {
                sb.append("\n");
                appendBucketTable(sb, gcStats, cpuStats, allocStats);
            }
            return sb.toString();
        }

        private static void appendGcSummary(StringBuilder sb, GcStats g) {
            sb.append(" GC Summary\n");
            sb.append(" ==========\n");
            String collectorSuffix = g.collectorName() != null ? "  Collector: " + g.collectorName() : "";
            sb.append(String.format(" GC runs:        %d  (young: %d, old: %d)%s%n",
                    g.gcCount(), g.youngGcCount(), g.oldGcCount(), collectorSuffix));
            if (!g.causeCounts().isEmpty()) {
                var causes = g.causeCounts().entrySet().stream()
                        .sorted(Comparator.comparingLong(e -> -e.getValue()))
                        .toList();
                StringBuilder causeStr = new StringBuilder();
                for (var e : causes) {
                    if (causeStr.length() > 0) causeStr.append(", ");
                    causeStr.append(e.getKey()).append(" (").append(e.getValue()).append(")");
                }
                sb.append(" Causes:         ").append(causeStr).append("\n");
            }
            sb.append(String.format(" Total GC time:  %s   GC CPU: user %s, sys %s%n",
                    formatMicros(g.totalGcMicros()),
                    formatMicros(g.totalGcCpuUserMicros()),
                    formatMicros(g.totalGcCpuSystemMicros())));
            sb.append(String.format(" Max pause:      %s   Median: %s   P95: %s%n",
                    formatMicros(g.maxGcMicros()),
                    formatMicros(g.medianGcMicros()),
                    formatMicros(g.p95GcMicros())));
            if (g.maxGcPhasePauseMicros() > 0) {
                sb.append(String.format(" Max phase pause: %s%n",
                        formatMicros(g.maxGcPhasePauseMicros())));
            }
            if (g.maxHeapUsedBeforeGcBytes() > 0) {
                sb.append(String.format(" Heap before GC: max %s%n",
                        formatBytes(g.maxHeapUsedBeforeGcBytes())));
            }
            if (g.maxHeapUsedAfterGcBytes() > 0) {
                sb.append(String.format(" Heap after GC:  max %s%n",
                        formatBytes(g.maxHeapUsedAfterGcBytes())));
            }
            if (g.maxMetaspaceUsedBytes() > 0) {
                sb.append(String.format(" Metaspace:      max %s%n",
                        formatBytes(g.maxMetaspaceUsedBytes())));
            }
        }

        private static void appendAllocSummary(StringBuilder sb, AllocStats a, Duration duration) {
            sb.append(" Allocation Summary\n");
            sb.append(" ==================\n");
            sb.append(String.format(" Allocated:      %s total%n",
                    formatBytes(a.totalAllocationBytes())));
            if (duration.toMillis() > 0) {
                double secs = duration.toMillis() / 1000.0;
                sb.append(String.format(" Alloc rate:     %s/s%n",
                        formatBytes((long) (a.totalAllocationBytes() / secs))));
            }
            if (a.totalPromotionBytes() > 0) {
                sb.append(String.format(" Promoted:       %s total%n",
                        formatBytes(a.totalPromotionBytes())));
                if (duration.toMillis() > 0) {
                    double secs = duration.toMillis() / 1000.0;
                    sb.append(String.format(" Promo rate:     %s/s%n",
                            formatBytes((long) (a.totalPromotionBytes() / secs))));
                }
            }
        }

        private static void appendBucketTable(
                StringBuilder sb, @Nullable GcStats gc, @Nullable CpuStats cpu,
                @Nullable AllocStats alloc) {
            long bucketSeconds = gc != null ? gc.bucketSeconds()
                    : (cpu != null ? cpu.bucketSeconds()
                    : (alloc != null ? alloc.bucketSeconds() : 10L));
            int gcBuckets = gc != null ? gc.buckets().length : 0;
            int cpuBuckets = cpu != null ? cpu.jvmUser().length : 0;
            int allocBuckets = alloc != null ? alloc.allocatedBytesPerBucket().length : 0;
            int totalBuckets = Math.max(gcBuckets, Math.max(cpuBuckets, allocBuckets));
            if (totalBuckets == 0) return;

            boolean hasGc = gc != null && gcBuckets > 0;
            boolean hasCpu = cpu != null && cpuBuckets > 0;
            boolean hasAlloc = alloc != null && allocBuckets > 0;
            boolean hasPromo = hasAlloc && alloc.totalPromotionBytes() > 0;

            // Build per-column data first to determine which columns are all "—"
            String[] timeCol = new String[totalBuckets];
            String[] gcRunsCol = new String[totalBuckets];
            String[] totalGcCol = new String[totalBuckets];
            String[] maxPauseCol = new String[totalBuckets];
            String[] heapBeforeCol = new String[totalBuckets];
            String[] heapAfterCol = new String[totalBuckets];
            String[] jvmCpuCol = new String[totalBuckets];
            String[] machineCpuCol = new String[totalBuckets];
            String[] allocCol = new String[totalBuckets];
            String[] promoCol = new String[totalBuckets];

            boolean anyGcRuns = false, anyTotalGc = false, anyMaxPause = false;
            boolean anyHeapBefore = false, anyHeapAfter = false;
            boolean anyJvmCpu = false, anyMachineCpu = false;
            boolean anyAlloc = false, anyPromo = false;

            for (int i = 0; i < totalBuckets; i++) {
                long fromSec = (long) i * bucketSeconds;
                long toSec = fromSec + bucketSeconds;
                timeCol[i] = fromSec + "–" + toSec + " s";

                gcRunsCol[i] = "—"; totalGcCol[i] = "—"; maxPauseCol[i] = "—";
                heapBeforeCol[i] = "—"; heapAfterCol[i] = "—";
                if (hasGc && i < gcBuckets) {
                    GcBucket b = gc.buckets()[i];
                    if (b.gcCount() > 0) {
                        gcRunsCol[i] = String.valueOf(b.gcCount()); anyGcRuns = true;
                        totalGcCol[i] = formatMicros(b.totalGcMicros()); anyTotalGc = true;
                        maxPauseCol[i] = formatMicros(b.maxGcMicros()); anyMaxPause = true;
                    }
                    if (b.heapUsedBeforeGcBytes() > 0) {
                        heapBeforeCol[i] = formatBytes(b.heapUsedBeforeGcBytes()); anyHeapBefore = true;
                    }
                    if (b.heapUsedAfterGcBytes() > 0) {
                        heapAfterCol[i] = formatBytes(b.heapUsedAfterGcBytes()); anyHeapAfter = true;
                    }
                }

                jvmCpuCol[i] = "—"; machineCpuCol[i] = "—";
                if (hasCpu && i < cpuBuckets) {
                    jvmCpuCol[i] = String.format("%.1f%%",
                            (cpu.jvmUser()[i] + cpu.jvmSystem()[i]) * 100); anyJvmCpu = true;
                    machineCpuCol[i] = String.format("%.1f%%",
                            cpu.machineTotal()[i] * 100); anyMachineCpu = true;
                }

                allocCol[i] = "—"; promoCol[i] = "—";
                if (hasAlloc && i < allocBuckets) {
                    if (alloc.allocatedBytesPerBucket()[i] > 0) {
                        allocCol[i] = formatBytes(alloc.allocatedBytesPerBucket()[i]); anyAlloc = true;
                    }
                    if (hasPromo && i < alloc.promotedBytesPerBucket().length
                            && alloc.promotedBytesPerBucket()[i] > 0) {
                        promoCol[i] = formatBytes(alloc.promotedBytesPerBucket()[i]); anyPromo = true;
                    }
                }
            }

            // Only show columns that have at least one non-"—" value
            record Col(String header, int width, String[] data) {}
            List<Col> cols = new ArrayList<>();
            cols.add(new Col("Time", 12, timeCol));
            if (anyGcRuns)    cols.add(new Col("GC runs", 8, gcRunsCol));
            if (anyTotalGc)   cols.add(new Col("Total GC", 10, totalGcCol));
            if (anyMaxPause)  cols.add(new Col("Max pause", 10, maxPauseCol));
            if (anyHeapBefore) cols.add(new Col("Heap before", 12, heapBeforeCol));
            if (anyHeapAfter)  cols.add(new Col("Heap after", 11, heapAfterCol));
            if (anyJvmCpu)    cols.add(new Col("JVM CPU", 8, jvmCpuCol));
            if (anyMachineCpu) cols.add(new Col("Machine CPU", 12, machineCpuCol));
            if (anyAlloc)     cols.add(new Col("Allocated", 11, allocCol));
            if (anyPromo)     cols.add(new Col("Promoted", 10, promoCol));

            // Header line
            sb.append(" ");
            for (Col col : cols) {
                sb.append(String.format("%-" + col.width() + "s  ", col.header()));
            }
            sb.append("\n ");
            int totalWidth = cols.stream().mapToInt(c -> c.width() + 2).sum();
            sb.append("-".repeat(totalWidth)).append("\n");

            // Data rows
            for (int i = 0; i < totalBuckets; i++) {
                sb.append(" ");
                for (Col col : cols) {
                    sb.append(String.format("%-" + col.width() + "s  ", col.data()[i]));
                }
                sb.append("\n");
            }
        }

        public Map<String, Object> toJSON(boolean shortSummary) {
            return toJSON(shortSummary, -1);
        }

        public Map<String, Object> toJSON(boolean shortSummary, int limit) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("format version", version());
            json.put("generator", generatorName());
            json.put("generator version", generatorVersion());
            json.put(
                    "generator configuration",
                    generatorConfiguration().isEmpty() ? "(default)" : generatorConfiguration());
            json.put("compression", compression().toString());
            json.put("start", TimeUtil.formatInstant(start()));
            json.put("start-epoch", start().toEpochMilli());
            json.put("end", TimeUtil.formatInstant(end()));
            json.put("end-epoch", end().toEpochMilli());
            json.put(
                    "duration", TimeUtil.formatDuration(duration().truncatedTo(ChronoUnit.MILLIS)));
            json.put("duration-millis", duration().toMillis());
            json.put("count", eventCount());

            if (!shortSummary) {
                Map<String, Object> events = new LinkedHashMap<>();
                var sorted =
                        eventCounts().entrySet().stream()
                                .sorted(Comparator.comparing(e -> -e.getValue()));
                if (limit >= 0) {
                    sorted = sorted.limit(limit);
                }
                for (var entry : sorted.toList()) {
                    events.put(entry.getKey(), entry.getValue());
                }
                json.put("events", events);
            }

            if (gcStats != null) {
                json.put("gc", gcStatsToJSON(gcStats));
            }
            if (cpuStats != null) {
                json.put("cpu", cpuStatsToJSON(cpuStats));
            }
            if (allocStats != null) {
                json.put("alloc", allocStatsToJSON(allocStats));
            }

            return json;
        }

        private static Map<String, Object> gcStatsToJSON(GcStats g) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", g.gcCount());
            m.put("youngCount", g.youngGcCount());
            m.put("oldCount", g.oldGcCount());
            if (g.collectorName() != null) m.put("collector", g.collectorName());
            m.put("causes", new LinkedHashMap<>(g.causeCounts()));
            m.put("totalMicros", g.totalGcMicros());
            m.put("maxMicros", g.maxGcMicros());
            m.put("medianMicros", g.medianGcMicros());
            m.put("p95Micros", g.p95GcMicros());
            if (g.maxGcPhasePauseMicros() > 0) m.put("maxPhasePauseMicros", g.maxGcPhasePauseMicros());
            m.put("maxHeapBeforeBytes", g.maxHeapUsedBeforeGcBytes());
            m.put("maxHeapAfterBytes", g.maxHeapUsedAfterGcBytes());
            m.put("maxMetaspaceBytes", g.maxMetaspaceUsedBytes());
            m.put("gcCpuUserMicros", g.totalGcCpuUserMicros());
            m.put("gcCpuSystemMicros", g.totalGcCpuSystemMicros());
            m.put("bucketSeconds", g.bucketSeconds());
            List<Object> buckets = new ArrayList<>();
            for (var b : g.buckets()) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("gcCount", b.gcCount());
                bm.put("totalMicros", b.totalGcMicros());
                bm.put("maxMicros", b.maxGcMicros());
                bm.put("heapUsedAfterBytes", b.heapUsedAfterGcBytes());
                bm.put("heapUsedBeforeBytes", b.heapUsedBeforeGcBytes());
                buckets.add(bm);
            }
            m.put("buckets", buckets);
            return m;
        }

        private static Map<String, Object> cpuStatsToJSON(CpuStats c) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucketSeconds", c.bucketSeconds());
            List<Object> buckets = new ArrayList<>();
            for (int i = 0; i < c.jvmUser().length; i++) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("jvmUser", c.jvmUser()[i]);
                bm.put("jvmSystem", c.jvmSystem()[i]);
                bm.put("machineTotal", c.machineTotal()[i]);
                buckets.add(bm);
            }
            m.put("buckets", buckets);
            return m;
        }

        private static Map<String, Object> allocStatsToJSON(AllocStats a) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalAllocationBytes", a.totalAllocationBytes());
            m.put("totalPromotionBytes", a.totalPromotionBytes());
            m.put("bucketSeconds", a.bucketSeconds());
            List<Object> buckets = new ArrayList<>();
            long[] alloc = a.allocatedBytesPerBucket();
            long[] promo = a.promotedBytesPerBucket();
            int n = Math.max(alloc.length, promo.length);
            for (int i = 0; i < n; i++) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("allocatedBytes", i < alloc.length ? alloc[i] : 0L);
                bm.put("promotedBytes", i < promo.length ? promo[i] : 0L);
                buckets.add(bm);
            }
            m.put("buckets", buckets);
            return m;
        }
    }

    /** Format microseconds as a human-readable duration string. */
    static String formatMicros(long micros) {
        return TimeUtil.formatDuration(Duration.ofNanos(micros * 1000));
    }

    /** Format bytes as a human-readable size string (B/KB/MB/GB). */
    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static Summary computeSummary(JFRReader reader) {
        Map<String, Long> eventCounts = new HashMap<>();
        ReadStruct struct;
        while ((struct = reader.readNextEvent()) != null) {
            eventCounts.merge(struct.getType().getName(), 1L, Long::sum);
        }
        var startMessage = reader.getStartMessage();
        var duration = reader.getDuration();
        if (reader instanceof CombiningJFRReader && duration.toDays() > 7) {
            System.err.println(
                    "Warning: Combined recording duration spans "
                            + TimeUtil.formatDuration(duration)
                            + ". The input files may be from unrelated recordings.");
        }
        return new Summary(
                eventCounts.values().stream().mapToLong(Long::longValue).sum(),
                duration,
                startMessage.version(),
                startMessage.generatorName(),
                startMessage.generatorVersion(),
                startMessage.generatorConfiguration(),
                startMessage.compression(),
                reader.getStartTime(),
                reader.getEndTime(),
                eventCounts,
                null,
                null,
                null);
    }
}
