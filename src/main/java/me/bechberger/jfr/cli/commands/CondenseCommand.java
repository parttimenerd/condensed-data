package me.bechberger.jfr.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils;
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
            description = "Compression algorithm, possible values: ${COMPLETION-CANDIDATES}")
    private Compression compression = null;

    @Option(
            names = {"-s", "--statistics"},
            description = "Print statistics")
    private boolean statistics = false;

    @Option(
            names = {"-f", "--force"},
            description = "Overwrite existing output file")
    private boolean force = false;

    @Option(
            names = {"-c", "--condenser-config"},
            description =
                    "The configuration to use, possible values: default, reasonable-default,"
                            + " reduced-default",
            defaultValue = "default",
            converter = ConfigurationConverter.class)
    private Configuration configuration;

    @Option(
            names = {"--events"},
            description = "Only condense these event types (repeatable, comma-separated)",
            split = ",")
    private List<String> eventTypes;

    private Path getOutputFile() {
        if (outputFile.toString().isEmpty()) {
            if (!inputFiles.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only one file is allowed if no output file given");
            }
            var inputName = inputFile.getFileName().toString();
            String outputName;
            if (inputName.endsWith(".jfr")) {
                outputName = inputName.substring(0, inputName.length() - ".jfr".length()) + ".cjfr";
            } else if (inputName.endsWith(".zip")) {
                var baseName = inputName.substring(0, inputName.length() - ".zip".length());
                if (baseName.endsWith(".jfr")) {
                    baseName = baseName.substring(0, baseName.length() - ".jfr".length());
                }
                outputName = baseName + ".cjfr";
            } else {
                outputName = inputName + ".cjfr";
            }
            return inputFile.resolveSibling(outputName);
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
            return CLIUtils.extractMatchingZipEntries(
                    path, entryName -> entryName.endsWith(".jfr"), "jfr-cli-condense");
        }
        return List.of(path);
    }

    public Integer call() {
        if (noCompression && compression != null) {
            System.err.println("Error: Cannot use both --no-compression and --compression");
            return 2;
        }
        Compression effectiveCompression =
                noCompression
                        ? Compression.NONE
                        : compression != null ? compression : Compression.DEFAULT;
        long resolvedInputSize = 0;
        Path tempFile = null;
        try {
            Path finalOutput = getOutputFile();
            CLIUtils.checkOutputFileWritable(finalOutput, force);
            Path parentDir =
                    finalOutput.getParent() != null ? finalOutput.getParent() : Path.of(".");
            Files.createDirectories(parentDir);
            try {
                tempFile = Files.createTempFile(parentDir, ".cjfr-", ".tmp");
            } catch (java.nio.file.AccessDeniedException e) {
                System.err.println("Error: Permission denied writing to: " + finalOutput);
                return 1;
            }
            try (var out =
                    new CondensedOutputStream(
                            Files.newOutputStream(tempFile),
                            new StartMessage(
                                    Constants.FORMAT_VERSION,
                                    "condensed jfr cli",
                                    Constants.VERSION,
                                    configuration.name(),
                                    effectiveCompression))) {
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
                // Read chunk header start times to get the actual recording start
                long minStartNanos = Long.MAX_VALUE;
                for (var input : resolvedInputs) {
                    try {
                        minStartNanos =
                                Math.min(
                                        minStartNanos,
                                        BasicJFRWriter.readChunkStartTimeNanos(input));
                    } catch (IOException ignored) {
                        // fall back to first-event start time
                    }
                }
                if (minStartNanos != Long.MAX_VALUE) {
                    basicJFRWriter.writeConfigurationAndUniverseIfNeeded(minStartNanos);
                }
                Set<String> eventFilter = eventTypes != null ? new HashSet<>(eventTypes) : null;
                Set<String> seenEventTypes = new HashSet<>();
                boolean firstFile = true;
                for (var input : resolvedInputs) {
                    if (!firstFile) {
                        basicJFRWriter.resetDeduplication();
                    }
                    firstFile = false;
                    try (RecordingFile r = new RecordingFile(input)) {
                        while (r.hasMoreEvents()) {
                            var e = r.readEvent();
                            String typeName = e.getEventType().getName();
                            seenEventTypes.add(typeName);
                            if (eventFilter != null && !eventFilter.contains(typeName)) {
                                continue;
                            }
                            basicJFRWriter.processEvent(e);
                        }
                    }
                }
                if (eventFilter != null) {
                    List<String> unknown =
                            eventTypes.stream()
                                    .filter(t -> !seenEventTypes.contains(t))
                                    .sorted()
                                    .toList();
                    if (!unknown.isEmpty()) {
                        System.err.println(
                                "Warning: No events found for type(s): "
                                        + String.join(", ", unknown));
                        System.err.println("Known event types include:");
                        seenEventTypes.stream()
                                .sorted()
                                .limit(10)
                                .forEach(t -> System.err.println("  " + t));
                        if (seenEventTypes.size() > 10) {
                            System.err.println(
                                    "  ... and " + (seenEventTypes.size() - 10) + " more");
                        }
                    }
                }
                basicJFRWriter.close();
                if (statistics) {
                    System.out.println(out.getStatistics().toPrettyString());
                }
            }
            Files.move(tempFile, finalOutput, StandardCopyOption.REPLACE_EXISTING);
            tempFile = null; // success — don't delete
            System.err.printf(
                    "Condensed to %s (%s)%n",
                    finalOutput, CLIUtils.formatFileSize(Files.size(finalOutput)));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            return me.bechberger.jfr.cli.CLIUtils.printError(e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
        // print JFR file size, output file size, compression ratio
        if (statistics) {
            try {
                var outSize = Files.size(getOutputFile());
                System.out.printf(
                        java.util.Locale.ROOT,
                        "JFR file size: %d, output file size: %d, compression ratio: %.2f\n",
                        resolvedInputSize,
                        outSize,
                        (double) outSize / resolvedInputSize);
            } catch (Exception e) {
                return me.bechberger.jfr.cli.CLIUtils.printError(e);
            }
        }
        return 0;
    }
}
