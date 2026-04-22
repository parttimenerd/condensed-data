package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class ConfigurationTest {

    /**
     * Bug: eventCombinersEnabled() only checks combinePLABPromotionEvents, but it gates ALL
     * combiner processing in JFREventCombiner.processNewEventType().
     *
     * <p>So if combinePLABPromotionEvents=false but combineObjectAllocationSampleEvents=true, the
     * early return in processNewEventType prevents the ObjectAllocationSample combiner from ever
     * being created — silently ignoring the user's configuration.
     */
    @Test
    public void testEventCombinersEnabledChecksAllCombinerFlags() {
        var config =
                Configuration.DEFAULT
                        .withCombinePLABPromotionEvents(false)
                        .withCombineObjectAllocationSampleEvents(true);

        // If any combiner is enabled, eventCombinersEnabled() should return true
        assertTrue(
                config.eventCombinersEnabled(),
                "eventCombinersEnabled() should be true when"
                        + " combineObjectAllocationSampleEvents=true, even if"
                        + " combinePLABPromotionEvents=false");
    }

    @Test
    public void testEventCombinersEnabledWhenBothTrue() {
        var config =
                Configuration.DEFAULT
                        .withCombinePLABPromotionEvents(true)
                        .withCombineObjectAllocationSampleEvents(true);
        assertTrue(config.eventCombinersEnabled());
    }

    @Test
    public void testEventCombinersDisabledWhenAllFalse() {
        var config =
                Configuration.DEFAULT
                        .withCombinePLABPromotionEvents(false)
                        .withCombineObjectAllocationSampleEvents(false)
                        .withCombineEventsWithoutDataLoss(false);
        assertFalse(config.eventCombinersEnabled());
    }

    // ========== Existing behavior coverage tests ==========

    @Test
    public void testWithFieldValueRoundtrip() {
        var config = Configuration.DEFAULT.withMaxStackTraceDepth(42);
        assertEquals(42, config.maxStackTraceDepth());
        assertEquals("default", config.name());
    }

    @Test
    public void testWithName() {
        var config = Configuration.DEFAULT.withName("custom");
        assertEquals("custom", config.name());
    }

    @Test
    public void testInvalidTimeStampTicksPerSecond() {
        // withFieldValue uses reflection, wrapping IllegalArgumentException in RuntimeException
        assertThrows(
                RuntimeException.class, () -> Configuration.DEFAULT.withTimeStampTicksPerSecond(0));
        assertThrows(
                RuntimeException.class,
                () -> Configuration.DEFAULT.withTimeStampTicksPerSecond(-1));
    }

    @Test
    public void testInvalidDurationTicksPerSecond() {
        assertThrows(
                RuntimeException.class, () -> Configuration.DEFAULT.withDurationTicksPerSecond(0));
    }

    @Test
    public void testInvalidMaxStackTraceDepth() {
        assertThrows(RuntimeException.class, () -> Configuration.DEFAULT.withMaxStackTraceDepth(0));
        assertThrows(
                RuntimeException.class, () -> Configuration.DEFAULT.withMaxStackTraceDepth(-2));
    }

    @Test
    public void testMaxStackTraceDepthUnlimited() {
        var config = Configuration.DEFAULT.withMaxStackTraceDepth(-1);
        assertEquals(-1, config.maxStackTraceDepth());
    }

    @Test
    public void testConfigurationComparable() {
        var a = Configuration.DEFAULT.withName("aaa");
        var b = Configuration.DEFAULT.withName("zzz");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertEquals(0, a.compareTo(a));
    }

    @Test
    public void testPredefinedConfigurations() {
        assertEquals(3, Configuration.configurations.size());
        assertNotNull(Configuration.configurations.get("default"));
        assertNotNull(Configuration.configurations.get("reasonable-default"));
        assertNotNull(Configuration.configurations.get("reduced-default"));
    }
}
