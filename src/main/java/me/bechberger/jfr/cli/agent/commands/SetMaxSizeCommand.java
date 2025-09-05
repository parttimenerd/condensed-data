package me.bechberger.jfr.cli.agent.commands;

import me.bechberger.jfr.cli.CLIUtils.ByteSizeConverter;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "set-max-size",
        description = "Set the max file size",
        mixinStandardHelpOptions = true)
public class SetMaxSizeCommand implements Runnable {

    @Parameters(
            description =
                    "The maximum size of the recording file (or the individual files when"
                            + " rotating files), >= 1kB, 0 means unlimited",
            converter = ByteSizeConverter.class)
    private long maxSize;

    @Override
    public void run() {
        if (Agent.getCurrentRecordingThread() == null) {
            AgentIO.getAgentInstance().println("No recording running");
            return;
        }
        Agent.getCurrentRecordingThread().setMaxSize(maxSize);
    }
}
