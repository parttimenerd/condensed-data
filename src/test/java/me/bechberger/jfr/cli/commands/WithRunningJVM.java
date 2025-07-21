package me.bechberger.jfr.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a test programs that generates JFR events and simulates memory pressure and garbage
 * collection.
 *
 * <p>The test program generates events in the following order per loop iteration:
 *
 * <ul>
 *   <li><strong>Large memory allocation</strong> (1GB byte array)
 *   <li><strong>System.gc()</strong> call
 *   <li><strong>TestEvent(0) commit</strong> with memory state
 *   <li><strong>Small memory allocation</strong> (1MB byte array)
 *   <li><strong>System.gc()</strong> call
 *   <li><strong>TestEvent(1) commit</strong> with updated memory state
 *   <li><strong>AnotherEvent commit</strong>
 *   <li><strong>CPU intensive work</strong> (30ms of calculations)
 *   <li><strong>Thread.sleep(20ms)</strong>
 *   <li><strong>Loop repeats</strong>
 * </ul>
 *
 * <p>This sequence creates predictable patterns of memory pressure, garbage collection, and custom
 * JFR events approximately every 50ms
 */
public class WithRunningJVM implements AutoCloseable {

    static final String TEST_PROGRAM =
            """
                    import jdk.jfr.*;

                    public class Test {
                        @Name("TestEvent")
                        @Label("Label")
                        @Description("Description")
                        @StackTrace()
                        static class TestEvent extends Event {
                            @Label("Label")
                            int number;

                            @Label("Memory")
                            @DataAmount
                            long memory = Runtime.getRuntime().freeMemory();

                            @Label("String")
                            String string = "Hello" + memory;

                            TestEvent(int number) {
                                this.number = number;
                            }
                        }

                        @Name("AnotherEvent")
                        @Label("Label")
                        @Description("Description")
                        @StackTrace
                        static class AnotherEvent extends Event {}

                        public static void main(String[] args) {
                            int counter = 0;
                            while (true) {
                                long start = System.currentTimeMillis();

                                counter += new byte[1024 * 1024 * 1024].length;
                                System.gc();

                                new TestEvent(0).commit();

                                counter += new byte[1024 * 1024].length;
                                System.gc();

                                new TestEvent(1).commit();

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

    public WithRunningJVM() throws Exception {
        // write the test program to a file and start it
        tmpFolder = Files.createTempDirectory("jfr-cli-test");

        var javaFile = tmpFolder.resolve("Test.java");
        Files.writeString(javaFile, TEST_PROGRAM);
        var pb = new ProcessBuilder("java", "-XX:+EnableDynamicAgentLoading", javaFile.toString());
        pb.inheritIO();
        process = pb.start();
    }

    public long pid() {
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
            if (process.isAlive()) {
                System.err.println("Process did not terminate properly, killing it.");
                process.destroyForcibly();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }
}
