package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.cli.agent.AgentIO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Run {@code mvn package -DskipTests} to compile the project before running this test. */
public class AgentCommandTest {

    private static void resetAgentIOStatics() throws Exception {
        Field instance = AgentIO.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static void setField(Object instance, String fieldName, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(instance, value);
    }

    @Test
    public void listAvailableVMsOnEmptyArgs() throws Exception {
        var res = new CommandExecuter("agent").checkNoError().run();
        assertThat(res.output()).startsWith("Specify a target JVM by PID or name filter.");
    }

    @Test
    public void testHelpOption() throws Exception {
        var res = new CommandExecuter("agent", "--help").checkNoError().run();
        assertThat(res.output()).contains("Usage: cjfr agent [-hV] TARGET [COMMAND]");
        assertThat(res.output()).doesNotContain("or 'all'");
    }

    @Test
    public void testHelpAlias() throws Exception {
        var res = new CommandExecuter("agent", "help").checkNoError().run();
        assertThat(res.output()).contains("Usage: cjfr agent [-hV] TARGET [COMMAND]");
    }

    @Test
    public void testPidWithoutSubcommandTriesToAttach() throws Exception {
        var res = new CommandExecuter("agent", ProcessHandle.current().pid() + "").run();
        // Self-attach is not supported, so we expect an error
        assertThat(res.exitCode()).isEqualTo(1);
    }

    @Test
    public void testReadCommandWithoutTargetFailsWithUsageError() throws Exception {
        var res = new CommandExecuter("agent", "read").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("Missing TARGET before subcommand 'read'.");
        assertThat(res.error()).contains("Usage: cjfr agent TARGET [COMMAND]");
    }

    @Test
    public void testStatusCommandWithoutTargetFailsWithUsageError() throws Exception {
        var res = new CommandExecuter("agent", "status").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("Missing TARGET before subcommand 'status'.");
        assertThat(res.error()).contains("Usage: cjfr agent TARGET [COMMAND]");
    }

    @Test
    @Timeout(3)
    public void testReadCommandWithEmptyAgentOutputFileExits(@TempDir Path tempDir)
            throws Exception {
        String oldTmpDir = System.getProperty("java.io.tmpdir");
        try {
            System.setProperty("java.io.tmpdir", tempDir.toString());
            resetAgentIOStatics();

            var agentIO = AgentIO.getAgentInstance();
            Files.writeString(agentIO.getOutputFile(), "");
            var readCommand = new AgentCommand.ReadCommand();
            assertThat(readCommand.call()).isEqualTo(0);
        } finally {
            if (oldTmpDir != null) {
                System.setProperty("java.io.tmpdir", oldTmpDir);
            }
            resetAgentIOStatics();
        }
    }

    @Test
    public void testExecuteWithNonNumericTargetUsesNameFilter() {
        int code = AgentCommand.execute(List.of("agent", "not-a-pid"));
        // Non-numeric target is treated as a name filter; no match returns 1
        assertThat(code).isEqualTo(1);
    }

    @Test
    public void testExecuteWithOnlyPidTriesToAttach() {
        int code = AgentCommand.execute(List.of("agent", ProcessHandle.current().pid() + ""));
        // Self-attach is not supported, so we expect error code 1
        assertThat(code).isEqualTo(1);
    }

    @Test
    public void testNonExistentPidReturnsError() throws Exception {
        var res = new CommandExecuter("agent", "999999999").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("No process found with PID 999999999");
    }

    @Test
    public void testNonExistentPidWithSubcommandReturnsError() throws Exception {
        var res = new CommandExecuter("agent", "999999999", "start").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("No process found with PID 999999999");
    }

    @Test
    public void testNonExistentPidWithStatusReturnsError() throws Exception {
        var res = new CommandExecuter("agent", "999999999", "status").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("No process found with PID 999999999");
    }

    @Test
    public void testNegativePidReturnsError() throws Exception {
        var res = new CommandExecuter("agent", "-1", "status").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("PID must be a positive integer, got: -1");
    }

    @Test
    public void testZeroPidReturnsError() throws Exception {
        var res = new CommandExecuter("agent", "0", "status").run();
        assertThat(res.exitCode()).isEqualTo(1);
        assertThat(res.error()).contains("PID must be a positive integer, got: 0");
    }

    @Test
    @Disabled
    public void testHelpCommand() throws Exception {
        try (var jvm = new WithRunningJVM()) {
            var res = new CommandExecuter("agent", jvm.pid() + "", "help").checkNoError().run();
            assertThat(res.output()).contains("Usage: cjfr agent " + jvm.pid() + " [COMMAND]");
        }
    }

    @Test
    @Disabled
    public void testStatusCommand() throws Exception {
        try (var jvm = new WithRunningJVM()) {
            var res = new CommandExecuter("agent", jvm.pid() + "", "status").checkNoError().run();
            assertThat(res.output()).contains("No recording running");
        }
    }

    @Test
    @Disabled
    public void testStartCommand() throws Exception {
        try (var jvm = new WithRunningJVM()) {
            assertTrue(jvm.isAlive());
            var res =
                    new CommandExecuter(
                                    "agent",
                                    jvm.pid() + "",
                                    "start",
                                    "T/recording.cjfr",
                                    "--duration",
                                    "1s")
                            .checkNoError()
                            .check(
                                    (r, files) -> {
                                        assertThat(files).containsKey("recording.cjfr");
                                        Thread.sleep(2000000);
                                        files.forEach(
                                                (key, value) ->
                                                        System.out.println(
                                                                "File: "
                                                                        + key
                                                                        + " Size: "
                                                                        + value.toString()));
                                        // call cjfr summary on the recording file
                                        new CommandExecuter(
                                                        "summary",
                                                        files.get("recording.cjfr").toString())
                                                .checkNoError()
                                                .run();
                                        try (var is =
                                                Files.newInputStream(files.get("recording.cjfr"))) {
                                            BasicJFRReader reader =
                                                    new BasicJFRReader(
                                                            new CondensedInputStream(is));
                                            boolean gotTestEvent = false;
                                            ReadStruct event;
                                            while ((event = reader.readNextEvent()) != null) {
                                                System.out.println(event);
                                                if (event.getType().getName().equals("TestEvent")) {
                                                    gotTestEvent = true;
                                                    break;
                                                }
                                            }
                                            assertTrue(
                                                    gotTestEvent,
                                                    "TestEvent not found in recording");
                                        }
                                    })
                            .run();
            assertThat(res.output()).contains("Condensed recording to");
        }
    }

    @Test
    public void testReadCommandAcceptsOutputPath(@TempDir Path tempDir) throws Exception {
        String oldTmpDir = System.getProperty("java.io.tmpdir");
        try {
            System.setProperty("java.io.tmpdir", tempDir.toString());
            resetAgentIOStatics();

            var agentIO = AgentIO.getAgentInstance();
            Files.writeString(agentIO.getOutputFile(), "");

            var outputFile = tempDir.resolve("agent-read-output.txt");
            var readCommand = new AgentCommand.ReadCommand();
            setField(readCommand, "outputFile", outputFile.toString());

            assertThat(readCommand.call()).isEqualTo(0);
            assertThat(Files.exists(outputFile)).isTrue();
            assertThat(Files.readString(outputFile)).isEmpty();
        } finally {
            if (oldTmpDir != null) {
                System.setProperty("java.io.tmpdir", oldTmpDir);
            }
            resetAgentIOStatics();
        }
    }
}
