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
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderParameterConsumer;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFilesOrZipOrFolderConsumer;
import me.bechberger.util.TimeUtil;
import org.json.JSONObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "summary",
        description = "Print a summary of the condensed JFR file",
        mixinStandardHelpOptions = true)
public class SummaryCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            description = "The input .cjfr file, can be a folder, or a zip",
            converter = ExistingCJFRFileOrZipOrFolderConverter.class,
            parameterConsumer = ExistingCJFRFileOrZipOrFolderParameterConsumer.class)
    private Path inputFile;

    @Option(
            names = {"-i", "--inputs"},
            description = "Additional input files",
            converter = ExistingCJFRFileOrZipOrFolderConverter.class,
            parameterConsumer = ExistingCJFRFilesOrZipOrFolderConsumer.class)
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
            description = "Write flamegraph HTML to the specified file",
            converter = FileOptionConverters.HTMLFileConverter.class)
    private Path flamegraphPath;

    @Mixin private EventFilterOptionMixin eventFilterOptionMixin;

    public Integer call() {
        inputFiles.add(0, inputFile);
        try {
            Statistic statistic = new Statistic();
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
                            eventFilterOptionMixin.createFilter(),
                            eventFilterOptionMixin.noReconstitution(),
                            statistic);

            var summary = computeSummary(jfrReader);

            if (json) {
                JSONObject output = summary.toJSON(shortSummary);

                // Add EventWriteTree data if --full is specified
                if (full) {
                    var flamegraphGenerator = new FlamegraphGenerator(statistic.getContextRoot());
                    output.put("eventWriteTree", flamegraphGenerator.toJSON());
                }

                System.out.println(output.toString(2));
            } else {
                // Print full statistics if requested
                if (full) {
                    System.out.println("\nEventWriteTree:");
                    System.out.println("===============");
                    var flamegraphGenerator = new FlamegraphGenerator(statistic.getContextRoot());
                    flamegraphGenerator.writeTable(System.out);

                    System.out.println("\nDetailed Statistics:");
                    System.out.println("===================");
                    System.out.println(statistic.toPrettyString());
                } else {
                    System.out.println(summary.toString(shortSummary));
                }
            }

            // Write flamegraph if path is provided
            if (flamegraphPath != null) {
                var flamegraphGenerator = new FlamegraphGenerator(statistic.getContextRoot());
                flamegraphGenerator.writeHTML(flamegraphPath);
                System.out.println("\nFlamegraph written to: " + flamegraphPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
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
            if (!shortSummary) {
                sb.append("\n");
                sb.append(" Event Type                                Count\n");
                sb.append("=================================================\n");
                for (var entry :
                        this.eventCounts().entrySet().stream()
                                .sorted(Comparator.comparing(e -> -e.getValue()))
                                .toList()) {
                    sb.append(String.format(" %-40s %6d%n", entry.getKey(), entry.getValue()));
                }
            }
            return sb.toString();
        }

        public JSONObject toJSON(boolean shortSummary) {
            JSONObject json = new JSONObject();
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
                JSONObject events = new JSONObject();
                for (var entry :
                        eventCounts().entrySet().stream()
                                .sorted(Comparator.comparing(e -> -e.getValue()))
                                .toList()) {
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
        return new Summary(
                eventCounts.values().stream().mapToInt(Integer::intValue).sum(),
                reader.getDuration(),
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
