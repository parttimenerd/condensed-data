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
                Configuration.LOSSLESS
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
                Configuration.LOSSLESS
                        .withCombinePLABPromotionEvents(true)
                        .withCombineObjectAllocationSampleEvents(true);
        assertTrue(config.eventCombinersEnabled());
    }

    @Test
    public void testEventCombinersDisabledWhenAllFalse() {
        var config =
                Configuration.LOSSLESS
                        .withCombinePLABPromotionEvents(false)
                        .withCombineObjectAllocationSampleEvents(false)
                        .withCombineEventsWithoutDataLoss(false)
                        .withCombineG1HeapRegionTypeChangeEvents(false)
                        .withCombineExceptionEvents(false)
                        .withCombineBlockingEvents(false)
                        .withCombineThreadParkLossless(false);
        assertFalse(config.eventCombinersEnabled());
    }

    // ========== Existing behavior coverage tests ==========

    @Test
    public void testWithFieldValueRoundtrip() {
        var config = Configuration.LOSSLESS.withMaxStackTraceDepth(42);
        assertEquals(42, config.maxStackTraceDepth());
        assertEquals("lossless", config.name());
    }

    @Test
    public void testWithName() {
        var config = Configuration.LOSSLESS.withName("custom");
        assertEquals("custom", config.name());
    }

    @Test
    public void testInvalidTimeStampTicksPerSecond() {
        // withFieldValue uses reflection, wrapping IllegalArgumentException in RuntimeException
        assertThrows(
                RuntimeException.class,
                () -> Configuration.LOSSLESS.withTimeStampTicksPerSecond(0));
        assertThrows(
                RuntimeException.class,
                () -> Configuration.LOSSLESS.withTimeStampTicksPerSecond(-1));
    }

    @Test
    public void testInvalidDurationTicksPerSecond() {
        assertThrows(
                RuntimeException.class, () -> Configuration.LOSSLESS.withDurationTicksPerSecond(0));
    }

    @Test
    public void testInvalidMaxStackTraceDepth() {
        assertThrows(
                RuntimeException.class, () -> Configuration.LOSSLESS.withMaxStackTraceDepth(0));
        assertThrows(
                RuntimeException.class, () -> Configuration.LOSSLESS.withMaxStackTraceDepth(-2));
    }

    @Test
    public void testMaxStackTraceDepthUnlimited() {
        var config = Configuration.LOSSLESS.withMaxStackTraceDepth(-1);
        assertEquals(-1, config.maxStackTraceDepth());
    }

    @Test
    public void testConfigurationComparable() {
        var a = Configuration.LOSSLESS.withName("aaa");
        var b = Configuration.LOSSLESS.withName("zzz");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertEquals(0, a.compareTo(a));
    }

    @Test
    public void testPredefinedConfigurations() {
        assertEquals(3, Configuration.configurations.size());
        assertNotNull(Configuration.configurations.get("lossless"));
        assertNotNull(Configuration.configurations.get("default"));
        assertNotNull(Configuration.configurations.get("reduced"));
    }

    @Test
    public void testLosslessMatchesDefault() {
        // "lossless" is an alias for the lossless base config, differing only in name.
        assertEquals(Configuration.LOSSLESS.withName("lossless"), Configuration.LOSSLESS);
        assertEquals(Configuration.LOSSLESS, Configuration.configurations.get("lossless"));
    }
}
