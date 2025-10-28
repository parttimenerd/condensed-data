package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.CREATE;
import static me.bechberger.util.MemoryUtil.formatMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
    // triggered stop already
    private boolean triggeredStop = false;

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
        Files.createDirectories(Path.of(path).toAbsolutePath().getParent());
        var out =
                new CondensedOutputStream(
                        Files.newOutputStream(Path.of(path), CREATE),
                        new StartMessage(
                                Constants.FORMAT_VERSION,
                                "condensed jfr agent",
                                Constants.VERSION,
                                Agent.getAgentArgs(),
                                Compression.DEFAULT));
        this.jfrWriter = new BasicJFRWriter(out);
        agentIO.writeOutput("Condensed recording to " + path + " started\n");
    }

    @Override
    void onEvent(RecordedEvent event) {
        if (triggeredStop) {
            return;
        }
        if (shouldEndFile()) {
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
        try {
            jfrWriter.processEvent(event);
        } catch (RuntimeException e) {
            agentIO.writeSevereError(
                    e.getMessage()
                            + " while processing event "
                            + event
                            + " shou "
                            + shouldEndFile());
        }
    }

    @Override
    public void close() {
        System.out.println("close");
        agentIO.writeOutput("Condensed recording to " + path + " finished\n");
        jfrWriter.close();
    }

    @Override
    List<Entry<String, String>> getMiscStatus() {
        return List.of(
                Map.entry("mode", "single file"),
                Map.entry("current-size-on-drive", formatMemory(jfrWriter.estimateSize(), 3)),
                Map.entry(
                        "current-size-uncompressed",
                        formatMemory(jfrWriter.getUncompressedStatistic().getBytes(), 3)),
                Map.entry("path", Path.of(path).toAbsolutePath().toString()));
    }

    private boolean shouldEndFile() {
        boolean durationCheck = getDuration().toNanos() > 0;
        boolean isDurationExceeded =
                Duration.between(this.start, Instant.now()).compareTo(getDuration()) > 0;
        boolean maxSizeCheck = getMaxSize() > 0;
        boolean jfrWriterCheck = jfrWriter != null;
        boolean sizeComparison = jfrWriter != null && jfrWriter.estimateSize() > getMaxSize();
        return (durationCheck && isDurationExceeded)
                || (maxSizeCheck && jfrWriterCheck && sizeComparison);
    }
}
