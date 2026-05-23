package me.bechberger.jfr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * Tests for Bug 253: System.exit(1) in EventDeduplication.put() catch block.
 *
 * <p>When a deduplication compared-field does not exist on a particular event instance (e.g. the
 * JFR schema changed between versions), the old code called System.exit(1) which terminates the
 * JVM. The fix returns false (treat as not duplicate) so processing continues gracefully.
 */
public class EventDeduplicationBugTest {

    /**
     * Verifies that deduplication with a non-existent field does not crash the JVM. Before the fix,
     * this would call System.exit(1).
     */
    @Test
    public void testDeduplicationWithMissingFieldDoesNotCrash() throws Exception {
        var dedup = new EventDeduplication();
        // Register dedup for an event type with a field name that won't exist
        dedup.put("jdk.BooleanFlag", "name", "nonExistentField");

        // Process actual events - should not crash
        Path testJfr = Path.of("profile.jfr");
        if (!testJfr.toFile().exists()) {
            return; // skip if test file not available
        }

        assertThatCode(
                        () -> {
                            try (var recording = new RecordingFile(testJfr)) {
                                while (recording.hasMoreEvents()) {
                                    var event = recording.readEvent();
                                    if (event.getEventType().getName().equals("jdk.BooleanFlag")) {
                                        dedup.recordAndCheckIfDuplicate(event);
                                    }
                                }
                            }
                        })
                .doesNotThrowAnyException();
    }

    /**
     * Verifies that the standard JFREventDeduplication works correctly on a real JFR file without
     * crashing (regression test for the System.exit fix).
     */
    @Test
    public void testJFREventDeduplicationDoesNotCrashOnRealFile() throws Exception {
        Path testJfr = Path.of("profile.jfr");
        if (!testJfr.toFile().exists()) {
            return; // skip if test file not available
        }

        var dedup = new JFREventDeduplication(Configuration.DEFAULT);

        int processed = 0;
        try (var recording = new RecordingFile(testJfr)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                dedup.recordAndCheckIfDuplicate(event);
                processed++;
            }
        }

        assertThat(processed).isGreaterThan(0);
    }

    /**
     * Verifies that when a compared field throws IllegalArgumentException, the event is treated as
     * NOT a duplicate (conservative behavior).
     */
    @Test
    public void testMissingFieldTreatedAsNotDuplicate() throws Exception {
        var dedup = new EventDeduplication();
        // Use a field that exists as token but a non-existent comparison field
        dedup.put("jdk.BooleanFlag", "name", "nonExistentField123");

        Path testJfr = Path.of("profile.jfr");
        if (!testJfr.toFile().exists()) {
            return;
        }

        int kept = 0;
        int dropped = 0;
        try (var recording = new RecordingFile(testJfr)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.BooleanFlag")) {
                    continue;
                }
                if (dedup.recordAndCheckIfDuplicate(event)) {
                    dropped++;
                } else {
                    kept++;
                }
            }
        }

        // With a non-existent comparison field, nothing should be detected as duplicate
        // because the comparison returns false on exception
        assertThat(kept).isGreaterThan(0);
        assertThat(dropped).isEqualTo(0);
    }
}
