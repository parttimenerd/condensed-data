package me.bechberger.jfr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.bechberger.JFRReader;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.cli.EventFilter;
import me.bechberger.jfr.cli.EventFilter.EventFilterInstance;
import org.jetbrains.annotations.Nullable;

/** Combines non-overlapping condensed JFR files */
public class CombiningJFRReader implements JFRReader {

    private static final Logger LOGGER = Logger.getLogger(CombiningJFRReader.class.getName());

    private record ReaderAndReadEvents(
            BasicJFRReader reader, StartMessage startMessage, List<ReadStruct> alreadyReadEvents) {}

    private final List<ReaderAndReadEvents> readers;
    private final EventFilterInstance filter;
    private final StartMessage startMessage;
    private int currentReaderIndex = 0;
    private ReaderAndReadEvents currentReader;
    private int currentEventIndex = 0;
    private ReadStruct lastReadEvent;

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
        return fromPaths(paths, (EventFilterInstance) null, true);
    }

    /**
     * Creates a reader for the {@code .cjfr} files in the given paths. This works with nested
     * folders and zip files.
     *
     * <p>Reads the events in two phases if needed for the filtering
     */
    public static <C> CombiningJFRReader fromPaths(
            List<Path> paths, @Nullable EventFilter<C> filter, boolean reconstitute) {
        if (filter == null) {
            return fromPaths(paths, (EventFilterInstance) null, reconstitute);
        }
        C context = filter.createContext();
        if (filter.isInformationGathering()) {
            var reader = fromPaths(paths, filter.createAnalyzeFilter(context), reconstitute);
            while (reader.readNextEvent() != null)
                ;
        }
        return fromPaths(paths, filter.createTestFilter(context), reconstitute);
    }

    /**
     * Creates a reader for the {@code .cjfr} files in the given paths. This works with nested
     * folders and zip files.
     */
    public static CombiningJFRReader fromPaths(
            List<Path> paths, EventFilterInstance filter, boolean reconstitute) {
        return new CombiningJFRReader(
                orderedUniqueReaders(
                        paths.stream()
                                .flatMap(p -> readersForPath(p, reconstitute).stream())
                                .toList()),
                filter);
    }

    private static List<ReaderAndReadEvents> orderedUniqueReaders(
            List<ReaderAndReadEvents> readers) {
        var sorted =
                readers.stream()
                        .sorted(
                                Comparator.comparingLong(
                                        a -> a.reader().getUniverse().getStartTimeNanos()))
                        .toList();
        // remove all readers that have the same start time and log a warning
        var startTimes = new HashSet<Long>();
        var uniqueReaders = new ArrayList<ReaderAndReadEvents>();
        for (var reader : sorted) {
            if (startTimes.add(reader.reader().getUniverse().getStartTimeNanos())) {
                uniqueReaders.add(reader);
            } else {
                LOGGER.warning("Multiple files with the same start time, only using the first one");
            }
        }
        return uniqueReaders;
    }

    private static List<ReaderAndReadEvents> readersForPath(Path path, boolean reconstitute) {
        if (Files.isDirectory(path)) {
            return Arrays.stream(Objects.requireNonNull(path.toFile().listFiles()))
                    .filter(f -> f.getName().endsWith(".cjfr"))
                    .map(f -> readersForPath(f.toPath(), reconstitute))
                    .flatMap(List::stream)
                    .toList();
        }
        if (Files.isRegularFile(path) && path.toString().endsWith(".cjfr")) {
            BasicJFRReader reader;
            try {
                return List.of(readerForInputStream(Files.newInputStream(path), reconstitute));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        // check if file is zip or tar.gz file
        if (isZip(path)) {
            return readersForZip(path, reconstitute);
        }
        return List.of();
    }

    private static ReaderAndReadEvents readerForInputStream(InputStream is, boolean reconstitute) {
        var reader = new BasicJFRReader(new CondensedInputStream(is), reconstitute);
        var alreadyReadEvents = new ArrayList<ReadStruct>();
        var event = reader.readNextEvent();
        if (event != null) {
            alreadyReadEvents.add(event);
        }
        var startMessage = reader.getStartMessage();
        return new ReaderAndReadEvents(reader, startMessage, alreadyReadEvents);
    }

    private static List<ReaderAndReadEvents> readersForZip(Path path, boolean reconstitute) {
        try (var is = Files.newInputStream(path)) {
            var zipReader = new ZipInputStream(is);
            var readers = new ArrayList<ReaderAndReadEvents>();
            ZipEntry entry;
            while ((entry = zipReader.getNextEntry()) != null) {
                if (entry.getName().endsWith(".cjfr")) {
                    readers.add(readerForInputStream(zipReader, reconstitute));
                }
            }
            return readers;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
                lastReadEvent = event;
                return event;
            }
            var event = currentReader.alreadyReadEvents.get(currentEventIndex++);
            if (filter != null && !filter.test(event)) {
                continue;
            }
            lastReadEvent = event;
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
        if (lastReadEvent == null) {
            return Instant.MIN;
        }
        var lastStartTime = lastReadEvent.get("startTime", Instant.class);
        return Instant.ofEpochSecond(lastStartTime.getEpochSecond(), lastStartTime.getNano());
    }

    @Override
    public CondensedInputStream getInputStream() {
        return readers.get(currentReaderIndex).reader().getInputStream();
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
