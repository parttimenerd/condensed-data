package me.bechberger.jfr.cli.agent;

import static me.bechberger.util.TimeUtil.humanReadableFormat;

import com.palantir.humanreadabletypes.HumanReadableByteCount;
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
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.agent.AgentIO.LogLevel;
import org.jetbrains.annotations.Nullable;

public abstract class RecordingThread implements Runnable {

    private final Configuration configuration;
    private final String jfrConfig;
    private final String miscJfrConfig;
    private final Runnable removeFromParent;
    private final RecordingStream recordingStream;
    final AgentIO agentIO = AgentIO.getAgentInstance();
    private final DynamicallyChangeableSettings dynSettings;

    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    final Instant start = Instant.now();

    RecordingThread(
            Configuration configuration,
            boolean verbose,
            String jfrConfig,
            String miscJfrConfig,
            Runnable removeFromParent,
            DynamicallyChangeableSettings dynSettings)
            throws IOException, ParseException {
        this.agentIO.setLogLevel(verbose ? LogLevel.ALL : LogLevel.WARNING);
        this.configuration = configuration;
        this.jfrConfig = jfrConfig;
        this.dynSettings = dynSettings;
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
            recordingStream.startAsync();
            recordingStream.awaitTermination();
            agentIO.writeInfo("finished");
            if (!shouldStop.get()) {
                this.stop();
            }
        } catch (RuntimeException e) {
            agentIO.writeSevereError("Error: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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
        status.add(Map.entry("start", humanReadableFormat(start)));
        status.add(
                Map.entry("duration", humanReadableFormat(Duration.between(start, Instant.now()))));
        status.add(Map.entry("max-duration", humanReadableFormat(getMaxDuration())));
        status.add(Map.entry("max-size", HumanReadableByteCount.bytes(getMaxSize()).toString()));
        status.addAll(getMiscStatus());
        return status;
    }

    abstract List<Entry<String, String>> getMiscStatus();

    /** Max size of a CJFR file, might change dynamically during the agents' execution */
    long getMaxSize() {
        return dynSettings.maxSize;
    }

    /** Max duration for a CJFR file, might change dynamically during the agents' execution */
    Duration getMaxDuration() {
        return dynSettings.maxDuration;
    }

    /**
     * Max number of CJFR files stored on the device, might change dynamically during the agents'
     * execution and is only valid when rotating
     */
    int getMaxFiles() {
        return dynSettings.maxFiles;
    }

    abstract void close();

    boolean shouldEndFile(@Nullable BasicJFRWriter jfrWriter) {
        return (getMaxDuration().toNanos() > 0
                        && Duration.between(this.start, Instant.now()).compareTo(getMaxDuration())
                                > 0)
                || (getMaxSize() > 0
                        && jfrWriter != null
                        && jfrWriter.estimateSize() > getMaxSize());
    }

    public void setMaxSize(long maxSize) {
        dynSettings.maxSize = maxSize;
    }

    public void setMaxDuration(Duration maxDuration) {
        dynSettings.maxDuration = maxDuration;
    }

    public void setMaxFiles(int maxFiles) {
        dynSettings.maxFiles = maxFiles;
    }
}
