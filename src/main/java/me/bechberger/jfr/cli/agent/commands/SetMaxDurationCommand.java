package me.bechberger.jfr.cli.agent.commands;

import java.time.Duration;
import java.util.concurrent.Callable;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import me.bechberger.jfr.cli.agent.DynamicallyChangeableSettings;

@Command(
        name = "set-max-duration",
        description = "Set the max duration of each individual recording when rotating files",
        mixinStandardHelpOptions = true)
public class SetMaxDurationCommand implements Callable<Integer> {

    @Parameters(
            description = "The maximum duration of the recording (>= 1ms), 0 means unlimited",
            converter = DurationConverter.class)
    private Duration maxDuration;

    @Override
    public Integer call() {
        synchronized (Agent.getSyncObject()) {
            if (Agent.getCurrentRecordingThread() == null) {
                AgentIO.getAgentInstance().println("No recording running");
                return 1;
            }
            try {
                Agent.getCurrentRecordingThread().setMaxDuration(maxDuration);
            } catch (DynamicallyChangeableSettings.ValidationException e) {
                AgentIO.getAgentInstance().writeSevereError(e.getMessage());
                return 1;
            }
            return 0;
        }
    }
}
