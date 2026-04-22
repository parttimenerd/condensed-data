package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class RotatingRecordingThreadStaticTest {

    @Test
    public void testContainsPlaceholderWithIndex() {
        assertTrue(RotatingRecordingThread.containsPlaceholder("recording_$index.cjfr"));
    }

    @Test
    public void testContainsPlaceholderWithDate() {
        assertTrue(RotatingRecordingThread.containsPlaceholder("recording_$date.cjfr"));
    }

    @Test
    public void testContainsPlaceholderWithoutPlaceholder() {
        assertFalse(RotatingRecordingThread.containsPlaceholder("recording.cjfr"));
    }

    @Test
    public void testReplacePlaceholdersReplacesIndex() {
        assertEquals(
                "recording_7.cjfr",
                RotatingRecordingThread.replacePlaceholders("recording_$index.cjfr", 7));
    }

    @Test
    public void testReplacePlaceholdersReplacesDate() {
        String replaced = RotatingRecordingThread.replacePlaceholders("recording_$date.cjfr", 1);
        assertTrue(replaced.startsWith("recording_"));
        assertTrue(replaced.endsWith(".cjfr"));
        assertFalse(replaced.contains("$date"));
    }

    @Test
    public void testReplacePlaceholdersReplacesBoth() {
        String replaced =
                RotatingRecordingThread.replacePlaceholders("recording_$date_$index.cjfr", 4);
        assertTrue(replaced.endsWith("_4.cjfr"));
        assertFalse(replaced.contains("$date"));
        assertFalse(replaced.contains("$index"));
    }

    @Test
    public void testReplacePlaceholdersWithoutPlaceholder() {
        assertEquals(
                "recording.cjfr",
                RotatingRecordingThread.replacePlaceholders("recording.cjfr", 99));
    }
}
