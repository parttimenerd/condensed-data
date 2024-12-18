package me.bechberger.jfr.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import me.bechberger.jfr.cli.Constants;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "condense", description = "Condense a JFR file", mixinStandardHelpOptions = true)
public class CondenseJFRCommand implements Callable<Integer> {

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
                    "The configuration to use, possible values:" + " ${COMPLETION-CANDIDATES}",
            completionCandidates = ConfigurationIterable.class,
            defaultValue = "default",
            showDefaultValue = Visibility.ALWAYS,
            converter = ConfigurationConverter.class)
    private Configuration configuration;

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
