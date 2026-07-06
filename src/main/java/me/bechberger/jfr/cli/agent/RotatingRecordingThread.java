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
    private Path currentPath;
    private volatile State state = null;
    private final List<Path> currentlyStoredFiles;
    private final List<Instant> currentlyStoredStarts;
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

    /** Delete files so that count is below max-files */
    private Optional<Path> deleteOldestFileIfNeeded() {
        Optional<Path> last = Optional.empty();
        while (currentlyStoredFiles.size() >= getMaxFiles()) {
            var fileToDelete = currentlyStoredFiles.remove(0);
            currentlyStoredStarts.remove(0);
            try {
                Files.delete(fileToDelete);
            } catch (IOException e) {
                agentIO.writeSevereError("Deleting oldest file failed: " + e.getMessage());
            }
            last = Optional.of(fileToDelete);
        }
        return last;
    }

    /** Initialize a new file and start a new jfrWriter, is called in the same thread as onEvent */
    private void initNewFile() throws IOException {
        var delFile = deleteOldestFileIfNeeded();
        Path newPath;
        if (delFile.isPresent() && !useNewNames()) {
            newPath = delFile.get();
        } else {
            newPath = createNewPath();
        }
        Files.createDirectories(newPath.toAbsolutePath().getParent());
        if (state != null) {
            closeState(state);
        }
        // Open with CREATE_NEW to avoid silently overwriting an existing file (e.g. two
        // rotations within the same second when using $date). Fall back to CREATE on collision.
        java.io.OutputStream rawOut;
        try {
            rawOut = Files.newOutputStream(newPath, CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Append a counter suffix to make the name unique
            String base = newPath.toString();
            int suffix = 1;
            Path unique;
            do {
                unique =
                        Path.of(
                                base.endsWith(".cjfr")
                                        ? base.replace(".cjfr", "_" + suffix + ".cjfr")
                                        : base + "_" + suffix);
                suffix++;
            } while (Files.exists(unique));
            newPath = unique;
            rawOut = Files.newOutputStream(newPath, CREATE_NEW);
        }
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
        // Commit to the list only after the stream is successfully opened
        currentlyStoredFiles.add(newPath);
        currentlyStoredStarts.add(newState.start);
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
        return (getMaxDuration().toNanos() > 0
                        && Duration.between(this.state.start, Instant.now())
                                        .compareTo(getMaxDuration())
                                > 0)
                || (getMaxSize() > 0
                        && state.jfrWriter != null
                        && state.jfrWriter.estimateSize() > getMaxSize());
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
        // Snapshot mutable collections to avoid IOOBE if a rotation races with this read
        var storedStarts = new ArrayList<>(currentlyStoredStarts);
        var path = currentPath;
        return List.of(
                Map.entry("mode", "rotating"),
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
