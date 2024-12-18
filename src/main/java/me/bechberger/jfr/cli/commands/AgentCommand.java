package me.bechberger.jfr.cli.commands;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import me.bechberger.jfr.cli.agent.AgentIO;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "agent",
        description = "Use the included Java agent on a specific JVM process",
        mixinStandardHelpOptions = true)
public class AgentCommand implements Callable<Integer> {
    @Parameters(
            index = "0",
            paramLabel = "PID",
            description = "The PID of the JVM process",
            defaultValue = "-1")
    private int pid;

    @Parameters(
            index = "1",
            paramLabel = "OPTIONS",
            description = "Options for the agent or 'read' to read the output continuously")
    private String options;

    private static Path ownJAR() throws URISyntaxException {
        return Path.of(
                        new File(
                                        AgentCommand.class
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI())
                                .getPath())
                .toAbsolutePath();
    }

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
        if (options.equals("read")) {
            AgentIO agentIO = AgentIO.getAgentInstance();
            while (Files.exists(agentIO.getOutputFile())) {
                var out = agentIO.readOutput();
                if (out != null) {
                    System.out.print(out);
                }
            }
            return 0;
        }
        try {
            VirtualMachine jvm = VirtualMachine.attach(pid + "");
            jvm.loadAgent(ownJAR().toString(), addLogToFileOption(options));
            jvm.detach();
            AgentIO agentIO = AgentIO.getAgentInstance(pid);
            String out;
            while ((out = agentIO.readOutput()) != null) {
                Thread.sleep(50);
                System.out.print(out);
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

    private String addLogToFileOption(String options) {
        if (options.contains("logToFile")) {
            return options;
        }
        return options + (options.isEmpty() ? "" : ",") + "logToFile";
    }
}
