package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.BasicJFRReader;
import org.junit.jupiter.api.Test;

/** Run {@code mvn package -DskipTests} to compile the project before running this test. */
public class AgentCommandTest {

    static class WithRunningJVM implements AutoCloseable {

        static final String TEST_PROGRAM =
                """
                import jdk.jfr.*;

                @Name("TestEvent")
                @Label("Label")
                @Description("Description")
                @StackTrace()
                class TestEvent2 extends Event {
                    @Label("Label")
                    int number;

                    @Label("Memory")
                    @DataAmount
                    long memory = Runtime.getRuntime().freeMemory();

                    @Label("String")
                    String string = "Hello" + memory;

                    TestEvent2(int number) {
                        this.number = number;
                    }
                }

                @Name("AnotherEvent")
                @Label("Label")
                @Description("Description")
                @StackTrace
                class AnotherEvent extends Event {}

                public class Test {
                    public static void main(String[] args) {
                        int counter = 0;
                        while (true) {
                            long start = System.currentTimeMillis();

                            counter += new byte[1024 * 1024 * 1024].length;
                            System.gc();

                            new TestEvent2(0).commit();

                            counter += new byte[1024 * 1024].length;
                            System.gc();

                            new TestEvent2(1).commit();

                            new AnotherEvent().commit();

                            // waste some CPU

                            double waste = 0.0;
                            while (System.currentTimeMillis() - start < 30) {
                                for (int i = 0; i < 1000000; i++) {
                                    waste += Math.sqrt(i);
                                }
                            }
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                """;
        private final Path tmpFolder;
        private final Process process;

        WithRunningJVM() throws Exception {
            // write the test program to a file and start it
            tmpFolder = Files.createTempDirectory("jfr-cli-test");
            var javaFile = tmpFolder.resolve("Test.java");
            Files.writeString(javaFile, TEST_PROGRAM);
            var pb =
                    new ProcessBuilder(
                            "java", "-XX:+EnableDynamicAgentLoading", javaFile.toString());
            pb.inheritIO();
            process = pb.start();
        }

        long pid() {
            return process.pid();
        }

        @Override
        public void close() {
            process.destroy();
            try {
                process.waitFor();
                try (var list = Files.list(tmpFolder)) {
                    list.forEach(p -> p.toFile().delete());
                }
                tmpFolder.toFile().delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

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
    public void testHelpCommand() throws Exception {
        try (var jvm = new WithRunningJVM()) {
            var res = new CommandExecuter("agent", jvm.pid() + "", "help").checkNoError().run();
            assertThat(res.output()).contains("Usage: ./cjfr agent " + jvm.pid() + " [COMMAND]");
        }
    }

    @Test
    public void testStatusCommand() throws Exception {
        try (var jvm = new WithRunningJVM()) {
            var res = new CommandExecuter("agent", jvm.pid() + "", "status").checkNoError().run();
            assertThat(res.output()).contains("No recording running");
        }
    }

    @Test
    public void testStartCommand() throws Exception {
        try (var jvm = new WithRunningJVM()) {
            var res =
                    new CommandExecuter(
                                    "agent",
                                    jvm.pid() + "",
                                    "start",
                                    "T/recording.cjfr",
                                    "--max-duration",
                                    "1s")
                            .checkNoError()
                            .check(
                                    (r, files) -> {
                                        assertThat(files).containsKey("recording.cjfr");
                                        Thread.sleep(2000);
                                        try (var is =
                                                Files.newInputStream(files.get("recording.cjfr"))) {
                                            BasicJFRReader reader =
                                                    new BasicJFRReader(
                                                            new CondensedInputStream(is));
                                            boolean gotTestEvent = false;
                                            ReadStruct event;
                                            while ((event = reader.readNextEvent()) != null) {
                                                if (event.getType()
                                                        .getName()
                                                        .equals("TestEvent2")) {
                                                    gotTestEvent = true;
                                                    break;
                                                }
                                            }
                                            assertThat(gotTestEvent).isTrue();
                                        }
                                    })
                            .run();
            assertThat(res.output()).contains("Condensed recording to");
        }
    }
}
