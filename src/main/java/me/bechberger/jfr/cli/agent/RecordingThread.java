package me.bechberger.jfr.cli.agent;

import static me.bechberger.util.MemoryUtil.formatMemory;
import static me.bechberger.util.TimeUtil.formatDuration;
import static me.bechberger.util.TimeUtil.formatInstant;

import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.agent.AgentIO.LogLevel;

public abstract class RecordingThread implements Runnable {

    private final Configuration configuration;
    private final String jfrConfig;
    private final String miscJfrConfig;
    private final Runnable removeFromParent;
    private final RecordingStream recordingStream;
    final AgentIO agentIO = AgentIO.getAgentInstance();
    private final DynamicallyChangeableSettings dynSettings;
    private final boolean rotating;

    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
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
        this.agentIO.setLogLevel(verbose ? LogLevel.ALL : LogLevel.WARNING);
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
        return Arrays.stream(settings.split("\\|"))
                .filter(s -> !s.isEmpty())
                .map(s -> s.split("=", 2))
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    }

    private Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void run() {
        try {
            recordingStream.onEvent(this::onEvent);
            agentIO.writeInfo("start");
            recordingStream.start();
            agentIO.writeInfo("finished");
            if (!shouldStop.get()) {
                this.stop();
            }
        } catch (RuntimeException e) {
            if (e.getMessage().startsWith("The stream is already closed while processing event")) {
                return; // TODO improve, this happens when the JVM is shutdown
            }
            agentIO.writeSevereError("Error: " + e.getMessage());
        }
        agentIO.writeInfo("finished run");
        shouldStop.set(false);
    }

    abstract void onEvent(RecordedEvent event);

    private volatile boolean stopped = false;

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        shouldStop.set(true);
        removeFromParent.run();
        recordingStream.close();
        while (shouldStop.get()) { // wait till it properly stopped
            Thread.onSpinWait();
        }
        this.close();
        agentIO.writeInfo("closed");
        agentIO.close();
    }

    public List<Entry<String, String>> getStatus() {
        List<Entry<String, String>> status = new ArrayList<>();
        status.add(Map.entry("generator-configuration", configuration.name()));
        status.add(Map.entry("jfr-config", jfrConfig));
        status.add(Map.entry("misc-jfr-config", miscJfrConfig));
        status.add(Map.entry("start", formatInstant(start)));
        status.add(Map.entry("duration", formatDuration(Duration.between(start, Instant.now()))));
        status.add(Map.entry("max-duration", formatDuration(getMaxDuration())));
        status.add(Map.entry("max-size", formatMemory(getMaxSize())));
        status.add(Map.entry("max-files", Integer.toString(getMaxFiles())));
        status.add(Map.entry("new-names", Boolean.toString(useNewNames())));
        status.add(Map.entry("duration", formatDuration(dynSettings.duration)));
        status.add(Map.entry("running", Boolean.toString(!stopped)));
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

    public void onClose() {}

    /** Must be at least 1kB or 0 (no max size) */
    public void setMaxSize(long maxSize) {
        dynSettings.maxSize = maxSize;
        dynSettings.validate(rotating);
    }

    /** Must be >= 1ms, 0 means no limit */
    public void setMaxDuration(Duration maxDuration) {
        dynSettings.maxDuration = maxDuration;
        dynSettings.validate(rotating);
    }

    public void setDuration(Duration duration) {
        dynSettings.duration = duration;
        dynSettings.validate(rotating);
    }

    public void setMaxFiles(int maxFiles) {
        if (maxFiles < 0) {
            throw new IllegalArgumentException("Max files must be at least 0");
        }
        dynSettings.maxFiles = maxFiles;
        dynSettings.validate(rotating);
    }

    public boolean useNewNames() {
        return dynSettings.newNames;
    }

    public void useNewNames(boolean newNames) {
        dynSettings.newNames = newNames;
        dynSettings.validate(rotating);
    }
}
