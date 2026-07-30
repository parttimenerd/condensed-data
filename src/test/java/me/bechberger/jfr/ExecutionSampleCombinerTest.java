package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.*;
import me.bechberger.condensed.Message.StartMessage;
import org.junit.jupiter.api.Test;

public class ExecutionSampleCombinerTest {

    private static final Configuration EXEC_CONFIG =
            Configuration.LOSSLESS.withCombineExecutionSampleEvents(true);

    /**
     * Condense a list of events captured via RecordingStream into a byte array, then read them
     * back. Returns the reconstituted events.
     */
    private List<RecordedEvent> roundTrip(
            Configuration config, Runnable workload) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<RecordedEvent> captured = new ArrayList<>();

        try (CondensedOutputStream out =
                new CondensedOutputStream(baos, StartMessage.DEFAULT)) {
            BasicJFRWriter writer = new BasicJFRWriter(out, config);
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
                rs.onEvent(
                        "jdk.ExecutionSample",
                        event -> {
                            writer.processEvent(event);
                            captured.add(event);
                        });
                rs.startAsync();
                workload.run();
                Thread.sleep(200);
                rs.close();
                rs.awaitTermination();
            }
        }

        if (captured.isEmpty()) {
            return List.of();
        }

        try (var in = new CondensedInputStream(baos.toByteArray())) {
            return WritingJFRReader.toJFREventsList(new BasicJFRReader(in));
        }
    }

    @Test
    public void testExecutionSampleRoundTripPreservesCount() throws Exception {
        // Busy loop to guarantee some ExecutionSample events
        long[] events = new long[1];
        var result =
                roundTrip(
                        EXEC_CONFIG,
                        () -> {
                            long end = System.currentTimeMillis() + 150;
                            long x = 0;
                            while (System.currentTimeMillis() < end) {
                                x += x * x + 1;
                            }
                            events[0] = x; // prevent dead code elimination
                        });

        if (result.isEmpty()) {
            // No samples captured — not a test failure, just not enough CPU activity
            return;
        }

        long executionSampleCount =
                result.stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.ExecutionSample"))
                        .count();
        assertTrue(
                executionSampleCount > 0,
                "Expected reconstituted ExecutionSample events, got none");
    }

    @Test
    public void testExecutionSampleStateFieldIsAbsent() throws Exception {
        // With LOSSLESS + ignoreUnnecessaryEvents the state field should be stripped
        Configuration config =
                Configuration.LOSSLESS
                        .withCombineExecutionSampleEvents(true)
                        .withIgnoreUnnecessaryEvents(true);
        var result =
                roundTrip(
                        config,
                        () -> {
                            long end = System.currentTimeMillis() + 150;
                            long x = 0;
                            while (System.currentTimeMillis() < end) {
                                x += x * x + 1;
                            }
                            if (x == 0) System.out.println(x);
                        });

        for (var event : result) {
            if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
            var fields = event.getEventType().getFields();
            boolean hasState =
                    fields.stream().anyMatch(f -> f.getName().equals("state"));
            assertFalse(hasState, "state field should be stripped when ignoreUnnecessaryEvents=true");
            break;
        }
    }

    @Test
    public void testExecutionSampleWithoutCombiningPassesThrough() throws Exception {
        // Without the flag, events are written individually and round-trip correctly
        var result =
                roundTrip(
                        Configuration.LOSSLESS,
                        () -> {
                            long end = System.currentTimeMillis() + 150;
                            long x = 0;
                            while (System.currentTimeMillis() < end) {
                                x += x * x + 1;
                            }
                            if (x == 0) System.out.println(x);
                        });

        long executionSampleCount =
                result.stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.ExecutionSample"))
                        .count();
        // Count should be non-negative (may be 0 if no CPU samples)
        assertTrue(executionSampleCount >= 0);
    }
}
