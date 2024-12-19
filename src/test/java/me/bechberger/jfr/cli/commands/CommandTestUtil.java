package me.bechberger.jfr.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;

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

    private static final int SAMPLE_JFR_FILE_DURATION = 2;

    private static final Path SAMPLE_JFR_FILE = TEMP_FOLDER.resolve("sample.jfr");

    private static int counter = 0;

    private static void recordSampleJFRFile() throws IOException, ParseException {
        int start = (int) System.currentTimeMillis();

        try (RecordingStream rs = new RecordingStream(Configuration.getConfiguration("profile"))) {
            rs.startAsync();
            counter += new byte[1024 * 1024 * 1024].length;
            System.gc();

            new TestEvent(0).commit();

            counter += new byte[1024 * 1024].length;
            System.gc();

            new TestEvent(1).commit();

            // waste some CPU

            double waste = 0.0;
            while (System.currentTimeMillis() - start < SAMPLE_JFR_FILE_DURATION * 1000) {
                for (int i = 0; i < 1000000; i++) {
                    waste += Math.sqrt(i);
                }
            }
            rs.dump(SAMPLE_JFR_FILE);
        }
    }

    /**
     * Returns a sample JFR file with two instances of the {@link TestEvent} (one with number 0 and
     * one with number 1)
     */
    public static Path getSampleJFRFile() {
        if (!Files.exists(SAMPLE_JFR_FILE)) {
            try {
                recordSampleJFRFile();
            } catch (IOException | ParseException e) {
                throw new RuntimeException(e);
            }
        }
        return SAMPLE_JFR_FILE;
    }

    public static String getSampleJFRFileName() {
        return getSampleJFRFile().getFileName().toString();
    }
}
