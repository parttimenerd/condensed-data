package me.bechberger.jfr.cli.agent;

import static me.bechberger.jfr.cli.CLIUtils.removeVersionOptionFromSubCommands;
import static me.bechberger.jfr.cli.CLIUtils.splitArgs;

import me.bechberger.jfr.cli.agent.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

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
        })
/*
 * TODO: Test and test that everything from my side can crash without impacting the rest of the
 * system Maybe record an error state
 */
public class Agent implements Runnable {

    private static final Object syncObject = new Object();
    private static RecordingThread currentRecordingThread;
    private static String agentArgs;

    @Spec CommandSpec spec;

    @Command(name = "help", description = "Print help information")
    public void help() {
        spec.commandLine().usage(AgentIO.getAgentInstance().createPrintStream());
    }

    @Override
    public void run() {
        help();
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
                        var cli = new CommandLine(new Agent());
                        cli.setExecutionExceptionHandler(
                                (ex, commandLine, parseResult) -> {
                                    if (ex.getClass().getName().equals(DynamicallyChangeableSettings.ValidationException.class.getName())) {
                                        AgentIO.getAgentInstance().writeSevereError(ex.getMessage());
                                        return 1;
                                    }
                                    AgentIO.getAgentInstance()
                                            .writeSevereError(ex.getMessage());
                                    ex.printStackTrace(AgentIO.getAgentInstance().createPrintStream());
                                    return 1;
                                })
                                .setParameterExceptionHandler((ex, commandLine) -> {
                                    AgentIO.getAgentInstance().writeSevereError(ex.getMessage());
                                    cli.getCommandSpec().commandLine().usage(AgentIO.getAgentInstance().createPrintStream());
                                    return 1;
                                });
                        removeVersionOptionFromSubCommands(cli);
                        try {
                            cli.execute(preprocResult.args);
                        } catch (RuntimeException e) {
                            AgentIO.getAgentInstance()
                                    .writeSevereError("Could not execute command: " + e.getMessage());
                            e.printStackTrace(AgentIO.getAgentInstance().createPrintStream());
                        }
                    } catch (Exception e) {
                        AgentIO.getAgentInstance()
                                .writeSevereError("Could not start agent: " + e.getMessage());
                        e.printStackTrace(AgentIO.getAgentInstance().createPrintStream());
                    }
                });
    }

    record PreprocResult(String[] args, boolean logToFile) {}

    static PreprocResult preprocessArgs(String agentArgs) {
        // this works as long as we don't log anything while no agent command is running
        if (agentArgs != null) {
            var args = splitArgs(agentArgs);
            var cleaned =
                    args.stream().filter(s -> !s.equals("--logToFile")).toArray(String[]::new);
            return new PreprocResult(cleaned, args.contains("--logToFile"));
        }
        return new PreprocResult(new String[0], false);
    }

    public static String getAgentArgs() {
        return agentArgs;
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