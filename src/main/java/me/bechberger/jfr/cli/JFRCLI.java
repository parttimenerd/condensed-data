package me.bechberger.jfr.cli;

import me.bechberger.condensed.Util;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.JFRCLI.VersionProvider;
import me.bechberger.jfr.cli.commands.*;
import picocli.AutoComplete.GenerateCompletion;
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
            CondenseJFRCommand.class,
            InflateJFRCommand.class,
            BenchmarkCommand.class,
            AgentCommand.class,
            SummaryCommand.class,
            ViewCommand.class,
            GenerateCompletion.class
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

    @Command(name = "help", description = "Print help information", mixinStandardHelpOptions = true)
    public void help() {
        spec.commandLine().usage(System.out);
    }

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    public static CommandLine createCommandLine() {
        return new CommandLine(new JFRCLI());
    }

    public static int execute(String[] args) {
        return createCommandLine().execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
