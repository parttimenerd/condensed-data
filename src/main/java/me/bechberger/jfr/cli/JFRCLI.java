package me.bechberger.jfr.cli;

import static me.bechberger.jfr.cli.CLIUtils.removeVersionOptionFromSubCommands;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.bechberger.condensed.Util;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.JFRCLI.VersionProvider;
import me.bechberger.jfr.cli.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/** A CLI for writing JFR (and more) based on {@link BasicJFRWriter} */
@Command(
        name = "cjfr",
        description = "CLI for the JFR condenser project",
        subcommands = {
            CondenseCommand.class,
            InflateCommand.class,
            BenchmarkCommand.class,
            AgentCommand.class,
            SummaryCommand.class,
            ViewCommand.class,
            GenerateCompletionCommand.class
        },
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class)
public class JFRCLI implements Runnable {

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {Util.getLibraryVersion()};
        }
    }

    @Spec CommandSpec spec;

    @Command(name = "help", description = "Print help information")
    public void help() {
        spec.commandLine().usage(System.out);
    }

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    public static CommandLine createCommandLine() {
        var cli = new CommandLine(new JFRCLI());
        removeVersionOptionFromSubCommands(cli);
        return cli;
    }

    public static int execute(String[] args) {
        var cli = createCommandLine();
        if (args.length >= 1 && args[0].equals("agent")) {
            return AgentCommand.execute(List.of(args), cli.getSubcommands().get("agent"));
        }
        return cli.execute(args);
    }

    public static Map<String, CommandLine> getCommands() {
        return createCommandLine().getSubcommands();
    }

    public static Map<String, CommandLine> getVisibleCommands() {
        return createCommandLine().getSubcommands().entrySet().stream()
                .filter(e -> !e.getValue().getCommandSpec().usageMessage().hidden())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
