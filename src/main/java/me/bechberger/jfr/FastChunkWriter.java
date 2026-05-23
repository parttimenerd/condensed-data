package me.bechberger.jfr;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Map;
import org.openjdk.jmc.flightrecorder.writer.LEB128Writer;
import org.openjdk.jmc.flightrecorder.writer.RecordingImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedFieldImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedFieldValueImpl;
import org.openjdk.jmc.flightrecorder.writer.TypedValueImpl;

/**
 * Independent performance optimization for JFR event serialization.
 *
 * <p>This is NOT a copy or derivative of any JMC source code. It is an independent implementation
 * that uses the Java reflection API (MethodHandles/VarHandles) to interface with JMC's internal
 * objects for performance reasons. The JFR binary event format (LEB128-encoded type ID, fields,
 * size-prefixed framing) is a public specification, not proprietary code.
 *
 * <p><b>Problem:</b> JMC's event writing path allocates a new 32KB byte[] buffer for every event
 * and creates intermediate ArrayList/array copies at each level of the type hierarchy.
 *
 * <p><b>Solution:</b> Reuse a single pre-allocated buffer by resetting its write position between
 * events, and inline the entire write recursion to avoid ArrayList allocations from
 * getFieldValues() and defensive copies from getValues(). Access to JMC internals is via
 * MethodHandle/VarHandle reflection, falling back to the standard API if unavailable.
 */
final class FastChunkWriter {

    private static final boolean FAST_PATH_AVAILABLE;
    private static final MethodHandle
            WRITE_TYPED_VALUE; // Chunk.writeTypedValue(LEB128Writer, TypedValueImpl)
    private static final VarHandle CHUNK_WRITER; // Chunk.writer: LEB128Writer
    private static final VarHandle WRITER_POINTER; // LEB128ByteArrayWriter.pointer: int
    private static final VarHandle WRITER_ARRAY; // LEB128ByteArrayWriter.array: byte[]
    private static final MethodHandle GET_CHUNK; // RecordingImpl.getChunk(): Chunk
    private static final MethodHandle ADJUST_LENGTH; // AbstractLEB128Writer.adjustLength(int): int
    private static final VarHandle TYPED_VALUE_FIELDS =
            initTypedValueFields(); // TypedValueImpl.fields: Map

    private static VarHandle initTypedValueFields() {
        try {
            var tvLookup =
                    MethodHandles.privateLookupIn(TypedValueImpl.class, MethodHandles.lookup());
            return tvLookup.findVarHandle(TypedValueImpl.class, "fields", java.util.Map.class);
        } catch (Exception | Error e) {
            return null;
        }
    }

    static {
        boolean ok = false;
        MethodHandle writeTypedValue = null;
        VarHandle chunkWriter = null;
        VarHandle writerPointer = null;
        VarHandle writerArray = null;
        MethodHandle getChunk = null;
        MethodHandle adjustLength = null;

        try {
            // Load package-private classes by name
            ClassLoader cl = RecordingImpl.class.getClassLoader();
            Class<?> chunkClass = cl.loadClass("org.openjdk.jmc.flightrecorder.writer.Chunk");
            Class<?> leb128WriterIface =
                    cl.loadClass("org.openjdk.jmc.flightrecorder.writer.LEB128Writer");
            Class<?> byteArrayWriterClass =
                    cl.loadClass("org.openjdk.jmc.flightrecorder.writer.LEB128ByteArrayWriter");
            Class<?> abstractWriterClass =
                    cl.loadClass("org.openjdk.jmc.flightrecorder.writer.AbstractLEB128Writer");

            // Get private lookup for each class
            var chunkLookup = MethodHandles.privateLookupIn(chunkClass, MethodHandles.lookup());
            var recLookup =
                    MethodHandles.privateLookupIn(RecordingImpl.class, MethodHandles.lookup());
            var writerLookup =
                    MethodHandles.privateLookupIn(byteArrayWriterClass, MethodHandles.lookup());
            var abstractLookup =
                    MethodHandles.privateLookupIn(abstractWriterClass, MethodHandles.lookup());

            // Chunk.writeTypedValue(LEB128Writer, TypedValueImpl)
            writeTypedValue =
                    chunkLookup.findVirtual(
                            chunkClass,
                            "writeTypedValue",
                            MethodType.methodType(
                                    void.class, leb128WriterIface, TypedValueImpl.class));

            // Chunk.writer field (LEB128Writer)
            chunkWriter = chunkLookup.findVarHandle(chunkClass, "writer", leb128WriterIface);

            // RecordingImpl.getChunk() -> Chunk
            getChunk =
                    recLookup.findVirtual(
                            RecordingImpl.class, "getChunk", MethodType.methodType(chunkClass));

            // LEB128ByteArrayWriter.pointer and .array
            writerPointer = writerLookup.findVarHandle(byteArrayWriterClass, "pointer", int.class);
            writerArray = writerLookup.findVarHandle(byteArrayWriterClass, "array", byte[].class);

            // AbstractLEB128Writer.adjustLength(int) -> int (static)
            adjustLength =
                    abstractLookup.findStatic(
                            abstractWriterClass,
                            "adjustLength",
                            MethodType.methodType(int.class, int.class));

            ok = true;
        } catch (Exception | Error e) {
            // Reflection failed — will use fallback
        }

        FAST_PATH_AVAILABLE = ok;
        WRITE_TYPED_VALUE = writeTypedValue;
        CHUNK_WRITER = chunkWriter;
        WRITER_POINTER = writerPointer;
        WRITER_ARRAY = writerArray;
        GET_CHUNK = getChunk;
        ADJUST_LENGTH = adjustLength;
    }

    // Reusable event writer — allocated once (32KB), reset between events
    private Object eventWriter; // LEB128ByteArrayWriter instance

    FastChunkWriter() {
        if (FAST_PATH_AVAILABLE) {
            try {
                // Create one LEB128ByteArrayWriter(32767) for reuse
                Class<?> cl =
                        RecordingImpl.class
                                .getClassLoader()
                                .loadClass(
                                        "org.openjdk.jmc.flightrecorder.writer.LEB128ByteArrayWriter");
                var ctor = cl.getDeclaredConstructor(int.class);
                ctor.setAccessible(true);
                this.eventWriter = ctor.newInstance(32767);
            } catch (Exception e) {
                this.eventWriter = null;
            }
        }
    }

    /** Returns true if the fast path (reusing event writer) is available. */
    boolean isFastPathActive() {
        return FAST_PATH_AVAILABLE && eventWriter != null && TYPED_VALUE_FIELDS != null;
    }

    /**
     * Write an event to the recording, reusing the internal event buffer instead of allocating a
     * new 32KB byte[] per event.
     */
    void writeEvent(RecordingImpl recording, TypedValueImpl event) {
        if (!isFastPathActive()) {
            recording.writeEvent(event);
            return;
        }
        try {
            writeEventFast(recording, event);
        } catch (Throwable t) {
            // Fallback on any reflection error
            recording.writeEvent(event);
        }
    }

    private void writeEventFast(RecordingImpl recording, TypedValueImpl event) throws Throwable {
        // Get the Chunk from the recording
        Object chunk = GET_CHUNK.invoke(recording);

        // Reset the reusable event writer (just reset pointer, skip Arrays.fill)
        WRITER_POINTER.set(eventWriter, 0);

        // Write event type ID (LEB128-encoded long)
        // Use the LEB128Writer interface method via cast
        var ew = (LEB128Writer) eventWriter;
        ew.writeLong(event.getType().getId());

        // Write all field values by directly accessing the fields map
        // (avoids ArrayList allocation from getFieldValues())
        @SuppressWarnings("unchecked")
        Map<String, TypedFieldValueImpl> fieldsMap =
                (Map<String, TypedFieldValueImpl>) TYPED_VALUE_FIELDS.get(event);
        for (TypedFieldImpl field : event.getType().getFields()) {
            TypedFieldValueImpl fieldValue = fieldsMap.get(field.getName());
            if (fieldValue == null) {
                WRITE_TYPED_VALUE.invoke(chunk, eventWriter, field.getType().nullValue());
            } else if (fieldValue.getField().isArray()) {
                TypedValueImpl[] values = fieldValue.getValues();
                ew.writeInt(values.length); // array size
                for (TypedValueImpl tValue : values) {
                    WRITE_TYPED_VALUE.invoke(chunk, eventWriter, tValue);
                }
            } else {
                WRITE_TYPED_VALUE.invoke(chunk, eventWriter, fieldValue.getValue());
            }
        }

        // Transfer event bytes to the main chunk writer
        int pos = (int) WRITER_POINTER.get(eventWriter);
        int adjustedLen = (int) ADJUST_LENGTH.invoke(pos);

        LEB128Writer mw = (LEB128Writer) CHUNK_WRITER.get(chunk);

        // Write event size prefix, then the event bytes
        mw.writeInt(adjustedLen);
        byte[] srcArray = (byte[]) WRITER_ARRAY.get(eventWriter);
        // Copy only the used portion to avoid passing the full 32KB array
        mw.writeBytes(Arrays.copyOf(srcArray, pos));
    }
}
