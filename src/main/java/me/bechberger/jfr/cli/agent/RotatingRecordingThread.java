package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.CREATE;
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
    private boolean triggeredStop = false;

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
        if (currentlyStoredFiles.size() >= getMaxFiles()) {
            var fileToDelete = currentlyStoredFiles.remove(0);
            currentlyStoredStarts.remove(0);
            try {
                Files.delete(fileToDelete);
            } catch (IOException e) {
                agentIO.writeSevereError("Deleting oldest file failed: " + e.getMessage());
            }
            return Optional.of(fileToDelete);
        }
        return Optional.empty();
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
        currentlyStoredFiles.add(newPath);
        overallWrittenFileCount++;
        Files.createDirectories(newPath.toAbsolutePath().getParent());
        if (state != null) {
            closeState(state);
        }
        var out =
                new CondensedOutputStream(
                        Files.newOutputStream(newPath, CREATE),
                        new StartMessage(
                                Constants.FORMAT_VERSION,
                                "condensed jfr agent",
                                Constants.VERSION,
                                Agent.getAgentArgs(),
                                Compression.DEFAULT));
        var newWriter = new BasicJFRWriter(out);
        var newState = new State(newWriter, newPath);
        currentlyStoredStarts.add(newState.start);
        currentPath = newPath;
        state = newState;
    }

    /** close the state, closing the writer and more */
    private void closeState(State state) {
        state.jfrWriter.close();
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
                    new Thread(
                                    () -> {
                                        synchronized (Agent.getSyncObject()) {
                                            stop();
                                        }
                                    })
                            .start();
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
    void close() {
        if (state != null) {
            closeState(state);
        }
    }

    @Override
    List<Entry<String, String>> getMiscStatus() {
        var state = this.state;
        if (state == null || state.jfrWriter == null) {
            return new ArrayList<>();
        }
        return List.of(
                Map.entry("mode", "rotating"),
                Map.entry("current-size-on-drive", formatMemory(state.jfrWriter.estimateSize(), 3)),
                Map.entry(
                        "current-size-uncompressed",
                        formatMemory(state.jfrWriter.getUncompressedStatistic().getBytes(), 3)),
                Map.entry("path", currentPath.toAbsolutePath().toString()),
                Map.entry("current-file-start", formatInstant(state.start)),
                Map.entry("stored-start", formatInstant(currentlyStoredStarts.get(0))));
    }

    private static final Map<String, Function<Integer, String>> rotatingFileNamePlaceholder =
            Map.of(
                    "$date",
                    i -> {
                        var date = Instant.now();
                        return date.toString().replace(":", "-").replace("T", "");
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
