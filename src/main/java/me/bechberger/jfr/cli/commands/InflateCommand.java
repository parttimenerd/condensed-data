package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.WritingJFRReader;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFRFileOrZipOrFolderParameterConsumer;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "inflate",
        description = "Inflate a condensed JFR file into JFR format",
        mixinStandardHelpOptions = true)
public class InflateCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            description = "The input .cjfr file, can be a folder, or a zip",
            converter = ExistingCJFRFileOrZipOrFolderConverter.class,
            parameterConsumer = ExistingCJFRFileOrZipOrFolderParameterConsumer.class)
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

    @Spec CommandSpec spec;

    private List<Path> inputs() {
        var inputs = new ArrayList<Path>();
        inputs.add(inputFile);
        inputs.addAll(inputFiles);
        return inputs;
    }

    private Path getOutputFile() {
        if (outputFile.toString().isEmpty()) {
            if (!inputFiles.isEmpty()) {
                throw new ParameterException(
                        spec.commandLine(), "Only one file is allowed if no output file given");
            }
            return inputFile.resolveSibling(
                    inputFile.getFileName().toString().replace(".cjfr", ".inflated.jfr"));
        }
        return outputFile;
    }

    public Integer call() {
        try {
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputs(),
                            eventFilterOptionMixin.createFilter(),
                            eventFilterOptionMixin.noReconstitution());
            WritingJFRReader.toJFRFile(jfrReader, getOutputFile());
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }
}
