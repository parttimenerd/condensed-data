package me.bechberger.jfr;

import org.openjdk.jmc.flightrecorder.writer.RecordingImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedValueImpl;

/**
 * Thin wrapper that routes event writes through the optimized local {@code Chunk.writeEvent}.
 *
 * <p>All per-event allocation elimination (reusing the event buffer, avoiding {@code
 * getFieldValues()} ArrayList, avoiding {@code getValues()} defensive copies, and direct
 * System.arraycopy into the main writer's backing array) is now handled entirely inside {@code
 * Chunk.writeEvent} via VarHandle reflection. This class exists only to preserve the call site in
 * {@link WritingJFRReader}.
 */
final class FastChunkWriter {

    void writeEvent(RecordingImpl recording, TypedValueImpl event) {
        recording.writeEvent(event);
    }
}
