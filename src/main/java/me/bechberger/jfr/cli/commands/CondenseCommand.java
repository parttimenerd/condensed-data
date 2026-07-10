package me.bechberger.jfr.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import me.bechberger.jfr.cli.Constants;
import me.bechberger.jfr.cli.FileOptionConverters.*;

@Command(
        name = "condense",
        description = "Condense one or more JFR files (or a folder/ZIP) into .cjfr format",
        mixinStandardHelpOptions = true)
public class CondenseCommand implements Callable<Integer> {

    /**
     * All positional args. The last arg is treated as the output .cjfr file if it ends with ".cjfr"
     * and there is more than one arg; otherwise all args are inputs and the output path is derived
     * from the first input.
     */
    @Parameters(
            arity = "1..*",
            description =
                    "Input .jfr files (folders or zips), optionally followed by the output .cjfr"
                            + " file. If the last argument ends with .cjfr it is used as output.")
    private List<String> args = new ArrayList<>();

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
                    "The configuration to use, possible values: default, lossless,"
                        + " reasonable-default, reduced-default, archival-max. 'archival-max' is a"
                        + " shortcut for reduced-default data reductions plus MAX_COMPRESSION.",
            defaultValue = "default")
    private String configName;

    @Option(
            names = {"--compression-level"},
            description =
                    "Compression effort, possible values: ${COMPLETION-CANDIDATES}. Higher levels"
                            + " trade CPU for smaller files.")
    private Compression.CompressionLevel compressionLevel = null;

    /** The special CLI-only config name that expands to reduced-default + MAX_COMPRESSION. */
    private static final String ARCHIVAL_MAX = "archival-max";

    /** Resolves {@link #configName} to a {@link Configuration}, handling the archival-max alias. */
    private Configuration resolveConfiguration() {
        if (ARCHIVAL_MAX.equals(configName)) {
            return Configuration.REDUCED_DEFAULT;
        }
        if (!Configuration.configurations.containsKey(configName)) {
            throw new IllegalArgumentException(
                    "Invalid value for --condenser-config: Unknown configuration: "
                            + configName
                            + ", use one of "
                            + Configuration.configurations.keySet()
                            + " or the archival-max shortcut");
        }
        return Configuration.configurations.get(configName);
    }

    /**
     * The compression level to write into the header: an explicit --compression-level wins,
     * otherwise archival-max implies MAX_COMPRESSION, otherwise the default HIGH_COMPRESSION.
     */
    private Compression.CompressionLevel resolveCompressionLevel() {
        if (compressionLevel != null) {
            return compressionLevel;
        }
        if (ARCHIVAL_MAX.equals(configName)) {
            return Compression.CompressionLevel.MAX_COMPRESSION;
        }
        return Compression.CompressionLevel.HIGH_COMPRESSION;
    }

    @Option(
            names = {"--events"},
            description = "Only condense these event types (repeatable, comma-separated)",
            split = ",")
    private List<String> eventTypes;

    /**
     * Returns the raw string args that are input files (all except the optional trailing .cjfr).
     */
    private List<String> inputArgs() {
        if (args.size() > 1 && args.get(args.size() - 1).endsWith(".cjfr")) {
            return args.subList(0, args.size() - 1);
        }
        return args;
    }

    List<Path> inputs() {
        var converter = new ExistingJFRFileOrZipOrFolderConverter();
        List<String> inputArgs = inputArgs();
        var inputs = new ArrayList<Path>(inputArgs.size());
        for (String s : inputArgs) {
            inputs.add(converter.convert(s));
        }
        return inputs;
    }

    private Path getOutputFile() {
        if (args.size() > 1 && args.get(args.size() - 1).endsWith(".cjfr")) {
            return new CJFRFileConverter().convert(args.get(args.size() - 1));
        }
        // Derive output from the first input
        if (args.size() > 1) {
            throw new IllegalArgumentException(
                    "Only one input file is allowed if no output file given");
        }
        var inputPath = Path.of(args.get(0));
        var inputName = inputPath.getFileName().toString();
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
        return inputPath.resolveSibling(outputName);
    }

    /** Expand a path into individual JFR files, handling folders and ZIPs */
    private static List<Path> expandJFRPath(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            var files = path.toFile().listFiles();
            if (files == null) {
                throw new IOException("Cannot read directory: " + path);
            }
            return java.util.Arrays.stream(files)
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
            Configuration configuration = resolveConfiguration();
            Compression.CompressionLevel level = resolveCompressionLevel();
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
                                    effectiveCompression,
                                    level))) {
                var resolvedInputs = new ArrayList<Path>();
                for (var input : inputs()) {
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
