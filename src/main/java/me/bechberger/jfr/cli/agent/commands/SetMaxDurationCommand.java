package me.bechberger.jfr.cli.agent.commands;

import java.time.Duration;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "set-max-duration",
        description = "Set the max duration of the recording",
        mixinStandardHelpOptions = true)
public class SetMaxDurationCommand implements Runnable {

    @Parameters(
            description = "The maximum duration of the recording",
            converter = DurationConverter.class)
    private Duration maxDuration;

    @Override
    public void run() {
        if (Agent.getCurrentRecordingThread() == null) {
            AgentIO.getAgentInstance().println("No recording running");
            return;
        }
        Agent.getCurrentRecordingThread().setMaxDuration(maxDuration);
    }
}