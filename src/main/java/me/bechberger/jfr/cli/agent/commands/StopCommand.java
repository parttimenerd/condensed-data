package me.bechberger.jfr.cli.agent.commands;

import java.util.concurrent.Callable;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;

@Command(name = "stop", description = "Stop the recording", mixinStandardHelpOptions = true)
public class StopCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        synchronized (Agent.getSyncObject()) {
            if (Agent.getCurrentRecordingThread() == null) {
                AgentIO.getAgentInstance().println("No recording running");
                return 1;
            }
            Agent.getCurrentRecordingThread().stop();
            return 0;
        }
    }
}
