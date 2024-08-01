package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.CREATE;
import static me.bechberger.jfr.Benchmark.formatMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.Constants;
import org.jetbrains.annotations.Nullable;

/** Record to multiple files */
public class RotatingRecordingThread extends RecordingThread {

    private final String pathTemplate;
    private Path currentPath;
    private AtomicReference<@Nullable State> state = new AtomicReference<>(null);
    private final List<Path> currentlyStoredFiles;
    private int overallWrittenFileCount = 0;

    record State(
            BasicJFRWriter jfrWriter,
            Path filePath,
            AtomicInteger currentlyRunningOnEventOperations) {
        State(BasicJFRWriter jfrWriter, Path filePath) {
            this(jfrWriter, filePath, new AtomicInteger(0));
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
        super(configuration, verbose, jfrConfig, miscJfrConfig, onRecordingStopped, dynSettings);
        this.pathTemplate = pathTemplate;
        this.currentlyStoredFiles = new ArrayList<>();
        initNewFile();
        agentIO.writeOutput("Condensed recording to " + pathTemplate + " started");
    }

    private Path createNewPath() {
        return Path.of(replacePlaceholders(pathTemplate, overallWrittenFileCount));
    }

    /** Delete files so that count is below max-files */
    private void deleteOldestFileIfNeeded() {
        if (currentlyStoredFiles.size() >= getMaxFiles()) {
            var fileToDelete = currentlyStoredFiles.remove(0);
            try {
                Files.delete(fileToDelete);
            } catch (IOException e) {
                agentIO.writeSevereError("Deleting oldest file failed: " + e.getMessage());
            }
        }
    }

    /** Initialize a new file and start a new jfrWriter */
    private void initNewFile() throws IOException {
        deleteOldestFileIfNeeded();
        var newPath = createNewPath();
        overallWrittenFileCount++;
        Files.createDirectories(newPath.toAbsolutePath().getParent());
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
        State oldState = state.get();
        currentPath = newPath;
        // cas loop to set state
        while (!state.compareAndSet(oldState, newState)) {
            oldState = state.get();
        }
        if (oldState != null) {
            closeState(oldState);
        }
    }

    /** close the state, closing the writer and more */
    private void closeState(State state) {
        while (!state.currentlyRunningOnEventOperations.compareAndSet(0, -10000))
            Thread.onSpinWait();
        state.jfrWriter.close();
    }

    @Override
    void onEvent(RecordedEvent event) {
        var currentState = state.get();
        if (currentState == null
                || currentState.currentlyRunningOnEventOperations.incrementAndGet() < 0) {
            return;
        }
        try {
            var state = this.state.get();
            if (state == null) {
                return;
            }
            if (shouldEndFile(state.jfrWriter)) {
                synchronized (Agent.getSyncObject()) {
                    closeState(state);
                    initNewFile();
                }
            }
            currentState.jfrWriter.processEvent(event);
        } catch (IOException e) {
            agentIO.writeSevereError("Error while processing event: " + e.getMessage() + " " + e);
        } finally {
            currentState.currentlyRunningOnEventOperations.decrementAndGet();
        }
    }

    @Override
    void close() {
        var currentState = state.get();
        if (currentState != null) {
            closeState(currentState);
        }
    }

    @Override
    List<Entry<String, String>> getMiscStatus() {
        var state = this.state.get();
        if (state == null || state.jfrWriter == null) {
            return new ArrayList<>();
        }
        return List.of(
                Map.entry("current-size-on-drive", formatMemory(state.jfrWriter.estimateSize(), 3)),
                Map.entry(
                        "current-size-uncompressed",
                        formatMemory(state.jfrWriter.getUncompressedStatistic().getBytes(), 3)),
                Map.entry("path", currentPath.toAbsolutePath().toString()));
    }

    private static final Map<String, Function<Integer, String>> rotatingFileNamePlaceholder =
            Map.of(
                    "$date",
                    i -> {
                        var date = Instant.now();
                        return date.toString().replace(":", "-").replace("T", "");
                    },
                    "$index",
                    i -> "_" + i);

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
