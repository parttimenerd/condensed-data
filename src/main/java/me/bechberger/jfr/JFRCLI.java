package me.bechberger.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.Callable;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.Benchmark.TableConfig;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

/** A CLI for writing JFR (and more) based on {@link BasicJFRWriter} */
@Command(
        name = "cjfr",
        description = "Work with condensed JFR files",
        subcommands = {
            JFRCLI.WriteJFRCommand.class,
            JFRCLI.InflateJFRCommand.class,
            JFRCLI.BenchmarkCommand.class
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
        private boolean compress = false;

        @Option(
                names = {"-s", "--statistics"},
                description = "Print statistics")
        private boolean statistics = false;

        static class ConfigurationIterable implements Iterable<String> {
            @NotNull
            @Override
            public Iterator<String> iterator() {
                return Configuration.configurations.values().stream()
                        .map(Configuration::name)
                        .sorted()
                        .iterator();
            }
        }

        @Option(
                names = {"-c", "--configuration"},
                description = "The configuration to use, possible values: ${COMPLETION-CANDIDATES}",
                completionCandidates = ConfigurationIterable.class,
                defaultValue = "default")
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
                            StartMessage.DEFAULT.compress(compress))) {
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

        public Integer call() {
            var results =
                    new Benchmark(Configuration.configurations.values().stream().sorted().toList())
                            .runBenchmarks(
                                    keepCondensedFile, inflateCondensedFile, keepInflatedFile);
            if (csv) {
                System.out.println(results.toTable(new TableConfig(false)).toCSV());
            } else {
                System.out.println(results.toTable(new TableConfig(true)));
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
