package me.bechberger.condensed;

import static me.bechberger.condensed.CompletableContainer.ensureRecursivelyComplete;
import static me.bechberger.condensed.Constants.START_STRING;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import me.bechberger.condensed.Message.CondensedTypeMessage;
import me.bechberger.condensed.Message.ReadInstance;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.RIOException.NoStartStringException;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.Reductions;
import me.bechberger.condensed.types.TypeCollection;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps an {@see InputStream} to offer additional methods for reading condensed data.
 *
 * <p>It e.g., supports signed and unsigned varint encoding
 *
 * <p>Unsigned varint format: MSB of each byte is set if there are more bytes to read
 *
 * <p>Signed varint format: Like the unsigned varint but using the second to MSB of the whole int to
 * store the sign
 *
 * <p>String format: The length of the string is encoded as an unsigned varint followed by the
 * string data
 */
public class CondensedInputStream extends InputStream {

    private final Universe universe;
    private final TypeCollection typeCollection;
    private InputStream inputStream;
    private Reductions reductions = Reductions.NONE;
    private boolean startStringRead = false;

    public CondensedInputStream(InputStream inputStream) {
        this.universe = new Universe();
        this.typeCollection = new TypeCollection();
        this.inputStream = inputStream;
    }

    public CondensedInputStream(byte[] data) {
        this(new ByteArrayInputStream(data));
    }

    public TypeCollection getTypeCollection() {
        return typeCollection;
    }

    /**
     * Reads the next message (collects type specifications) and returns it
     *
     * @return returns the read message
     */
    public @Nullable Message readNextMessageAndProcess() {
        if (!startStringRead) {
            readAndProcessStartString();
        }
        int typeId = (int) readUnsignedVarintOrEnd();
        if (typeId == -1) {
            return null;
        }
        if (TypeCollection.isSpecifiedType(typeId)) {
            return readAndProcessSpecifiedTypeMessage(typeId);
        }
        if (typeCollection.hasType(typeId)) {
            return readAndProcessInstanceMessage(typeId);
        }
        throw new TypeCollection.NoSuchTypeException(typeId);
    }

    /**
     * Reads the next instance message and returns it
     *
     * @return message read or null if at end of stream
     */
    public @Nullable ReadInstance<?, ?> readNextInstance() {
        while (true) {
            Message message = readNextMessageAndProcess();
            if (message == null) {
                return null;
            }
            if (message instanceof ReadInstance<?, ?> instance) {
                return instance;
            }
        }
    }

    @SuppressWarnings("unchecked")
    <T, R> @Nullable CondensedType<T, R> readNextTypeMessageAndProcess() {
        var msg = readNextMessageAndProcess();
        return msg == null ? null : (CondensedType<T, R>) ((CondensedTypeMessage) msg).type();
    }

    private CondensedTypeMessage readAndProcessSpecifiedTypeMessage(int typeId) {
        return new CondensedTypeMessage(
                TypeCollection.getSpecifiedType(typeId).readTypeSpecification(this));
    }

    private void readAndProcessStartString() {
        String startString;
        try {
            startString = readString();
        } catch (RIOException e) {
            throw new NoStartStringException(e);
        }
        if (!startString.equals(START_STRING)) {
            throw new NoStartStringException(startString);
        }
        startStringRead = true;
        StartMessage message =
                new StartMessage(
                        (int) readUnsignedVarint(),
                        readString(),
                        readString(),
                        readString(),
                        Compression.valueOf(readString()));
        this.inputStream = message.compression().wrap(inputStream);
        universe.setStartMessage(message);
    }

    @SuppressWarnings("unchecked")
    private Message readAndProcessInstanceMessage(int typeId) {
        var type = typeCollection.getType(typeId);
        var in =
                new ReadInstance<>(
                        (CondensedType<Object, Object>) type,
                        ensureRecursivelyComplete(type.readFrom(this)));
        return in;
    }

    /**
     * Reads a varlong from the stream and returns it as a long.
     *
     * @return the decoded varlong or -1 if at end of stream
     */
    public long readUnsignedVarintOrEnd() {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = read();
            if (b < 0) {
                return -1;
            }
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Reads a varlong from the stream and returns it as a long.
     *
     * <p>The varlong is read from the stream and decoded as an unsigned long.
     *
     * @return the decoded varlong
     */
    public long readUnsignedVarint() {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = read();
            if (b < 0) {
                throw new RIOException("Unexpected end of stream");
            }
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Reads a varlong from the stream and returns it as a long.
     *
     * @return the decoded varlong
     */
    public long readSignedVarint() {
        long unsigned = readUnsignedVarint();
        return (unsigned >>> 1) ^ -(unsigned & 1);
    }

    /** Read UFT-8 encoded string */
    public String readString() {
        return readString(null);
    }

    /**
     * Reads a string from the stream.
     *
     * @param encoding the encoding of the string or null for UTF-8
     * @return the decoded string
     */
    public String readString(@Nullable String encoding) {
        int length = (int) readUnsignedVarint();
        if (length == 0) {
            return "";
        }
        if (length < 0) {
            throw new RIOException("Invalid string length: " + length);
        }
        byte[] data = new byte[length];
        try {
            int res = read(data);
            if (res != length) {
                throw new RIOException("Unexpected end of stream");
            }
        } catch (IOException e) {
            throw new RIOException("Can't read string", e);
        }
        try {
            return new String(data, encoding != null ? encoding : "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RIOException("Can't read string", e);
        }
    }

    /**
     * Reads a signed long from the stream encoded with the given number of bytes.
     *
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
     *
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

    public float readFloat() {
        return Float.intBitsToFloat((int) readUnsignedLong(4));
    }

    public float readFloat16() {
        return Util.fp16ToFloat((short) readUnsignedLong(2));
    }

    public float readBFloat16() {
        return Util.bf16ToFloat((short) readUnsignedLong(2));
    }

    public double readDouble() {
        return Double.longBitsToDouble(readUnsignedLong(8));
    }

    /** Reads a single byte from the stream and returns it as an array of 8 booleans */
    public boolean[] readFlags() {
        int b = read();
        if (b < 0) {
            throw new RIOException("Unexpected end of stream");
        }
        // lowest to highest bit
        boolean[] result = new boolean[8];
        for (int i = 0; i < 8; i++) {
            result[i] = (b & (1 << i)) != 0;
        }
        return result;
    }

    /**
     * Reads a single byte from the stream
     *
     * @return the byte read
     */
    @Override
    public int read() {
        try {
            return inputStream.read();
        } catch (IOException e) {
            return -1; // end of stream
        }
    }

    @Override
    public void close() {
        try {
            inputStream.close();
        } catch (IOException e) {
            throw new RIOException("Can't close stream", e);
        }
    }

    public Universe getUniverse() {
        return universe;
    }

    public CondensedInputStream setReductions(Reductions reductions) {
        this.reductions = reductions;
        return this;
    }

    public Reductions getReductions() {
        return reductions;
    }
}