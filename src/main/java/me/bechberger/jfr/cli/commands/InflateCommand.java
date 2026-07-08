package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.*;
import me.bechberger.jfr.JMCDependent;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter;

@Command(
        name = "inflate",
        description = "Inflate a condensed JFR file into JFR format",
        mixinStandardHelpOptions = true)
public class InflateCommand implements Callable<Integer> {

    /**
     * All positional args. The last arg is treated as the output .jfr file if it ends with ".jfr"
     * and there is more than one arg; otherwise all args are input files and the output path is
     * derived from the first input.
     */
    @Parameters(
            arity = "1..*",
            description =
                    "Input .cjfr files (folders or zips), optionally followed by the output .jfr"
                            + " file. If the last argument ends with .jfr it is used as output.")
    private List<String> args = new ArrayList<>();

    @Option(
            names = {"-f", "--force"},
            description = "Overwrite existing output file")
    private boolean force = false;

    @Mixin EventFilterOptionMixin eventFilterOptionMixin;

    Spec spec;

    List<Path> inputs() {
        var converter = new ExistingCJFRFileOrZipOrFolderConverter();
        List<String> inputArgs = inputArgs();
        var inputs = new ArrayList<Path>(inputArgs.size());
        for (String s : inputArgs) {
            inputs.add(converter.convert(s));
        }
        return inputs;
    }

    /** Returns the raw string args that are input files (all except the optional trailing .jfr). */
    private List<String> inputArgs() {
        if (args.size() > 1 && args.get(args.size() - 1).endsWith(".jfr")) {
            return args.subList(0, args.size() - 1);
        }
        return args;
    }

    Path getOutputFile() {
        if (args.size() > 1 && args.get(args.size() - 1).endsWith(".jfr")) {
            return new FileOptionConverters.JFRFileConverter().convert(args.get(args.size() - 1));
        }
        // Derive output from the first input
        if (args.size() > 1) {
            throw new IllegalArgumentException(
                    "Only one input file is allowed if no output file given");
        }
        var inputName = Path.of(args.get(0)).getFileName().toString();
        var inputPath = Path.of(args.get(0));
        if (inputName.endsWith(".cjfr")) {
            return inputPath.resolveSibling(inputName.replace(".cjfr", ".inflated.jfr"));
        }
        return inputPath.resolveSibling(inputName + ".inflated.jfr");
    }

    public Integer call() {
        return CLIUtils.callImpl(this, "inflate");
    }

    /** JMC-dependent implementation, loaded via reflection. */
    @JMCDependent
    public static class Impl {
        public static void run(InflateCommand cmd) throws Exception {
            CLIUtils.checkOutputFileWritable(cmd.getOutputFile(), cmd.force);
            var jfrReader =
                    me.bechberger.jfr.CombiningJFRReader.fromPaths(
                            cmd.inputs(),
                            cmd.eventFilterOptionMixin.createFilter(),
                            !cmd.eventFilterOptionMixin.noReconstitution());
            me.bechberger.jfr.WritingJFRReader.toJFRFile(jfrReader, cmd.getOutputFile());
            System.err.printf(
                    "Inflated to %s (%s)%n",
                    cmd.getOutputFile(),
                    CLIUtils.formatFileSize(java.nio.file.Files.size(cmd.getOutputFile())));
        }
    }
}
