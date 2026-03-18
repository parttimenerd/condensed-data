package me.bechberger.jfr.cli.agent.commands;

import java.util.concurrent.Callable;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;

@Command(name = "stop", description = "Stop the recording", mixinStandardHelpOptions = true)
public class StopCommand implements Callable<Integer> {

    @Override
    public Integer call() {
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
