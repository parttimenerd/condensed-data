package me.bechberger.jfr.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.Constants;
import me.bechberger.jfr.cli.FileOptionConverters.*;

@Command(name = "condense", description = "Condense a JFR file", mixinStandardHelpOptions = true)
public class CondenseCommand implements Callable<Integer> {

    // optional out path, compress flag, statistics flag

    @Parameters(
            description = "The input .jfr file, can be a folder, or a zip",
            converter = ExistingJFRFileOrZipOrFolderConverter.class)
    private Path inputFile;

    @Parameters(
            index = "1",
            arity = "0..1",
            description = "The output file, default is the inputFile with the ending *.cjfr",
            defaultValue = "",
            converter = CJFRFileConverter.class)
    private Path outputFile;

    @Option(
            names = {"-i", "--inputs"},
            description = "Additional input files",
            converter = ExistingJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Option(
            names = {"--no-compression"},
            description = "Don't compress the output file")
    private boolean noCompression = false;

    @Option(
            names = {"--compression"},
            description = "Compression algorithm, possible values: ${COMPLETION-CANDIDATES}",
            defaultValue = "ZSTD")
    private Compression compression = Compression.DEFAULT;

    @Option(
            names = {"-s", "--statistics"},
            description = "Print statistics")
    private boolean statistics = false;

    @Option(
            names = {"-c", "--generatorConfiguration"},
            description =
                    "The configuration to use, possible values: default, reasonable-default,"
                            + " reduced-default",
            defaultValue = "default",
            converter = ConfigurationConverter.class)
    private Configuration configuration;

    private Path getOutputFile() {
        if (outputFile.toString().isEmpty()) {
            if (!inputFiles.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only one file is allowed if no output file given");
            }
            return inputFile.resolveSibling(
                    inputFile.getFileName().toString().replace(".jfr", ".cjfr"));
        }
        return outputFile;
    }

    /** Expand a path into individual JFR files, handling folders and ZIPs */
    private static List<Path> expandJFRPath(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return java.util.Arrays.stream(Objects.requireNonNull(path.toFile().listFiles()))
                    .filter(f -> f.getName().endsWith(".jfr"))
                    .map(java.io.File::toPath)
                    .toList();
        }
        if (path.toString().endsWith(".zip")) {
            var jfrFiles = new ArrayList<Path>();
            var tmpFolder = Files.createTempDirectory("jfr-cli-condense");
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        try {
                                            for (var f : jfrFiles) {
                                                Files.deleteIfExists(f);
                                            }
                                            Files.deleteIfExists(tmpFolder);
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }));
            try (var is = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry entry;
                while ((entry = is.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".jfr")) {
                        var tmpFile = tmpFolder.resolve(Path.of(entry.getName()).getFileName());
                        Files.copy(is, tmpFile);
                        jfrFiles.add(tmpFile);
                    }
                }
            }
            return jfrFiles;
        }
        return List.of(path);
    }

    public Integer call() {
        long resolvedInputSize = 0;
        try (var out =
                new CondensedOutputStream(
                        Files.newOutputStream(getOutputFile()),
                        new StartMessage(
                                Constants.FORMAT_VERSION,
                                "condensed jfr cli",
                                Constants.VERSION,
                                configuration.name(),
                                noCompression ? Compression.NONE : compression))) {
            var inputs = new ArrayList<Path>();
            inputs.add(inputFile);
            inputs.addAll(inputFiles);
            var resolvedInputs = new ArrayList<Path>();
            for (var input : inputs) {
                resolvedInputs.addAll(expandJFRPath(input));
            }
            if (statistics) {
                for (var input : resolvedInputs) {
                    resolvedInputSize += Files.size(input);
                }
            }
            var basicJFRWriter = new BasicJFRWriter(out, configuration);
            for (var input : resolvedInputs) {
                try (RecordingFile r = new RecordingFile(input)) {
                    while (r.hasMoreEvents()) {
                        var e = r.readEvent();
                        basicJFRWriter.processEvent(e);
                    }
                }
            }
            basicJFRWriter.close();
            if (statistics) {
                System.out.println(out.getStatistics().toPrettyString());
            }
        } catch (Exception e) {
            return me.bechberger.jfr.cli.CLIUtils.printError(e);
        }
        // print JFR file size, output file size, compression ratio
        if (statistics) {
            try {
                var outSize = Files.size(getOutputFile());
                System.out.printf(
                        "JFR file size: %d, output file size: %d, compression ratio: %.2f\n",
                        resolvedInputSize, outSize, (double) outSize / resolvedInputSize);
            } catch (Exception e) {
                return me.bechberger.jfr.cli.CLIUtils.printError(e);
            }
        }
        return 0;
    }
}
