package me.bechberger.jfr.cli.agent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ByteSizeConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "-javaagent:condensed-agent.jar",
        description = "Agent for recording condensed JFR files",
        subcommands = {
            Agent.StartCommand.class,
            Agent.StopCommand.class,
            Agent.StatusCommand.class,
            Agent.SetMaxSizeCommand.class,
            Agent.SetMaxDurationCommand.class,
            Agent.SetMaxFilesCommand.class
        },
        mixinStandardHelpOptions = true)
/**
 * TODO: Test and test that everything from my side can crash without impacting the rest of the
 * system Maybe record an error state
 */
public class Agent implements Runnable {

    private static final Object syncObject = new Object();
    private static RecordingThread currentRecordingThread;
    private static String agentArgs;

    @Command(name = "start", description = "Start the recording", mixinStandardHelpOptions = true)
    static class StartCommand implements Callable<Integer> {

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

        @Option(names = "--verbose", description = "Be verbose", defaultValue = "false")
        private boolean verbose;

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

        @Option(
                names = "rotating",
                description =
                        "Use rotating files and replace $date and $index in the file names, if no"
                            + " place holder is specified, replaces '.cjfr' with '_$index.cjfr'",
                defaultValue = "false")
        private boolean rotating;

        @Mixin private DynamicallyChangeableSettings dynSettings;

        @Override
        public Integer call() throws Exception {
            if (currentRecordingThread != null) {
                AgentIO.getAgentInstance()
                        .writeSevereError("Recording already running, please stop it first");
                return 1;
            }
            if (rotating) {
                if (dynSettings.maxFiles < 1) {
                    AgentIO.getAgentInstance().writeSevereError("max-files must be at least 1");
                    return 1;
                }
                if (!RotatingRecordingThread.containsPlaceholder(path)) {
                    path = path.replace(".cjfr", "_$index.cjfr");
                }
                currentRecordingThread =
                        new RotatingRecordingThread(
                                path,
                                configuration,
                                verbose,
                                jfrConfig,
                                miscJfrConfig,
                                () -> currentRecordingThread = null,
                                dynSettings);
            } else {
                currentRecordingThread =
                        new SingleRecordingThread(
                                path,
                                configuration,
                                verbose,
                                jfrConfig,
                                miscJfrConfig,
                                () -> currentRecordingThread = null,
                                dynSettings);
            }
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        synchronized (syncObject) {
                                            if (currentRecordingThread != null) {
                                                currentRecordingThread.stop();
                                            }
                                        }
                                    }));
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
                    AgentIO.getAgentInstance().writeSevereError("No recording running");
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
                    AgentIO.getAgentInstance().writeSevereError("No recording running");
                } else {
                    AgentIO.getAgentInstance().println("Recording running");
                    var status = currentRecordingThread.getStatus();
                    var maxNameLength =
                            status.stream().mapToInt(e -> e.getKey().length()).max().orElse(0);
                    for (var entry : currentRecordingThread.getStatus()) {
                        AgentIO.getAgentInstance()
                                .printf(
                                        "%" + maxNameLength + "s: %s%n",
                                        entry.getKey(),
                                        entry.getValue());
                    }
                }
            }
            return 0;
        }
    }

    @Command(
            name = "set-max-size",
            description = "Set the max file size",
            mixinStandardHelpOptions = true)
    static class SetMaxSizeCommand implements Runnable {

        @Parameters(
                description =
                        "The maximum size of the recording file (or the individual files when"
                                + " rotating files)",
                converter = ByteSizeConverter.class)
        private long maxSize;

        @Override
        public void run() {
            if (currentRecordingThread == null) {
                AgentIO.getAgentInstance().writeSevereError("No recording running");
                return;
            }
            currentRecordingThread.setMaxSize(maxSize);
        }
    }

    @Command(
            name = "set-max-duration",
            description = "Set the max duration of the recording",
            mixinStandardHelpOptions = true)
    static class SetMaxDurationCommand implements Runnable {

        @Parameters(
                description = "The maximum duration of the recording",
                converter = DurationConverter.class)
        private Duration maxDuration;

        @Override
        public void run() {
            if (currentRecordingThread == null) {
                AgentIO.getAgentInstance().writeSevereError("No recording running");
                return;
            }
            currentRecordingThread.setMaxDuration(maxDuration);
        }
    }

    @Command(
            name = "set-max-files",
            description = "Set the max file count when rotating",
            mixinStandardHelpOptions = true)
    static class SetMaxFilesCommand implements Runnable {

        @Parameters(description = "The maximum number of files to keep, when rotating files")
        private int maxFiles;

        @Override
        public void run() {
            if (currentRecordingThread == null) {
                AgentIO.getAgentInstance().writeSevereError("No recording running");
                return;
            }
            currentRecordingThread.setMaxFiles(maxFiles);
        }
    }

    @Spec CommandSpec spec;

    @Command(name = "help", description = "Print help information")
    public void help() {
        spec.commandLine().usage(AgentIO.getAgentInstance().createPrintStream());
    }

    @Override
    public void run() {
        help();
    }

    public static void agentmain(String agentArgs) {
        premain(agentArgs);
    }

    public static void premain(String agentArgs) {
        Agent.agentArgs = agentArgs;
        var preprocResult = preprocessArgs(agentArgs);
        AgentIO.withLogToFile(
                preprocResult.logToFile,
                () -> {
                    try {
                        new CommandLine(new Agent()).execute(preprocResult.args);
                    } catch (Exception e) {
                        AgentIO.getAgentInstance()
                                .writeSevereError("Could not start agent: " + e.getMessage());
                        e.printStackTrace(AgentIO.getAgentInstance().createPrintStream());
                    }
                });
    }

    record PreprocResult(String[] args, boolean logToFile) {}

    static PreprocResult preprocessArgs(String agentArgs) {
        AtomicBoolean logToFile = new AtomicBoolean(false);
        // this works as long as we don't log anything while no agent command is running
        var args =
                Arrays.stream((agentArgs == null ? "" : agentArgs).split(","))
                        .flatMap(
                                a -> {
                                    if (a.contains("=")) {
                                        var parts = a.split("=", 2);
                                        return Stream.of("--" + parts[0], parts[1]);
                                    }
                                    if (a.equals("verbose")) {
                                        return Stream.of("--" + a);
                                    }
                                    if (a.equals("logToFile")) {
                                        logToFile.set(true);
                                        return Stream.of();
                                    }
                                    return Stream.of(a);
                                })
                        .toArray(String[]::new);
        return new PreprocResult(args, logToFile.get());
    }

    static String getAgentArgs() {
        return agentArgs;
    }

    static Object getSyncObject() {
        return syncObject;
    }
}
