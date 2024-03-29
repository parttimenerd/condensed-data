package me.bechberger.condensed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.function.Consumer;
import java.util.function.Function;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.SpecifiedType;
import me.bechberger.condensed.types.TypeCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps an {@see OutputStream} to offer additional methods for writing condensed data.
 *
 * <p>Writes data that can be read by {@see CondensedInputStream}
 */
public class CondensedOutputStream extends OutputStream {

    public enum WriteMode {
        TYPE,
        INSTANCE,
        OTHER
    }

    public static class SubStatistic {
        private final WriteMode mode;
        private int count = 0;
        private int bytes = 0;
        private int strings = 0;
        private int stringBytes = 0;

        public SubStatistic(WriteMode mode) {
            this.mode = mode;
        }

        @Override
        public String toString() {
            return "SubStatistic{"
                    + "mode="
                    + mode
                    + ", count="
                    + count
                    + ", bytes="
                    + bytes
                    + ", strings="
                    + strings
                    + ", stringBytes="
                    + stringBytes
                    + '}';
        }
    }

    /** Statistics about the written data */
    public static class Statistic {
        private final SubStatistic customTypes = new SubStatistic(WriteMode.TYPE);
        private final SubStatistic instanceMessages = new SubStatistic(WriteMode.INSTANCE);
        private final SubStatistic other = new SubStatistic(WriteMode.OTHER);

        {
            other.count = 1;
        }

        private int bytes = 0;
        private WriteMode mode = WriteMode.OTHER;

        @Override
        public String toString() {
            return "Statistic{"
                    + "customTypes="
                    + customTypes
                    + ", instanceMessages="
                    + instanceMessages
                    + ", other="
                    + other
                    + ", bytes="
                    + bytes
                    + '}';
        }

        public String toPrettyString() {
            // print as aligned table, columns: mode, count, bytes, strings, stringBytes, last row
            // is total, first row is header
            return String.format(
                    """
                            %-10s %10s %10s %10s %10s
                            %-10s %10d %10d %10d %10d
                            %-10s %10d %10d %10d %10d
                            %-10s %10d %10d %10d %10d
                            %-10s %10d %10d %10d %10d""",
                    "mode",
                    "count",
                    "bytes",
                    "strings",
                    "stringBytes",
                    "types",
                    customTypes.count,
                    customTypes.bytes,
                    customTypes.strings,
                    customTypes.stringBytes,
                    "instance",
                    instanceMessages.count,
                    instanceMessages.bytes,
                    instanceMessages.strings,
                    instanceMessages.stringBytes,
                    "other",
                    other.count,
                    other.bytes,
                    other.strings,
                    other.stringBytes,
                    "total",
                    customTypes.count + instanceMessages.count + other.count,
                    bytes,
                    customTypes.strings + instanceMessages.strings + other.strings,
                    customTypes.stringBytes + instanceMessages.stringBytes + other.stringBytes);
        }

        public void setModeAndCount(WriteMode mode) {
            this.mode = mode;
            switch (mode) {
                case TYPE -> customTypes.count++;
                case INSTANCE -> instanceMessages.count++;
                case OTHER -> other.count++;
            }
        }

        private @NotNull SubStatistic getSubStatistic() {
            return switch (mode) {
                case TYPE -> customTypes;
                case INSTANCE -> instanceMessages;
                case OTHER -> other;
            };
        }

        public void record(int bytes) {
            this.bytes += bytes;
            getSubStatistic().bytes += bytes;
        }

        public void recordString(int bytes) {
            getSubStatistic().strings++;
            getSubStatistic().stringBytes += bytes;
        }
    }

    private final Universe universe;

    private final TypeCollection typeCollection;

    private final OutputStream outputStream;

    private final Statistic statistic = new Statistic();

    public CondensedOutputStream(OutputStream outputStream, StartMessage startMessage) {
        this(outputStream);
        writeStartString(startMessage);
    }

    /** Create an output stream without a start string and message, used for testing */
    CondensedOutputStream(OutputStream outputStream) {
        this.universe = new Universe();
        this.typeCollection = new TypeCollection();
        this.outputStream = outputStream;
    }

    private void writeStartString(StartMessage startMessage) {
        universe.setStartMessage(startMessage);
        writeString(Constants.START_STRING);
        writeUnsignedVarInt(startMessage.version());
        writeString(startMessage.generatorName());
        writeString(startMessage.generatorVersion());
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

    public <C extends CondensedType<?, ?>> C writeAndStoreType(Function<Integer, C> typeCreator) {
        var type = typeCollection.addType(typeCreator);
        writeType(type);
        return type;
    }

    /**
     * Writes a type specification to the stream
     *
     * @param type the type instance to write
     */
    @SuppressWarnings("unchecked")
    private void writeType(CondensedType<?, ?> type) {
        statistic.setModeAndCount(WriteMode.TYPE);
        var spec = ((SpecifiedType<CondensedType<?, ?>>) type.getSpecifiedType());
        writeMessageType(spec.id());
        spec.writeTypeSpecification(this, type);
    }

    public <T, R> void writeMessage(CondensedType<T, R> type, T value) {
        statistic.setModeAndCount(WriteMode.INSTANCE);
        writeMessageType(type.getId());
        type.writeTo(this, value);
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
        int bytesBefore = statistic.bytes;
        byte[] bytes;
        try {
            bytes = value.getBytes(encoding != null ? encoding : "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        writeUnsignedVarInt(bytes.length);
        try {
            write(bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new RIOException("Can't write string", e);
        }
        statistic.recordString(statistic.bytes - bytesBefore);
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
        writeUnsignedLong(value, bytes, OverflowMode.ERROR);
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

    public Statistic getStatistic() {
        return statistic;
    }

    public TypeCollection getTypeCollection() {
        return typeCollection;
    }
}
