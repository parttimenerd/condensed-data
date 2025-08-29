package me.bechberger.jfr.cli.agent.commands;

import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "set-max-files",
        description = "Set the max file count when rotating",
        mixinStandardHelpOptions = true)
public class SetMaxFilesCommand implements Runnable {

    @Parameters(description = "The maximum number of files to keep, when rotating files")
    private int maxFiles;

    @Override
    public void run() {
        if (Agent.getCurrentRecordingThread() == null) {
            AgentIO.getAgentInstance().println("No recording running");
            return;
        }
        Agent.getCurrentRecordingThread().setMaxFiles(maxFiles);
    }
}
