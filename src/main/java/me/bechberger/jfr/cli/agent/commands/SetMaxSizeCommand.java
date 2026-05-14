package me.bechberger.jfr.cli.agent.commands;

import java.util.concurrent.Callable;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.cli.CLIUtils.ByteSizeConverter;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;

@Command(
        name = "set-max-size",
        description = "Set the max file size",
        mixinStandardHelpOptions = true)
public class SetMaxSizeCommand implements Callable<Integer> {

    @Parameters(
            description =
                    "The maximum size of the recording file (or the individual files when"
                            + " rotating files), >= 1kB, 0 means unlimited",
            converter = ByteSizeConverter.class)
    private long maxSize;

    @Override
    public Integer call() {
        if (Agent.getCurrentRecordingThread() == null) {
            AgentIO.getAgentInstance().println("No recording running");
            return 1;
        }
        Agent.getCurrentRecordingThread().setMaxSize(maxSize);
        return 0;
    }
}
