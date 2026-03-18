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

    @Parameters(
            description = "The input .cjfr file, can be a folder, or a zip",
            converter = ExistingCJFRFileOrZipOrFolderConverter.class)
    private Path inputFile;

    @Parameters(
            index = "1",
            description = "The output JFR file",
            defaultValue = "",
            converter = FileOptionConverters.JFRFileConverter.class)
    private Path outputFile = null;

    @Option(
            names = {"-i", "--inputs"},
            description = "Additional input files",
            converter = ExistingCJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Mixin EventFilterOptionMixin eventFilterOptionMixin;

    Spec spec;

    List<Path> inputs() {
        var inputs = new ArrayList<Path>();
        inputs.add(inputFile);
        inputs.addAll(inputFiles);
        return inputs;
    }

    Path getOutputFile() {
        if (outputFile.toString().isEmpty()) {
            if (!inputFiles.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only one file is allowed if no output file given");
            }
            return inputFile.resolveSibling(
                    inputFile.getFileName().toString().replace(".cjfr", ".inflated.jfr"));
        }
        return outputFile;
    }

    public Integer call() {
        return CLIUtils.callImpl(this, "inflate");
    }

    /** JMC-dependent implementation, loaded via reflection. */
    @JMCDependent
    public static class Impl {
        public static void run(InflateCommand cmd) {
            var jfrReader =
                    me.bechberger.jfr.CombiningJFRReader.fromPaths(
                            cmd.inputs(),
                            cmd.eventFilterOptionMixin.createFilter(),
                            cmd.eventFilterOptionMixin.noReconstitution());
            me.bechberger.jfr.WritingJFRReader.toJFRFile(jfrReader, cmd.getOutputFile());
        }
    }
}
