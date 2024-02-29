package me.bechberger.condensed;

import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.assertj.core.api.Assertions;

import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testing that data written via {@see CondensedOutputStream} can be read back via {@see CondensedInputStream}
 */
public class CondensedStreamRoundTripTest {

    private <T> void assertRoundTrip(T value, Consumer<CondensedOutputStream> writer, Function<CondensedInputStream, T> reader, Function<T, T> valueToExpected) {
        byte[] data = CondensedOutputStream.use(writer);
        try (var in = new CondensedInputStream(data)) {
            T result = reader.apply(in);
            T expected = valueToExpected.apply(value);
            if (!expected.equals(result)) {
                try (var in2 = new CondensedInputStream(data)) {
                    T result2 = reader.apply(in2);
                    Assertions.fail("Expected " + expected + " but got " + result + " (or " + result2 + ") for " + value);
                }
            }
        }
    }

    private <T> void assertRoundTrip(T value, Consumer<CondensedOutputStream> writer, Function<CondensedInputStream, T> reader) {
        assertRoundTrip(value, writer, reader, Function.identity());
    }

    @Property
    public void testUnsignedVarintRoundTrip(@ForAll int value) {
        assertRoundTrip(value, out -> out.writeUnsignedVarint(value), CondensedInputStream::readUnsignedVarint);
    }

    @Property
    public void testSignedVarintRoundTrip(@ForAll int value) {
        assertRoundTrip(value, out -> out.writeSignedVarint(value), CondensedInputStream::readSignedVarint);
    }

    @Property
    public void testUnsignedVarlongRoundTrip(@ForAll long value) {
        assertRoundTrip(value, out -> out.writeUnsignedVarlong(value), CondensedInputStream::readUnsignedVarlong);
    }

    @Property
    public void testSignedVarlongRoundTrip(@ForAll long value) {
        assertRoundTrip(value, out -> out.writeSignedVarlong(value), CondensedInputStream::readSignedVarlong);
    }

    @Property
    public void testStringRoundTrip(@ForAll String value) {
        assertRoundTrip(value, out -> out.writeString(value), CondensedInputStream::readString);
    }

    @Property
    public void testWriteLong(@ForAll long value) {
        assertRoundTrip(value, out -> out.writeLong(value), CondensedInputStream::readLong);
    }

    @Property
    public void testWriteInt(@ForAll int value) {
        assertRoundTrip(value, out -> out.writeInt(value), CondensedInputStream::readInt);
    }

    @Property
    public void testWriteShort(@ForAll short value) {
        assertRoundTrip(value, out -> out.writeShort(value), CondensedInputStream::readShort);
    }

    @Property
    public void testWriteByte(@ForAll byte value) {
        assertRoundTrip(value, out -> out.writeByte(value), CondensedInputStream::readByte);
    }


    @Property
    public void testUnsignedLongRoundTrip(@ForAll @LongRange(min = 0) long value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        // reduce value to bytes
        long reducedValue = bytes == 8 ? value : Math.min(value, (1L << (bytes * 8)) - 1);
        assertRoundTrip(value, out -> out.writeUnsignedLong(value, bytes, OverflowMode.SATURATE), in -> in.readUnsignedLong(bytes), v -> reducedValue);
    }

    @Example
    public void testUnsignedLongRoundTripExample() {
        testUnsignedLongRoundTrip(1, 8);
    }

    @Property
    public void testSignedLongRoundTripSaturate(@ForAll long value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        byte[] data = CondensedOutputStream.use(out -> out.writeSignedLong(value, bytes, OverflowMode.SATURATE));
        assertThat(data).hasSize(bytes);
        try (var in = new CondensedInputStream(data)) {
            long result = in.readSignedLong(bytes);
            if (result > 0) {
                assertThat(result).isGreaterThanOrEqualTo(0);
                long maxValue = (1L << (bytes * 8 - 1)) - 1;
                long expectedValue = Math.min(value, maxValue);
                assertThat(result).isEqualTo(expectedValue);
            } else {
                assertThat(result).isLessThanOrEqualTo(0);
                long minValue = -(1L << (bytes * 8 - 1));
                long expectedValue = Math.max(value, minValue);
                assertThat(result).isEqualTo(expectedValue);
            }
        }
    }
    @Example
    public void testSignedLongRoundTripSaturateMinus1And1Byte() {
        testSignedLongRoundTripSaturate(-1, 1);
    }

    @Property
    public void testUnsignedLongRoundTripSaturate(@ForAll long value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        long maxValue = bytes == 8 ? -1L : (1L << (bytes * 8)) - 1;
        long reducedValue = Long.compareUnsigned(value, maxValue) > 0 ? maxValue : value;
        assertRoundTrip(value, out -> out.writeUnsignedLong(value, bytes, OverflowMode.SATURATE), in -> in.readUnsignedLong(bytes), v -> reducedValue);
    }

    @Example
    public void testUnsignedLongRoundTripSaturate1with1byte() {
        assertRoundTrip(1L, out -> out.writeUnsignedLong(1, 1, OverflowMode.SATURATE), in -> in.readUnsignedLong(1), v -> 1L);
    }

    @Example
    public void testUnsignedLongRoundTripSaturate1with8byte() {
        assertRoundTrip(1L, out -> out.writeUnsignedLong(1, 8, OverflowMode.SATURATE), in -> in.readUnsignedLong(8), v -> 1L);
    }

    @Property
    public void testUnsignedLongError(@ForAll long value, @ForAll @IntRange(min = 1, max = 7) int bytes) {
        if (value > 0) {
            CondensedOutputStream.use(out -> {
                if (value > (1L << (bytes * 8)) - 1) {
                    Assertions.assertThatThrownBy(() -> out.writeUnsignedLong(value, bytes, OverflowMode.ERROR), "value too large").isInstanceOf(IllegalArgumentException.class);
                } else {
                    out.writeUnsignedLong(value, bytes, OverflowMode.ERROR);
                }
            });
        }
    }

    @Example
    public void testUnsigned128FitsInto2Bytes() {
        CondensedOutputStream.use(out -> out.writeUnsignedLong(128, 2, OverflowMode.ERROR));
    }

    @Property
    public void testPercentageRoundTripSaturate(@ForAll @DoubleRange(min=-2, max=4) double value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        byte[] data = CondensedOutputStream.use(out -> out.writePercentage(value, bytes, OverflowMode.SATURATE));
        try (var in = new CondensedInputStream(data)) {
            double result = in.readPercentage(bytes);
            assertThat(result).isLessThanOrEqualTo(1).isGreaterThanOrEqualTo(0);
        }
    }

    @Property
    public void testPercentageFailsForTooLargeWithError(@ForAll @DoubleRange(min=1.1) double value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        Assertions.assertThatThrownBy(() -> CondensedOutputStream.use(out -> out.writePercentage(value, bytes, OverflowMode.ERROR))).isInstanceOf(IllegalArgumentException.class);
    }
}
