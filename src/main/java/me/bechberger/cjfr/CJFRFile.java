package me.bechberger.cjfr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.bechberger.condensed.CJFRFooter;
import me.bechberger.condensed.CJFRFooterReader;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.BasicJFRReader;
import org.jetbrains.annotations.Nullable;

/**
 * Single {@code .cjfr} file reader.
 *
 * <p>Implements {@link AutoCloseable}; use in a try-with-resources block:
 *
 * <pre>{@code
 * try (CJFRFile f = CJFRFile.open(path)) {
 *     CJFREvent e;
 *     while ((e = f.readEvent()) != null) {
 *         System.out.println(e.getEventType().getName() + ": " + e.getStartTime());
 *     }
 * }
 * }</pre>
 *
 * <p>For reading multiple files in time order see {@link CJFRFiles}.
 */
public final class CJFRFile implements AutoCloseable {

    private final @Nullable Path path;
    private final BasicJFRReader reader;
    private final InputStream stream;
    private final @Nullable CJFRFooter footer;
    private final Options options;

    private @Nullable ReadStruct peeked = null;
    private boolean peekedNull = false;

    private CJFRFile(
            @Nullable Path path,
            BasicJFRReader reader,
            InputStream stream,
            @Nullable CJFRFooter footer,
            Options options) {
        this.path = path;
        this.reader = reader;
        this.stream = stream;
        this.footer = footer;
        this.options = options;
    }

    /**
     * Opens the {@code .cjfr} file at {@code path} with default options.
     *
     * @throws IOException if the file cannot be read or is not a valid {@code .cjfr} stream
     */
    public static CJFRFile open(Path path) throws IOException {
        return open(path, Options.defaults());
    }

    /**
     * Opens the {@code .cjfr} file at {@code path} with the given options.
     *
     * @throws IOException if the file cannot be read or is not a valid {@code .cjfr} stream
     */
    public static CJFRFile open(Path path, Options options) throws IOException {
        Optional<CJFRFooter> footer = CJFRFooterReader.tryRead(path);
        InputStream stream = Files.newInputStream(path);
        CondensedInputStream in = new CondensedInputStream(stream);
        BasicJFRReader reader = new BasicJFRReader(in, options.toReaderOptions());
        return new CJFRFile(path, reader, stream, footer.orElse(null), options);
    }

    /**
     * Opens a {@code .cjfr} stream from an {@link InputStream} with default options.
     *
     * <p>Footer metadata ({@link #getFooter()}, {@link #getStartTime()}, etc.) will not be
     * available because random-access seeking is required to read the footer.
     */
    public static CJFRFile open(InputStream inputStream) throws IOException {
        return open(inputStream, Options.defaults());
    }

    /** Opens a {@code .cjfr} stream from an {@link InputStream} with the given options. */
    public static CJFRFile open(InputStream inputStream, Options options) throws IOException {
        CondensedInputStream in = new CondensedInputStream(inputStream);
        BasicJFRReader reader = new BasicJFRReader(in, options.toReaderOptions());
        return new CJFRFile(null, reader, inputStream, null, options);
    }

    /**
     * Returns the precomputed footer, or {@code null} when the file has no footer (old format,
     * truncated file, or stream without a backing path).
     */
    public @Nullable CJFRFooter getFooter() {
        return footer;
    }

    /**
     * Returns the condenser configuration name (e.g. {@code "default"}, {@code "lossless"}, {@code
     * "reduced"}). Only available after at least one event has been read; call {@link #readEvent()}
     * first if needed.
     */
    public String getGeneratorConfiguration() {
        return reader.getConfiguration().name();
    }

    /**
     * Returns the recording start time from the footer, or from the stream header when no footer is
     * available.
     */
    public @Nullable Instant getStartTime() {
        if (footer != null) {
            return Instant.ofEpochSecond(0, footer.startTimeMicros() * 1000L);
        }
        return reader.getStartTime();
    }

    /** Returns the recording end time from the footer, or {@code null} when not available. */
    public @Nullable Instant getEndTime() {
        if (footer != null) {
            long endMicros = footer.startTimeMicros() + footer.durationMicros();
            return Instant.ofEpochSecond(0, endMicros * 1000L);
        }
        return reader.getEndTime();
    }

    /**
     * Returns the recording duration from the footer, or {@code Duration.ZERO} when not available.
     */
    public Duration getDuration() {
        if (footer != null) {
            return Duration.ofNanos(footer.durationMicros() * 1000L);
        }
        return reader.getDuration();
    }

    /** Returns the footer format version, or {@code -1} when no footer is present. */
    public int getFormatVersion() {
        return footer != null ? footer.version() : -1;
    }

    /**
     * Returns {@code true} if there is at least one more event to read.
     *
     * <p>This peeks ahead internally; a subsequent call to {@link #readEvent()} returns that same
     * event.
     */
    public boolean hasMoreEvents() {
        return peekNextEvent() != null;
    }

    private @Nullable ReadStruct peekNextEvent() {
        if (!peekedNull && peeked == null) {
            peeked = nextFiltered();
            if (peeked == null) peekedNull = true;
        }
        return peeked;
    }

    private @Nullable ReadStruct nextFiltered() {
        while (true) {
            ReadStruct raw = reader.readNextEvent();
            if (raw == null) return null;
            if (options.acceptsType(raw.getType().getName())) return raw;
        }
    }

    /** Reads and returns the next event, or {@code null} at end of stream. */
    public @Nullable CJFREvent readEvent() {
        ReadStruct raw;
        if (peeked != null) {
            raw = peeked;
            peeked = null;
            peekedNull = false;
        } else if (peekedNull) {
            return null;
        } else {
            raw = nextFiltered();
        }
        return raw == null ? null : new CJFREvent(raw);
    }

    /**
     * Reads all remaining events into a list. For large files prefer streaming via {@link
     * #readEvent()}.
     */
    public List<CJFREvent> readAllEvents() {
        List<CJFREvent> result = new ArrayList<>();
        CJFREvent e;
        while ((e = readEvent()) != null) {
            result.add(e);
        }
        return result;
    }

    /**
     * Returns event types defined in the recording so far. Fully populated only after all events
     * have been read, because types are registered lazily as the stream is parsed.
     */
    public List<CJFREventType> getEventTypes() {
        return reader.getInputStream().getTypeCollection().getTypes().stream()
                .filter(t -> t instanceof StructType<?, ?>)
                .map(t -> new CJFREventType((StructType<?, ?>) t))
                .toList();
    }

    /** Returns {@code true} if the stream was truncated (e.g. a recording in progress). */
    public boolean isTruncated() {
        return reader.isTruncated();
    }

    /** Returns the backing path, or {@code null} when opened from an {@link InputStream}. */
    public @Nullable Path getPath() {
        return path;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    @Override
    public String toString() {
        return "CJFRFile{" + (path != null ? path.getFileName() : "<stream>") + "}";
    }
}
