package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static me.bechberger.util.MemoryUtil.formatMemory;
import static me.bechberger.util.TimeUtil.formatInstant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.Constants;

/** Record to multiple files */
public class RotatingRecordingThread extends RecordingThread {

    private final String pathTemplate;
    private volatile Path currentPath;
    private volatile State state = null;
    private final List<Path> currentlyStoredFiles;
    private final List<Instant> currentlyStoredStarts;
    private final Object filesLock = new Object();
    private int overallWrittenFileCount = 0;
    // triggered stop already
    private volatile boolean triggeredStop = false;

    record State(BasicJFRWriter jfrWriter, Path filePath, Instant start) {
        State(BasicJFRWriter jfrWriter, Path filePath) {
            this(jfrWriter, filePath, Instant.now());
        }
    }

    public RotatingRecordingThread(
            String pathTemplate,
            Configuration configuration,
            boolean verbose,
            String jfrConfig,
            String miscJfrConfig,
            Runnable onRecordingStopped,
            DynamicallyChangeableSettings dynSettings)
            throws IOException, ParseException {
        super(
                configuration,
                verbose,
                jfrConfig,
                miscJfrConfig,
                onRecordingStopped,
                dynSettings,
                true);
        this.pathTemplate = pathTemplate;
        this.currentlyStoredFiles = new ArrayList<>();
        this.currentlyStoredStarts = new ArrayList<>();
        initNewFile();
        agentIO.writeOutput("Condensed recording to " + pathTemplate + " started");
    }

    private Path createNewPath() {
        return Path.of(replacePlaceholders(pathTemplate, overallWrittenFileCount));
    }

    /** Delete oldest files so that count is below max-files. Must be called with filesLock held. */
    private Optional<Path> deleteOldestFileIfNeeded() {
        Optional<Path> last = Optional.empty();
        int max = Math.max(1, getMaxFiles());
        while (currentlyStoredFiles.size() >= max) {
            var fileToDelete = currentlyStoredFiles.get(0);
            try {
                Files.delete(fileToDelete);
                currentlyStoredFiles.remove(0);
                currentlyStoredStarts.remove(0);
            } catch (IOException e) {
                // File may have been deleted externally — remove tracking entry anyway
                currentlyStoredFiles.remove(0);
                currentlyStoredStarts.remove(0);
                agentIO.writeSevereError("Deleting oldest file failed: " + e.getMessage());
            }
            last = Optional.of(fileToDelete);
        }
        return last;
    }

    /**
     * Open a new output stream, retrying on name collision up to 100 times. Returns the opened
     * stream; sets {@code currentPath} to the final path used. Throws IOException if all attempts
     * fail. Only sets {@code currentPath} after a successful open.
     */
    private static final int MAX_COLLISION_RETRIES = 100;

    private java.io.OutputStream openNewOutputStream(Path basePath) throws IOException {
        // Try the base name first
        try {
            java.io.OutputStream out = Files.newOutputStream(basePath, CREATE_NEW);
            currentPath = basePath;
            return out;
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // fall through to suffix loop
        }
        String base = basePath.toString();
        for (int suffix = 1; suffix <= MAX_COLLISION_RETRIES; suffix++) {
            Path candidate =
                    Path.of(
                            base.endsWith(".cjfr")
                                    ? base.substring(0, base.length() - 5) + "_" + suffix + ".cjfr"
                                    : base + "_" + suffix);
            try {
                java.io.OutputStream out = Files.newOutputStream(candidate, CREATE_NEW);
                currentPath = candidate;
                return out;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // keep trying
            }
        }
        throw new IOException(
                "Could not create a unique output file after "
                        + MAX_COLLISION_RETRIES
                        + " attempts (base: "
                        + basePath
                        + ")");
    }

    /**
     * Initialize a new file and start a new jfrWriter. Called on the JFR event thread. Opens the
     * new file BEFORE closing the old writer so a transient failure leaves the old writer intact.
     */
    private void initNewFile() throws IOException {
        Path newPath;
        Optional<Path> delFile;
        synchronized (filesLock) {
            delFile = deleteOldestFileIfNeeded();
            if (delFile.isPresent() && !useNewNames()) {
                newPath = delFile.get();
            } else {
                newPath = createNewPath();
            }
        }
        Files.createDirectories(newPath.toAbsolutePath().getParent());

        // Open with CREATE_NEW BEFORE closing the old writer so a disk-full or permission error
        // leaves the previous recording stream intact and recording continues.
        // currentPath is set inside openNewOutputStream only on success.
        java.io.OutputStream rawOut = openNewOutputStream(newPath);
        newPath = currentPath; // reflect actual path (may differ from basePath if collision)

        var out =
                new CondensedOutputStream(
                        rawOut,
                        new StartMessage(
                                Constants.FORMAT_VERSION,
                                "condensed jfr agent",
                                Constants.VERSION,
                                Agent.getAgentArgs(),
                                Compression.DEFAULT));
        var newWriter = new BasicJFRWriter(out);
        var newState = new State(newWriter, newPath);

        // Close old writer only after new one is ready
        var oldState = this.state;
        if (oldState != null) {
            closeState(oldState);
        }

        // Commit to the list after the stream is successfully opened
        synchronized (filesLock) {
            currentlyStoredFiles.add(newPath);
            currentlyStoredStarts.add(newState.start);
        }
        overallWrittenFileCount++;
        currentPath = newPath;
        state = newState;
    }

    /** close the state, closing the writer and more */
    private void closeState(State state) {
        state.jfrWriter.close();
    }

    @Override
    public void close() {
        agentIO.writeOutput("Condensed recording to " + pathTemplate + " finished");
        var state = this.state;
        if (state != null) {
            closeState(state);
        }
    }

    @Override
    void onEvent(RecordedEvent event) {
        try {
            if (triggeredStop) {
                return;
            }
            if (shouldEndRecording()) {
                if (!triggeredStop) {
                    // we're currently stuck in JFR code, so we need to stop the recording
                    // from a different thread so we don't deadlock
                    Thread t =
                            new Thread(
                                    () -> {
                                        synchronized (Agent.getSyncObject()) {
                                            stop();
                                        }
                                    });
                    t.setDaemon(true);
                    t.start();
                    triggeredStop = true;
                }
                return;
            }
            var state = this.state;
            if (state == null) {
                return;
            }
            if (shouldEndFile()) {
                initNewFile();
            }
            state = this.state;
            if (state == null) {
                return;
            }
            state.jfrWriter.processEvent(event);
        } catch (IOException e) {
            agentIO.writeSevereError("Error while processing event: " + e.getMessage() + " " + e);
            if (!triggeredStop) {
                triggeredStop = true;
                Thread t =
                        new Thread(
                                () -> {
                                    synchronized (Agent.getSyncObject()) {
                                        stop();
                                    }
                                });
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private boolean shouldEndFile() {
        State s = this.state;
        if (s == null) return false;
        return (getMaxDuration().toNanos() > 0
                        && Duration.between(s.start, Instant.now()).compareTo(getMaxDuration()) > 0)
                || (getMaxSize() > 0
                        && s.jfrWriter != null
                        && s.jfrWriter.estimateSize() > getMaxSize());
    }

    private boolean shouldEndRecording() {
        boolean durationCheck = getDuration().toNanos() > 0;
        boolean isDurationExceeded =
                Duration.between(this.start, Instant.now()).compareTo(getDuration()) > 0;
        return isDurationExceeded && durationCheck;
    }

    @Override
    List<Entry<String, String>> getMiscStatus() {
        var state = this.state;
        if (state == null || state.jfrWriter == null) {
            return new ArrayList<>();
        }
        List<Instant> storedStarts;
        int storedCount;
        synchronized (filesLock) {
            storedStarts = new ArrayList<>(currentlyStoredStarts);
            storedCount = currentlyStoredFiles.size();
        }
        var path = currentPath;
        return List.of(
                Map.entry("mode", "rotating"),
                Map.entry("stored-files", storedCount + "/" + getMaxFiles()),
                Map.entry("total-files-written", Integer.toString(overallWrittenFileCount)),
                Map.entry("current-size-on-drive", formatMemory(state.jfrWriter.estimateSize(), 3)),
                Map.entry(
                        "current-size-uncompressed",
                        formatMemory(state.jfrWriter.getUncompressedStatistic().getBytes(), 3)),
                Map.entry("path", path != null ? path.toAbsolutePath().toString() : pathTemplate),
                Map.entry("current-file-start", formatInstant(state.start)),
                Map.entry(
                        "stored-start",
                        storedStarts.isEmpty() ? "none" : formatInstant(storedStarts.get(0))));
    }

    private static final Map<String, Function<Integer, String>> rotatingFileNamePlaceholder =
            Map.of(
                    "$date",
                    i -> {
                        var now = java.time.LocalDateTime.now();
                        return String.format(
                                "%04d-%02d-%02d_%02d-%02d-%02d-%03d",
                                now.getYear(),
                                now.getMonthValue(),
                                now.getDayOfMonth(),
                                now.getHour(),
                                now.getMinute(),
                                now.getSecond(),
                                now.getNano() / 1_000_000);
                    },
                    "$index",
                    i -> i + "");

    public static boolean containsPlaceholder(String path) {
        return rotatingFileNamePlaceholder.keySet().stream().anyMatch(path::contains);
    }

    public static String replacePlaceholders(String path, int index) {
        for (var entry : rotatingFileNamePlaceholder.entrySet()) {
            path = path.replace(entry.getKey(), entry.getValue().apply(index));
        }
        return path;
    }
}
