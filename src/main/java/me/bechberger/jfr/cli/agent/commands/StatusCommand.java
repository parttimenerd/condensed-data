package me.bechberger.jfr.cli.agent.commands;

import java.util.concurrent.Callable;
import me.bechberger.jfr.cli.agent.Agent;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;

@Command(
        name = "status",
        description = "Get the status of the recording",
        mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        synchronized (Agent.getSyncObject()) {
            if (Agent.getCurrentRecordingThread() == null) {
                AgentIO.getAgentInstance().println("No recording running");
            } else {
                AgentIO.getAgentInstance().println("Recording running");
                var status = Agent.getCurrentRecordingThread().getStatus();
                var maxNameLength =
                        status.stream().mapToInt(e -> e.getKey().length()).max().orElse(0);
                for (var entry : Agent.getCurrentRecordingThread().getStatus()) {
                    AgentIO.getAgentInstance()
                            .printf(
                                    "%" + maxNameLength + "s: %s%n",
                                    entry.getKey(),
                                    entry.getValue());
                }
            }
        }
        return 0;
    }
}