package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import me.bechberger.jfr.cli.JFRCLI;

/** Run a command with the given files in a temporary folder and check all files in the folder */
public class CommandExecuter {

    @FunctionalInterface
    public interface ConsumerWithException<T> {
        void accept(T t) throws Exception;
    }

    @FunctionalInterface
    public interface BiConsumerWithException<S, T> {
        void accept(S s, T t) throws Exception;
    }

    public record CommandResult(int exitCode, String output, String error) {

        public void assertNoErrorOrOutput() {
            assertAll(
                    () -> assertThat(exitCode).isEqualTo(0),
                    () -> assertThat(error).isEmpty(),
                    () -> assertThat(output).isEmpty());
        }
    }

    /**
     * Run a command with the given files in a temporary folder and check all files in the folder
     */
    public static CommandResult run(
            List<String> args,
            Map<Path, String> copiedInFiles,
            boolean checkNoError,
            boolean checkNoOutput,
            BiConsumerWithException<CommandResult, Map<String, Path>> checkFilesInTemp)
            throws Exception {
        Path tempFolder = Files.createTempDirectory("jfr-cli-test");
        for (var entry : copiedInFiles.entrySet()) {
            Files.copy(entry.getKey(), tempFolder.resolve(entry.getValue()));
        }
        AtomicInteger exitCode = new AtomicInteger();
        AtomicReference<String> err = new AtomicReference<>();
        var modifiedArgs = args.stream().map(s -> s.replaceAll("^T/", tempFolder.toString() + "/"));
        String out =
                SystemLambda.tapSystemOut(
                        () ->
                                err.set(
                                        SystemLambda.tapSystemErr(
                                                () -> {
                                                    exitCode.set(
                                                            JFRCLI.execute(
                                                                    modifiedArgs.toArray(
                                                                            String[]::new)));
                                                })));
        if (checkNoError) {
            assertAll(
                    () -> assertThat(exitCode.get()).isEqualTo(0),
                    () -> assertThat(err.get()).isEmpty());
        }
        if (checkNoOutput) {
            assertThat(out).isEmpty();
        }
        try (var stream = Files.list(tempFolder)) {
            checkFilesInTemp.accept(
                    new CommandResult(exitCode.get(), out, err.get()),
                    stream.collect(Collectors.toMap(p -> p.getFileName().toString(), p -> p)));
        }
        return new CommandResult(exitCode.get(), out, err.get());
    }

    private final List<String> args;
    private Map<Path, String> copiedInFiles = new HashMap<>();
    private boolean checkNoError = false;
    private boolean checkNoOutput = false;
    private BiConsumerWithException<CommandResult, Map<String, Path>> checkFilesInTemp =
            (r, m) -> {};

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

    public CommandExecuter withFiles(Path... copiedInFiles) {
        return withFiles(List.of(copiedInFiles));
    }

    public CommandExecuter check(
            BiConsumerWithException<CommandResult, Map<String, Path>> checkFilesInTemp) {
        this.checkFilesInTemp = checkFilesInTemp;
        return this;
    }

    /** Check that the command did not produce any error output and that the exit code is zero */
    public CommandExecuter checkNoError() {
        this.checkNoError = true;
        return this;
    }

    /** Check that the command did not produce any output on standard out */
    public CommandExecuter checkNoOutput() {
        this.checkNoOutput = true;
        return this;
    }

    public CommandResult run() throws Exception {
        return run(args, copiedInFiles, checkNoError, checkNoOutput, checkFilesInTemp);
    }
}
