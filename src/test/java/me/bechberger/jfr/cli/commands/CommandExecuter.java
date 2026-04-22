package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.bechberger.femtocli.RunResult;
import me.bechberger.jfr.cli.JFRCLI;

/** Run a command with the given files in a temporary folder and check all files in the folder */
public class CommandExecuter {

    /**
     * Set the system property {@code cjfr.test.jar} to a JAR path to run commands via {@code java
     * -jar <jar>} instead of in-process. This allows testing reduced/minimal JARs (e.g. built by
     * {@code reduce-jar.py}).
     */
    private static final String TEST_JAR_PROPERTY = "cjfr.test.jar";

        private static final Set<String> IGNORED_STDERR_WARNING_PREFIXES =
            Set.of(
                "WARNING: A restricted method in java.lang.System has been called",
                "WARNING: java.lang.System::load has been called by",
                "WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers",
                "WARNING: Restricted methods will be blocked in a future release unless native access is enabled",
                "WARNING: A Java agent has been loaded dynamically",
                "WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning",
                "WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information",
                "WARNING: Dynamic loading of agents will be disallowed by default in a future release");

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

    private static RunResult runViaJar(String jarPath, String[] args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jarPath);
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        String out;
        String err;
        try (var outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                var errReader =
                        new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            out = outReader.lines().collect(Collectors.joining("\n"));
            err = errReader.lines().collect(Collectors.joining("\n"));
        }
        int exitCode = process.waitFor();
        return new RunResult(out, err, exitCode);
    }

    private static String stripIgnoredStderrWarnings(String err) {
        if (err == null || err.isBlank()) {
            return "";
        }
        return err.lines()
                .filter(
                        line -> {
                            var trimmed = line.stripLeading();
                            return IGNORED_STDERR_WARNING_PREFIXES.stream()
                                    .noneMatch(trimmed::startsWith);
                        })
                .collect(Collectors.joining("\n"))
                .trim();
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
        var modifiedArgs =
                args.stream()
                        .map(s -> s.replaceAll("^T/", tempFolder.toString() + "/"))
                        .toArray(String[]::new);
        String testJar = System.getProperty(TEST_JAR_PROPERTY);
        RunResult result;
        if (testJar != null) {
            result = runViaJar(testJar, modifiedArgs);
        } else {
            result = JFRCLI.builder().runCaptured(new JFRCLI(), modifiedArgs);
        }
        var filteredError = stripIgnoredStderrWarnings(result.err());
        if (checkNoError) {
            assertAll(
                    () -> assertThat(result.exitCode()).isEqualTo(0),
                    () -> assertThat(filteredError).isEmpty());
        }
        if (checkNoOutput) {
            assertThat(result.out()).isEmpty();
        }
        var commandResult = new CommandResult(result.exitCode(), result.out(), filteredError);
        try (var stream = Files.list(tempFolder)) {
            checkFilesInTemp.accept(
                    commandResult,
                    stream.collect(Collectors.toMap(p -> p.getFileName().toString(), p -> p)));
        }
        return commandResult;
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
