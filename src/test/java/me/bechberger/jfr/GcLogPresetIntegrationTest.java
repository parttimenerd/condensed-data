package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import org.junit.jupiter.api.Test;

public class GcLogPresetIntegrationTest {

    @Test
    public void gcLogCondenserPresetExists() {
        assertNotNull(Configuration.configurations.get("gc-log"));
        assertEquals("gc-log", Configuration.GC_LOG.name());
    }

    @Test
    public void gcLogJfcLoadsFromClasspath() throws Exception {
        var resource =
                GcLogPresetIntegrationTest.class.getResourceAsStream("/META-INF/jfr/gc-log.jfc");
        assertNotNull(resource, "gc-log.jfc must be present in META-INF/jfr/ on classpath");
        try (resource) {
            var config =
                    jdk.jfr.Configuration.create(
                            new InputStreamReader(resource, StandardCharsets.UTF_8));
            assertEquals("gc-log", config.getLabel());
        }
    }

    @Test
    public void gcLogJfcHasGcEventsEnabled() throws Exception {
        var resource =
                GcLogPresetIntegrationTest.class.getResourceAsStream("/META-INF/jfr/gc-log.jfc");
        assertNotNull(resource);
        try (resource) {
            var config =
                    jdk.jfr.Configuration.create(
                            new InputStreamReader(resource, StandardCharsets.UTF_8));
            var settings = config.getSettings();
            assertEquals(
                    "true",
                    settings.get("jdk.GarbageCollection#enabled"),
                    "jdk.GarbageCollection must be enabled in gc-log.jfc");
            assertEquals(
                    "true",
                    settings.get("jdk.GCPhasePause#enabled"),
                    "jdk.GCPhasePause must be enabled in gc-log.jfc");
            assertEquals(
                    "true",
                    settings.get("jdk.CPULoad#enabled"),
                    "jdk.CPULoad must be enabled in gc-log.jfc");
        }
    }

    @Test
    public void gcLogJfcRecordingStreamStartsWithGcEvents() throws Exception {
        var resource =
                GcLogPresetIntegrationTest.class.getResourceAsStream("/META-INF/jfr/gc-log.jfc");
        assertNotNull(resource);
        jdk.jfr.Configuration jfrConfig;
        try (resource) {
            jfrConfig =
                    jdk.jfr.Configuration.create(
                            new InputStreamReader(resource, StandardCharsets.UTF_8));
        }

        var latch = new CountDownLatch(1);
        try (var rs = new RecordingStream(jfrConfig)) {
            rs.onEvent("jdk.GarbageCollection", e -> latch.countDown());
            rs.setMaxAge(java.time.Duration.ofSeconds(5));
            rs.startAsync();
            System.gc();
            assertTrue(
                    latch.await(10, TimeUnit.SECONDS),
                    "Expected at least one jdk.GarbageCollection event from gc-log.jfc recording");
        }
    }

    @Test
    public void gcLogCondenserPresetRoundTrip() throws Exception {
        var resource =
                GcLogPresetIntegrationTest.class.getResourceAsStream("/META-INF/jfr/gc-log.jfc");
        assertNotNull(resource);
        jdk.jfr.Configuration jfrConfig;
        try (resource) {
            jfrConfig =
                    jdk.jfr.Configuration.create(
                            new InputStreamReader(resource, StandardCharsets.UTF_8));
        }

        var rawOut = new ByteArrayOutputStream();
        var condensedOut = new CondensedOutputStream(rawOut, StartMessage.DEFAULT);
        var writer = new BasicJFRWriter(condensedOut, Configuration.GC_LOG);

        var latch = new CountDownLatch(1);
        try (var rs = new RecordingStream(jfrConfig)) {
            rs.onEvent(
                    e -> {
                        try {
                            writer.processEvent(e);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                        if (e.getEventType().getName().equals("jdk.GarbageCollection")) {
                            latch.countDown();
                        }
                    });
            rs.setMaxAge(java.time.Duration.ofSeconds(5));
            rs.startAsync();
            System.gc();
            latch.await(10, TimeUnit.SECONDS);
        }
        writer.close();

        assertTrue(
                rawOut.size() > 0,
                "BasicJFRWriter with gc-log condenser preset should produce non-empty output");
    }
}
