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
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private volatile int eventErrorCount = 0;
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
        try {
            var parsedMiscJfrConfig = parseJfrSettings(miscJfrConfig);
            parsedJfrConfig.getSettings().putAll(parsedMiscJfrConfig);
            this.recordingStream = new RecordingStream(parsedJfrConfig);
        } catch (Exception ex) {
            throw ex;
        }
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
            result.put(parts[0], parts[1]);
        }
        return result;
    }

    @SuppressWarnings("unused") // reserved for future use
    private Configuration getConfiguration() {
        return configuration;
    }

    private volatile boolean shuttingDown = false;
    private final Thread shutdownHook = new Thread(() -> shuttingDown = true);

    {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public void run() {
        try {
            recordingStream.onEvent(this::safeOnEvent);
            agentIO.writeInfo("start");
            recordingStream.start();
            agentIO.writeInfo("finished");
            if (!shouldStop.get()) {
                this.stop();
            }
        } catch (Throwable e) {
            if (shuttingDown) {
                return; // JVM is shutting down, silently stop
            }
            agentIO.writeSevereError("Error: " + e.getMessage());
        }
        agentIO.writeInfo("finished run");
        shouldStop.set(false);
    }

    /** Wraps {@link #onEvent} to prevent exceptions from propagating into JFR infrastructure */
    private void safeOnEvent(RecordedEvent event) {
        try {
            onEvent(event);
        } catch (Throwable e) {
            eventErrorCount++;
            if (eventErrorCount <= MAX_EVENT_ERRORS) {
                agentIO.writeSevereError(
                        "Error processing event: "
                                + e.getMessage()
                                + " ("
                                + eventErrorCount
                                + "/"
                                + MAX_EVENT_ERRORS
                                + ")");
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
        shouldStop.set(true);
        try {
            removeFromParent.run();
        } catch (Throwable e) {
            agentIO.writeSevereError("Error removing from parent: " + e.getMessage());
        }
        try {
            recordingStream.close();
        } catch (Throwable e) {
            agentIO.writeSevereError("Error closing recording stream: " + e.getMessage());
        }
        // wait till run() clears shouldStop — bounded to avoid infinite spin on JFR anomalies
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (shouldStop.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (shouldStop.get()) {
            agentIO.writeSevereError(
                    "Recording thread did not stop within 5s; proceeding with best-effort"
                            + " cleanup");
        }
        try {
            this.close();
        } catch (Throwable e) {
            agentIO.writeSevereError("Error closing writer: " + e.getMessage());
        }
        agentIO.writeInfo("closed");
        try {
            agentIO.close();
        } catch (Throwable e) {
            // last resort — can't report via agentIO since it's closing
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
        status.add(Map.entry("event-errors", Integer.toString(eventErrorCount)));
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
    }

    public boolean useNewNames() {
        return dynSettings.newNames;
    }

    public void useNewNames(boolean newNames) {
        dynSettings.newNames = newNames;
        dynSettings.validate(rotating);
    }
}
