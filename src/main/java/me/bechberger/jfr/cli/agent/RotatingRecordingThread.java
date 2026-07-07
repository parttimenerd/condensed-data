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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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

    private static final long ROTATION_WATCHDOG_INTERVAL_MS = 5_000;

    private final String pathTemplate;
    private volatile Path currentPath;
    private volatile State state = null;
    private final List<Path> currentlyStoredFiles;
    private final List<Instant> currentlyStoredStarts;
    private final Object filesLock = new Object();

    /**
     * Serializes event writes, rotation open/close steps, and close(). Using ReentrantLock so
     * onEvent() can hold the lock while calling initNewFile() (which re-acquires it).
     */
    private final ReentrantLock rotationLock = new ReentrantLock();

    private final AtomicInteger overallWrittenFileCount = new AtomicInteger(0);
    // triggered stop already
    private final AtomicBoolean triggeredStop = new AtomicBoolean(false);
    private final Thread rotationWatchdog;

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
        this.rotationWatchdog =
                new Thread(
                        this::runRotationWatchdog, "cjfr-rotation-watchdog[" + pathTemplate + "]");
        this.rotationWatchdog.setDaemon(true);
        this.rotationWatchdog.start();
        registerShutdownHook();
    }

    private static final int MAX_WATCHDOG_ROTATION_ERRORS = 5;

    private void runRotationWatchdog() {
        int consecutiveRotationErrors = 0;
        while (!triggeredStop.get()) {
            try {
                Thread.sleep(ROTATION_WATCHDOG_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (triggeredStop.get()) {
                return;
            }
            if (shouldEndRecording()) {
                triggerStop();
                return;
            }
            if (shouldEndFile()) {
                try {
                    initNewFile(false);
                    consecutiveRotationErrors = 0;
                } catch (IOException e) {
                    consecutiveRotationErrors++;
                    if (consecutiveRotationErrors <= MAX_WATCHDOG_ROTATION_ERRORS) {
                        agentIO.writeSevereError(
                                "Watchdog: failed to rotate file ("
                                        + consecutiveRotationErrors
                                        + "/"
                                        + MAX_WATCHDOG_ROTATION_ERRORS
                                        + "): "
                                        + e.getMessage());
                    }
                    if (consecutiveRotationErrors == MAX_WATCHDOG_ROTATION_ERRORS) {
                        agentIO.writeSevereError(
                                "Watchdog: too many consecutive rotation failures; stopping"
                                        + " recording.");
                        triggerStop();
                        return;
                    }
                }
            }
        }
    }

    private Path createNewPath() {
        return Path.of(replacePlaceholders(pathTemplate, overallWrittenFileCount.get()));
    }

    /** Delete oldest files so that count is below max-files. Must be called with filesLock held. */
    private Optional<Path> deleteOldestFileIfNeeded() {
        Optional<Path> last = Optional.empty();
        int max = Math.max(1, getMaxFiles());
        while (currentlyStoredFiles.size() >= max) {
            var fileToDelete = currentlyStoredFiles.get(0);
            try {
                Files.delete(fileToDelete);
            } catch (java.nio.file.NoSuchFileException ignored) {
                // Already deleted externally — forget it and keep enforcing maxFiles.
            } catch (IOException e) {
                agentIO.writeSevereError("Deleting oldest file failed: " + e.getMessage());
                // Drop from tracking so the same file isn't retried forever; the file
                // may remain on disk but retention is enforced going forward.
            }
            currentlyStoredFiles.remove(0);
            currentlyStoredStarts.remove(0);
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
     * Initialize a new file and start a new jfrWriter. Called on the JFR event thread (possibly
     * while rotationLock is already held) or the rotation watchdog. Serialized by {@code
     * rotationLock} (ReentrantLock, so re-entrant from onEvent is safe) to prevent double-rotation
     * and to ensure processEvent is never called on a closed writer. Opens the new file BEFORE
     * closing the old writer so a transient failure leaves the old writer intact.
     *
     * @param force if {@code false}, re-checks {@link #shouldEndFile()} inside the lock and skips
     *     the rotation if another thread already rotated first.
     */
    private void initNewFile(boolean force) throws IOException {
        rotationLock.lock();
        try {
            if (triggeredStop.get()) {
                return; // shutting down — do not open new files
            }
            if (!force && !shouldEndFile()) {
                return; // another thread already rotated
            }
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
            Path parent = newPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Open with CREATE_NEW BEFORE closing the old writer so a disk-full or permission
            // error leaves the previous recording stream intact and recording continues.
            // currentPath is set inside openNewOutputStream only on success.
            java.io.OutputStream rawOut = openNewOutputStream(newPath);
            newPath = currentPath; // reflect actual path (may differ if collision)

            CondensedOutputStream out;
            BasicJFRWriter newWriter;
            try {
                out =
                        new CondensedOutputStream(
                                rawOut,
                                new StartMessage(
                                        Constants.FORMAT_VERSION,
                                        "condensed jfr agent",
                                        Constants.VERSION,
                                        Agent.getAgentArgs(),
                                        Compression.DEFAULT));
                newWriter = new BasicJFRWriter(out);
            } catch (Throwable t) {
                try {
                    rawOut.close();
                } catch (IOException ignored) {
                }
                try {
                    Files.deleteIfExists(newPath);
                } catch (IOException ignored) {
                }
                if (t instanceof IOException ioe) throw ioe;
                throw new IOException("Failed to create writer", t);
            }

            var newState = new State(newWriter, newPath);

            // Close old writer only after new one is ready
            var oldState = this.state;

            synchronized (filesLock) {
                currentlyStoredFiles.add(newPath);
                currentlyStoredStarts.add(newState.start);
                overallWrittenFileCount.incrementAndGet();
                currentPath = newPath;
                state = newState;
            }

            if (oldState != null) {
                try {
                    closeState(oldState);
                } catch (Throwable t) {
                    agentIO.writeSevereError(
                            "Failed to close previous file (data may be incomplete): "
                                    + t.getMessage());
                }
            }
        } finally {
            rotationLock.unlock();
        }
    }

    private void initNewFile() throws IOException {
        initNewFile(true);
    }

    /** close the state, closing the writer and more */
    private void closeState(State state) {
        state.jfrWriter.close();
    }

    /** Spawns a stop thread under the sync object, guarded by CAS so only one fires. */
    private void triggerStop() {
        if (triggeredStop.compareAndSet(false, true)) {
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

    @Override
    public void close() {
        triggeredStop.set(true);
        rotationWatchdog.interrupt();
        try {
            rotationWatchdog.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        agentIO.writeOutput("Condensed recording to " + pathTemplate + " finished");
        rotationLock.lock();
        try {
            var s = this.state;
            this.state = null;
            if (s != null) {
                closeState(s);
            }
        } finally {
            rotationLock.unlock();
        }
    }

    @Override
    void onEvent(RecordedEvent event) {
        if (triggeredStop.get()) {
            return;
        }
        if (shouldEndRecording()) {
            triggerStop();
            return;
        }
        rotationLock.lock();
        try {
            if (triggeredStop.get()) {
                return;
            }
            // Rotate if needed — re-entrant acquisition inside initNewFile is safe.
            if (shouldEndFile()) {
                try {
                    initNewFile();
                } catch (IOException e) {
                    agentIO.writeSevereError(
                            "Error while rotating file: " + e.getMessage() + " " + e);
                    triggerStop();
                    return;
                }
            }
            var s = this.state;
            if (s == null) {
                return;
            }
            try {
                s.jfrWriter.processEvent(event);
            } catch (Exception e) {
                agentIO.writeSevereError(
                        "Error while processing event: " + e.getMessage() + " " + e);
                triggerStop();
            }
        } finally {
            rotationLock.unlock();
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
                Map.entry("total-files-written", Integer.toString(overallWrittenFileCount.get())),
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
                        var now = ZonedDateTime.now(ZoneOffset.UTC);
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
