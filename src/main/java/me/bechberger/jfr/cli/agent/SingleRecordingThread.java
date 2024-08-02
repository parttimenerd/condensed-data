package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.CREATE;
import static me.bechberger.util.MemoryUtil.formatMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
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

    public SingleRecordingThread(
            String path,
            Configuration configuration,
            boolean verbose,
            String jfrConfig,
            String miscJfrConfig,
            Runnable onRecordingStopped,
            DynamicallyChangeableSettings dynSettings)
            throws IOException, ParseException {
        super(configuration, verbose, jfrConfig, miscJfrConfig, onRecordingStopped, dynSettings);
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
        agentIO.writeOutput("Condensed recording to " + path + " started");
    }

    @Override
    void onEvent(RecordedEvent event) {
        if (shouldEndFile(jfrWriter)) {
            synchronized (Agent.getSyncObject()) {
                close();
            }
            return;
        }
        try {
            jfrWriter.processEvent(event);
        } catch (RuntimeException e) {
            agentIO.writeSevereError(e.getMessage() + " while processing event " + event);
        }
    }

    @Override
    void close() {
        jfrWriter.close();
    }

    @Override
    List<Entry<String, String>> getMiscStatus() {
        return List.of(
                Map.entry("current-size-on-drive", formatMemory(jfrWriter.estimateSize(), 3)),
                Map.entry(
                        "current-size-uncompressed",
                        formatMemory(jfrWriter.getUncompressedStatistic().getBytes(), 3)),
                Map.entry("path", Path.of(path).toAbsolutePath().toString()));
    }
}
