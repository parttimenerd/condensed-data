package me.bechberger.jfr.cli.agent.commands;

import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.time.Duration;

@Command(
        name = "set-duration",
        description = "Set the duration of the overall recording",
        mixinStandardHelpOptions = true)
public class SetDurationCommand implements Runnable {

    @Parameters(
            description = "The maximum duration of the recording (>= 1ms), 0 means unlimited",
            converter = DurationConverter.class)
    private Duration duration;

    @Override
    public void run() {
        if (Agent.getCurrentRecordingThread() == null) {
            AgentIO.getAgentInstance().println("No recording running");
            return;
        }
        Agent.getCurrentRecordingThread().setDuration(duration);
    }
}