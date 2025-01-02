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
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderParameterConsumer;
import me.bechberger.util.TimeUtil;
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
            converter = ExistingCJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Option(
            names = {"-s", "--short"},
            description = "Print a short summary without the event counts",
            defaultValue = "false")
    private boolean shortSummary;

    @Mixin private EventFilterOptionMixin eventFilterOptionMixin;

    public Integer call() {
        inputFiles.add(0, inputFile);
        try {
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
                            eventFilterOptionMixin.createFilter(),
                            eventFilterOptionMixin.noReconstitution());
            var summary = computeSummary(jfrReader);
            System.out.println();
            System.out.println(" Format Version: " + summary.version());
            System.out.println(" Generator: " + summary.generatorName());
            System.out.println(" Generator Version: " + summary.generatorVersion());
            System.out.println(
                    " Generator Configuration: "
                            + (summary.generatorConfiguration().isEmpty()
                                    ? "(default)"
                                    : summary.generatorConfiguration()));
            System.out.println(" Compression: " + summary.compression());
            System.out.println(" Start: " + TimeUtil.formatInstant(summary.start()));
            System.out.println(" End: " + TimeUtil.formatInstant(summary.end()));
            System.out.println(
                    " Duration: "
                            + TimeUtil.formatDuration(
                                    summary.duration().truncatedTo(ChronoUnit.MILLIS)));
            System.out.println(" Events: " + summary.eventCount());
            if (!shortSummary) {
                System.out.println();
                System.out.println(" Event Type                                Count");
                System.out.println("=================================================");
                for (var entry :
                        summary.eventCounts().entrySet().stream()
                                .sorted(Comparator.comparing(e -> -e.getValue()))
                                .toList()) {
                    System.out.printf(" %-40s %6d%n", entry.getKey(), entry.getValue());
                }
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
            Map<String, Integer> eventCounts) {}

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
