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
        String[] tokens = parseAgentArgs(agentArgs);
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

    /**
     * Split a comma-separated agent args string into an argv array. Mirrors {@code
     * AgentArgs.toArgv} from femtocli (which is package-private and therefore not directly
     * callable).
     */
    private static String[] parseAgentArgs(String agentArgs) {
        var out = new java.util.ArrayList<String>();
        var cur = new StringBuilder();
        var protectedChars = new java.util.ArrayList<Boolean>();
        boolean escaping = false, inQuotes = false;
        for (int i = 0; i < agentArgs.length(); i++) {
            char c = agentArgs.charAt(i);
            if (escaping) {
                if (c == '\\' || c == ',' || c == '=') {
                    cur.append(c);
                    protectedChars.add(true);
                } else {
                    throw new IllegalArgumentException("Invalid escape: \\" + c);
                }
                escaping = false;
                continue;
            }
            if (c == '\\') { escaping = true; continue; }
            if (c == '\'') { inQuotes = !inQuotes; continue; }
            if (!inQuotes && c == ',') {
                addToken(out, cur, protectedChars);
                cur.setLength(0);
                protectedChars.clear();
                continue;
            }
            cur.append(c);
            protectedChars.add(inQuotes);
        }
        if (escaping) throw new IllegalArgumentException("Dangling escape in agent args");
        if (inQuotes) throw new IllegalArgumentException("Unterminated quote in agent args");
        addToken(out, cur, protectedChars);
        return out.toArray(String[]::new);
    }

    private static void addToken(
            java.util.List<String> out, StringBuilder cur, java.util.List<Boolean> prot) {
        String raw = cur.toString();
        int s = 0, e = raw.length();
        while (s < e && Character.isWhitespace(raw.charAt(s)) && !prot.get(s)) s++;
        while (e > s && Character.isWhitespace(raw.charAt(e - 1)) && !prot.get(e - 1)) e--;
        String token = raw.substring(s, e);
        if (token.isEmpty()) throw new IllegalArgumentException("Empty token in agent args");
        out.add(token);
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
