package me.bechberger.jfr.cli;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.*;
import me.bechberger.jfr.Benchmark.TableConfig;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import me.bechberger.jfr.cli.JFRView.TruncateMode;
import me.bechberger.util.TimeUtil;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

/** A CLI for writing JFR (and more) based on {@link BasicJFRWriter} */
@Command(
        name = "cjfr",
        description = "CLI for condensed JFR files",
        subcommands = {
            JFRCLI.WriteJFRCommand.class,
            JFRCLI.InflateJFRCommand.class,
            JFRCLI.BenchmarkCommand.class,
            JFRCLI.AgentCommand.class,
            JFRCLI.SummaryCommand.class,
            JFRCLI.ViewCommand.class
        },
        mixinStandardHelpOptions = true)
public class JFRCLI implements Runnable {

    @Spec CommandSpec spec;

    @Command(name = "condense", description = "Condense a JFR file", mixinStandardHelpOptions = true)
    public static class WriteJFRCommand implements Callable<Integer> {

        // optional out path, compress flag, statistics flag

        @Parameters(index = "0", description = "The input file")
        private Path inputFile;

        @Parameters(index = "1", description = "The output file", defaultValue = "")
        private Path outputFile;

        @Option(
                names = {"--compress"},
                description = "Compress the output file")
        private boolean compress = true;

        @Option(
                names = {"-s", "--statistics"},
                description = "Print statistics")
        private boolean statistics = false;

        @Option(
                names = {"-c", "--generatorConfiguration"},
                description =
                        "The generatorConfiguration to use, possible values:"
                                + " ${COMPLETION-CANDIDATES}",
                completionCandidates = ConfigurationIterable.class,
                defaultValue = "default",
                converter = ConfigurationConverter.class)
        private Configuration configuration = Configuration.DEFAULT;

        private Path getOutputFile() {
            if (outputFile.toString().isEmpty()) {
                return inputFile.resolveSibling(inputFile.getFileName() + ".cjfr");
            }
            return outputFile;
        }

        public Integer call() {
            if (!Files.exists(inputFile)) {
                System.err.println("Input file does not exist: " + inputFile);
                return 1;
            }
            try (var out =
                    new CondensedOutputStream(
                            Files.newOutputStream(getOutputFile()),
                            new StartMessage(
                                    Constants.FORMAT_VERSION,
                                    "condensed jfr cli",
                                    Constants.VERSION,
                                    configuration.name(),
                                    Compression.DEFAULT))) {
                var basicJFRWriter = new BasicJFRWriter(out, configuration);
                try (RecordingFile r = new RecordingFile(inputFile)) {
                    while (r.hasMoreEvents()) {
                        basicJFRWriter.processEvent(r.readEvent());
                    }
                }
                if (statistics) {
                    System.out.println(out.getStatistic().toPrettyString());
                }
            } catch (Exception e) {
                e.printStackTrace();
                return 1;
            }
            // print JFR file size, output file size, compression ratio
            if (statistics) {
                try {
                    var outSize = Files.size(getOutputFile());
                    var inputSize = Files.size(inputFile);
                    System.out.printf(
                            "JFR file size: %d, output file size: %d, compression ratio: %.2f\n",
                            inputSize, outSize, (double) outSize / inputSize);
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
            return 0;
        }
    }

    @Command(name = "help", description = "Print help information", mixinStandardHelpOptions = true)
    public void help() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "inflate", description = "Inflate a condensed JFR file into JFR format")
    public static class InflateJFRCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The input file")
        private Path inputFile;

        @Parameters(index = "1", description = "The output file", defaultValue = "")
        private Path outputFile;

        private Path getOutputFile() {
            if (outputFile.toString().isEmpty()) {
                return inputFile.resolveSibling(inputFile.getFileName() + ".inflated.jfr");
            }
            return outputFile;
        }

        public Integer call() {
            if (!Files.exists(inputFile)) {
                System.err.println("Input file does not exist: " + inputFile);
                return 1;
            }
            try (var inputStream = Files.newInputStream(inputFile)) {
                var jfrReader = new BasicJFRReader(new CondensedInputStream(inputStream));
                WritingJFRReader.toJFRFile(jfrReader, getOutputFile());
            } catch (Exception e) {
                e.printStackTrace();
                return 1;
            }
            return 0;
        }
    }

    @Command(
            name = "benchmark",
            description = "Run the benchmarks on all files in the benchmark folder",
    mixinStandardHelpOptions = true)
    public static class BenchmarkCommand implements Callable<Integer> {
        @Option(
                names = {"-k", "--keep-condensed-file"},
                description = "Keep the condensed file")
        private boolean keepCondensedFile = false;

        @Option(
                names = {"-i", "--inflate-condensed-file"},
                description = "Inflate the condensed file")
        private boolean inflateCondensedFile = false;

        @Option(
                names = {"-I", "--keep-inflated-file"},
                description = "Keep the inflated file")
        private boolean keepInflatedFile = false;

        @Option(names = "--csv", description = "Output results as CSV")
        private boolean csv = false;

        @Option(names = "--only-per-hour", description = "Only show per-hour columns")
        private boolean onlyPerHour = false;

        @Option(names = "--regexp", description = "Regular expression to filter files")
        private String regexp = ".*";

        @Option(
                names = {"-c", "--configuratiosn"},
                description =
                        "The generatorConfiguration to use, possible values:"
                                + " ${COMPLETION-CANDIDATES}",
                completionCandidates = ConfigurationIterable.class,
                converter = ConfigurationConverter.class,
                arity = "1..")
        private List<Configuration> configurations =
                Configuration.configurations.values().stream().sorted().toList();

        public Integer call() {
            var results =
                    new Benchmark(configurations, regexp)
                            .runBenchmarks(
                                    keepCondensedFile, inflateCondensedFile, keepInflatedFile);
            if (csv) {
                System.out.println(results.toTable(new TableConfig(false, onlyPerHour)).toCSV());
            } else {
                System.out.println(results.toTable(new TableConfig(true, onlyPerHour)));
            }
            return 0;
        }
    }

    @Command(name = "agent", description = "Use the included Java agent on a specific JVM process",
    mixinStandardHelpOptions = true)
    public static class AgentCommand implements Callable<Integer> {
        @Parameters(
                index = "0",
                paramLabel = "PID",
                description = "The PID of the JVM process",
                defaultValue = "-1")
        private int pid;

        @Parameters(
                index = "1",
                paramLabel = "OPTIONS",
                description = "Options for the agent or 'read' to read the output continuously")
        private String options;

        private static Path ownJAR() throws URISyntaxException {
            return Path.of(
                            new File(
                                            AgentCommand.class
                                                    .getProtectionDomain()
                                                    .getCodeSource()
                                                    .getLocation()
                                                    .toURI())
                                    .getPath())
                    .toAbsolutePath();
        }

        private static void listVMs() {
            System.out.println("You have to parse the process id of a JVM");
            System.out.println("Possible JVMs that are currently running are: ");
            for (var vm : VirtualMachine.list()) {
                if (vm.displayName().isEmpty()) {
                    continue;
                }
                System.out.printf("%6s  %s%n", vm.id(), vm.displayName());
            }
            System.out.println(
                    "This might include JVMs lower than version 17 which are not supported.");
        }

        public Integer call() {
            if (pid == -1) {
                listVMs();
                return -1;
            }
            if (options.equals("read")) {
                AgentIO agentIO = new AgentIO(pid);
                while (Files.exists(agentIO.getOutputFile())) {
                    var out = agentIO.readOutput();
                    if (out != null) {
                        System.out.print(out);
                    }
                }
                return 0;
            }
            try {
                VirtualMachine jvm = VirtualMachine.attach(pid + "");
                jvm.loadAgent(ownJAR().toString(), options);
                jvm.detach();
                AgentIO agentIO = new AgentIO(pid);
                String out;
                while ((out = agentIO.readOutput()) != null) {
                    Thread.sleep(50);
                    System.out.print(out);
                }
            } catch (URISyntaxException ex) {
                System.err.println("Can't find the current JAR file");
                return 1;
            } catch (AgentLoadException | IOException | AgentInitializationException e) {
                System.err.println("Can't load the agent: " + e.getMessage());
                return 1;
            } catch (AttachNotSupportedException e) {
                System.err.println("Can't attach to the JVM process");
                return 1;
            } catch (InterruptedException e) {
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "summary", description = "Print a summary of the condensed JFR file", mixinStandardHelpOptions = true)
    public static class SummaryCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The input file")
        private Path inputFile;

        @Option(
                names = {"-s", "--short"},
                description = "Print a short summary without the event counts",
                defaultValue = "false")
        private boolean shortSummary;

        public Integer call() {
            if (!Files.exists(inputFile)) {
                System.err.println("Input file does not exist: " + inputFile);
                return 1;
            }
            try (var inputStream = Files.newInputStream(inputFile)) {
                var jfrReader = new BasicJFRReader(new CondensedInputStream(inputStream));
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
                System.out.println(" Start: " + TimeUtil.humanReadableFormat(summary.start()));
                System.out.println(" End: " + TimeUtil.humanReadableFormat(summary.end()));
                System.out.println(
                        " Duration: "
                                + TimeUtil.humanReadableFormat(
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

    static Summary computeSummary(BasicJFRReader reader) {
        Map<String, Integer> eventCounts = new HashMap<>();
        ReadStruct struct;
        while ((struct = reader.readNextEvent()) != null) {
            eventCounts.merge(struct.getType().getName(), 1, Integer::sum);
        }
        var startMessage = reader.getInputStream().getUniverse().getStartMessage();
        return new Summary(
                eventCounts.values().stream().mapToInt(Integer::intValue).sum(),
                reader.getUniverse().getDuration(),
                startMessage.version(),
                startMessage.generatorName(),
                startMessage.generatorVersion(),
                startMessage.generatorConfiguration(),
                startMessage.compression(),
                Instant.ofEpochMilli(reader.getUniverse().getStartTimeNanos() / 1000_000),
                Instant.ofEpochMilli(reader.getUniverse().getLastStartTimeNanos() / 1000_000),
                eventCounts);
    }

    @Command(
            name = "view",
            description = "View a specific event of a condensed JFR file as a table",
            mixinStandardHelpOptions = true)
    public static class ViewCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The event name", paramLabel = "EVENT_NAME")
        private String eventName;

        @Parameters(index = "1", description = "The input file", paramLabel = "INPUT_FILE")
        private Path inputFile;

        @Option(names = "--width", description = "Width of the table")
        private int width = 160;

        @Option(
                names = "--truncate",
                description = "How to truncate the output cells, 'begining' or 'end'")
        private String truncate = "end";

        @Option(names = "--cell-height", description = "Height of the table cells")
        private int cellHeight = 1;

        @Option(
                names = "--limit",
                description =
                        "Limit the number of events of the given type to print, or -1 for no limit")
        private int limit = -1;

        @Override
        public Integer call() {
            if (!Files.exists(inputFile)) {
                System.err.println("Input file does not exist: " + inputFile);
                return 1;
            }
            try (var inputStream = Files.newInputStream(inputFile)) {
                var jfrReader = new BasicJFRReader(new CondensedInputStream(inputStream));
                var struct = jfrReader.readNextEvent();
                JFRView view = null;
                int count = 0;
                while (struct != null) {
                    if (struct.getType().getName().equals(eventName)) {
                        if (view == null) {
                            view =
                                    new JFRView(
                                            new JFRViewConfig(struct.getType()),
                                            new PrintConfig(
                                                    width,
                                                    cellHeight,
                                                    TruncateMode.valueOf(truncate.toUpperCase())));
                            for (var line : view.header()) {
                                System.out.println(line);
                            }
                        }
                        for (var line : view.rows(struct)) {
                            System.out.println(line);
                        }
                        count++;
                        if (limit != -1 && count >= limit) {
                            break;
                        }
                    }
                    struct = jfrReader.readNextEvent();
                }
            } catch (Exception e) {
                e.printStackTrace();
                return 1;
            }
            return 0;
        }
    }

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    public static void main(String[] args) {
        int exitCode = new picocli.CommandLine(new JFRCLI()).execute(args);
        System.exit(exitCode);
    }
}
