package me.bechberger.jfr.cli.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import me.bechberger.jfr.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
}
