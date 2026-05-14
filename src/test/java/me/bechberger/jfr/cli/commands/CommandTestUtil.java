package me.bechberger.jfr.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    // Custom edge-case events for stress testing
    @Name("LargeStringEvent")
    @Label("Large String Event")
    @StackTrace(false)
    static class LargeStringEvent extends Event {
        String largeString = "x".repeat(10000); // 10KB string
    }

    @Name("ExtremeNumericEvent")
    @Label("Extreme Numeric Event")
    @StackTrace(false)
    static class ExtremeNumericEvent extends Event {
        long maxLong = Long.MAX_VALUE;
        long minLong = Long.MIN_VALUE;
        int maxInt = Integer.MAX_VALUE;
        int minInt = Integer.MIN_VALUE;
        double maxDouble = Double.MAX_VALUE;
        double minDouble = Double.MIN_VALUE;
        float maxFloat = Float.MAX_VALUE;
        float minFloat = Float.MIN_VALUE;
    }

    @Name("UnicodeStringEvent")
    @Label("Unicode String Event")
    @StackTrace(false)
    static class UnicodeStringEvent extends Event {
        String emoji = "🎉🚀💥✨🔥"; // emoji and special chars
        String chinese = "你好世界";
        String arabic = "مرحبا بالعالم";
        String specialChars = "!@#$%^&*()<>?{}[]|\\;':\",.";
    }

    @Name("ManyFieldsEvent")
    @Label("Many Fields Event")
    @StackTrace(false)
    static class ManyFieldsEvent extends Event {
        int field1 = 1;
        int field2 = 2;
        int field3 = 3;
        int field4 = 4;
        int field5 = 5;
        int field6 = 6;
        int field7 = 7;
        int field8 = 8;
        int field9 = 9;
        int field10 = 10;
        String name1 = "test1";
        String name2 = "test2";
        String name3 = "test3";
        long time1 = System.nanoTime();
    }

    @Name("ByteCountEvent")
    @Label("Byte Count Event")
    @StackTrace(false)
    static class ByteCountEvent extends Event {
        @DataAmount(DataAmount.BYTES)
        long bytesReadAligned = 16;

        @DataAmount(DataAmount.BYTES)
        long bytesReadOdd = 15;

        @DataAmount(DataAmount.BYTES)
        long bytesReadTiny = 3;
    }

    /** Event with uninitialized (null) String fields — tests null-safety in condense pipeline. */
    @Name("NullStringEvent")
    @StackTrace(false)
    static class NullStringEvent extends Event {
        String nullField; // intentionally uninitialized → null at emit time
        String emptyField = "";
        int controlInt = 99;
    }

    /** Event with IEEE-754 special double values: NaN, +Inf, -Inf, negative zero. */
    @Name("SpecialDoubleEvent")
    @StackTrace(false)
    static class SpecialDoubleEvent extends Event {
        double nanValue = Double.NaN;
        double posInfinity = Double.POSITIVE_INFINITY;
        double negInfinity = Double.NEGATIVE_INFINITY;
        double negZero = -0.0;
    }

    @Name("PreciseDoubleEvent")
    @StackTrace(false)
    static class PreciseDoubleEvent extends Event {
        double preciseValue = 1.23456789012345d;
    }

    /** Lightweight event committed at high frequency to stress buffering. */
    @Name("HighFreqEvent")
    @StackTrace(false)
    static class HighFreqEvent extends Event {
        int seq;

        HighFreqEvent(int seq) {
            this.seq = seq;
        }
    }

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

    private static void recordEmptyJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.startAsync();
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

    public static Path getEmptyJFRFile() {
        var file = TEMP_FOLDER.resolve("empty.jfr");
        if (!Files.exists(file)) {
            try {
                recordEmptyJFRFile(file);
            } catch (IOException e) {
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

    public static Path getEmptyCJFRFile() {
        var file = TEMP_FOLDER.resolve("empty.cjfr");
        if (!Files.exists(file)) {
            try {
                var jfrFile = getEmptyJFRFile();
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

    public static String getEmptyCJFRFileName() {
        return getEmptyCJFRFile().getFileName().toString();
    }

    /** Create a ZIP file with the given mapping of entry path -> source file path. */
    public static void createZipWithFiles(Path zipFile, Map<String, Path> entryToSource)
            throws IOException {
        var parent = zipFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var zipOutputStream = Files.newOutputStream(zipFile);
                var zip = new ZipOutputStream(zipOutputStream)) {
            for (var entry : entryToSource.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                Files.copy(entry.getValue(), zip);
                zip.closeEntry();
            }
        }
    }

    // Methods to create JFR files with custom edge-case events
    private static void recordLargeStringJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("LargeStringEvent");
            rs.onEvent("LargeStringEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 5; i++) {
                new LargeStringEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
            // Don't call close before dump - stream is auto-closed by try-with-resources
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getLargeStringJFRFile() {
        var file = TEMP_FOLDER.resolve("large_string.jfr");
        if (!Files.exists(file)) {
            try {
                recordLargeStringJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordExtremeNumericJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("ExtremeNumericEvent");
            rs.onEvent("ExtremeNumericEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 3; i++) {
                new ExtremeNumericEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getExtremeNumericJFRFile() {
        var file = TEMP_FOLDER.resolve("extreme_numeric.jfr");
        if (!Files.exists(file)) {
            try {
                recordExtremeNumericJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordUnicodeStringJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("UnicodeStringEvent");
            rs.onEvent("UnicodeStringEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 5; i++) {
                new UnicodeStringEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getUnicodeStringJFRFile() {
        var file = TEMP_FOLDER.resolve("unicode_string.jfr");
        if (!Files.exists(file)) {
            try {
                recordUnicodeStringJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordManyFieldsJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("ManyFieldsEvent");
            rs.onEvent("ManyFieldsEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 10; i++) {
                new ManyFieldsEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getManyFieldsJFRFile() {
        var file = TEMP_FOLDER.resolve("many_fields.jfr");
        if (!Files.exists(file)) {
            try {
                recordManyFieldsJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordByteCountJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("ByteCountEvent");
            rs.onEvent("ByteCountEvent", event -> {});
            rs.startAsync();
            new ByteCountEvent().commit();
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getByteCountJFRFile() {
        var file = TEMP_FOLDER.resolve("byte_count.jfr");
        if (!Files.exists(file)) {
            try {
                recordByteCountJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordNullStringJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("NullStringEvent");
            rs.onEvent("NullStringEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 5; i++) {
                new NullStringEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getNullStringJFRFile() {
        var file = TEMP_FOLDER.resolve("null_string.jfr");
        if (!Files.exists(file)) {
            try {
                recordNullStringJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordSpecialDoubleJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("SpecialDoubleEvent");
            rs.onEvent("SpecialDoubleEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 5; i++) {
                new SpecialDoubleEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getSpecialDoubleJFRFile() {
        var file = TEMP_FOLDER.resolve("special_double.jfr");
        if (!Files.exists(file)) {
            try {
                recordSpecialDoubleJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordPreciseDoubleJFRFile(Path jfrFile) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("PreciseDoubleEvent");
            rs.onEvent("PreciseDoubleEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < 5; i++) {
                new PreciseDoubleEvent().commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static Path getPreciseDoubleJFRFile() {
        var file = TEMP_FOLDER.resolve("precise_double.jfr");
        if (!Files.exists(file)) {
            try {
                recordPreciseDoubleJFRFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    public static Path getHighFreqJFRFile(int count) {
        var file = TEMP_FOLDER.resolve("high_freq_" + count + ".jfr");
        if (!Files.exists(file)) {
            try {
                recordHighFreqJFRFile(file, count);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    private static void recordHighFreqJFRFile(Path jfrFile, int count) throws IOException {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("HighFreqEvent");
            rs.onEvent("HighFreqEvent", event -> {});
            rs.startAsync();
            for (int i = 0; i < count; i++) {
                new HighFreqEvent(i).commit();
            }
            Thread.sleep(100);
            rs.dump(jfrFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
