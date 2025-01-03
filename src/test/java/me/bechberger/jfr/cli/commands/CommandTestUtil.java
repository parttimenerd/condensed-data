package me.bechberger.jfr.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.jfr.cli.JFRCLI;

public class CommandTestUtil {

    private static final Path TEMP_FOLDER;

    static {
        try {
            TEMP_FOLDER = Files.createTempDirectory("jfr-cli-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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

    private static final int SAMPLE_JFR_FILE_DURATION = 2;

    private static int counter = 0;

    private static void recordSampleJFRFile(Path jfrFile) throws IOException, ParseException {
        int start = (int) System.currentTimeMillis();

        try (RecordingStream rs = new RecordingStream(Configuration.getConfiguration("profile"))) {
            rs.startAsync();
            counter += new byte[1024 * 1024 * 1024].length;
            System.gc();

            new TestEvent(0).commit();

            counter += new byte[1024 * 1024].length;
            System.gc();

            new TestEvent(1).commit();

            new AnotherEvent().commit();

            // waste some CPU

            double waste = 0.0;
            while (System.currentTimeMillis() - start < SAMPLE_JFR_FILE_DURATION * 1000) {
                for (int i = 0; i < 1000000; i++) {
                    waste += Math.sqrt(i);
                }
            }
            rs.dump(jfrFile);
        }
    }

    public static Path getSampleJFRFile() {
        return getSampleJFRFile(0);
    }

    /**
     * Returns a sample JFR file with two instances of the {@link TestEvent} (one with number 0 and
     * one with number 1)
     */
    public static Path getSampleJFRFile(int number) {
        var file = TEMP_FOLDER.resolve(number == 0 ? "sample.jfr" : "sample_" + number + ".jfr");
        if (!Files.exists(file)) {
            try {
                recordSampleJFRFile(file);
            } catch (IOException | ParseException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    public static String getSampleJFRFileName() {
        return getSampleJFRFile().getFileName().toString();
    }

    public static String getSampleJFRFileName(int number) {
        return getSampleJFRFile(number).getFileName().toString();
    }

    public static Path getSampleCJFRFile() {
        return getSampleCJFRFile(0);
    }

    /**
     * Returns a sample CJFR file with two instances of the {@link TestEvent} (one with number 0 and
     * one with number 1)
     */
    public static Path getSampleCJFRFile(int number) {
        var file = TEMP_FOLDER.resolve(number == 0 ? "sample.cjfr" : "sample_" + number + ".cjfr");
        if (!Files.exists(file)) {
            try {
                var jfrFile = getSampleJFRFile(number);
                JFRCLI.execute(new String[] {"condense", jfrFile.toString(), file.toString()});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    public static String getSampleCJFRFileName(int number) {
        return getSampleCJFRFile(number).getFileName().toString();
    }

    public static String getSampleCJFRFileName() {
        return getSampleCJFRFile().getFileName().toString();
    }
}
