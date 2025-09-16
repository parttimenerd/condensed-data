package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.BasicJFRReader;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Run {@code mvn package -DskipTests} to compile the project before running this test. */
public class AgentCommandTest {

    @Test
    public void listAvailableVMsOnEmptyArgs() throws Exception {
        var res = new CommandExecuter("agent").checkNoError().run();
        assertThat(res.output())
                .startsWith("You have to parse the process id of a JVM")
                .contains(ProcessHandle.current().pid() + "");
    }

    @Test
    public void testHelpOption() throws Exception {
        var res = new CommandExecuter("agent", "--help").checkNoError().run();
        assertThat(res.output()).contains("Usage: cjfr agent [-h] PID [COMMAND]");
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
                                        files.entrySet()
                                                .forEach(
                                                        entry -> {
                                                            System.out.println(
                                                                    "File: "
                                                                            + entry.getKey()
                                                                            + " Size: "
                                                                            + entry.getValue()
                                                                                    .toString());
                                                        });
                                        // call cjfr summary on the recording file
                                        var summaryRes =
                                                new CommandExecuter(
                                                                "summary",
                                                                files.get("recording.cjfr")
                                                                        .toString())
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
}
