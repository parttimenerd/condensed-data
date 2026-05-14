package me.bechberger.jfr.cli.agent.commands;

import java.util.concurrent.Callable;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;

@Command(
        name = "set-max-files",
        description = "Set the max file count when rotating",
        mixinStandardHelpOptions = true)
public class SetMaxFilesCommand implements Callable<Integer> {

    @Parameters(description = "The maximum number of files to keep, when rotating files")
    private int maxFiles;

    @Override
    public Integer call() {
        if (Agent.getCurrentRecordingThread() == null) {
            AgentIO.getAgentInstance().println("No recording running");
            return 1;
        }
        Agent.getCurrentRecordingThread().setMaxFiles(maxFiles);
        return 0;
    }
}
