package me.bechberger.jfr.cli.agent.commands;

import java.time.Duration;
import java.util.concurrent.Callable;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import me.bechberger.jfr.cli.agent.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "start", description = "Start the recording", helpCommand = true)
public class StartCommand implements Callable<Integer> {

    @Option(
            names = {"-d", "--condenser-config"},
            description =
                    "The condenser generatorConfiguration to use, possible values:"
                            + " ${COMPLETION-CANDIDATES}",
            completionCandidates = ConfigurationIterable.class,
            defaultValue = "reasonable-default",
            converter = ConfigurationConverter.class)
    private Configuration configuration;

    @Option(
            names = {"-m", "--misc-jfr-config"},
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

    @Option(
            names = {"-c", "--config"},
            description = "The JFR generatorConfiguration to use.",
            defaultValue = "default")
    private String jfrConfig = "default";

    @Option(
            names = "--rotating",
            description =
                    "Use rotating files and replace $date and $index in the file names, if no"
                            + " place holder is specified, replaces '.cjfr' with '_$index.cjfr'",
            defaultValue = "false")
    private boolean rotating;

    @Mixin private DynamicallyChangeableSettings dynSettings;

    @Override
    public Integer call() throws Exception {
        if (Agent.getCurrentRecordingThread() != null) {
            AgentIO.getAgentInstance()
                    .writeSevereError("Recording already running, please stop it first");
            return 1;
        }
        if (rotating) {
            if (dynSettings.maxFiles < 1) {
                AgentIO.getAgentInstance().writeSevereError("max-files must be at least 1");
                return 1;
            }
            if (dynSettings.maxSize == 0 && dynSettings.maxDuration == Duration.ZERO) {
                AgentIO.getAgentInstance()
                        .writeSevereError("max-size or max-duration required when rotating files");
                return 1;
            }
            if (!RotatingRecordingThread.containsPlaceholder(path)) {
                path = path.replace(".cjfr", "_$index.cjfr");
            }
            Agent.setCurrentRecordingThread(
                    new RotatingRecordingThread(
                            path,
                            configuration,
                            verbose,
                            jfrConfig,
                            miscJfrConfig,
                            () -> Agent.setCurrentRecordingThread(null),
                            dynSettings));
        } else {
            Agent.setCurrentRecordingThread(
                    new SingleRecordingThread(
                            path,
                            configuration,
                            verbose,
                            jfrConfig,
                            miscJfrConfig,
                            () -> Agent.setCurrentRecordingThread(null),
                            dynSettings));
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    synchronized (Agent.getSyncObject()) {
                                        if (Agent.getCurrentRecordingThread() != null) {
                                            Agent.getCurrentRecordingThread().stop();
                                        }
                                    }
                                }));
        new Thread(Agent.getCurrentRecordingThread()).start();
        return 0;
    }
}
