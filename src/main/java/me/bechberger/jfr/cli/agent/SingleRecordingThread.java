package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static me.bechberger.util.MemoryUtil.formatMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.Constants;

/** Record to a single file */
public class SingleRecordingThread extends RecordingThread {

    private final String path;
    private final BasicJFRWriter jfrWriter;
    private final AtomicBoolean triggeredStop = new AtomicBoolean(false);

    /**
     * Serializes processEvent, close, and getMiscStatus to prevent torn state in BasicJFRWriter.
     */
    private final ReentrantLock writerLock = new ReentrantLock();

    public SingleRecordingThread(
            String path,
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
                false);
        this.path = path;
        // Register the shutdown hook before opening any file so a JVM exit during construction
        // still triggers finalization. Unregistered below if construction fails.
        registerShutdownHook();
        java.io.OutputStream rawOut = null;
        BasicJFRWriter writer = null;
        try {
            Path parent = Path.of(path).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            rawOut = Files.newOutputStream(Path.of(path), WRITE, CREATE, TRUNCATE_EXISTING);
            var condensedOut =
                    new CondensedOutputStream(
                            rawOut,
                            new StartMessage(
                                    Constants.FORMAT_VERSION,
                                    "condensed jfr agent",
                                    Constants.VERSION,
                                    Agent.getAgentArgs(),
                                    Compression.DEFAULT));
            writer = new BasicJFRWriter(condensedOut);
        } catch (Throwable t) {
            if (rawOut != null) {
                try {
                    rawOut.close();
                } catch (IOException ignored) {
                }
            }
            unregisterShutdownHook();
            closeRecordingStream();
            if (t instanceof IOException ioe) throw ioe;
            throw new IOException("Failed to create writer", t);
        }
        this.jfrWriter = writer;
        agentIO.writeOutput("Condensed recording to " + path + " started\n");
    }

    @Override
    void onEvent(RecordedEvent event) {
        if (triggeredStop.get()) {
            return;
        }
        writerLock.lock();
        String pendingError = null;
        try {
            if (triggeredStop.get()) {
                return;
            }
            // shouldEndFile() is evaluated under writerLock so estimateSize() is not read
            // concurrently with getMiscStatus() on the status thread.
            if (shouldEndFileUnderLock()) {
                triggerStop();
                return;
            }
            jfrWriter.processEvent(event);
        } catch (Exception e) {
            pendingError = "Error processing event: " + e.getMessage();
            triggerStop();
        } finally {
            writerLock.unlock();
        }
        if (pendingError != null) {
            agentIO.writeSevereError(pendingError);
        }
    }

    private void triggerStop() {
        if (triggeredStop.compareAndSet(false, true)) {
            // we're currently stuck in JFR code, so we need to stop the recording
            // from a different thread so we don't deadlock
            Thread t =
                    new Thread(
                            () -> {
                                synchronized (Agent.getSyncObject()) {
                                    stop();
                                }
                            },
                            "cjfr-single-stop");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void close() {
        boolean locked;
        try {
            locked = writerLock.tryLock(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (locked) {
            try {
                jfrWriter.close();
            } finally {
                writerLock.unlock();
            }
            agentIO.writeOutput("Condensed recording to " + path + " finished\n");
        } else {
            agentIO.writeSevereError(
                    "Could not acquire writerLock within 2s during close;"
                            + " abandoning writer (best-effort shutdown)");
            agentIO.writeOutput(
                    "Condensed recording to "
                            + path
                            + " finished (last file may be truncated — shutdown under load)\n");
        }
    }

    @Override
    List<Entry<String, String>> getMiscStatus() {
        boolean locked;
        try {
            locked = writerLock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            return List.of(
                    Map.entry("mode", "single file"),
                    Map.entry("path", Path.of(path).toAbsolutePath().toString()),
                    Map.entry("state", "writing (status unavailable)"));
        }
        long sizeOnDrive;
        long sizeUncompressed;
        try {
            sizeOnDrive = jfrWriter.estimateSize();
            sizeUncompressed = jfrWriter.getUncompressedStatistic().getBytes();
        } catch (Throwable t) {
            return List.of(
                    Map.entry("mode", "single file"),
                    Map.entry("path", Path.of(path).toAbsolutePath().toString()),
                    Map.entry("state", "closing"));
        } finally {
            writerLock.unlock();
        }
        return List.of(
                Map.entry("mode", "single file"),
                Map.entry("current-size-on-drive", formatMemory(sizeOnDrive, 3)),
                Map.entry("current-size-uncompressed", formatMemory(sizeUncompressed, 3)),
                Map.entry("path", Path.of(path).toAbsolutePath().toString()));
    }

    /** Must be called while holding {@code writerLock}. */
    private boolean shouldEndFileUnderLock() {
        long durationNanos = getDuration().toNanos();
        boolean isDurationExceeded =
                durationNanos > 0 && (System.nanoTime() - startNanos) > durationNanos;
        boolean isSizeExceeded = getMaxSize() > 0 && jfrWriter.estimateSize() > getMaxSize();
        return isDurationExceeded || isSizeExceeded;
    }
}
