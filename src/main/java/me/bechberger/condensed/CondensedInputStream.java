package me.bechberger.condensed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps an {@see InputStream} to offer additional methods for reading condensed data.
 * <p>
 * It e.g., supports signed and unsigned varint encoding
 * <p>
 * Unsigned varint format: MSB of each byte is set if there are more bytes to read
 * <p>
 * Signed varint format: Like the unsigned varint but using the second to MSB of the whole int to store the sign
 * <p>
 * String format: The length of the string is encoded as an unsigned varint followed by the string data
 */
public class CondensedInputStream extends InputStream {
    private final InputStream inputStream;

    public CondensedInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    public CondensedInputStream(byte[] data) {
        this(new ByteArrayInputStream(data));
    }

    /**
     * Reads a varint from the stream and returns it as an int.
     * <p>
     * The varint is read from the stream and decoded as an unsigned integer.
     *
     * @return the decoded varint
     */
    public int readUnsignedVarint() {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = read();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Reads a varint from the stream and returns it as an int.
     * @return the decoded varint
     */
    public int readSignedVarint() {
        int unsigned = readUnsignedVarint();
        return (unsigned >>> 1) ^ -(unsigned & 1);
    }

    /**
     * Reads a varlong from the stream and returns it as a long.
     * <p>
     * The varlong is read from the stream and decoded as an unsigned long.
     *
     * @return the decoded varlong
     */
    public long readUnsignedVarlong() {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = read();
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Reads a varlong from the stream and returns it as a long.
     * @return the decoded varlong
     */
    public long readSignedVarlong() {
        long unsigned = readUnsignedVarlong();
        return (unsigned >>> 1) ^ -(unsigned & 1);
    }

    public String readString() {
        int length = readUnsignedVarint();
        if (length == 0) {
            return "";
        }
        if (length < 0) {
            throw new RIOException("Invalid string length: " + length);
        }
        byte[] data = new byte[length];
        try {
            int res = inputStream.read(data);
            if (res != length) {
                throw new RIOException("Unexpected end of stream");
            }
        } catch (IOException e) {
            throw new RIOException(e);
        }
        return new String(data);
    }

    public long readLong() {
        return readUnsignedLong(8);
    }

    public int readInt() {
        return (int) readUnsignedLong(4);
    }

    public short readShort() {
        return (short) readUnsignedLong(2);
    }

    public byte readByte() {
        return (byte) readUnsignedLong(1);
    }


    /**
     * Reads a signed long from the stream encoded with the given number of bytes.
     * @param bytes the number of bytes to read
     * @return the decoded signed long
     */
    public long readSignedLong(long bytes) {
        long result = readUnsignedLong(bytes);
        if (bytes == 8) {
            return result;
        }
        // sign extend
        long signBit = 1L << ((bytes * 8) - 1);
        if ((result & signBit) != 0) { // negative
            result |= -1L << (bytes * 8);
        }
        return result;
    }

    /**
     * Reads an unsigned long from the stream encoded with the given number of bytes.
     * @param bytes the number of bytes to read
     * @return the decoded unsigned long
     */
    public long readUnsignedLong(long bytes) {
        long result = 0;
        for (int i = 0; i < bytes; i++) {
            int b = read();
            if (b < 0) {
                throw new RIOException("Unexpected end of stream");
            }
            result |= (long) (b & 0xFF) << (i * 8);
        }
        return result;
    }

    /**
     * 0 <= percentage <= 1, represented as a fixed width integer
     */
    public double readPercentage(long bytes) {
        return readUnsignedLong(bytes) / Math.pow(2, 8 * bytes);
    }

    @Override
    public int read() {
        try {
            return inputStream.read();
        } catch (IOException e) {
            throw new RIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            inputStream.close();
        } catch (IOException e) {
            throw new RIOException(e);
        }
    }
}
