package me.bechberger.jfr.cli.agent;

import static me.bechberger.util.MemoryUtil.formatMemory;
import static me.bechberger.util.TimeUtil.formatDuration;
import static me.bechberger.util.TimeUtil.formatInstant;

import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.agent.AgentIO.LogLevel;

public abstract class RecordingThread implements Runnable {

    private static final int MAX_EVENT_ERRORS = 10;

    private final Configuration configuration;
    private final String jfrConfig;
    private final String miscJfrConfig;
    private final Runnable removeFromParent;
    private final RecordingStream recordingStream;
    final AgentIO agentIO = AgentIO.getAgentInstance();
    private final DynamicallyChangeableSettings dynSettings;
    private final boolean rotating;

    /** Counts down to 0 when run() exits; used by stop() to wait for the event thread. */
    private final CountDownLatch runExited = new CountDownLatch(1);

    private final AtomicInteger eventErrorCount = new AtomicInteger(0);
    final Instant start = Instant.now();

    RecordingThread(
            Configuration configuration,
            boolean verbose,
            String jfrConfig,
            String miscJfrConfig,
            Runnable removeFromParent,
            DynamicallyChangeableSettings dynSettings,
            boolean rotating)
            throws IOException, ParseException {
        AgentIO.setLogLevel(verbose ? LogLevel.ALL : LogLevel.WARNING);
        this.configuration = configuration;
        this.jfrConfig = jfrConfig;
        this.dynSettings = dynSettings;
        this.rotating = rotating;
        dynSettings.validate(rotating);
        var parsedJfrConfig =
                jfrConfig.isEmpty()
                        ? jdk.jfr.Configuration.getConfiguration("default")
                        : jdk.jfr.Configuration.getConfiguration(jfrConfig);
        agentIO.writeInfo("Using config " + parsedJfrConfig.getName());
        this.miscJfrConfig = miscJfrConfig;
        var parsedMiscJfrConfig = parseJfrSettings(miscJfrConfig);
        parsedJfrConfig.getSettings().putAll(parsedMiscJfrConfig);
        RecordingStream rs = new RecordingStream(parsedJfrConfig);
        this.recordingStream = rs;
        this.removeFromParent = removeFromParent;
    }

    private static Map<String, String> parseJfrSettings(String settings) {
        var result = new HashMap<String, String>();
        for (var s : settings.split("\\|")) {
            if (s.isEmpty()) continue;
            if (!s.contains("=")) {
                AgentIO.getAgentInstance()
                        .writeSevereError(
                                "Ignoring malformed --misc-jfr-config entry (missing '='): '"
                                        + s
                                        + "'");
                continue;
            }
            var parts = s.split("=", 2);
            if (parts[0].isEmpty()) {
                AgentIO.getAgentInstance()
                        .writeSevereError(
                                "Ignoring malformed --misc-jfr-config entry (empty key): '"
                                        + s
                                        + "'");
                continue;
            }
            result.put(parts[0].strip(), parts[1].strip());
        }
        return result;
    }

    @SuppressWarnings("unused") // reserved for future use
    private Configuration getConfiguration() {
        return configuration;
    }

    private volatile boolean shuttingDown = false;
    private final Thread shutdownHook =
            new Thread(
                    () -> {
                        shuttingDown = true;
                        // Finalize the current file so it is readable after JVM exit (e.g. SIGTERM)
                        stop();
                    },
                    "cjfr-shutdown-finalizer");

    /** Must be called as the last line of every subclass constructor. */
    protected final void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /** Remove the shutdown hook; call on constructor failure to avoid a JVM-shutdown block. */
    protected final void unregisterShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down — hook cannot be removed.
        }
    }

    @Override
    public void run() {
        try {
            recordingStream.onEvent(this::safeOnEvent);
            agentIO.writeInfo("start");
            recordingStream.start();
            agentIO.writeInfo("finished");
        } catch (Throwable e) {
            if (shuttingDown) {
                return; // JVM is shutting down, silently stop
            }
            agentIO.writeSevereError("Error: " + e.getMessage());
        } finally {
            runExited.countDown();
        }
        agentIO.writeInfo("finished run");
    }

    /** Wraps {@link #onEvent} to prevent exceptions from propagating into JFR infrastructure */
    private void safeOnEvent(RecordedEvent event) {
        try {
            onEvent(event);
        } catch (Throwable e) {
            int count = eventErrorCount.incrementAndGet();
            if (count <= MAX_EVENT_ERRORS) {
                agentIO.writeSevereError(
                        "Error processing event: "
                                + e.getMessage()
                                + " ("
                                + count
                                + "/"
                                + MAX_EVENT_ERRORS
                                + ")");
            }
            if (count == MAX_EVENT_ERRORS) {
                agentIO.writeSevereError(
                        "Too many event errors; stopping recording to avoid silent data loss.");
                Thread t =
                        new Thread(
                                () -> {
                                    synchronized (Agent.getSyncObject()) {
                                        stop();
                                    }
                                },
                                "cjfr-error-stop");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    abstract void onEvent(RecordedEvent event);

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down — hook cannot be removed
        }
        try {
            recordingStream.close();
        } catch (Throwable e) {
            agentIO.writeSevereError("Error closing recording stream: " + e.getMessage());
        }
        // Wait for the event-dispatch thread to exit before closing the writer,
        // so we don't close a writer that is mid-write.
        try {
            if (!runExited.await(5, TimeUnit.SECONDS)) {
                agentIO.writeSevereError(
                        "Recording thread did not stop within 5s; proceeding with best-effort"
                                + " cleanup");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            this.close();
        } catch (Throwable e) {
            agentIO.writeSevereError("Error closing writer: " + e.getMessage());
        }
        agentIO.writeInfo("closed");
        try {
            removeFromParent.run();
        } catch (Throwable e) {
            agentIO.writeSevereError("Error removing from parent: " + e.getMessage());
        }
    }

    public List<Entry<String, String>> getStatus() {
        List<Entry<String, String>> status = new ArrayList<>();
        status.add(Map.entry("generator-configuration", configuration.name()));
        status.add(Map.entry("jfr-config", jfrConfig));
        status.add(Map.entry("misc-jfr-config", miscJfrConfig));
        status.add(Map.entry("start", formatInstant(start)));
        status.add(Map.entry("elapsed", formatDuration(Duration.between(start, Instant.now()))));
        status.add(Map.entry("max-duration", formatDuration(getMaxDuration())));
        status.add(Map.entry("max-size", formatMemory(getMaxSize())));
        status.add(Map.entry("max-files", Integer.toString(getMaxFiles())));
        status.add(Map.entry("new-names", Boolean.toString(useNewNames())));
        status.add(Map.entry("duration", formatDuration(dynSettings.duration)));
        status.add(Map.entry("running", Boolean.toString(!stopped.get())));
        status.add(Map.entry("event-errors", Integer.toString(eventErrorCount.get())));
        status.addAll(getMiscStatus());
        return status;
    }

    abstract List<Entry<String, String>> getMiscStatus();

    /** Max size of a CJFR file, might change dynamically during the agents' execution */
    long getMaxSize() {
        return dynSettings.maxSize;
    }

    /**
     * Max duration for the individual recording when rotating, might change dynamically during the
     * agents' execution
     */
    Duration getMaxDuration() {
        return dynSettings.maxDuration;
    }

    Duration getDuration() {
        return dynSettings.duration;
    }

    /**
     * Max number of CJFR files stored on the device, might change dynamically during the agents'
     * execution and is only valid when rotating
     */
    int getMaxFiles() {
        return dynSettings.maxFiles;
    }

    /** Close the current jfrWriter, only called once */
    public abstract void close();

    /** Must be at least 1kB or 0 (no max size) */
    public void setMaxSize(long maxSize) {
        long old = dynSettings.maxSize;
        dynSettings.maxSize = maxSize;
        try {
            dynSettings.validate(rotating);
        } catch (DynamicallyChangeableSettings.ValidationException e) {
            dynSettings.maxSize = old;
            throw e;
        }
        if (rotating && maxSize == 0 && dynSettings.maxDuration.isZero()) {
            dynSettings.maxSize = old;
            throw new DynamicallyChangeableSettings.ValidationException(
                    "Cannot set --max-size to 0 while --max-duration is also 0:"
                            + " rotating mode requires at least one rotation trigger");
        }
    }

    /** Must be >= 1ms, 0 means no limit */
    public void setMaxDuration(Duration maxDuration) {
        var old = dynSettings.maxDuration;
        dynSettings.maxDuration = maxDuration;
        try {
            dynSettings.validate(rotating);
        } catch (DynamicallyChangeableSettings.ValidationException e) {
            dynSettings.maxDuration = old;
            throw e;
        }
        if (rotating && maxDuration.isZero() && dynSettings.maxSize == 0) {
            dynSettings.maxDuration = old;
            throw new DynamicallyChangeableSettings.ValidationException(
                    "Cannot set --max-duration to 0 while --max-size is also 0:"
                            + " rotating mode requires at least one rotation trigger");
        }
    }

    public void setDuration(Duration duration) {
        var old = dynSettings.duration;
        dynSettings.duration = duration;
        try {
            dynSettings.validate(rotating);
        } catch (DynamicallyChangeableSettings.ValidationException e) {
            dynSettings.duration = old;
            throw e;
        }
    }

    public void setMaxFiles(int maxFiles) {
        if (maxFiles < 0) {
            throw new IllegalArgumentException("Max files must be at least 0");
        }
        if (rotating && maxFiles < 1) {
            throw new IllegalArgumentException("Max files must be at least 1 when rotating files");
        }
        int old = dynSettings.maxFiles;
        dynSettings.maxFiles = maxFiles;
        try {
            dynSettings.validate(rotating);
        } catch (DynamicallyChangeableSettings.ValidationException e) {
            dynSettings.maxFiles = old;
            throw e;
        }
        if (rotating) {
            onMaxFilesChanged();
        }
    }

    /** Called after maxFiles is successfully updated in rotating mode. Subclasses may evict. */
    protected void onMaxFilesChanged() {}

    public boolean useNewNames() {
        return dynSettings.newNames;
    }

    public void useNewNames(boolean newNames) {
        boolean old = dynSettings.newNames;
        dynSettings.newNames = newNames;
        try {
            dynSettings.validate(rotating);
        } catch (DynamicallyChangeableSettings.ValidationException e) {
            dynSettings.newNames = old;
            throw e;
        }
    }
}
