package me.bechberger.jfr.cli.commands;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jfr.cli.agent.AgentIO;
import me.bechberger.jfr.cli.agent.commands.*;
import me.bechberger.jfr.cli.commands.AgentCommand.ReadCommand;

@Command(
        name = "agent",
        description = "Use the included Java agent on a specific JVM process",
        mixinStandardHelpOptions = true,
        subcommands = {
            SetMaxDurationCommand.class,
            SetMaxFilesCommand.class,
            SetMaxSizeCommand.class,
            StartCommand.class,
            StatusCommand.class,
            StopCommand.class,
            ReadCommand.class,
            SetDurationCommand.class
        })
public class AgentCommand implements Callable<Integer> {

    @Command(
            name = "read",
            description = "Read the output of the agent",
            mixinStandardHelpOptions = true)
    public static class ReadCommand implements Callable<Integer> {

        private static final int IDLE_POLL_SLEEP_MILLIS = 50;
        private static final int MAX_IDLE_POLLS = 20;

        @Parameters(
                index = "0",
                paramLabel = "OUTPUT",
                description = "Optional output file path (default: stdout)",
                defaultValue = "")
        private String outputFile = "";

        @Override
        public Integer call() {
            AgentIO agentIO = AgentIO.getAgentInstance();
            return run(agentIO, outputFile);
        }

        static int runForPid(long pid, String outputFile) {
            return run(AgentIO.getAgentInstance(pid), outputFile);
        }

        private static int run(AgentIO agentIO, String outputFile) {
            try {
                java.io.PrintStream out =
                        outputFile.isEmpty()
                                ? System.out
                                : new java.io.PrintStream(
                                        Files.newOutputStream(Path.of(outputFile)));
                try {
                    int idlePolls = 0;
                    while (Files.exists(agentIO.getOutputFile())) {
                        var content = agentIO.readOutput();
                        if (content != null) {
                            out.print(content);
                            idlePolls = 0;
                        } else {
                            idlePolls++;
                            if (idlePolls >= MAX_IDLE_POLLS) {
                                break;
                            }
                            Thread.sleep(IDLE_POLL_SLEEP_MILLIS);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 1;
                } finally {
                    if (out != System.out) {
                        out.close();
                    }
                }
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    @Parameters(
            index = "0",
            paramLabel = "TARGET",
            description =
                    "The target JVM: 'all' (every discovered JVM), a PID, or a name filter"
                            + " (case-insensitive substring match on main class)",
            defaultValue = "")
    private String target = "";

    /** Collect subcommand names from the @Command annotation on this class */
    private static Set<String> subcommandNames() {
        Set<String> names = new java.util.HashSet<>();
        Command cmd = AgentCommand.class.getAnnotation(Command.class);
        if (cmd != null) {
            for (Class<?> sub : cmd.subcommands()) {
                Command subCmd = sub.getAnnotation(Command.class);
                if (subCmd != null) {
                    names.add(subCmd.name());
                }
            }
        }
        // Also include standard help/version flags
        names.addAll(Set.of("-h", "--help", "-V", "--version"));
        names.add("help");
        return names;
    }

    /** Represents a discovered JVM process */
    public record JVMProcess(long pid, String displayName) {
        @Override
        public String toString() {
            return String.format("%6d  %s", pid, displayName);
        }
    }

    /** List all running JVMs (excluding those with empty display names) */
    private static List<JVMProcess> discoverJVMs() {
        long currentPid = ProcessHandle.current().pid();
        List<JVMProcess> jvms = new ArrayList<>();
        for (VirtualMachineDescriptor vm : VirtualMachine.list()) {
            if (vm.displayName().isEmpty()) {
                continue;
            }
            try {
                long pid = Long.parseLong(vm.id());
                if (pid == currentPid) continue;
                jvms.add(new JVMProcess(pid, vm.displayName()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return jvms;
    }

    /** Filter JVMs by case-insensitive substring match on display name */
    private static List<JVMProcess> filterJVMs(String filter) {
        String lowerFilter = filter.toLowerCase();
        List<JVMProcess> result = new ArrayList<>();
        for (JVMProcess jvm : discoverJVMs()) {
            if (jvm.displayName().toLowerCase().contains(lowerFilter)) {
                result.add(jvm);
            }
        }
        return result;
    }

    private static void listVMs() {
        listVMs(null);
    }

    private static void listVMs(String context) {
        if (context != null) {
            System.out.println(context);
        } else {
            System.out.println("Specify a target JVM by PID or name filter.");
        }
        System.out.println("Currently running JVMs:");
        List<JVMProcess> jvms = discoverJVMs();
        if (jvms.isEmpty()) {
            System.out.println("  (none found)");
        } else {
            for (JVMProcess jvm : jvms) {
                System.out.println("  " + jvm);
            }
        }
        System.out.println(
                "This might include JVMs lower than version 17 which are not supported.");
    }

    /**
     * Resolve a target string to a single PID. Returns the PID, or -1 on error (error already
     * printed).
     */
    static long resolveTarget(String target) {
        if (target == null || target.isBlank()) {
            listVMs();
            return -1;
        }

        // Try numeric PID first
        if (target.matches("-?\\d+")) {
            long pid;
            try {
                pid = Long.parseLong(target);
            } catch (NumberFormatException e) {
                System.err.println("Error: Invalid PID: " + target);
                return -1;
            }
            if (pid <= 0) {
                System.err.println("Error: PID must be a positive integer, got: " + target);
                return -1;
            }
            // Validate the process exists
            if (!ProcessHandle.of(pid).isPresent()) {
                System.err.println("Error: No process found with PID " + pid);
                return -1;
            }
            return pid;
        }

        // Name filter: case-insensitive substring match
        List<JVMProcess> matches = filterJVMs(target);
        if (matches.isEmpty()) {
            listVMs("No JVM found matching '" + target + "'.");
            return -1;
        }
        if (matches.size() > 1) {
            System.out.println(
                    "Multiple JVMs match '" + target + "'. Please be more specific or use a PID:");
            for (JVMProcess jvm : matches) {
                System.out.println("  " + jvm);
            }
            return -1;
        }
        JVMProcess match = matches.get(0);
        System.out.printf("Attaching to %d (%s)%n", match.pid(), match.displayName());
        return match.pid();
    }

    public Integer call() {
        if (target.isEmpty()) {
            listVMs();
            return 0;
        }
        if (target.equalsIgnoreCase("all")) {
            return executeForAll(List.of("status"));
        }
        long resolvedPid = resolveTarget(target);
        if (resolvedPid == -1) {
            return 1;
        }
        // PID given without subcommand — show status
        return handleSubCommand((int) resolvedPid, List.of("status"));
    }

    /** Execute a subcommand against all discovered JVMs. Returns 0 only if all succeed. */
    private static int executeForAll(List<String> agentArgs) {
        List<JVMProcess> jvms = discoverJVMs();
        if (jvms.isEmpty()) {
            System.out.println("No running JVMs found.");
            return 1;
        }
        int exitCode = 0;
        for (JVMProcess jvm : jvms) {
            System.out.printf("--- %d (%s) ---%n", jvm.pid(), jvm.displayName());
            int result = handleSubCommand((int) jvm.pid(), agentArgs);
            if (result != 0) {
                exitCode = result;
            }
        }
        return exitCode;
    }

    private static String addLogToFileOption(String options) {
        if (options.contains("--logToFile")) {
            return options;
        }
        return options + (options.isEmpty() ? "" : ",") + "--logToFile";
    }

    public static int execute(List<String> args) {
        var subCommandArgs = args.subList(1, args.size());
        if (subCommandArgs.isEmpty()) {
            listVMs();
            return 0;
        }
        String firstArg = subCommandArgs.get(0);
        // Help/version aliases should still be handled by the CLI framework.
        if (Set.of("help", "-h", "--help", "-V", "--version").contains(firstArg)) {
            return -1;
        }
        // For all other known subcommands, TARGET is required first.
        if (subcommandNames().contains(firstArg)) {
            System.err.println("Error: Missing TARGET before subcommand '" + firstArg + "'.");
            System.err.println("Usage: cjfr agent TARGET [COMMAND]");
            return 1;
        }
        // Handle 'all' target
        if (firstArg.equalsIgnoreCase("all")) {
            List<String> agentArgs =
                    subCommandArgs.size() > 1
                            ? subCommandArgs.subList(1, subCommandArgs.size())
                            : List.of("status");
            return executeForAll(agentArgs);
        }
        // Resolve the target (PID or name filter)
        long pid = resolveTarget(firstArg);
        if (pid == -1) {
            return 1;
        }
        if (subCommandArgs.size() == 1) {
            // Just a target, no subcommand — show status
            return handleSubCommand((int) pid, List.of("status"));
        }
        List<String> agentArgs = subCommandArgs.subList(1, subCommandArgs.size());
        // 'read' is a CLI-side command that reads from the agent IPC files; it must NOT be
        // forwarded to the agent because the agent doesn't know about it.
        if (agentArgs.get(0).equals("read")) {
            String outputFile = agentArgs.size() > 1 ? agentArgs.get(1) : "";
            return ReadCommand.runForPid(pid, outputFile);
        }
        return handleSubCommand((int) pid, agentArgs);
    }

    public static Path ownJAR() throws URISyntaxException {
        var path =
                Path.of(
                                new File(
                                                AgentCommand.class
                                                        .getProtectionDomain()
                                                        .getCodeSource()
                                                        .getLocation()
                                                        .toURI())
                                        .getPath())
                        .toAbsolutePath();
        if (path.endsWith(".jar")) {
            return path;
        }
        // we are running in the IDE, so return the condensed-data.jar from the folder
        return path.getParent().resolve("condensed-data.jar");
    }

    private static final int IDLE_POLL_SLEEP_MILLIS = 50;
    private static final int MAX_IDLE_POLLS = 20;

    private static int handleSubCommand(int pid, List<String> agentArgs) {
        AgentIO agentIO = AgentIO.getAgentInstance(pid);
        try {
            VirtualMachine jvm = VirtualMachine.attach(pid + "");
            jvm.loadAgent(ownJAR().toString(), addLogToFileOption(String.join(",", agentArgs)));
            jvm.detach();
            int idlePolls = 0;
            while (true) {
                String out = agentIO.readOutput();
                if (out != null) {
                    System.out.print(
                            out.replace(
                                    "Usage: -javaagent:condensed-agent.jar [COMMAND]",
                                    "Usage: cjfr agent " + pid + " [COMMAND]"));
                    idlePolls = 0;
                } else {
                    idlePolls++;
                    if (idlePolls >= MAX_IDLE_POLLS) {
                        break;
                    }
                    try {
                        Thread.sleep(IDLE_POLL_SLEEP_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (URISyntaxException ex) {
            System.err.println("Can't find the current JAR file");
            return 1;
        } catch (AgentLoadException | IOException | AgentInitializationException e) {
            System.err.println("Can't load the agent: " + e.getMessage());
            return 1;
        } catch (AttachNotSupportedException e) {
            System.err.println("Can't attach to the JVM process");
            return 1;
        }
        int exitCode = agentIO.readExitCode();
        if (exitCode < 0) {
            // Exit-code file not yet written — retry briefly to handle slow filesystems
            for (int i = 0; i < 5 && exitCode < 0; i++) {
                try {
                    Thread.sleep(IDLE_POLL_SLEEP_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                exitCode = agentIO.readExitCode();
            }
        }
        return exitCode < 0 ? 1 : exitCode;
    }
}
