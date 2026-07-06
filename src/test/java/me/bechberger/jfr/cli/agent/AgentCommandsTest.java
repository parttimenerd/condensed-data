package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.agent.commands.SetDurationCommand;
import me.bechberger.jfr.cli.agent.commands.SetMaxDurationCommand;
import me.bechberger.jfr.cli.agent.commands.SetMaxFilesCommand;
import me.bechberger.jfr.cli.agent.commands.SetMaxSizeCommand;
import me.bechberger.jfr.cli.agent.commands.StartCommand;
import me.bechberger.jfr.cli.agent.commands.StatusCommand;
import me.bechberger.jfr.cli.agent.commands.StopCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class AgentCommandsTest {

    private static final class StubRecordingThread extends RecordingThread {
        private Duration lastDuration;
        private Duration lastMaxDuration;
        private Long lastMaxSize;
        private Integer lastMaxFiles;
        private boolean stopCalled;

        private StubRecordingThread(boolean rotating) throws ParseException, java.io.IOException {
            super(
                    Configuration.REASONABLE_DEFAULT,
                    false,
                    "default",
                    "",
                    () -> {},
                    createSettings(),
                    rotating);
        }

        @Override
        void onEvent(RecordedEvent event) {}

        @Override
        List<Map.Entry<String, String>> getMiscStatus() {
            return List.of(Map.entry("mode", "stub"), Map.entry("path", "stub.cjfr"));
        }

        @Override
        public void close() {}

        @Override
        public void setMaxSize(long maxSize) {
            lastMaxSize = maxSize;
        }

        @Override
        public void setMaxDuration(Duration maxDuration) {
            lastMaxDuration = maxDuration;
        }

        @Override
        public void setDuration(Duration duration) {
            lastDuration = duration;
        }

        @Override
        public void setMaxFiles(int maxFiles) {
            lastMaxFiles = maxFiles;
        }

        @Override
        public void stop() {
            stopCalled = true;
        }
    }

    private static final class ValidatingStubRecordingThread extends RecordingThread {
        private ValidatingStubRecordingThread(boolean rotating)
                throws ParseException, java.io.IOException {
            super(
                    Configuration.REASONABLE_DEFAULT,
                    false,
                    "default",
                    "",
                    () -> {},
                    createSettings(),
                    rotating);
        }

        @Override
        void onEvent(RecordedEvent event) {}

        @Override
        List<Map.Entry<String, String>> getMiscStatus() {
            return List.of(Map.entry("mode", "validating-stub"));
        }

        @Override
        public void close() {}
    }

    private static DynamicallyChangeableSettings createSettings() {
        var settings = new DynamicallyChangeableSettings();
        settings.maxDuration = Duration.ZERO;
        settings.maxSize = 0;
        settings.maxFiles = 10;
        settings.newNames = false;
        settings.duration = Duration.ZERO;
        return settings;
    }

    private static <T> T captureStdout(ThrowingSupplier<T> supplier, StringBuilder output)
            throws Exception {
        var previous = System.out;
        var buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true));
            T result = supplier.get();
            output.append(buffer);
            return result;
        } finally {
            System.setOut(previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static void setField(Object instance, String fieldName, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static String ensureRotatingPathHasPlaceholder(String path) throws Exception {
        var method =
                StartCommand.class.getDeclaredMethod(
                        "ensureRotatingPathHasPlaceholder", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, path);
    }

    @AfterEach
    public void tearDown() {
        Agent.setCurrentRecordingThread(null);
    }

    @Test
    public void testSetDurationCommandWithoutRecordingPrintsMessage() throws Exception {
        var command = new SetDurationCommand();
        setField(command, "duration", Duration.ofSeconds(5));
        var output = new StringBuilder();

        int result = captureStdout(command::call, output);

        assertEquals(1, result);
        assertTrue(output.toString().contains("No recording running"));
    }

    @Test
    public void testSetDurationCommandDelegatesToRecordingThread() throws Exception {
        var thread = new StubRecordingThread(false);
        Agent.setCurrentRecordingThread(thread);
        var command = new SetDurationCommand();
        setField(command, "duration", Duration.ofSeconds(5));

        assertEquals(0, command.call());

        assertEquals(Duration.ofSeconds(5), thread.lastDuration);
    }

    @Test
    public void testSetMaxDurationCommandWithoutRecordingPrintsMessage() throws Exception {
        var command = new SetMaxDurationCommand();
        setField(command, "maxDuration", Duration.ofSeconds(2));
        var output = new StringBuilder();

        int result = captureStdout(command::call, output);

        assertEquals(1, result);
        assertTrue(output.toString().contains("No recording running"));
    }

    @Test
    public void testSetMaxDurationCommandDelegatesToRecordingThread() throws Exception {
        var thread = new StubRecordingThread(true);
        Agent.setCurrentRecordingThread(thread);
        var command = new SetMaxDurationCommand();
        setField(command, "maxDuration", Duration.ofSeconds(2));

        assertEquals(0, command.call());

        assertEquals(Duration.ofSeconds(2), thread.lastMaxDuration);
    }

    @Test
    public void testSetMaxFilesCommandWithoutRecordingPrintsMessage() throws Exception {
        var command = new SetMaxFilesCommand();
        setField(command, "maxFiles", 3);
        var output = new StringBuilder();

        int result = captureStdout(command::call, output);

        assertEquals(1, result);
        assertTrue(output.toString().contains("No recording running"));
    }

    @Test
    public void testSetMaxFilesCommandDelegatesToRecordingThread() throws Exception {
        var thread = new StubRecordingThread(true);
        Agent.setCurrentRecordingThread(thread);
        var command = new SetMaxFilesCommand();
        setField(command, "maxFiles", 3);

        assertEquals(0, command.call());

        assertEquals(3, thread.lastMaxFiles);
    }

    @Test
    public void testSetMaxFilesCommandRejectsZeroForRotatingRecording() throws Exception {
        var thread = new ValidatingStubRecordingThread(true);
        Agent.setCurrentRecordingThread(thread);
        var command = new SetMaxFilesCommand();
        setField(command, "maxFiles", 0);
        var output = new StringBuilder();

        int result = captureStdout(command::call, output);

        assertEquals(1, result);
        assertTrue(
                output.toString().contains("Max files must be at least 1 when rotating"),
                "Expected error message, got: " + output);
        assertEquals(10, thread.getMaxFiles());
    }

    @Test
    public void testSetMaxSizeCommandWithoutRecordingPrintsMessage() throws Exception {
        var command = new SetMaxSizeCommand();
        setField(command, "maxSize", 2048L);
        var output = new StringBuilder();

        int result = captureStdout(command::call, output);

        assertEquals(1, result);
        assertTrue(output.toString().contains("No recording running"));
    }

    @Test
    public void testSetMaxSizeCommandDelegatesToRecordingThread() throws Exception {
        var thread = new StubRecordingThread(true);
        Agent.setCurrentRecordingThread(thread);
        var command = new SetMaxSizeCommand();
        setField(command, "maxSize", 2048L);

        assertEquals(0, command.call());

        assertEquals(2048L, thread.lastMaxSize);
    }

    @Test
    public void testStopCommandWithoutRecordingReturnsOne() throws Exception {
        var output = new StringBuilder();
        var command = new StopCommand();

        int result = captureStdout(command::call, output);

        assertEquals(1, result);
        assertTrue(output.toString().contains("No recording running"));
    }

    @Test
    public void testStopCommandDelegatesToRecordingThread() throws Exception {
        var thread = new StubRecordingThread(false);
        Agent.setCurrentRecordingThread(thread);
        var command = new StopCommand();

        assertEquals(0, command.call());
        assertTrue(thread.stopCalled);
    }

    @Test
    public void testStatusCommandWithoutRecordingPrintsMessage() throws Exception {
        var output = new StringBuilder();
        var command = new StatusCommand();

        assertEquals(1, captureStdout(command::call, output));
        assertTrue(output.toString().contains("No recording running"));
    }

    @Test
    public void testStatusCommandPrintsStatusEntries() throws Exception {
        var thread = new StubRecordingThread(true);
        Agent.setCurrentRecordingThread(thread);
        var output = new StringBuilder();
        var command = new StatusCommand();

        assertEquals(0, captureStdout(command::call, output));

        assertTrue(output.toString().contains("Recording running"));
        assertTrue(output.toString().contains("generator-configuration"));
        assertTrue(output.toString().contains("mode"));
    }

    @Test
    public void testStartCommandRejectsAlreadyRunningRecording() throws Exception {
        Agent.setCurrentRecordingThread(new StubRecordingThread(false));
        var output = new StringBuilder();
        var command = new StartCommand();
        setField(command, "dynSettings", createSettings());

        assertEquals(1, captureStdout(command::call, output));
        assertTrue(output.toString().contains("Recording already running"));
    }

    @Test
    public void testStartCommandRejectsRotatingWithTooFewFiles() throws Exception {
        var command = new StartCommand();
        var settings = createSettings();
        settings.maxFiles = 0;
        settings.maxDuration = Duration.ofSeconds(1);
        setField(command, "path", "recording.cjfr");
        setField(command, "configuration", Configuration.REASONABLE_DEFAULT);
        setField(command, "miscJfrConfig", "");
        setField(command, "verbose", false);
        setField(command, "jfrConfig", "default");
        setField(command, "rotating", true);
        setField(command, "dynSettings", settings);
        var output = new StringBuilder();

        assertEquals(1, captureStdout(command::call, output));
        assertTrue(output.toString().contains("max-files must be at least 1"));
    }

    @Test
    public void testStartCommandRejectsRotatingWithoutLimit() throws Exception {
        var command = new StartCommand();
        var settings = createSettings();
        settings.maxFiles = 2;
        setField(command, "path", "recording.cjfr");
        setField(command, "configuration", Configuration.REASONABLE_DEFAULT);
        setField(command, "miscJfrConfig", "");
        setField(command, "verbose", false);
        setField(command, "jfrConfig", "default");
        setField(command, "rotating", true);
        setField(command, "dynSettings", settings);
        var output = new StringBuilder();

        assertEquals(1, captureStdout(command::call, output));
        assertTrue(output.toString().contains("max-size or max-duration required"));
    }

    @Test
    public void testEnsureRotatingPathHasPlaceholderReplacesSuffix() {
        assertDoesNotThrow(
                () ->
                        assertEquals(
                                "tmp/recording_$index.cjfr",
                                ensureRotatingPathHasPlaceholder("tmp/recording.cjfr")));
    }

    @Test
    public void testEnsureRotatingPathHasPlaceholderAppendsForPathWithoutSuffix() {
        assertDoesNotThrow(
                () ->
                        assertEquals(
                                "tmp/recording_$index.cjfr",
                                ensureRotatingPathHasPlaceholder("tmp/recording")));
    }

    @Test
    public void testEnsureRotatingPathHasPlaceholderKeepsExistingPlaceholder() {
        assertDoesNotThrow(
                () ->
                        assertEquals(
                                "tmp/recording_$date.cjfr",
                                ensureRotatingPathHasPlaceholder("tmp/recording_$date.cjfr")));
    }
}
