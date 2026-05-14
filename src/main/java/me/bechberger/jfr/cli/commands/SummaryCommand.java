package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import me.bechberger.JFRReader;
import me.bechberger.condensed.Compression;
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
            Statistic statistic = new Statistic();
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
                            eventFilterOptionMixin.createFilter(),
                            !eventFilterOptionMixin.noReconstitution(),
                            statistic);

            var summary = computeSummary(jfrReader);

            // Filter the EventWriteTree if --events is specified
            var contextRoot = statistic.getContextRoot();
            if (eventFilterOptionMixin.getEventTypes() != null
                    && !eventFilterOptionMixin.getEventTypes().isEmpty()) {
                contextRoot =
                        contextRoot.filtered(
                                new java.util.HashSet<>(eventFilterOptionMixin.getEventTypes()));
            }

            if (json) {
                Map<String, Object> output = summary.toJSON(shortSummary, limit);

                // Add EventWriteTree data if --full is specified
                if (full) {
                    var flamegraphGenerator = new FlamegraphGenerator(contextRoot);
                    output.put("eventWriteTree", flamegraphGenerator.toJSON());
                }

                System.out.println(PrettyPrinter.prettyPrint(output));
            } else {
                // Always print the basic summary
                System.out.println(summary.toString(shortSummary, limit));

                // Print full statistics if requested
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

            // Write flamegraph if path is provided
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

    record Summary(
            int eventCount,
            Duration duration,
            int version,
            String generatorName,
            String generatorVersion,
            String generatorConfiguration,
            Compression compression,
            Instant start,
            Instant end,
            Map<String, Integer> eventCounts) {

        public Summary {
            // check validity of inputs
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
            return toString(shortSummary, -1);
        }

        public String toString(boolean shortSummary, int limit) {
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
            return sb.toString();
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

            return json;
        }
    }

    static Summary computeSummary(JFRReader reader) {
        Map<String, Integer> eventCounts = new HashMap<>();
        ReadStruct struct;
        while ((struct = reader.readNextEvent()) != null) {
            eventCounts.merge(struct.getType().getName(), 1, Integer::sum);
        }
        var startMessage = reader.getStartMessage();
        var duration = reader.getDuration();
        // Warn if combined duration seems unreasonably long (> 7 days),
        // which likely means unrelated recordings were combined
        if (reader instanceof CombiningJFRReader && duration.toDays() > 7) {
            System.err.println(
                    "Warning: Combined recording duration spans "
                            + TimeUtil.formatDuration(duration)
                            + ". The input files may be from unrelated recordings.");
        }
        return new Summary(
                eventCounts.values().stream().mapToInt(Integer::intValue).sum(),
                duration,
                startMessage.version(),
                startMessage.generatorName(),
                startMessage.generatorVersion(),
                startMessage.generatorConfiguration(),
                startMessage.compression(),
                reader.getStartTime(),
                reader.getEndTime(),
                eventCounts);
    }
}
