package me.bechberger.jfr.cli.agent;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jfr.cli.agent.commands.*;

@Command(
        name = "agent",
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
public class Agent implements Runnable {

    private static final Object syncObject = new Object();
    private static volatile RecordingThread currentRecordingThread;
    private static String agentArgs;

    Spec spec;

    @Override
    public void run() {
        spec.usage();
    }

    public static void agentmain(String agentArgs, java.lang.instrument.Instrumentation inst) {
        me.bechberger.jfr.UnsafeRecordedObjectAccessor.openModule(inst);
        if ("open-jfr-module".equals(agentArgs)) return; // self-attach: just open module
        premain(agentArgs);
    }

    public static void agentmain(String agentArgs) {
        premain(agentArgs);
    }

    public static void premain(String agentArgs, java.lang.instrument.Instrumentation inst) {
        me.bechberger.jfr.UnsafeRecordedObjectAccessor.openModule(inst);
        premain(agentArgs);
    }

    public static void premain(String agentArgs) {
        Agent.agentArgs = agentArgs;
        var preprocResult = preprocessArgs(agentArgs);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    RecordingThread t;
                                    synchronized (syncObject) {
                                        t = currentRecordingThread;
                                    }
                                    if (t != null) {
                                        t.stop();
                                    }
                                },
                                "cjfr-agent-shutdown"));
        AgentIO.withLogToFile(
                preprocResult.logToFile,
                () -> {
                    try {
                        var ps = AgentIO.getAgentInstance().createPrintStream();
                        int exitCode =
                                FemtoCli.builder()
                                        .alertOnMixedStyleInAgent(true)
                                        .runAgent(new Agent(), ps, ps, preprocResult.argv);
                        AgentIO.getAgentInstance().writeExitCode(exitCode);
                    } catch (Throwable e) {
                        try {
                            AgentIO.getAgentInstance()
                                    .writeSevereError("Could not start agent: " + e.getMessage());
                            e.printStackTrace(AgentIO.getAgentInstance().createPrintStream());
                            AgentIO.getAgentInstance().writeExitCode(1);
                        } catch (Throwable ignored) {
                            // last resort — don't let agent errors crash the host JVM
                        }
                    }
                });
    }

    record PreprocResult(String[] argv, boolean logToFile) {}

    static PreprocResult preprocessArgs(String agentArgs) {
        if (agentArgs == null || agentArgs.isBlank()) {
            return new PreprocResult(new String[0], false);
        }
        String[] tokens = FemtoCli.toArgv(agentArgs);
        boolean logToFile = false;
        var filtered = new java.util.ArrayList<String>(tokens.length);
        for (String token : tokens) {
            if (token.equals("--logToFile")) {
                logToFile = true;
            } else {
                filtered.add(token);
            }
        }
        return new PreprocResult(filtered.toArray(String[]::new), logToFile);
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
        synchronized (syncObject) {
            Agent.currentRecordingThread = currentRecordingThread;
        }
    }
}
