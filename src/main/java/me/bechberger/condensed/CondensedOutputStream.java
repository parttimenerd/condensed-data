package me.bechberger.condensed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.function.Consumer;
import java.util.function.Function;
import me.bechberger.condensed.Compression.CompressionLevel;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.stats.BasicStatistic;
import me.bechberger.condensed.stats.Statistic;
import me.bechberger.condensed.stats.WriteCause;
import me.bechberger.condensed.stats.WriteMode;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.Reductions;
import me.bechberger.condensed.types.SpecifiedType;
import me.bechberger.condensed.types.TypeCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps an {@see OutputStream} to offer additional methods for writing condensed data.
 *
 * <p>Writes data that can be read by {@see CondensedInputStream}
 */
public class CondensedOutputStream extends OutputStream implements AutoCloseable {

    private Universe universe;

    private final TypeCollection typeCollection;

    private OutputStream outputStream;
    private final CountingOutputStream underlyingCountingStream;

    private Reductions reductions = Reductions.NONE;

    private Statistic statistic = new BasicStatistic();

    private boolean closed = false;

    public CondensedOutputStream(OutputStream outputStream, StartMessage startMessage) {
        this(outputStream, startMessage, new Universe());
    }

    public void setHashAndEqualsConfig(HashAndEqualsConfig hashAndEqualsConfig) {
        universe.setHashAndEqualsConfig(hashAndEqualsConfig);
    }

    public CondensedOutputStream(
            OutputStream outputStream, StartMessage startMessage, Universe universe) {
        this(outputStream, universe);
        try (var t = statistic.withWriteCauseContext(WriteCause.Start)) {
            writeStartString(startMessage);
        }
        if (startMessage.compression() != Compression.NONE) {
            this.outputStream =
                    startMessage
                            .compression()
                            .wrap(outputStream, CompressionLevel.HIGH_COMPRESSION);
        }
    }

    /** Create an output stream without a start string and message, used for testing */
    CondensedOutputStream(OutputStream outputStream, Universe universe) {
        this.typeCollection = new TypeCollection();
        this.underlyingCountingStream = new CountingOutputStream(outputStream);
        this.outputStream = underlyingCountingStream;
        this.universe = universe;
    }

    CondensedOutputStream(OutputStream outputStream) {
        this(outputStream, new Universe());
    }

    public void enableFullStatistics() {
        if (statistic instanceof BasicStatistic) {
            this.statistic = new Statistic();
        }
    }

    public void setStatistics(Statistic statistic) {
        this.statistic = statistic;
    }

    private void writeStartString(StartMessage startMessage) {
        universe.setStartMessage(startMessage);
        writeString(Constants.START_STRING);
        writeUnsignedVarInt(startMessage.version());
        writeString(startMessage.generatorName());
        writeString(startMessage.generatorVersion());
        writeString(startMessage.generatorConfiguration());
        writeString(startMessage.compression().name());
    }

    static byte[] useCompressed(Consumer<CondensedOutputStream> consumer) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CondensedOutputStream condensedOutputStream =
                new CondensedOutputStream(
                        outputStream, StartMessage.DEFAULT.compress(Compression.DEFAULT));
        consumer.accept(condensedOutputStream);
        return outputStream.toByteArray();
    }

    static byte[] use(Consumer<CondensedOutputStream> consumer, boolean useStartMessage) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CondensedOutputStream condensedOutputStream =
                useStartMessage
                        ? new CondensedOutputStream(outputStream, StartMessage.DEFAULT)
                        : new CondensedOutputStream(outputStream);
        consumer.accept(condensedOutputStream);
        return outputStream.toByteArray();
    }

    private void writeMessageType(int messageType) {
        writeUnsignedVarInt(messageType);
    }

    public synchronized <C extends CondensedType<?, ?>> C writeAndStoreType(
            Function<Integer, C> typeCreator) {
        var type = typeCollection.addType(typeCreator);
        writeType(type);
        return type;
    }

    /**
     * Writes a type specification to the stream
     *
     * <p>The type has to be present in the type collection.
     *
     * @param type the type instance to write
     */
    @SuppressWarnings("unchecked")
    public void writeType(CondensedType<?, ?> type) {
        if (!typeCollection.hasType(type.getId())
                || !typeCollection.getType(type.getId()).equals(type)) {
            throw new AssertionError("Type " + type + " is not present in type collection");
        }
        try (var t = statistic.withWriteCauseContext(WriteCause.TypeSpecification)) {
            statistic.setModeAndCount(WriteMode.TYPE);
            var spec = ((SpecifiedType<CondensedType<?, ?>>) type.getSpecifiedType());
            writeMessageType(spec.id());
            spec.writeTypeSpecification(this, type);
        }
    }

    public synchronized <T, R> void writeMessage(CondensedType<T, R> type, T value) {
        try (var t = statistic.withWriteCauseContext(new WriteCause.TypeWriteCause(type))) {
            writeMessageType(type.getId());
            type.writeTo(this, value);
        }
    }

    /** take care that the value matches the type if the type has a reduction */
    @SuppressWarnings("unchecked")
    public synchronized <T, R, V> void writeMessageReduced(CondensedType<T, R> type, V value) {
        try (var t = statistic.withWriteCauseContext(new WriteCause.TypeWriteCause(type))) {
            statistic.setModeAndCount(WriteMode.INSTANCE);
            writeMessageType(type.getId());
            type.writeTo(this, (T) value);
        }
    }

    /**
     * Writes a varlong to the stream.
     *
     * <p>The varlong is encoded as an unsigned long.
     *
     * @param value the value to write
     */
    public void writeUnsignedVarInt(long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
            write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        write((int) (value & 0x7F));
    }

    /**
     * Writes a varlong to the stream.
     *
     * <p>The varlong is encoded as a signed long.
     *
     * @param value the value to write
     */
    public void writeSignedVarInt(long value) {
        writeUnsignedVarInt((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a string to the stream.
     *
     * @param value string to write
     */
    public void writeString(String value) {
        writeString(value, null);
    }

    /**
     * Writes a string to the stream.
     *
     * @param value string to write
     */
    public void writeString(String value, @Nullable String encoding) {
        try (var t = statistic.withWriteCauseContext(WriteCause.String)) {
            int bytesBefore = statistic.getBytes();
            byte[] bytes;
            try {
                bytes = value.getBytes(encoding != null ? encoding : "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            writeUnsignedVarInt(bytes.length);
            if (bytes.length > 0) {
                try {
                    outputStream.write(bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new RIOException("Can't write string", e);
                }
            }
            statistic.recordString(statistic.getBytes() - bytesBefore);
        }
    }

    public void writeTypeId(CondensedType<?, ?> type) {
        if (!typeCollection.containsType(type)) {
            throw new IllegalArgumentException("Type not registered: " + type);
        }
        writeUnsignedVarInt(type.getId());
    }

    public enum OverflowMode {
        /** Throw an IllegalArgumentException exception if the value is too large */
        ERROR,
        /**
         * If the value is too large, return the maximum possible value, similar with too small
         * values
         */
        SATURATE
    }

    /**
     * Internal method to write a long to the stream as a fixed width integer.
     *
     * @param value the value to write
     * @param bytes the width of the integer, 1 <= bytes <= 8, cut off the higher bits
     */
    private void writeLong(long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            write((int) (value & 0xFF));
            value >>>= 8;
        }
    }

    /** Returns a bitmask that can be used to mask the lower n bytes of a long. */
    private long bitMask(int bytes) {
        return (1L << (bytes * 8)) - 1;
    }

    public void writeUnsignedLong(long value, int bytes) {
        if (bytes == 1) {
            writeSingleByte(value);
        }
        writeUnsignedLong(value, bytes, OverflowMode.ERROR);
    }

    public void writeSingleByte(long value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Value " + value + " does not fit into 1 byte");
        }
        write((byte) value);
    }

    /**
     * Writes an unsigned long to the stream as a fixed width integer.
     *
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
                    throw new IllegalArgumentException(
                            "Value " + value + " does not fit into " + bytes + " bytes");
                } else {
                    value = Long.compareUnsigned(value, maxValue) > 0 ? maxValue : value;
                }
            }
            writeLong(value, bytes);
        }
    }

    public void writeSignedLong(long value, int bytes) {
        writeSignedLong(value, bytes, OverflowMode.ERROR);
    }

    /**
     * Writes an signed long to the stream as a fixed width integer.
     *
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
                    throw new IllegalArgumentException(
                            "Value " + value + " does not fit into " + bytes + " bytes");
                } else {
                    value = Math.max(minValue, Math.min(maxValue, value));
                }
            }
            writeLong(value, bytes);
        }
    }

    /** Writes a float to the stream */
    public void writeFloat(float value) {
        writeSignedLong(Float.floatToRawIntBits(value), 4, OverflowMode.ERROR);
    }

    public void writeFloat16(float value) {
        writeSignedLong(Util.floatToFp16(value), 2, OverflowMode.ERROR);
    }

    public void writeBFloat16(float value) {
        writeSignedLong(Util.floatToBf16(value), 2, OverflowMode.ERROR);
    }

    /** Writes a double to the stream */
    public void writeDouble(double value) {
        writeSignedLong(Double.doubleToRawLongBits(value), 8, OverflowMode.ERROR);
    }

    /**
     * Write (at most 8) flags in a single byte
     *
     * @param flags lowest to highest bit
     * @throws IllegalArgumentException if more than 8 flags are given
     */
    public void writeFlags(boolean... flags) {
        if (flags.length > 8) {
            throw new IllegalArgumentException("Too many flags");
        }
        byte result = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) {
                result |= (byte) (1 << i);
            }
        }
        write(result);
    }

    /** Writes the specified byte to the stream */
    @Override
    public void write(int b) {
        try {
            outputStream.write(b);
            statistic.record(1);
        } catch (IOException e) {
            throw new RIOException("Can't write byte", e);
        }
    }

    @Override
    public void write(byte @NotNull [] b) {
        try {
            statistic.record(b.length);
            outputStream.write(b);
        } catch (IOException e) {
            throw new RIOException("Can't write byte array", e);
        }
    }

    public Universe getUniverse() {
        return universe;
    }

    public Statistic getStatistics() {
        return statistic;
    }

    public TypeCollection getTypeCollection() {
        return typeCollection;
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            outputStream.close();
        } catch (IOException e) {
            throw new RIOException("Can't close stream", e);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void setReductions(Reductions reductions) {
        this.reductions = reductions;
    }

    public Reductions getReductions() {
        return reductions;
    }

    /** Estimates the size of the currently written file in bytes */
    public int estimateSize() {
        return underlyingCountingStream.writtenBytes();
    }
}