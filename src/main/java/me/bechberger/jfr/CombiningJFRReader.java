package me.bechberger.jfr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import me.bechberger.JFRReader;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.RIOException;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.stats.BasicStatistic;
import me.bechberger.condensed.stats.Statistic;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.EventFilter;
import me.bechberger.jfr.cli.EventFilter.EventFilterInstance;
import org.jetbrains.annotations.Nullable;

/** Combines non-overlapping condensed JFR files */
public class CombiningJFRReader implements JFRReader {

    private record ReaderAndReadEvents(
            BasicJFRReader reader, StartMessage startMessage, List<ReadStruct> alreadyReadEvents) {}

    private final List<ReaderAndReadEvents> readers;
    private final EventFilterInstance filter;
    private final StartMessage startMessage;
    private int currentReaderIndex = 0;
    private ReaderAndReadEvents currentReader;
    private int currentEventIndex = 0;

    private CombiningJFRReader(
            List<ReaderAndReadEvents> orderedReaders, EventFilterInstance filter) {
        this.readers = orderedReaders;
        this.filter = filter;
        if (orderedReaders.isEmpty()) {
            throw new IllegalArgumentException("No cjfr files given");
        }
        this.startMessage = createCombinedStartMessage(orderedReaders);
    }

    public static CombiningJFRReader fromPaths(List<Path> paths) {
        return fromPaths(paths, (EventFilterInstance) null, true, new BasicStatistic());
    }

    /**
     * Creates a reader for the {@code .cjfr} files in the given paths. This works with nested
     * folders and zip files.
     *
     * <p>Reads the events in two phases if needed for the filtering
     */
    public static <C> CombiningJFRReader fromPaths(
            List<Path> paths,
            @Nullable EventFilter<C> filter,
            boolean reconstitute,
            Statistic statistics) {
        if (filter == null) {
            return fromPaths(paths, (EventFilterInstance) null, reconstitute, statistics);
        }
        C context = filter.createContext();
        if (filter.isInformationGathering()) {
            var reader =
                    fromPaths(paths, filter.createAnalyzeFilter(context), reconstitute, statistics);
            while (reader.readNextEvent() != null)
                ;
        }
        return fromPaths(paths, filter.createTestFilter(context), reconstitute, statistics);
    }

    public static <C> CombiningJFRReader fromPaths(
            List<Path> paths, @Nullable EventFilter<C> filter, boolean reconstitute) {
        return fromPaths(paths, filter, reconstitute, new BasicStatistic());
    }

    /**
     * Creates a reader for the {@code .cjfr} files in the given paths. This works with nested
     * folders and zip files.
     */
    public static CombiningJFRReader fromPaths(
            List<Path> paths,
            EventFilterInstance filter,
            boolean reconstitute,
            Statistic statistics) {
        return new CombiningJFRReader(
                orderedUniqueReaders(
                        paths.stream()
                                .flatMap(p -> readersForPath(p, reconstitute, statistics).stream())
                                .toList()),
                filter);
    }

    public static CombiningJFRReader fromPaths(
            List<Path> paths, EventFilterInstance filter, boolean reconstitute) {
        return fromPaths(paths, filter, reconstitute, new BasicStatistic());
    }

    private static List<ReaderAndReadEvents> orderedUniqueReaders(
            List<ReaderAndReadEvents> readers) {
        var sorted =
                readers.stream()
                        .sorted(
                                Comparator.comparingLong(
                                        a -> a.reader().getUniverse().getStartTimeNanos()))
                        .toList();
        // remove all readers that have the same start time and configuration, log a warning
        var seen = new HashSet<String>();
        var uniqueReaders = new ArrayList<ReaderAndReadEvents>();
        for (var reader : sorted) {
            reader.reader().readTillFirstEvent();
            var key =
                    reader.reader().getUniverse().getStartTimeNanos()
                            + ":"
                            + reader.startMessage().generatorConfiguration();
            if (seen.add(key)) {
                uniqueReaders.add(reader);
            } else {
                System.err.println(
                        "Warning: Multiple files with the same start time and configuration, only"
                                + " using the first one");
            }
        }
        return uniqueReaders;
    }

    private static List<ReaderAndReadEvents> readersForPath(
            Path path, boolean reconstitute, Statistic statistics) {
        if (Files.isDirectory(path)) {
            return Arrays.stream(Objects.requireNonNull(path.toFile().listFiles()))
                    .filter(f -> f.getName().endsWith(".cjfr") || f.getName().endsWith(".jfr"))
                    .map(f -> readersForPath(f.toPath(), reconstitute, statistics))
                    .flatMap(List::stream)
                    .toList();
        }
        if (Files.isRegularFile(path) && path.toString().endsWith(".cjfr")) {
            try {
                return List.of(
                        readerForInputStream(Files.newInputStream(path), reconstitute, statistics));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (Files.isRegularFile(path) && path.toString().endsWith(".jfr")) {
            return List.of(readerForJFRFile(path, reconstitute, statistics));
        }
        // check if file is zip or tar.gz file
        if (isZip(path)) {
            return readersForZip(path, reconstitute, statistics);
        }
        return List.of();
    }

    private static ReaderAndReadEvents readerForInputStream(
            InputStream is, boolean reconstitute, Statistic statistics) {
        var reader =
                new BasicJFRReader(
                        new CondensedInputStream(is),
                        BasicJFRReader.Options.DEFAULT.withReconstitute(reconstitute));
        reader.setStatistics(statistics);
        var alreadyReadEvents = new ArrayList<ReadStruct>();
        var event = reader.readNextEvent();
        if (event != null) {
            alreadyReadEvents.add(event);
        } else if (reader.getUniverse().getStartTimeNanos() == -1) {
            // No events and no valid universe metadata — file is truncated or corrupt
            throw new RIOException(
                    "File appears to be truncated or corrupt: "
                            + "no events or valid metadata found");
        }
        var startMessage = reader.getStartMessage();
        return new ReaderAndReadEvents(reader, startMessage, alreadyReadEvents);
    }

    /**
     * Configuration for on-the-fly condensation of JFR files. Disables dedup ({@code
     * ignoreUnnecessaryEvents}) and event combining so that events whose field values were made
     * identical by lossy compression (e.g. BFloat16) are not incorrectly dropped as duplicates when
     * re-reading, and the combiner doesn't crash on unfamiliar event types.
     */
    private static final Configuration ON_THE_FLY_CONFIG =
            Configuration.DEFAULT
                    .withIgnoreUnnecessaryEvents(false)
                    .withCombineEventsWithoutDataLoss(false);

    /** Condense a .jfr file on-the-fly and return a reader for the condensed data */
    private static ReaderAndReadEvents readerForJFRFile(
            Path jfrPath, boolean reconstitute, Statistic statistics) {
        try {
            var baos = new java.io.ByteArrayOutputStream();
            try (var out =
                    new me.bechberger.condensed.CondensedOutputStream(
                            baos,
                            new me.bechberger.condensed.Message.StartMessage(
                                    me.bechberger.jfr.cli.Constants.FORMAT_VERSION,
                                    "condensed jfr cli",
                                    me.bechberger.jfr.cli.Constants.VERSION,
                                    ON_THE_FLY_CONFIG.name(),
                                    Compression.DEFAULT))) {
                var writer = new BasicJFRWriter(out, ON_THE_FLY_CONFIG);
                try (var recording = new jdk.jfr.consumer.RecordingFile(jfrPath)) {
                    while (recording.hasMoreEvents()) {
                        writer.processEvent(recording.readEvent());
                    }
                }
                writer.close();
            }
            return readerForInputStream(
                    new java.io.ByteArrayInputStream(baos.toByteArray()), reconstitute, statistics);
        } catch (IOException e) {
            throw new RuntimeException("Failed to condense JFR file: " + jfrPath, e);
        }
    }

    private static List<ReaderAndReadEvents> readersForZip(
            Path path, boolean reconstitute, Statistic statistics) {
        // unpack all .cjfr/.jfr files to a temp folder
        // reading directly from the zip file is not possible because individual files are read
        // independently and may need random access
        List<Path> cjfrFiles;
        try {
            cjfrFiles =
                    CLIUtils.extractMatchingZipEntries(
                            path,
                            entryName -> entryName.endsWith(".cjfr") || entryName.endsWith(".jfr"),
                            "jfr-cli");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return cjfrFiles.stream()
                .flatMap(p -> readersForPath(p, reconstitute, statistics).stream())
                .toList();
    }

    @Override
    public ReadStruct readNextEvent() {
        while (true) {
            if (currentReader == null) {
                if (currentReaderIndex >= readers.size()) {
                    return null;
                }
                currentReader = readers.get(currentReaderIndex);
            }
            if (currentEventIndex >= currentReader.alreadyReadEvents.size()) {
                var event = currentReader.reader().readNextEvent();
                if (event == null) {
                    currentReader = null;
                    currentReaderIndex++;
                    currentEventIndex = 0;
                    continue;
                }
                if (filter != null && !filter.test(event)) {
                    continue;
                }
                return event;
            }
            var event = currentReader.alreadyReadEvents.get(currentEventIndex++);
            if (filter != null && !filter.test(event)) {
                continue;
            }
            return event;
        }
    }

    /**
     * Get the combined start message and check that all readers have the format version and
     * compression configuration
     */
    private static StartMessage createCombinedStartMessage(List<ReaderAndReadEvents> readers) {
        Set<Integer> versions = new HashSet<>();
        Set<String> generatorNames = new HashSet<>();
        Set<String> generatorVersions = new HashSet<>();
        Set<String> generatorConfigurations = new HashSet<>();
        Set<Compression> compressions = new HashSet<>();
        for (var reader : readers) {
            versions.add(reader.startMessage.version());
            generatorNames.add(reader.startMessage.generatorName());
            generatorVersions.add(reader.startMessage.generatorVersion());
            generatorConfigurations.add(reader.startMessage.generatorConfiguration());
            compressions.add(reader.startMessage.compression());
        }
        // check that versions and compressions only contain one entry
        if (versions.size() != 1 || compressions.size() != 1) {
            throw new IllegalStateException(
                    "Versions and compressions must be the same for all readers: "
                            + versions
                            + ", "
                            + compressions);
        }
        return new StartMessage(
                versions.iterator().next(),
                String.join(", ", generatorNames),
                String.join(", ", generatorVersions),
                String.join(", ", generatorConfigurations),
                compressions.iterator().next());
    }

    @Override
    public StartMessage getStartMessage() {
        return startMessage;
    }

    @Override
    public Instant getStartTime() {
        return Instant.ofEpochSecond(0, readers.get(0).reader().getUniverse().getStartTimeNanos());
    }

    @Override
    public Instant getEndTime() {
        return readers.stream()
                .map(reader -> reader.reader().getEndTime())
                .max(Comparator.naturalOrder())
                .orElseGet(this::getStartTime);
    }

    @Override
    public CondensedInputStream getInputStream() {
        if (currentReader != null) {
            return currentReader.reader().getInputStream();
        }
        int index = Math.min(currentReaderIndex, readers.size() - 1);
        return readers.get(index).reader().getInputStream();
    }

    /**
     * Check if the file is a readable ZIP or GZIP file, based only on the first 4 bytes of the file
     */
    private static boolean isZip(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] signature = new byte[4];
            if (is.read(signature) != 4) {
                return false;
            }
            // Check for ZIP file signature
            if (signature[0] == 0x50
                    && signature[1] == 0x4B
                    && signature[2] == 0x03
                    && signature[3] == 0x04) {
                return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
