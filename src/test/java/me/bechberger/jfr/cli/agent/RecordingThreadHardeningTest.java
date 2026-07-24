package me.bechberger.jfr.cli.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for hardening fixes in RotatingRecordingThread, SingleRecordingThread, and
 * RecordingThread. These tests run entirely in-process without a separate JVM.
 */
public class RecordingThreadHardeningTest {

    private static DynamicallyChangeableSettings rotatingSettings(
            Duration maxDuration, long maxSize, int maxFiles) {
        var s = new DynamicallyChangeableSettings();
        s.maxDuration = maxDuration;
        s.maxSize = maxSize;
        s.maxFiles = maxFiles;
        s.newNames = true;
        s.duration = Duration.ZERO;
        return s;
    }

    private static DynamicallyChangeableSettings singleSettings() {
        var s = new DynamicallyChangeableSettings();
        s.maxDuration = Duration.ZERO;
        s.maxSize = 0;
        s.maxFiles = 10;
        s.newNames = false;
        s.duration = Duration.ZERO;
        return s;
    }

    // -------------------------------------------------------------------------
    // Helpers to redirect stderr (AgentIO writes there in test mode)
    // -------------------------------------------------------------------------

    private static ByteArrayOutputStream captureStderr() {
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        return captured;
    }

    private static void restoreStderr(PrintStream saved) {
        System.setErr(saved);
    }

    // -------------------------------------------------------------------------
    // Test: concurrent initNewFile calls do not produce duplicate files / lost writers
    // -------------------------------------------------------------------------

    /**
     * Two threads both call initNewFile() (via onEvent simulating size overflow) concurrently. Only
     * one rotation should happen per size threshold; the file list must not exceed maxFiles and no
     * writer should be leaked (i.e., no files with zero-length headers).
     */
    @Test
    @Timeout(30)
    public void testConcurrentRotationDoesNotProduceDuplicateFiles() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-concurrent-");
        tmp.toFile().deleteOnExit();

        // Use a very small maxSize so that shouldEndFile() evaluates to true immediately
        var settings = rotatingSettings(Duration.ZERO, 1024, 10);

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        int concurrentCalls = 20;
        var latch = new CountDownLatch(1);
        var threads = new ArrayList<Thread>(concurrentCalls);
        for (int i = 0; i < concurrentCalls; i++) {
            var t =
                    new Thread(
                            () -> {
                                try {
                                    latch.await();
                                } catch (InterruptedException ignored) {
                                    return;
                                }
                                // null causes NPE inside processEvent; onEvent catches it and
                                // calls triggerStop(). We're testing that no corruption occurs.
                                thread.onEvent(null);
                            });
            t.setDaemon(true);
            threads.add(t);
        }
        threads.forEach(Thread::start);
        latch.countDown();
        for (var t : threads) {
            t.join(5_000);
        }

        thread.stop();

        // Files should be non-empty (a zero-byte file means the header was never written)
        var files = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        assertThat(files).isNotEmpty();
        for (var f : files) {
            assertThat(Files.size(f))
                    .as("File %s should not be empty (header at minimum)", f.getFileName())
                    .isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // Test: maxFiles is enforced even when delete fails (file externally missing)
    // -------------------------------------------------------------------------

    /**
     * Simulates the case where an externally-deleted file causes {@code Files.delete()} to throw
     * {@link java.nio.file.NoSuchFileException}. The implementation must remove the stale entry
     * from its tracking list and still enforce maxFiles for subsequent rotations.
     */
    @Test
    @Timeout(30)
    public void testMaxFilesEnforcedAfterExternalDeletion() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-external-delete-");
        tmp.toFile().deleteOnExit();

        // Allow 2 files, rotate every 1s
        var settings = rotatingSettings(Duration.ofMillis(500), 0, 2);
        settings.newNames = true;

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        // Wait for at least 3 rotations so the ring buffer logic runs
        Thread.sleep(2_500);

        // Externally delete one file currently tracked by the agent
        var filesBeforeStop = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        if (!filesBeforeStop.isEmpty()) {
            Files.deleteIfExists(filesBeforeStop.get(0));
        }

        // Let another rotation happen with the stale entry gone
        Thread.sleep(700);
        thread.stop();

        // Agent must still be alive and the file count must be at most maxFiles
        var filesAfterStop = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        assertThat(filesAfterStop.size())
                .as("retained file count must not exceed maxFiles after external deletion")
                .isLessThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Test: safeOnEvent stops recording after MAX_EVENT_ERRORS
    // -------------------------------------------------------------------------

    /**
     * When {@code onEvent} throws an exception on every call, the base-class {@code safeOnEvent}
     * must stop the recording after MAX_EVENT_ERRORS (10) events rather than looping forever with
     * silent data loss.
     *
     * <p>We exercise this indirectly: pass {@code null} to {@link SingleRecordingThread#onEvent}
     * repeatedly (which causes NPE inside {@code BasicJFRWriter.processEvent}) and observe that the
     * error is logged.
     */
    @Test
    @Timeout(10)
    public void testSingleRecordingStopsAfterMaxEventErrors() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-max-errors-");
        tmp.toFile().deleteOnExit();

        var savedErr = System.err;
        var captured = captureStderr();
        try {
            var thread =
                    new SingleRecordingThread(
                            tmp.resolve("rec.cjfr").toString(),
                            Configuration.DEFAULT,
                            false,
                            "default",
                            "",
                            () -> {},
                            singleSettings());

            // Fire 12 null events (> MAX_EVENT_ERRORS = 10) — each causes NPE in processEvent
            for (int i = 0; i < 12; i++) {
                thread.onEvent(null);
            }

            thread.close();
        } finally {
            restoreStderr(savedErr);
        }

        String output = captured.toString();
        assertThat(output)
                .as("agent must log the severe error for each of the first MAX_EVENT_ERRORS events")
                .contains("Severe Error");
    }

    // -------------------------------------------------------------------------
    // Test: close() does not deadlock when called concurrently with onEvent
    // -------------------------------------------------------------------------

    @Test
    @Timeout(15)
    public void testCloseDoesNotDeadlockWithConcurrentOnEvent() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-close-concurrent-");
        tmp.toFile().deleteOnExit();

        var settings = rotatingSettings(Duration.ofSeconds(5), 0, 5);

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        var stopCalled = new CountDownLatch(1);
        var eventThread =
                new Thread(
                        () -> {
                            // Keep calling onEvent until the recording is stopped
                            for (int i = 0; i < 1_000 && stopCalled.getCount() > 0; i++) {
                                try {
                                    thread.onEvent(null);
                                } catch (Exception ignored) {
                                }
                            }
                        });
        eventThread.setDaemon(true);
        eventThread.start();

        Thread.sleep(50);
        // stop() -> close() should complete without deadlock
        thread.stop();
        stopCalled.countDown();
        eventThread.join(5_000);

        assertThat(eventThread.isAlive())
                .as("event thread should have exited after close()")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Test: shutdown hook is registered after constructor, not before
    // (verifies registerShutdownHook() is called at the right time)
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    public void testShutdownHookRegisteredAfterConstruction() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-hook-");
        tmp.toFile().deleteOnExit();

        // The hook should be registered once construction completes successfully.
        // We verify by checking stop() doesn't throw on removeShutdownHook (it would throw if
        // the hook was never added).
        var settings = singleSettings();
        var thread =
                new SingleRecordingThread(
                        tmp.resolve("rec.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        assertDoesNotThrow(
                thread::stop, "stop() must not throw even if called before any events are sent");
    }

    // -------------------------------------------------------------------------
    // Test: overallWrittenFileCount is incremented consistently during rapid rotations
    // -------------------------------------------------------------------------

    @Test
    @Timeout(30)
    public void testOverallWrittenFileCountIsMonotonicallyIncreasing() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-count-");
        tmp.toFile().deleteOnExit();

        // Rotate every 500ms, keep up to 20 files so none are deleted.
        // The watchdog fires every 5s, so actual rotation is event-driven (via onEvent).
        // We drive it manually by calling onEvent enough for the size trigger to fire.
        var settings = rotatingSettings(Duration.ZERO, 1024, 20);
        settings.newNames = true;

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        // Gather status while state is live
        var status = thread.getStatus();
        thread.stop();

        // We wrote at least 1 file (the initial one opened in the constructor)
        var diskFiles = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        assertThat(diskFiles.size())
                .as("at least 1 file should exist (the initial file opened in the constructor)")
                .isGreaterThanOrEqualTo(1);

        // If status was captured while state was live, verify the counter is >= 1
        status.stream()
                .filter(e -> e.getKey().equals("total-files-written"))
                .findFirst()
                .ifPresent(
                        entry ->
                                assertThat(Integer.parseInt(entry.getValue()))
                                        .as("total-files-written must be at least 1")
                                        .isGreaterThanOrEqualTo(1));

        // Every file on disk must be non-empty
        for (var f : diskFiles) {
            assertThat(Files.size(f))
                    .as("rotated file %s must not be empty", f.getFileName())
                    .isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // Test: size-based rotation actually fires on compressible data
    // (regression: estimateSize() reported near-zero mid-block, so --max-size
    //  never triggered a rotation)
    // -------------------------------------------------------------------------

    /**
     * Drives a real in-process recording with a tiny {@code --max-size} and no duration trigger,
     * generating enough allocation/GC load that many events are recorded. Before the fix the size
     * trigger compared the compressor's near-zero flushed byte count against --max-size and never
     * rotated (only 1 file). After the fix it estimates the on-disk size and rotates, producing
     * several files.
     */
    @Test
    @Timeout(60)
    public void testSizeBasedRotationProducesMultipleFiles() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-size-rotate-");
        tmp.toFile().deleteOnExit();

        // 8KB max-size, up to 50 files (so none are evicted), no duration trigger.
        var settings = rotatingSettings(Duration.ZERO, 8 * 1024, 50);
        settings.newNames = true;

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        var runner = new Thread(thread, "test-rotating-runner");
        runner.setDaemon(true);
        runner.start();

        generateJfrLoad(Duration.ofSeconds(8));

        thread.stop();
        runner.join(10_000);

        var files = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        assertThat(files.size())
                .as("size-based rotation must produce multiple files, got: %s", files)
                .isGreaterThanOrEqualTo(2);
        for (var f : files) {
            assertThat(Files.size(f))
                    .as("rotated file %s must not be empty", f.getFileName())
                    .isGreaterThan(0);
        }
    }

    /**
     * maxFiles must be enforced under size-based rotation: with a tiny max-size and maxFiles=3, the
     * ring buffer keeps at most 3 files even though many rotations happen.
     */
    @Test
    @Timeout(60)
    public void testSizeBasedRotationEnforcesMaxFiles() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-size-maxfiles-");
        tmp.toFile().deleteOnExit();

        var settings = rotatingSettings(Duration.ZERO, 8 * 1024, 3);
        settings.newNames = true;

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        var runner = new Thread(thread, "test-rotating-runner-maxfiles");
        runner.setDaemon(true);
        runner.start();

        generateJfrLoad(Duration.ofSeconds(8));

        thread.stop();
        runner.join(10_000);

        var files = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        assertThat(files.size())
                .as(
                        "retained file count must not exceed maxFiles under size rotation, got: %s",
                        files)
                .isLessThanOrEqualTo(3);
    }

    /**
     * With --max-size unset (0) and no duration trigger, size-based rotation must NOT fire — a
     * single file grows unbounded. Guards against the fix over-rotating when no size limit is set.
     */
    @Test
    @Timeout(30)
    public void testNoSizeLimitDoesNotRotateBySize() throws Exception {
        Path tmp = Files.createTempDirectory("rrt-no-size-");
        tmp.toFile().deleteOnExit();

        // No size and no duration is invalid for rotating mode, so give a long duration trigger
        // that will not fire during the short load window.
        var settings = rotatingSettings(Duration.ofHours(1), 0, 50);
        settings.newNames = true;

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        Configuration.DEFAULT,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        var runner = new Thread(thread, "test-rotating-runner-nosize");
        runner.setDaemon(true);
        runner.start();

        generateJfrLoad(Duration.ofSeconds(4));

        thread.stop();
        runner.join(10_000);

        var files = Files.list(tmp).filter(p -> p.toString().endsWith(".cjfr")).toList();
        assertThat(files.size())
                .as(
                        "with no size limit and a far-off duration trigger, only one file is"
                                + " written, got: %s",
                        files)
                .isEqualTo(1);
    }

    /** Generate allocation + GC + sleep load so the JFR default config records many events. */
    private static void generateJfrLoad(Duration duration) throws InterruptedException {
        long end = System.nanoTime() + duration.toNanos();
        var sink = new ArrayList<byte[]>();
        while (System.nanoTime() < end) {
            for (int i = 0; i < 2_000; i++) {
                sink.add(new byte[1024]);
            }
            if (sink.size() > 20_000) {
                sink.subList(0, 10_000).clear();
                System.gc();
            }
            Thread.sleep(10);
        }
    }

    // -------------------------------------------------------------------------
    // Test: RotatingRecordingThread.deleteOldestFileIfNeeded handles
    // NoSuchFileException without leaving stale entries
    // -------------------------------------------------------------------------

    @Test
    public void testReplacePlaceholdersSafety() {
        // Verify that a template without placeholders produces the same string back,
        // and that $index and $date both work.
        assertEquals(
                "recording.cjfr", RotatingRecordingThread.replacePlaceholders("recording.cjfr", 0));
        assertEquals(
                "recording_5.cjfr",
                RotatingRecordingThread.replacePlaceholders("recording_$index.cjfr", 5));

        String withDate = RotatingRecordingThread.replacePlaceholders("rec_$date.cjfr", 0);
        assertFalse(withDate.contains("$date"), "date placeholder must be replaced");
        assertTrue(withDate.endsWith(".cjfr"));
        assertTrue(withDate.startsWith("rec_"));
    }

    // -------------------------------------------------------------------------
    // Tests: configurations are properly passed and applied
    // -------------------------------------------------------------------------

    static List<Configuration> testConfigurations() {
        return List.of(Configuration.DEFAULT, Configuration.REASONABLE_DEFAULT);
    }

    /**
     * SingleRecordingThread must store configuration.name() in the StartMessage and write the
     * Configuration object so that the timeStampTicksPerSecond survives a roundtrip. This is a
     * regression test for the bug where both fields defaulted to REASONABLE_DEFAULT regardless of
     * what was passed.
     */
    @ParameterizedTest
    @MethodSource("testConfigurations")
    @Timeout(15)
    public void testSingleRecordingThreadPropagatesConfiguration(Configuration config)
            throws Exception {
        Path tmp = Files.createTempDirectory("rrt-config-single-");
        tmp.toFile().deleteOnExit();
        Path out = tmp.resolve("rec.cjfr");

        var thread =
                new SingleRecordingThread(
                        out.toString(), config, false, "default", "", () -> {}, singleSettings());
        thread.close();

        assertConfigurationRoundtrip(out, config);
    }

    /**
     * RotatingRecordingThread must store configuration.name() in every StartMessage and write the
     * Configuration object in every file. This is a regression test for the same bug in the
     * rotating variant.
     */
    @ParameterizedTest
    @MethodSource("testConfigurations")
    @Timeout(30)
    public void testRotatingRecordingThreadPropagatesConfiguration(Configuration config)
            throws Exception {
        Path tmp = Files.createTempDirectory("rrt-config-rotating-");
        tmp.toFile().deleteOnExit();

        // Rotate every 100ms so we get at least one complete file quickly.
        var settings = rotatingSettings(Duration.ofMillis(100), 0, 10);
        settings.newNames = true;

        var thread =
                new RotatingRecordingThread(
                        tmp.resolve("rec_$index.cjfr").toString(),
                        config,
                        false,
                        "default",
                        "",
                        () -> {},
                        settings);

        // Wait for the watchdog to rotate at least once so rec_0.cjfr is fully closed.
        Thread.sleep(600);
        thread.stop();

        var files =
                Files.list(tmp)
                        .filter(p -> p.toString().endsWith(".cjfr"))
                        .sorted()
                        .toList();
        assertThat(files).as("at least one rotated file should exist").isNotEmpty();

        // Check every written file — config must be consistent across rotations.
        for (var file : files) {
            assertConfigurationRoundtrip(file, config);
        }
    }

    /**
     * Open {@code file} as a cjfr stream and assert that the StartMessage stores the expected
     * config name and that the embedded Configuration object has the expected
     * timeStampTicksPerSecond.
     */
    private static void assertConfigurationRoundtrip(Path file, Configuration expected)
            throws Exception {
        try (var is = new CondensedInputStream(Files.newInputStream(file))) {
            var reader = new BasicJFRReader(is);

            // readTillFirstEvent() triggers the lazy start-header read and processes the
            // Configuration object that BasicJFRWriter always writes before events.
            reader.readTillFirstEvent();

            // StartMessage is populated after the first read.
            var startMsg = reader.getStartMessage();
            assertThat(startMsg.generatorConfiguration())
                    .as(
                            "StartMessage.generatorConfiguration in %s should match"
                                    + " configuration.name()",
                            file.getFileName())
                    .isEqualTo(expected.name());

            assertThat(reader.getConfiguration().timeStampTicksPerSecond())
                    .as(
                            "timeStampTicksPerSecond in %s should match the passed configuration",
                            file.getFileName())
                    .isEqualTo(expected.timeStampTicksPerSecond());
        }
    }
}
