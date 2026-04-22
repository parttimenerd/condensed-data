package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

public class DynamicallyChangeableSettingsTest {

    private DynamicallyChangeableSettings createSettings() {
        var s = new DynamicallyChangeableSettings();
        s.maxDuration = Duration.ZERO;
        s.maxSize = 0;
        s.maxFiles = 10;
        s.newNames = false;
        s.duration = Duration.ZERO;
        return s;
    }

    /**
     * Bug: The validation check {@code maxDuration.toMillis() > 0 && maxDuration.toMillis() < 1} is
     * always false because toMillis() returns a long, and a long > 0 is always >= 1.
     *
     * <p>This means sub-millisecond durations (e.g., 500us) pass as toMillis() == 0, which is
     * treated as "no limit" — silently ignoring the user's intent.
     *
     * <p>Same issue with the duration field validation.
     */
    @Test
    public void testSubMillisecondMaxDurationNotSilentlyIgnored() {
        var settings = createSettings();
        settings.maxDuration = Duration.ofNanos(500_000); // 500 microseconds
        // This should either reject the sub-ms duration or handle it properly
        // Currently: toMillis() returns 0, treated as "no limit" — user's 500us config is silently
        // lost
        assertThrows(
                DynamicallyChangeableSettings.ValidationException.class,
                () -> settings.validate(true),
                "Sub-millisecond maxDuration should be rejected or handled, not silently treated as"
                        + " 0 (no limit)");
    }

    @Test
    public void testSubMillisecondDurationNotSilentlyIgnored() {
        var settings = createSettings();
        settings.duration = Duration.ofNanos(500_000); // 500 microseconds
        assertThrows(
                DynamicallyChangeableSettings.ValidationException.class,
                () -> settings.validate(false),
                "Sub-millisecond duration should be rejected or handled, not silently treated as 0"
                        + " (no limit)");
    }

    // ========== Passing validation tests ==========

    @Test
    public void testValidDefaultSettings() {
        var settings = createSettings();
        assertDoesNotThrow(() -> settings.validate(false));
    }

    @Test
    public void testValidRotatingSettings() {
        var settings = createSettings();
        settings.maxDuration = Duration.ofSeconds(10);
        settings.maxSize = 1024 * 1024; // 1MB
        settings.maxFiles = 5;
        assertDoesNotThrow(() -> settings.validate(true));
    }

    @Test
    public void testMaxSizeTooSmall() {
        var settings = createSettings();
        settings.maxSize = 512; // < 1kB
        assertThrows(
                DynamicallyChangeableSettings.ValidationException.class,
                () -> settings.validate(true));
    }

    @Test
    public void testNegativeMaxFiles() {
        var settings = createSettings();
        settings.maxFiles = -1;
        assertThrows(
                DynamicallyChangeableSettings.ValidationException.class,
                () -> settings.validate(true));
    }

    @Test
    public void testNegativeMaxSize() {
        var settings = createSettings();
        settings.maxSize = -1;
        var exception =
                assertThrows(
                        DynamicallyChangeableSettings.ValidationException.class,
                        () -> settings.validate(true));
        assertTrue(exception.getMessage().contains("Max size must be at least 0"));
    }

    @Test
    public void testMultipleValidationErrorsAreCombined() {
        var settings = createSettings();
        settings.maxFiles = -1;
        settings.maxSize = 512;
        var exception =
                assertThrows(
                        DynamicallyChangeableSettings.ValidationException.class,
                        () -> settings.validate(true));
        assertTrue(exception.getMessage().contains("Max files must be at least 0"));
        assertTrue(exception.getMessage().contains("Max size must be at least 1kB"));
    }

    @Test
    public void testValidationExceptionFromListJoinsMessages() {
        var exception =
                new DynamicallyChangeableSettings.ValidationException(
                        java.util.List.of("first", "second"));
        assertTrue(exception.getMessage().contains("first"));
        assertTrue(exception.getMessage().contains("second"));
    }

    @Test
    public void testMaxDurationNotAllowedWhenNotRotating() {
        var settings = createSettings();
        settings.maxDuration = Duration.ofSeconds(10);
        assertThrows(
                DynamicallyChangeableSettings.ValidationException.class,
                () -> settings.validate(false),
                "Max duration should only be allowed when rotating");
    }

    @Test
    public void testMaxSizeNotAllowedWhenNotRotating() {
        var settings = createSettings();
        settings.maxSize = 1024 * 1024;
        assertThrows(
                DynamicallyChangeableSettings.ValidationException.class,
                () -> settings.validate(false),
                "Max size should only be allowed when rotating");
    }
}
