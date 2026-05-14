package me.bechberger.jfr.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.bechberger.condensed.Util;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.RunResult;
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

    /**
     * Check if args represent an "agent PID subcommand" pattern and dispatch through
     * AgentCommand.execute() if so. Returns null if args should be handled by the framework.
     */
    private static Integer tryAgentDispatch(String[] args) {
        if (args.length >= 2 && args[0].equals("agent")) {
            int result = AgentCommand.execute(List.of(args));
            if (result != -1) {
                return result;
            }
        }
        return null;
    }

    public static int execute(String[] args) {
        Integer agentResult = tryAgentDispatch(args);
        if (agentResult != null) {
            return agentResult;
        }
        return builder().run(new JFRCLI(), args);
    }

    /** Run with output capturing, including agent PID dispatch */
    public static RunResult runCapturedWithDispatch(String[] args) {
        if (args.length >= 2 && args[0].equals("agent")) {
            var baosOut = new ByteArrayOutputStream();
            var baosErr = new ByteArrayOutputStream();
            var oldOut = System.out;
            var oldErr = System.err;
            try {
                System.setOut(new PrintStream(baosOut, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(baosErr, true, StandardCharsets.UTF_8));
                int result = AgentCommand.execute(List.of(args));
                if (result != -1) {
                    return new RunResult(
                            baosOut.toString(StandardCharsets.UTF_8),
                            baosErr.toString(StandardCharsets.UTF_8),
                            result);
                }
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
        }
        return builder().runCaptured(new JFRCLI(), args);
    }

    public static FemtoCli.Builder builder() {
        var builder =
                FemtoCli.builder()
                        .commandConfig(
                                c -> {
                                    c.version = Util.getLibraryVersion();
                                    c.showUsageOnError = false;
                                });
        if (!CLIUtils.hasInflaterRelatedClasses()) {
            builder.removeCommands(InflateCommand.class, BenchmarkCommand.class);
        }
        return builder;
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
