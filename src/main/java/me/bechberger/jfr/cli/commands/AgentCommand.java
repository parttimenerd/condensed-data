package me.bechberger.jfr.cli.commands;

import static me.bechberger.jfr.cli.CLIUtils.combineArgs;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.bechberger.jfr.cli.agent.AgentIO;
import me.bechberger.jfr.cli.agent.commands.*;
import me.bechberger.jfr.cli.commands.AgentCommand.ReadCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;

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
            HelpCommand.class,
            SetDurationCommand.class
        })
public class AgentCommand implements Callable<Integer> {

    @Command(
            name = "read",
            description = "Read the output of the agent",
            mixinStandardHelpOptions = true)
    public static class ReadCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            AgentIO agentIO = AgentIO.getAgentInstance();
            while (Files.exists(agentIO.getOutputFile())) {
                var out = agentIO.readOutput();
                if (out != null) {
                    System.out.print(out);
                }
            }
            return 0;
        }
    }

    @Parameters(
            index = "0",
            paramLabel = "PID",
            description = "The PID of the JVM process",
            defaultValue = "-1")
    private int pid = -1;

    private static void listVMs() {
        System.out.println("You have to parse the process id of a JVM");
        System.out.println("Possible JVMs that are currently running are: ");
        for (var vm : VirtualMachine.list()) {
            if (vm.displayName().isEmpty()) {
                continue;
            }
            System.out.printf("%6s  %s%n", vm.id(), vm.displayName());
        }
        System.out.println(
                "This might include JVMs lower than version 17 which are not supported.");
    }

    public Integer call() {
        if (pid == -1) {
            listVMs();
            return -1;
        }
        return 0;
    }

    private static String addLogToFileOption(String options) {
        if (options.contains("--logToFile")) {
            return options;
        }
        return options + (options.isEmpty() ? "" : " ") + "--logToFile";
    }

    public static int execute(List<String> args, CommandLine subCommandCommandLine) {
        var subCommandArgs = args.subList(1, args.size());
        // we have to prevent sub commands from being executed
        ParseResult parseResult;
        try {
            parseResult =
                    new CommandLine(new AgentCommand())
                            .parseArgs(subCommandArgs.toArray(new String[0]));
        } catch (CommandLine.UnmatchedArgumentException ex) {
            // replace "at index %d+:" with "at index " + (index +1)
            Matcher m = Pattern.compile("(\\d+):").matcher(ex.getMessage());
            if (!m.find()) {
                throw ex;
            }
            int index = Integer.parseInt(m.toMatchResult().group(0).replace(":", ""));
            System.err.println(m.replaceFirst((index + 1) + ":"));
            return 2;
        }
        if (parseResult.hasSubcommand()) {
            return handleSubCommand(parseResult, subCommandArgs);
        }
        if (subCommandArgs.isEmpty()) {
            listVMs();
            return 0;
        }
        if (subCommandArgs.contains("--help")) {
            subCommandCommandLine.usage(System.out);
            return 0;
        }
        subCommandCommandLine.usage(System.err);
        return 2;
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

    private static int handleSubCommand(ParseResult parseResult, List<String> subCommandArgs) {
        int pid = Integer.parseInt(subCommandArgs.get(0));
        List<String> agentArgs = subCommandArgs.subList(1, subCommandArgs.size());
        try {
            VirtualMachine jvm = VirtualMachine.attach(pid + "");
            jvm.loadAgent(ownJAR().toString(), addLogToFileOption(combineArgs(agentArgs)));
            jvm.detach();
            AgentIO agentIO = AgentIO.getAgentInstance(pid);
            String out;
            while ((out = agentIO.readOutput()) != null) {
                Thread.sleep(50);
                System.out.println(
                        out.replace(
                                "Usage: -javaagent:condensed-agent.jar [COMMAND]",
                                "Usage: cjfr agent " + pid + " [COMMAND]"));
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
        } catch (InterruptedException e) {
            return 1;
        }
        return 0;
    }
}