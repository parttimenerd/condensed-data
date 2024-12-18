package me.bechberger.jfr.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.WritingJFRReader;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "inflate", description = "Inflate a condensed JFR file into JFR format")
public class InflateJFRCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The output file", defaultValue = "")
    private Path outputFile;

    @Parameters(
            index = "1..*",
            description = "The input .cjfr files, can be folders, or zips",
            arity = "1..*")
    private List<Path> inputFiles = new ArrayList<>();

    @Mixin EventFilterOptionMixin eventFilterOptionMixin;

    @Spec CommandSpec spec;

    private Path getOutputFile() {
        if (outputFile.endsWith(".cjfr")) {
            if (!inputFiles.isEmpty()) {
                throw new ParameterException(
                        spec.commandLine(), "Only one file is allowed if no output file given");
            }
            if (!Files.exists(outputFile)) {
                System.err.println("Input file does not exist: " + outputFile);
                return null;
            }
            return outputFile.resolveSibling(
                    outputFile.getFileName().toString().replace(".cjfr", ".inflated.jfr"));
        }
        return outputFile;
    }

    public Integer call() {
        try {
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
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
