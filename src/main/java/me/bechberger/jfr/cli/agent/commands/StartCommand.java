package me.bechberger.jfr.cli.agent.commands;

import java.time.Duration;
import java.util.concurrent.Callable;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Mixin;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.agent.*;

@Command(name = "start", description = "Start the recording", mixinStandardHelpOptions = true)
public class StartCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "PATH",
            description = "Path to the recording file .cjfr file",
            defaultValue = "recording.cjfr")
    private String path;

    @Option(
            names = "--condenser-config",
            description = "The condenser generatorConfiguration to use",
            defaultValue = "reasonable-default",
            converter = ConfigurationConverter.class)
    private Configuration configuration;

    @Option(
            names = "--misc-jfr-config",
            description =
                    "Additional JFR config, '|' separated, like"
                            + " 'jfr.ExecutionSample#interval=1s'",
            defaultValue = "")
    private String miscJfrConfig = "";

    @Option(names = "--verbose", description = "Be verbose", defaultValue = "false")
    private boolean verbose;

    @Option(
            names = "--config",
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
            path = ensureRotatingPathHasPlaceholder(path);
        }
        try {
            dynSettings.validate(rotating);
        } catch (DynamicallyChangeableSettings.ValidationException e) {
            AgentIO.getAgentInstance().writeSevereError(e.getMessage());
            return 1;
        }
        if (rotating) {
            try {
                Agent.setCurrentRecordingThread(
                        new RotatingRecordingThread(
                                path,
                                configuration,
                                verbose,
                                jfrConfig,
                                miscJfrConfig,
                                () -> Agent.setCurrentRecordingThread(null),
                                dynSettings));
            } catch (Exception e) {
                Agent.setCurrentRecordingThread(null);
                AgentIO.getAgentInstance()
                        .writeSevereError("Could not start rotating recording: " + e.getMessage());
                return 1;
            }
        } else {
            try {
                Agent.setCurrentRecordingThread(
                        new SingleRecordingThread(
                                path,
                                configuration,
                                verbose,
                                jfrConfig,
                                miscJfrConfig,
                                () -> Agent.setCurrentRecordingThread(null),
                                dynSettings));
            } catch (Exception e) {
                Agent.setCurrentRecordingThread(null);
                AgentIO.getAgentInstance()
                        .writeSevereError("Could not start recording: " + e.getMessage());
                return 1;
            }
        }
        Thread t = new Thread(Agent.getCurrentRecordingThread());
        t.setDaemon(true);
        t.start();
        return 0;
    }

    static String ensureRotatingPathHasPlaceholder(String path) {
        if (RotatingRecordingThread.containsPlaceholder(path)) {
            return path;
        }
        if (path.endsWith(".cjfr")) {
            return path.replace(".cjfr", "_$index.cjfr");
        }
        return path + "_$index.cjfr";
    }
}
