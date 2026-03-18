package me.bechberger.jfr.cli;

import java.util.List;
import me.bechberger.condensed.Util;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.commands.*;

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
        },
        mixinStandardHelpOptions = true)
public class JFRCLI implements Runnable {

    Spec spec;

    @Override
    public void run() {
        spec.usage();
    }

    /** Subcommand names for tests */
    public static List<String> subCommandNames() {
        return List.of("condense", "inflate", "benchmark", "agent", "summary", "view");
    }

    public static int execute(String[] args) {
        return builder().run(new JFRCLI(), args);
    }

    public static FemtoCli.Builder builder() {
        var builder = FemtoCli.builder().commandConfig(c -> c.version = Util.getLibraryVersion());
        if (!CLIUtils.hasInflaterRelatedClasses()) {
            builder.removeCommands(InflateCommand.class, BenchmarkCommand.class);
        }
        return builder;
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}