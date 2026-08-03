package me.bechberger.cjfr;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link CJFRFiles}: multi-file merging. */
public class CJFRFilesTest {

    @Name("CounterEvent")
    @Label("Counter")
    static class CounterEvent extends Event {
        @Label("Value")
        int value;

        CounterEvent(int v) {
            this.value = v;
        }
    }

    static byte[] writeCounterEvents(int start, int count) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var out = new CondensedOutputStream(bos, StartMessage.DEFAULT)) {
            var writer = new BasicJFRWriter(out, Configuration.LOSSLESS);
            try (var rs = new RecordingStream()) {
                rs.enable("CounterEvent");
                var remaining = new int[] {count};
                rs.onEvent(
                        "CounterEvent",
                        ev -> {
                            writer.processEvent(ev);
                            remaining[0]--;
                            if (remaining[0] == 0) rs.close();
                        });
                rs.startAsync();
                for (int i = start; i < start + count; i++) {
                    new CounterEvent(i).commit();
                    Thread.sleep(1);
                }
                rs.awaitTermination(Duration.ofSeconds(10));
            }
        }
        return bos.toByteArray();
    }

    @Test
    void openTwoFiles_readsAllEvents(@TempDir Path tmp) throws Exception {
        byte[] bytes1 = writeCounterEvents(0, 3);
        byte[] bytes2 = writeCounterEvents(10, 3);

        Path f1 = tmp.resolve("part0.cjfr");
        Path f2 = tmp.resolve("part1.cjfr");
        Files.write(f1, bytes1);
        Files.write(f2, bytes2);

        try (CJFRFiles files = CJFRFiles.open(List.of(f1, f2))) {
            List<CJFREvent> events = files.readAllEvents();
            assertThat(events).hasSizeGreaterThanOrEqualTo(6);
        }
    }

    @Test
    void getStartTime_notNull(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCounterEvents(0, 2);
        Path f = tmp.resolve("test.cjfr");
        Files.write(f, bytes);

        try (CJFRFiles files = CJFRFiles.open(List.of(f))) {
            assertThat(files.getStartTime()).isNotNull();
        }
    }

    @Test
    void getEndTime_notNull(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCounterEvents(0, 2);
        Path f = tmp.resolve("test.cjfr");
        Files.write(f, bytes);

        try (CJFRFiles files = CJFRFiles.open(List.of(f))) {
            // Read all so end time is resolved
            files.readAllEvents();
            assertThat(files.getEndTime()).isNotNull();
        }
    }

    @Test
    void getDuration_nonNegative(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCounterEvents(0, 2);
        Path f = tmp.resolve("test.cjfr");
        Files.write(f, bytes);

        try (CJFRFiles files = CJFRFiles.open(List.of(f))) {
            files.readAllEvents();
            assertThat(files.getDuration()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @Test
    void hasMoreEvents_trueBeforeRead(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCounterEvents(0, 2);
        Path f = tmp.resolve("test.cjfr");
        Files.write(f, bytes);

        try (CJFRFiles files = CJFRFiles.open(List.of(f))) {
            assertThat(files.hasMoreEvents()).isTrue();
        }
    }

    @Test
    void hasMoreEvents_falseAfterDrain(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCounterEvents(0, 2);
        Path f = tmp.resolve("test.cjfr");
        Files.write(f, bytes);

        try (CJFRFiles files = CJFRFiles.open(List.of(f))) {
            files.readAllEvents();
            assertThat(files.hasMoreEvents()).isFalse();
        }
    }

    @Test
    void eventFilter_restrictsByType(@TempDir Path tmp) throws Exception {
        byte[] bytes = writeCounterEvents(0, 3);
        Path f = tmp.resolve("test.cjfr");
        Files.write(f, bytes);

        Options opts = Options.defaults().withEventFilter(n -> n.equals("CounterEvent"));
        try (CJFRFiles files = CJFRFiles.open(List.of(f), opts)) {
            List<CJFREvent> events = files.readAllEvents();
            assertThat(events)
                    .allSatisfy(
                            e -> assertThat(e.getEventType().getName()).isEqualTo("CounterEvent"));
        }
    }

    @Test
    void openEmpty_throwsIllegalArgument(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> CJFRFiles.open(List.of()));
    }
}
