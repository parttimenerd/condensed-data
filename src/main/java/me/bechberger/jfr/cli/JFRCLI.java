package me.bechberger.jfr.cli;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.*;
import me.bechberger.jfr.Benchmark.TableConfig;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

/** A CLI for writing JFR (and more) based on {@link BasicJFRWriter} */
@Command(
        name = "cjfr",
        description = "Work with condensed JFR files",
        subcommands = {
            JFRCLI.WriteJFRCommand.class,
            JFRCLI.InflateJFRCommand.class,
            JFRCLI.BenchmarkCommand.class,
            JFRCLI.AgentCommand.class
        },
        mixinStandardHelpOptions = true)
public class JFRCLI implements Runnable {

    @Spec CommandSpec spec;

    @Command(name = "condense", description = "Condense a JFR file")
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
                names = {"-c", "--configuration"},
                description = "The configuration to use, possible values: ${COMPLETION-CANDIDATES}",
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
                            StartMessage.DEFAULT.compress(Compression.DEFAULT))) {
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

    @Command(name = "help", description = "Print help information")
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
            description = "Run the benchmarks on all files in the benchmark folder")
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
                description = "The configuration to use, possible values: ${COMPLETION-CANDIDATES}",
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

    @Command(name = "agent", description = "Use the included Java agent on a specific JVM process")
    public static class AgentCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PID", description = "The PID of the JVM process", defaultValue = "-1")
        private int pid;

        @Parameters(index = "1", paramLabel = "OPTIONS", description = "Options for the agent or 'read' to read the output continuously")
        private String options;

        private static Path ownJAR() throws URISyntaxException {
            return Path.of(new File(AgentCommand.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).getPath()).toAbsolutePath();
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
            System.out.println("This might include JVMs lower than version 17 which are not supported.");
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

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    public static void main(String[] args) {
        int exitCode = new picocli.CommandLine(new JFRCLI()).execute(args);
        System.exit(exitCode);
    }

}
