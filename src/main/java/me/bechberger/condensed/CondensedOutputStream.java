package me.bechberger.condensed;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Wraps an {@see OutputStream} to offer additional methods for writing condensed data.
 * <p/>
 * Writes data that can be read by {@see CondensedInputStream}
 */
public class CondensedOutputStream extends OutputStream {

    private final OutputStream outputStream;

    public CondensedOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    public static byte[] use(Consumer<CondensedOutputStream> consumer) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CondensedOutputStream condensedOutputStream = new CondensedOutputStream(outputStream);
        consumer.accept(condensedOutputStream);
        return outputStream.toByteArray();
    }

    /**
     * Writes a varint to the stream.
     * <p>
     * The varint is encoded as an unsigned integer.
     *
     * @param value the value to write
     */
    public void writeUnsignedVarint(int value) {
        while ((value & 0xFFFFFF80) != 0) {
            write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        write(value & 0x7F);
    }

    /**
     * Writes a varint to the stream.
     * <p>
     * The varint is encoded as a signed integer.
     *
     * @param value the value to write
     */
    public void writeSignedVarint(int value) {
        writeUnsignedVarint((value << 1) ^ (value >> 31));
    }

    /**
     * Writes a varlong to the stream.
     * <p>
     * The varlong is encoded as an unsigned long.
     *
     * @param value the value to write
     */
    public void writeUnsignedVarlong(long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
            write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        write((int) (value & 0x7F));
    }

    /**
     * Writes a varlong to the stream.
     * <p>
     * The varlong is encoded as a signed long.
     *
     * @param value the value to write
     */
    public void writeSignedVarlong(long value) {
        writeUnsignedVarlong((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a string to the stream.
     * @param value string to write
     */
    public void writeString(String value) {
        byte[] bytes = value.getBytes();
        writeUnsignedVarint(bytes.length);
        try {
            write(bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new RIOException(e);
        }
    }

    public enum OverflowMode {
        /** Throw an IllegalArgumentException exception if the value is too large */
        ERROR,
        /** If the value is too large, return the maximum possible value, similar with too small values */
        SATURATE
    }

    public void writeLong(long value) {
        writeLong(value, 8);
    }

    public void writeInt(int value) {
        writeLong(value, 4);
    }

    public void writeShort(short value) {
        writeLong(value, 2);
    }

    public void writeByte(byte value) {
        writeLong(value, 1);
    }

    /**
     * Internal method to write a long to the stream as a fixed width integer.
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8, cut off the higher bits
     */
    private void writeLong(long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            write((int) (value & 0xFF));
            value >>>= 8;
        }
    }

    /**
     * Returns a bitmask that can be used to mask the lower n bytes of a long.
     */
    long bitMask(int bytes) {
        return (1L << (bytes * 8)) - 1;
    }

    /**
     * Writes an unsigned long to the stream as a fixed width integer.
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8
     * @param mode the overflow mode
     */
    public void writeUnsignedLong(long value, int bytes, OverflowMode mode) {
        if (bytes < 1 || bytes > 8) {
            throw new IllegalArgumentException("Number of bytes must be between 1 and 8");
        }
        if (bytes == 8) { // we can write every number
            writeLong(value, bytes);
        } else {
            long maxValue = bitMask(bytes);
            if (value < 0 || value > maxValue) {
                if (mode == OverflowMode.ERROR) {
                    throw new IllegalArgumentException("Value " + value + " does not fit into " + bytes + " bytes");
                } else {
                    value = Long.compareUnsigned(value, maxValue) > 0 ? maxValue : value;
                }
            }
            writeLong(value, bytes);
        }
    }

    /**
     * Writes an signed long to the stream as a fixed width integer.
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8
     * @param mode the overflow and underflow mode
     */
    public void writeSignedLong(long value, int bytes, OverflowMode mode) {
        if (bytes < 1 || bytes > 8) {
            throw new IllegalArgumentException("Number of bytes must be between 1 and 8");
        }
        if (bytes == 8) { // we can write every number
            writeLong(value, bytes);
        } else {
            long maxValue = (1L << (8 * bytes - 1)) - 1;
            long minValue = -(1L << (8 * bytes - 1));
            if (value < minValue || value > maxValue) {
                if (mode == OverflowMode.ERROR) {
                    throw new IllegalArgumentException("Value " + value + " does not fit into " + bytes + " bytes");
                } else {
                    value = Math.max(minValue, Math.min(maxValue, value));
                }
            }
            writeLong(value, bytes);
        }
    }

    /**
     * Writes a 0 <= percentage <= 1 to the stream as a fixed width integer.
     * @param value the percentage to write
     * @param bytes width of the integer
     * @param mode the overflow mode
     */
    public void writePercentage(double value, int bytes, OverflowMode mode) {
        if (value < 0 || value > 1) {
            if (mode == OverflowMode.ERROR) {
                throw new IllegalArgumentException("Value " + value + " is not a percentage");
            } else {
                value = Math.max(0, Math.min(1, value));
            }
        }
        double scaled = value * Math.pow(2, 8 * bytes);
        writeUnsignedLong(Math.round(scaled), bytes, OverflowMode.SATURATE);
    }

    @Override
    public void write(int b) {
        try {
            outputStream.write(b);
        } catch (IOException e) {
            throw new RIOException(e);
        }
    }

    @Override
    public void write(byte @NotNull [] b) {
        try {
            outputStream.write(b);
        } catch (IOException e) {
            throw new RIOException(e);
        }
    }
}
