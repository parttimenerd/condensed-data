package me.bechberger.jfr.cli.commands;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import me.bechberger.jfr.cli.JFRCLI;

/** Run a command with the given files in a temporary folder and check all files in the folder */
public class CommandExecuter {
    public record CommandResult(int exitCode, String output, String error) {}

    /**
     * Run a command with the given files in a temporary folder and check all files in the folder
     */
    public static CommandResult run(
            List<String> args,
            Map<Path, String> copiedInFiles,
            Consumer<Map<String, Path>> checkFilesInTemp)
            throws Exception {
        Path tempFolder = Files.createTempDirectory("jfr-cli-test");
        for (var entry : copiedInFiles.entrySet()) {
            Files.copy(entry.getKey(), tempFolder.resolve(entry.getValue()));
        }
        AtomicInteger exitCode = new AtomicInteger();
        AtomicReference<String> err = new AtomicReference<>();
        String out =
                SystemLambda.tapSystemOut(
                        () ->
                                err.set(
                                        SystemLambda.tapSystemErr(
                                                () -> {
                                                    exitCode.set(
                                                            JFRCLI.execute(
                                                                    args.toArray(String[]::new)));
                                                })));
        try (var stream = Files.list(tempFolder)) {
            checkFilesInTemp.accept(
                    stream.collect(Collectors.toMap(p -> p.getFileName().toString(), p -> p)));
        }
        return new CommandResult(exitCode.get(), out, err.get());
    }

    private final List<String> args;
    private Map<Path, String> copiedInFiles = new HashMap<>();
    private Consumer<Map<String, Path>> checkFilesInTemp = m -> {};

    public CommandExecuter(String... jfrCLIArgs) {
        this.args = List.of(jfrCLIArgs);
    }

    // fluent API
    public CommandExecuter withFiles(Map<Path, String> copiedInFiles) {
        this.copiedInFiles = copiedInFiles;
        return this;
    }

    public CommandExecuter withFiles(List<Path> copiedInFiles) {
        return withFiles(
                copiedInFiles.stream()
                        .collect(Collectors.toMap(p -> p, p -> p.getFileName().toString())));
    }

    public CommandExecuter checkFiles(Consumer<Map<String, Path>> checkFilesInTemp) {
        this.checkFilesInTemp = checkFilesInTemp;
        return this;
    }

    public CommandResult run() throws Exception {
        return run(args, copiedInFiles, checkFilesInTemp);
    }
}
