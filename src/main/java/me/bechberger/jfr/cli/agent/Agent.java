package me.bechberger.jfr.cli.agent;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jfr.cli.agent.commands.*;

@Command(
        name = "-javaagent:condensed-agent.jar",
        description = "Agent for recording condensed JFR files",
        subcommands = {
            StartCommand.class,
            StopCommand.class,
            StatusCommand.class,
            SetMaxSizeCommand.class,
            SetMaxDurationCommand.class,
            SetMaxFilesCommand.class,
            SetDurationCommand.class
        },
        customSynopsis = "java -javaagent:condensed-agent.jar='[COMMAND]'")
/*
 * TODO: Test and test that everything from my side can crash without impacting the rest of the
 * system Maybe record an error state
 */
public class Agent implements Runnable {

    private static final Object syncObject = new Object();
    private static RecordingThread currentRecordingThread;
    private static String agentArgs;

    Spec spec;

    @Override
    public void run() {
        spec.usage(AgentIO.getAgentInstance().createPrintStream());
    }

    public static void agentmain(String agentArgs) {
        premain(agentArgs);
    }

    public static void premain(String agentArgs) {
        Agent.agentArgs = agentArgs;
        var preprocResult = preprocessArgs(agentArgs);
        AgentIO.withLogToFile(
                preprocResult.logToFile,
                () -> {
                    try {
                        var ps = AgentIO.getAgentInstance().createPrintStream();
                        FemtoCli.runAgent(new Agent(), ps, ps, preprocResult.args);
                    } catch (Exception e) {
                        AgentIO.getAgentInstance()
                                .writeSevereError("Could not start agent: " + e.getMessage());
                        e.printStackTrace(AgentIO.getAgentInstance().createPrintStream());
                    }
                });
    }

    record PreprocResult(String args, boolean logToFile) {}

    static PreprocResult preprocessArgs(String agentArgs) {
        // this works as long as we don't log anything while no agent command is running
        if (agentArgs != null && !agentArgs.isBlank()) {
            boolean logToFile = false;
            var cleaned = new java.util.ArrayList<String>();
            for (String token : agentArgs.split(",", -1)) {
                if (token.trim().equals("--logToFile")) {
                    logToFile = true;
                } else {
                    cleaned.add(token);
                }
            }
            return new PreprocResult(String.join(",", cleaned), logToFile);
        }
        return new PreprocResult("", false);
    }

    public static String getAgentArgs() {
        return agentArgs != null ? agentArgs : "";
    }

    public static Object getSyncObject() {
        return syncObject;
    }

    public static RecordingThread getCurrentRecordingThread() {
        return currentRecordingThread;
    }

    public static void setCurrentRecordingThread(RecordingThread currentRecordingThread) {
        Agent.currentRecordingThread = currentRecordingThread;
    }
}
