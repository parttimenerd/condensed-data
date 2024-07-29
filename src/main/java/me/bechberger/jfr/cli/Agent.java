package me.bechberger.jfr.cli;

import com.palantir.humanreadabletypes.HumanReadableByteCount;
import com.palantir.humanreadabletypes.HumanReadableDuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ByteSizeConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "cjfr agent",
        description = "Agent for recording condensed JFR files",
        subcommands = {
            Agent.StartCommand.class,
            Agent.StopCommand.class,
            Agent.StatusCommand.class
        },
        mixinStandardHelpOptions = true)
/**
 * TODO: Test and test that everything from my side can crash without impacting the rest of the
 * system Maybe record an error state
 */
public class Agent implements Runnable {

    abstract static class RecordingThread implements Runnable {

        private final Configuration configuration;
        private final String jfrConfig;
        private final String miscJfrConfig;
        private final Runnable removeFromParent;
        private final RecordingStream recordingStream;
        final AgentIO agentIO = new AgentIO("jfr-condenser-agent", ProcessHandle.current().pid());
        private final Logger logger = agentIO.getLogger("jfr-condenser-agent");

        private final AtomicBoolean shouldStop = new AtomicBoolean(false);
        final Instant start = Instant.now();

        RecordingThread(
                Configuration configuration,
                boolean verbose,
                String jfrConfig,
                String miscJfrConfig,
                Runnable removeFromParent)
                throws IOException, ParseException {
            this.agentIO.setLogLevel(verbose ? Level.ALL : Level.WARNING);
            this.configuration = configuration;
            this.jfrConfig = jfrConfig;
            var parsedJfrConfig =
                    jfrConfig.isEmpty()
                            ? jdk.jfr.Configuration.getConfiguration("default")
                            : jdk.jfr.Configuration.getConfiguration(jfrConfig);
            logger.info("Using config " + parsedJfrConfig.getName());
            this.miscJfrConfig = miscJfrConfig;
            try {
                var parsedMiscJfrConfig = parseJfrSettings(miscJfrConfig);
                parsedJfrConfig.getSettings().putAll(parsedMiscJfrConfig);
                this.recordingStream = new RecordingStream(parsedJfrConfig);
            } catch (Exception ex) {
                throw ex;
            }
            this.removeFromParent = removeFromParent;
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
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
                logger.info("start");
                recordingStream.startAsync();
                recordingStream.awaitTermination();
                logger.info("finished");
                if (!shouldStop.get()) {
                    this.stop();
                }
            } catch (RuntimeException e) {
                logger.severe("Error: " + e.getMessage());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            logger.info("finished run");
            shouldStop.set(false);
        }

        abstract void onEvent(RecordedEvent event);

        abstract void close();

        void stop() {
            shouldStop.set(true);
            removeFromParent.run();
            recordingStream.close();
            while (shouldStop.get()) { // wait till it properly stopped
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            logger.info("finished stop");
            agentIO.close();
        }

        List<Entry<String, String>> getStatus() {
            List<Entry<String, String>> status = new ArrayList<>();
            status.add(Map.entry("generatorConfiguration", configuration.name()));
            status.add(Map.entry("jfr-config", jfrConfig));
            status.add(Map.entry("misc-jfr-config", miscJfrConfig));
            status.add(Map.entry("start", start.toString()));
            status.add(
                    Map.entry(
                            "duration",
                            HumanReadableDuration.nanoseconds(
                                            Duration.between(start, Instant.now()).toNanos())
                                    .toString()));
            status.addAll(getMiscStatus());
            return status;
        }

        abstract List<Entry<String, String>> getMiscStatus();
    }

    /** Record to a single file */
    static class SingleRecordingThread extends RecordingThread {

        private final String path;
        private final Duration maxDuration;
        private final long maxSize;
        private final BasicJFRWriter jfrWriter;
        private final Logger logger;

        SingleRecordingThread(
                String path,
                Configuration configuration,
                boolean verbose,
                String jfrConfig,
                String miscJfrConfig,
                Duration maxDuration,
                long maxSize,
                Runnable onRecordingStopped)
                throws IOException, ParseException {
            super(configuration, verbose, jfrConfig, miscJfrConfig, onRecordingStopped);
            this.logger = agentIO.getLogger("jfr-condenser-agent");
            this.path = path;
            this.maxDuration = maxDuration;
            this.maxSize = maxSize;
            var out =
                    new CondensedOutputStream(
                            Files.newOutputStream(Path.of(path)),
                            new StartMessage(
                                    Constants.FORMAT_VERSION,
                                    "condensed jfr agent",
                                    Constants.VERSION,
                                    Agent.agentArgs,
                                    Compression.DEFAULT));
            this.jfrWriter = new BasicJFRWriter(out);
        }

        @Override
        void onEvent(RecordedEvent event) {
            if (maxDuration.toNanos() > 0
                    && Duration.between(this.start, Instant.now()).compareTo(maxDuration) > 0) {
                synchronized (syncObject) {
                    stop();
                }
                return;
            }
            if (maxSize > 0 && jfrWriter.estimateSize() > maxSize) {
                synchronized (syncObject) {
                    stop();
                }
                return;
            }
            jfrWriter.processEvent(event);
        }

        @Override
        void close() {
            jfrWriter.close();
        }

        @Override
        List<Entry<String, String>> getMiscStatus() {
            List<Entry<String, String>> status = new ArrayList<>();
            status.add(Map.entry("path", path));
            status.add(
                    Map.entry(
                            "max-duration",
                            HumanReadableDuration.nanoseconds(maxDuration.toNanos()).toString()));
            status.add(Map.entry("max-size", HumanReadableByteCount.bytes(maxSize).toString()));
            status.add(
                    Map.entry(
                            "current-size",
                            HumanReadableByteCount.bytes(jfrWriter.estimateSize()).toString()));
            return status;
        }
    }

    static class StartMixin {

        @Option(
                names = "condenser-config",
                description =
                        "The condenser generatorConfiguration to use, possible values:"
                                + " ${COMPLETION-CANDIDATES}",
                completionCandidates = ConfigurationIterable.class,
                defaultValue = "reasonable-default",
                converter = ConfigurationConverter.class)
        private Configuration configuration;

        @Option(
                names = "misc-jfr-config",
                description =
                        "Additional JFR config, '|' separated, like"
                                + " 'jfr.ExecutionSample#interval=1s'",
                defaultValue = "")
        private String miscJfrConfig = "";

        @Option(
                names = "max-duration",
                description = "The maximum duration of the recording",
                defaultValue = "0s",
                converter = DurationConverter.class)
        private Duration maxDuration;

        @Option(names = "verbose", description = "Be verbose", defaultValue = "false")
        private boolean verbose;
    }

    private static final Object syncObject = new Object();
    private static Agent.RecordingThread currentRecordingThread;

    @Command(name = "start", description = "Start the recording", mixinStandardHelpOptions = true)
    static class StartCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                paramLabel = "PATH",
                description = "Path to the recording file .cjfr file",
                defaultValue = "recording.cjfr")
        private String path;

        @Parameters(
                index = "1",
                paramLabel = "JFR_CONFIG",
                description = "The JFR generatorConfiguration to use.",
                defaultValue = "default")
        private String jfrConfig = "default";

        @Mixin private StartMixin startMixin;

        @Option(
                names = "max-size",
                description = "The maximum size of the recording file",
                defaultValue = "0B",
                converter = ByteSizeConverter.class)
        private long maxSize;

        @Override
        public Integer call() throws Exception {
            if (currentRecordingThread != null) {
                System.err.println("Recording already running, please stop it first");
                return 1;
            }
            currentRecordingThread =
                    new Agent.SingleRecordingThread(
                            path,
                            startMixin.configuration,
                            startMixin.verbose,
                            jfrConfig,
                            startMixin.miscJfrConfig,
                            startMixin.maxDuration,
                            maxSize,
                            () -> currentRecordingThread = null);
            new Thread(currentRecordingThread).start();
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop the recording", mixinStandardHelpOptions = true)
    static class StopCommand implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            synchronized (syncObject) {
                if (currentRecordingThread == null) {
                    System.err.println("No recording running");
                    return 1;
                }
                currentRecordingThread.stop();
                return 0;
            }
        }
    }

    @Command(
            name = "status",
            description = "Get the status of the recording",
            mixinStandardHelpOptions = true)
    static class StatusCommand implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            synchronized (syncObject) {
                if (currentRecordingThread == null) {
                    System.err.println("No recording running");
                } else {
                    System.out.println("Recording running");
                    for (var entry : currentRecordingThread.getStatus()) {
                        System.out.printf("%15s: %s%n", entry.getKey(), entry.getValue());
                    }
                }
            }
            return 0;
        }
    }

    @Spec CommandSpec spec;

    @Command(name = "help", description = "Print help information")
    public void help() {
        spec.commandLine().usage(System.out);
    }

    @Override
    public void run() {
        help();
    }

    public static void agentmain(String agentArgs) {
        premain(agentArgs);
    }

    private static String agentArgs;

    public static void premain(String agentArgs) {
        Agent.agentArgs = agentArgs;
        new CommandLine(new Agent())
                .execute(
                        Arrays.stream((agentArgs == null ? "" : agentArgs).split(","))
                                .flatMap(
                                        a -> {
                                            if (a.contains("=")) {
                                                var parts = a.split("=", 2);
                                                return Stream.of("--" + parts[0], parts[1]);
                                            }
                                            if (a.equals("help") || a.equals("verbose")) {
                                                return Stream.of("--" + a);
                                            }
                                            return Stream.of(a);
                                        })
                                .toArray(String[]::new));
    }
}
