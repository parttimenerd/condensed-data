package me.bechberger.cjfr;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.CombiningJFRReader;
import org.jetbrains.annotations.Nullable;

/**
 * Multi-file {@code .cjfr} reader that merges files in time order.
 *
 * <p>Use when you have a rotating set of files from a single JVM and want to read them as one
 * continuous stream:
 *
 * <pre>{@code
 * var paths = List.of(
 *     Path.of("app_0.cjfr"),
 *     Path.of("app_1.cjfr"),
 *     Path.of("app_2.cjfr"));
 * try (CJFRFiles files = CJFRFiles.open(paths)) {
 *     CJFREvent e;
 *     while ((e = files.readEvent()) != null) {
 *         System.out.println(e.getEventType().getName() + " " + e.getStartTime());
 *     }
 * }
 * }</pre>
 *
 * <p>For a single file use {@link CJFRFile}.
 */
public final class CJFRFiles implements AutoCloseable {

    private final CombiningJFRReader reader;
    private final Options options;

    private @Nullable ReadStruct peeked = null;
    private boolean peekedNull = false;

    private CJFRFiles(CombiningJFRReader reader, Options options) {
        this.reader = reader;
        this.options = options;
    }

    /**
     * Opens the given {@code .cjfr} files and merges them in time order.
     *
     * @throws IOException if any file cannot be read or is not a valid {@code .cjfr} stream
     */
    public static CJFRFiles open(List<Path> paths) throws IOException {
        return open(paths, Options.defaults());
    }

    /**
     * Opens the given {@code .cjfr} files and merges them in time order using the given options.
     *
     * @throws IOException if any file cannot be read or is not a valid {@code .cjfr} stream
     */
    public static CJFRFiles open(List<Path> paths, Options options) throws IOException {
        CombiningJFRReader reader = CombiningJFRReader.fromPaths(paths);
        return new CJFRFiles(reader, options);
    }

    /** Returns the start time of the earliest file in this set. */
    public Instant getStartTime() {
        return reader.getStartTime();
    }

    /** Returns the end time of the latest file in this set. */
    public Instant getEndTime() {
        return reader.getEndTime();
    }

    /** Returns the total duration spanned by all files. */
    public Duration getDuration() {
        return reader.getDuration();
    }

    /** Returns {@code true} if there is at least one more event to read. */
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
     * Returns event types seen so far across all files. Fully populated only after all events have
     * been read.
     */
    public List<CJFREventType> getEventTypes() {
        return reader.getInputStream().getTypeCollection().getTypes().stream()
                .filter(t -> t instanceof StructType<?, ?>)
                .map(t -> new CJFREventType((StructType<?, ?>) t))
                .toList();
    }

    @Override
    public void close() throws IOException {
        // CombiningJFRReader does not implement Closeable; streams close when GC'd.
        // Nothing to close explicitly.
    }

    @Override
    public String toString() {
        return "CJFRFiles{" + reader.getAllKnownTypeNames().size() + " types}";
    }
}
