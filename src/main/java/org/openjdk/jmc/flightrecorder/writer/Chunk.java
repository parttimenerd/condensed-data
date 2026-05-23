/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2025, Datadog, Inc. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The contents of this file are subject to the terms of either the Universal Permissive License
 * v 1.0 as shown at https://oss.oracle.com/licenses/upl
 *
 * or the following license:
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.openjdk.jmc.flightrecorder.writer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * A representation of JFR chunk - self contained set of JFR data.
 *
 * <p>This is an optimized version that reuses the per-event LEB128Writer instead of allocating a
 * new 32KB byte array for every single event. Uses VarHandle to reset the writer's internal pointer
 * without the costly Arrays.fill zero operation.
 */
final class Chunk {
    private static final VarHandle POINTER_HANDLE;
    private static final VarHandle ARRAY_HANDLE;

    static {
        VarHandle ph = null, ah = null;
        try {
            var lookup =
                    MethodHandles.privateLookupIn(
                            LEB128ByteArrayWriter.class, MethodHandles.lookup());
            ph = lookup.findVarHandle(LEB128ByteArrayWriter.class, "pointer", int.class);
            ah = lookup.findVarHandle(LEB128ByteArrayWriter.class, "array", byte[].class);
        } catch (Exception e) {
            // Fall back to creating new writers each time
        }
        POINTER_HANDLE = ph;
        ARRAY_HANDLE = ah;
    }

    private final LEB128Writer writer = LEB128Writer.getInstance();
    private final LEB128Writer eventWriter = LEB128Writer.getInstance();
    private final long startTicks;
    private final long startNanos;

    Chunk() {
        this.startTicks = System.nanoTime();
        this.startNanos = System.currentTimeMillis() * 1_000_000L;
    }

    /** Finalize the chunk. The chunk should not be used after it has been finished. */
    void finish(Consumer<LEB128Writer> completer) {
        completer.accept(writer);
    }

    void writeTypedValue(LEB128Writer writer, TypedValueImpl value) {
        if (value == null) {
            throw new IllegalArgumentException();
        }

        TypeImpl t = value.getType();
        if (t.isBuiltin()) {
            writeBuiltinType(writer, value);
        } else {
            if (value.getType().hasConstantPool()) {
                writer.writeLong(value.getConstantPoolIndex());
            } else {
                writeFields(writer, value);
            }
        }
    }

    private void writeFields(LEB128Writer writer, TypedValueImpl value) {
        for (TypedFieldValueImpl fieldValue : value.getFieldValues()) {
            if (fieldValue.getField().isArray()) {
                writer.writeInt(fieldValue.getValues().length); // array size
                for (TypedValueImpl tValue : fieldValue.getValues()) {
                    writeTypedValue(writer, tValue);
                }
            } else {
                writeTypedValue(writer, fieldValue.getValue());
            }
        }
    }

    private void writeBuiltinType(LEB128Writer writer, TypedValueImpl typedValue) {
        TypeImpl type = typedValue.getType();
        Object value = typedValue.getValue();
        TypesImpl.Builtin builtin = Types.Builtin.ofType(type);
        if (builtin == null) {
            throw new IllegalArgumentException();
        }

        switch (builtin) {
            case STRING:
                {
                    if (value == null) {
                        writer.writeByte((byte) 0);
                    } else if (((String) value).isEmpty()) {
                        writer.writeByte((byte) 1);
                    } else {
                        long idx = typedValue.getConstantPoolIndex();
                        if (idx > Long.MIN_VALUE) {
                            writer.writeByte((byte) 2).writeLong(idx);
                        } else {
                            writer.writeCompactUTF((String) value);
                        }
                    }
                    break;
                }
            case BYTE:
                {
                    writer.writeByte(value == null ? (byte) 0 : (byte) value);
                    break;
                }
            case CHAR:
                {
                    writer.writeChar(value == null ? (char) 0 : (char) value);
                    break;
                }
            case SHORT:
                {
                    writer.writeShort(value == null ? (short) 0 : (short) value);
                    break;
                }
            case INT:
                {
                    writer.writeInt(value == null ? 0 : (int) value);
                    break;
                }
            case LONG:
                {
                    writer.writeLong(value == null ? 0L : (long) value);
                    break;
                }
            case FLOAT:
                {
                    writer.writeFloat(value == null ? 0.0f : (float) value);
                    break;
                }
            case DOUBLE:
                {
                    writer.writeDouble(value == null ? 0.0 : (double) value);
                    break;
                }
            case BOOLEAN:
                {
                    writer.writeBoolean(value != null && (boolean) value);
                    break;
                }
            default:
                {
                    throw new IllegalArgumentException(
                            "Unsupported built-in type " + type.getTypeName());
                }
        }
    }

    void writeEvent(TypedValueImpl event) {
        if (!"jdk.jfr.Event".equals(event.getType().getSupertype())) {
            throw new IllegalArgumentException();
        }

        if (POINTER_HANDLE != null) {
            // Fast path: reuse cached eventWriter, reset pointer without zeroing array
            POINTER_HANDLE.set(eventWriter, 0);

            eventWriter.writeLong(event.getType().getId());
            for (TypedFieldValueImpl fieldValue : event.getFieldValues()) {
                writeTypedValue(eventWriter, fieldValue.getValue());
            }

            int length = AbstractLEB128Writer.adjustLength((int) POINTER_HANDLE.get(eventWriter));
            writer.writeInt(length);
            // Direct array access to avoid export() allocation
            byte[] src = (byte[]) ARRAY_HANDLE.get(eventWriter);
            byte[] slice = new byte[length];
            System.arraycopy(src, 0, slice, 0, length);
            writer.writeBytes(slice);
        } else {
            // Fallback: original behavior (allocates new writer per event)
            LEB128Writer ew = LEB128Writer.getInstance();
            ew.writeLong(event.getType().getId());
            for (TypedFieldValueImpl fieldValue : event.getFieldValues()) {
                writeTypedValue(ew, fieldValue.getValue());
            }
            writer.writeInt(ew.length()).writeBytes(ew.export());
        }
    }

    @Override
    public String toString() {
        return "Chunk [writer="
                + writer
                + ", startTicks="
                + startTicks
                + ", startNanos="
                + startNanos
                + "]";
    }
}
