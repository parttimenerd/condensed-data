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
import java.util.concurrent.TimeUnit;
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

    /**
     * Returns the watchdog sleep interval in ms: min(5s, maxDuration/2) so we don't overshoot by a
     * full interval on small maxDuration values. Falls back to 5s when maxDuration is 0 (size-only
     * rotation) or very large.
     */
    private long watchdogIntervalMs() {
        Duration maxDur = getMaxDuration();
        if (maxDur.isZero() || maxDur.isNegative()) {
            return ROTATION_WATCHDOG_INTERVAL_MS;
        }
        long halfMs = maxDur.toMillis() / 2;
        return halfMs < 100 ? 100 : Math.min(halfMs, ROTATION_WATCHDOG_INTERVAL_MS);
    }

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

    private final AtomicInteger overallWrittenFileCount = new AtomicInteger(-1);
    // triggered stop already
    private final AtomicBoolean triggeredStop = new AtomicBoolean(false);
    private volatile Thread rotationWatchdog;

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
        registerShutdownHook();
        try {
            initNewFile(true);
        } catch (IOException | RuntimeException e) {
            // If first file open fails, remove the hook and the RecordingStream allocated
            // by the super-constructor so no native JFR resources are leaked on retry.
            unregisterShutdownHook();
            closeRecordingStream();
            throw e;
        }
        agentIO.writeOutput("Condensed recording to " + pathTemplate + " started");
        Thread watchdog;
        try {
            watchdog =
                    new Thread(
                            this::runRotationWatchdog,
                            "cjfr-rotation-watchdog[" + pathTemplate + "]");
            watchdog.setDaemon(true);
            watchdog.start();
        } catch (RuntimeException | Error e) {
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
            unregisterShutdownHook();
            closeRecordingStream();
            throw e;
        }
        this.rotationWatchdog = watchdog;
    }

    private static final int MAX_WATCHDOG_ROTATION_ERRORS = 5;

    private void runRotationWatchdog() {
        int consecutiveRotationErrors = 0;
        while (!triggeredStop.get()) {
            try {
                Thread.sleep(watchdogIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (triggeredStop.get()) {
                return;
            }
            if (shouldEndRecording()) {
                agentIO.writeInfo("Watchdog: total recording duration reached; stopping.");
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

    /**
     * Delete oldest sealed files until count is below max. Must be called with filesLock held.
     * {@code excludePath} is the path of the currently-open writer and is never evicted here; pass
     * {@code null} when there is no live writer (e.g. on final close).
     */
    private void deleteOldestFilesIfNeeded(Path excludePath) {
        int max = Math.max(1, getMaxFiles());
        int i = 0;
        while (currentlyStoredFiles.size() >= max && i < currentlyStoredFiles.size()) {
            Path candidate = currentlyStoredFiles.get(i);
            if (candidate.equals(excludePath)) {
                // Skip the live writer's file — it will be evicted once the writer is closed.
                i++;
                continue;
            }
            currentlyStoredFiles.remove(i);
            currentlyStoredStarts.remove(i);
            try {
                Files.delete(candidate);
            } catch (java.nio.file.NoSuchFileException ignored) {
                // Already deleted externally — forget it and keep enforcing maxFiles.
            } catch (IOException e) {
                agentIO.writeSevereError("Deleting oldest file failed: " + e.getMessage());
            }
        }
        if (currentlyStoredFiles.size() > max) {
            // Only live file(s) remain; can't evict further until a rotation seals them.
            agentIO.writeInfo(
                    "stored-files ("
                            + currentlyStoredFiles.size()
                            + ") exceeds max-files ("
                            + max
                            + ") because the live writer file cannot be evicted yet");
        }
    }

    /**
     * Open a new output stream, retrying on name collision up to 100 times. Returns the actual path
     * used (may differ from basePath on collision). Throws IOException if all attempts fail. Does
     * NOT modify currentPath — caller assigns it atomically in the publish step.
     */
    private static final int MAX_COLLISION_RETRIES = 100;

    private record OpenResult(java.io.OutputStream stream, Path actualPath) {}

    private OpenResult openNewOutputStream(Path basePath) throws IOException {
        try {
            java.io.OutputStream out = Files.newOutputStream(basePath, CREATE_NEW);
            return new OpenResult(out, basePath);
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
                return new OpenResult(out, candidate);
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
     * Initialize a new file and start a new jfrWriter. Serialized by {@code rotationLock}
     * (ReentrantLock; re-entrant from onEvent is safe). Opens the new file BEFORE closing the old
     * writer so a transient failure leaves the old writer intact. Path assignment and new-file
     * registration happen atomically in a single {@code filesLock} section after the new writer is
     * ready. Eviction of oldest sealed files happens AFTER the old writer is closed so we never
     * delete a file that still has an open writer.
     *
     * @param force if {@code false}, re-checks {@link #shouldEndFile()} inside the lock and skips
     *     the rotation if another thread already rotated.
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

            // Decide the candidate base path. Increment counter first so $index is monotone.
            // If the open fails, decrement to avoid gaps in $index.
            Path basePath;
            synchronized (filesLock) {
                overallWrittenFileCount.incrementAndGet();
                basePath = createNewPath();
            }

            Path parent = basePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Open the new file BEFORE closing the old writer. On failure the old writer
            // remains intact and recording continues.
            OpenResult opened;
            try {
                opened = openNewOutputStream(basePath);
            } catch (IOException e) {
                synchronized (filesLock) {
                    overallWrittenFileCount.decrementAndGet();
                }
                throw e;
            }
            Path newPath = opened.actualPath();
            java.io.OutputStream rawOut = opened.stream();

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
                synchronized (filesLock) {
                    overallWrittenFileCount.decrementAndGet();
                }
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
            var oldState = this.state;
            Path oldPath = oldState != null ? oldState.filePath : null;

            // Atomically: add new entry, publish new state.
            // We do NOT evict here — the old writer is still open and its file must not be deleted.
            synchronized (filesLock) {
                currentlyStoredFiles.add(newPath);
                currentlyStoredStarts.add(newState.start);
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
                // Evict oldest sealed files now that the old writer is closed.
                // Exclude the newly-added file (live writer); exclude nothing for the old path
                // since it is now sealed and eligible for eviction.
                synchronized (filesLock) {
                    deleteOldestFilesIfNeeded(newPath);
                }
            }
        } finally {
            rotationLock.unlock();
        }
    }

    /** close the state, closing the writer and more */
    private void closeState(State state) {
        state.jfrWriter.close();
    }

    /**
     * Called after maxFiles is reduced. Evicts oldest sealed files immediately so disk use is
     * bounded without waiting for the next rotation.
     */
    @Override
    protected void onMaxFilesChanged() {
        synchronized (filesLock) {
            Path livePath = currentPath;
            deleteOldestFilesIfNeeded(livePath);
        }
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
                            },
                            "cjfr-rotating-stop");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void close() {
        triggeredStop.set(true);
        if (rotationWatchdog != null) {
            rotationWatchdog.interrupt();
            try {
                rotationWatchdog.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        boolean locked;
        try {
            locked = rotationLock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (locked) {
            try {
                var s = this.state;
                this.state = null;
                if (s != null) {
                    closeState(s);
                }
                // Final eviction pass: the last file is now sealed and eligible for eviction.
                // Pass null as excludePath because there is no longer a live writer.
                synchronized (filesLock) {
                    deleteOldestFilesIfNeeded(null);
                }
            } finally {
                rotationLock.unlock();
            }
        } else {
            agentIO.writeSevereError(
                    "Could not acquire rotationLock within 2s during close; abandoning writer"
                            + " (best-effort shutdown)");
        }
        if (overallWrittenFileCount.get() >= 0) {
            agentIO.writeOutput("Condensed recording to " + pathTemplate + " finished");
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
            // Use force=false so the re-check inside the lock prevents double-rotation
            // (e.g., when the watchdog already rotated while we were waiting for the lock).
            if (shouldEndFile()) {
                long now = System.nanoTime();
                if (!rotationFailurePending
                        || (now - lastRotationFailureNanos) > ROTATION_FAILURE_BACKOFF_NS) {
                    try {
                        initNewFile(false);
                        rotationFailurePending = false;
                    } catch (IOException e) {
                        rotationFailurePending = true;
                        lastRotationFailureNanos = System.nanoTime();
                        // Rotation failed — keep writing to the current file rather than dropping
                        // the event or stopping. Back off for 1s to avoid flooding on a full disk.
                        agentIO.writeSevereError(
                                "Error while rotating file (continuing with current file, will"
                                        + " retry in 1s): "
                                        + e.getMessage());
                    }
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
        rotationLock.lock();
        try {
            State s = this.state;
            if (s == null || s.jfrWriter == null) {
                return new ArrayList<>();
            }
            List<Instant> storedStarts;
            int storedCount;
            Path path;
            int totalWritten;
            synchronized (filesLock) {
                storedStarts = new ArrayList<>(currentlyStoredStarts);
                storedCount = currentlyStoredFiles.size();
                path = currentPath;
                totalWritten = overallWrittenFileCount.get() + 1;
            }
            return List.of(
                    Map.entry("mode", "rotating"),
                    Map.entry("stored-files", storedCount + "/" + getMaxFiles()),
                    Map.entry("total-files-written", Integer.toString(totalWritten)),
                    Map.entry("current-size-on-drive", formatMemory(s.jfrWriter.estimateSize(), 3)),
                    Map.entry(
                            "current-size-uncompressed",
                            formatMemory(s.jfrWriter.getUncompressedStatistic().getBytes(), 3)),
                    Map.entry(
                            "path", path != null ? path.toAbsolutePath().toString() : pathTemplate),
                    Map.entry("current-file-start", formatInstant(s.start)),
                    Map.entry(
                            "stored-start",
                            storedStarts.isEmpty() ? "none" : formatInstant(storedStarts.get(0))));
        } finally {
            rotationLock.unlock();
        }
    }

    private static final long ROTATION_FAILURE_BACKOFF_NS = 1_000_000_000L;
    private volatile boolean rotationFailurePending = false;
    private volatile long lastRotationFailureNanos = 0L;

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
