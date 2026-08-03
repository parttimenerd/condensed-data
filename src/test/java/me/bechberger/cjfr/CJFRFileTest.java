package me.bechberger.cjfr;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link CJFRFile}: open, iterate events, field access, metadata. */
public class CJFRFileTest {

    // ---- helpers ----------------------------------------------------------

    @Name("SimpleEvent")
    @Label("Simple Event")
    @Description("Test event for CJFRFile API tests")
    static class SimpleEvent extends Event {
        @Label("Count")
        int count;

        @Label("Message")
        String message;

        SimpleEvent(int count, String msg) {
            this.count = count;
            this.message = msg;
        }
    }

    /** Writes N SimpleEvents to a .cjfr byte array. */
    static byte[] writeCjfrBytes(int n) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var out = new CondensedOutputStream(bos, StartMessage.DEFAULT)) {
            var writer = new BasicJFRWriter(out, Configuration.LOSSLESS);
            try (var rs = new RecordingStream()) {
                rs.enable("SimpleEvent");
                var remaining = new int[] {n};
                rs.onEvent(
                        "SimpleEvent",
                        ev -> {
                            writer.processEvent(ev);
                            remaining[0]--;
                            if (remaining[0] == 0) rs.close();
                        });
                rs.startAsync();
                for (int i = 0; i < n; i++) {
                    var e = new SimpleEvent(i, "msg-" + i);
                    e.commit();
                }
                rs.awaitTermination(Duration.ofSeconds(10));
            }
        }
        return bos.toByteArray();
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void openFromInputStream_iteratesAllEvents() throws Exception {
        int n = 5;
        byte[] bytes = writeCjfrBytes(n);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            List<CJFREvent> events = f.readAllEvents();
            assertThat(events).hasSizeGreaterThanOrEqualTo(n);
            assertThat(events)
                    .allSatisfy(
                            e -> assertThat(e.getEventType().getName()).isEqualTo("SimpleEvent"));
        }
    }

    @Test
    void readEvent_returnsNullAtEOF() throws Exception {
        byte[] bytes = writeCjfrBytes(1);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            // Drain all events
            while (f.readEvent() != null) {}
            assertNull(f.readEvent());
        }
    }

    @Test
    void hasMoreEvents_falseAfterDrain() throws Exception {
        byte[] bytes = writeCjfrBytes(2);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            while (f.readEvent() != null) {}
            assertFalse(f.hasMoreEvents());
        }
    }

    @Test
    void hasMoreEvents_trueWhenEventsRemain() throws Exception {
        byte[] bytes = writeCjfrBytes(3);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            assertThat(f.hasMoreEvents()).isTrue();
        }
    }

    @Test
    void openFromPath_readsEvents(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCjfrBytes(4);
        Path file = tmp.resolve("test.cjfr");
        Files.write(file, bytes);

        try (CJFRFile f = CJFRFile.open(file)) {
            List<CJFREvent> events = f.readAllEvents();
            assertThat(events).hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void getGeneratorConfiguration_returnsName() throws Exception {
        byte[] bytes = writeCjfrBytes(1);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            // Read at least one event so the configuration is parsed
            f.readEvent();
            assertThat(f.getGeneratorConfiguration()).isNotNull().isNotEmpty();
        }
    }

    @Test
    void getStartTime_notNull_afterRead() throws Exception {
        byte[] bytes = writeCjfrBytes(2);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            f.readEvent();
            assertThat(f.getStartTime()).isNotNull();
        }
    }

    @Test
    void getDuration_notNegative_afterRead() throws Exception {
        byte[] bytes = writeCjfrBytes(2);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            f.readAllEvents();
            assertThat(f.getDuration()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @Test
    void getEventTypes_includesSimpleEvent() throws Exception {
        byte[] bytes = writeCjfrBytes(2);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            f.readAllEvents();
            List<String> names = f.getEventTypes().stream().map(CJFREventType::getName).toList();
            assertThat(names).contains("SimpleEvent");
        }
    }

    @Test
    void eventTypeLabel_returnsLabel() throws Exception {
        byte[] bytes = writeCjfrBytes(1);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            f.readAllEvents();
            CJFREventType type =
                    f.getEventTypes().stream()
                            .filter(t -> t.getName().equals("SimpleEvent"))
                            .findFirst()
                            .orElseThrow();
            assertThat(type.getLabel()).isEqualTo("Simple Event");
        }
    }

    @Test
    void eventType_fieldNames_containsExpected() throws Exception {
        byte[] bytes = writeCjfrBytes(1);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            f.readAllEvents();
            CJFREventType type =
                    f.getEventTypes().stream()
                            .filter(t -> t.getName().equals("SimpleEvent"))
                            .findFirst()
                            .orElseThrow();
            assertThat(type.getFieldNames()).contains("count", "message");
        }
    }

    @Test
    void eventFilter_onlyReturnsMatchingTypes() throws Exception {
        byte[] bytes = writeCjfrBytes(3);
        Options opts = Options.defaults().withEventFilter(n -> n.equals("SimpleEvent"));
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes), opts)) {
            List<CJFREvent> events = f.readAllEvents();
            assertThat(events)
                    .allSatisfy(
                            e -> assertThat(e.getEventType().getName()).isEqualTo("SimpleEvent"));
        }
    }

    @Test
    void eventFilter_noMatchingTypes_returnsEmpty() throws Exception {
        byte[] bytes = writeCjfrBytes(2);
        Options opts = Options.defaults().withEventFilter(n -> n.equals("nonexistent.Event"));
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes), opts)) {
            List<CJFREvent> events = f.readAllEvents();
            assertThat(events).isEmpty();
        }
    }

    @Test
    void getPath_nullForInputStream() throws Exception {
        byte[] bytes = writeCjfrBytes(1);
        try (CJFRFile f = CJFRFile.open(new ByteArrayInputStream(bytes))) {
            assertNull(f.getPath());
        }
    }

    @Test
    void getPath_notNullForFile(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCjfrBytes(1);
        Path file = tmp.resolve("test.cjfr");
        Files.write(file, bytes);
        try (CJFRFile f = CJFRFile.open(file)) {
            assertThat(f.getPath()).isEqualTo(file);
        }
    }

    @Test
    void openProfileDefaultCjfr_readsEvents() throws Exception {
        Path file = Path.of("profile_default.cjfr");
        Assumptions.assumeTrue(Files.exists(file), "profile_default.cjfr not found — skipping");

        try (CJFRFile f = CJFRFile.open(file)) {
            List<CJFREvent> events = new ArrayList<>();
            CJFREvent e;
            int count = 0;
            while ((e = f.readEvent()) != null && count < 1000) {
                events.add(e);
                count++;
            }
            assertThat(events).isNotEmpty();
            assertThat(f.getStartTime()).isNotNull();
        }
    }

    @Test
    void openProfileDefaultCjfr_footer(@TempDir Path tmp) throws Exception {
        Path src = Path.of("profile_default.cjfr");
        Assumptions.assumeTrue(Files.exists(src), "profile_default.cjfr not found — skipping");

        try (CJFRFile f = CJFRFile.open(src)) {
            // footer may or may not be present depending on file version
            // getStartTime should work regardless
            assertThat(f.getStartTime()).isNotNull();
        }
    }

    @Test
    void openRecordingCjfr_readsEvents() throws Exception {
        Path file = Path.of("recording.cjfr");
        Assumptions.assumeTrue(Files.exists(file), "recording.cjfr not found — skipping");

        try (CJFRFile f = CJFRFile.open(file)) {
            // File may be truncated; just verify it opens without throwing
            CJFREvent first = f.readEvent();
            if (first != null) {
                assertThat(first.getEventType().getName()).isNotEmpty();
            }
        }
    }
}
