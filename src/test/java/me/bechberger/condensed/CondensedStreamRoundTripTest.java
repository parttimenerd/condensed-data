package me.bechberger.condensed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.Message.StartMessage;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Testing that data written via {@see CondensedOutputStream} can be read back via {@see
 * CondensedInputStream}
 */
public class CondensedStreamRoundTripTest {

    private <T> void assertRoundTrip(
            T value,
            Consumer<CondensedOutputStream> writer,
            Function<CondensedInputStream, T> reader,
            Function<T, T> valueToExpected,
            BiPredicate<T, T> equality) {
        byte[] data = CondensedOutputStream.use(writer, false);
        try (var in = new CondensedInputStream(data)) {
            T result = reader.apply(in);
            T expected = valueToExpected.apply(value);
            if (!equality.test(result, expected)) {
                try (var in2 = new CondensedInputStream(data)) {
                    T result2 = reader.apply(in2);
                    Assertions.fail(
                            "Expected "
                                    + expected
                                    + " but got "
                                    + result
                                    + " (or "
                                    + result2
                                    + ") for "
                                    + value);
                }
            }
        }
    }

    private <T> void assertRoundTrip(
            T value,
            Consumer<CondensedOutputStream> writer,
            Function<CondensedInputStream, T> reader,
            Function<T, T> valueToExpected) {
        assertRoundTrip(value, writer, reader, valueToExpected, Object::equals);
    }

    private <T> void assertRoundTrip(
            T value,
            Consumer<CondensedOutputStream> writer,
            Function<CondensedInputStream, T> reader) {
        assertRoundTrip(value, writer, reader, Function.identity(), Object::equals);
    }

    private <T> void assertRoundTrip(
            T value,
            Consumer<CondensedOutputStream> writer,
            Function<CondensedInputStream, T> reader,
            BiPredicate<T, T> equality) {
        assertRoundTrip(value, writer, reader, Function.identity(), equality);
    }

    @Property
    public void testUnsignedVarintRoundTrip(@ForAll long value) {
        assertRoundTrip(
                value,
                out -> out.writeUnsignedVarInt(value),
                CondensedInputStream::readUnsignedVarint);
    }

    @Property
    public void testSignedVarintRoundTrip(@ForAll long value) {
        assertRoundTrip(
                value, out -> out.writeSignedVarInt(value), CondensedInputStream::readSignedVarint);
    }

    @Provide
    public Arbitrary<String> encodings() {
        return Arbitraries.of(Charset.defaultCharset().toString(), "UTF-8", "UTF-16");
    }

    private static String stringToBytesString(String value, String encoding) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("new byte[] {");
            byte[] bytes = value.getBytes(encoding);
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("(byte) ");
                sb.append(bytes[i]);
            }
            sb.append("}");
            return sb.toString();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Property
    public void testStringRoundTrip(@ForAll String value, @ForAll("encodings") String encoding) {
        assertRoundTrip(
                value,
                out -> out.writeString(value, encoding),
                in -> in.readString(encoding),
                (a, b) -> {
                    if (!a.equals(b)) {
                        System.out.println(
                                "a: "
                                        + a
                                        + " "
                                        + stringToBytesString(a, encoding)
                                        + " b: "
                                        + b
                                        + " "
                                        + stringToBytesString(b, encoding));
                        return false;
                    }
                    return true;
                });
    }

    @Property
    public void testUnsignedLongRoundTrip(
            @ForAll @LongRange(min = 0) long value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        // reduce value to bytes
        long reducedValue = bytes == 8 ? value : Math.min(value, (1L << (bytes * 8)) - 1);
        assertRoundTrip(
                value,
                out -> out.writeUnsignedLong(value, bytes, OverflowMode.SATURATE),
                in -> in.readUnsignedLong(bytes),
                v -> reducedValue);
    }

    @Example
    public void testUnsignedLongRoundTripExample() {
        testUnsignedLongRoundTrip(1, 8);
    }

    @Property
    public void testSignedLongRoundTripSaturate(
            @ForAll long value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        byte[] data =
                CondensedOutputStream.use(
                        out -> out.writeSignedLong(value, bytes, OverflowMode.SATURATE), false);
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
    public void testUnsignedLongRoundTripSaturate(
            @ForAll long value, @ForAll @IntRange(min = 1, max = 8) int bytes) {
        long maxValue = bytes == 8 ? -1L : (1L << (bytes * 8)) - 1;
        long reducedValue = Long.compareUnsigned(value, maxValue) > 0 ? maxValue : value;
        assertRoundTrip(
                value,
                out -> out.writeUnsignedLong(value, bytes, OverflowMode.SATURATE),
                in -> in.readUnsignedLong(bytes),
                v -> reducedValue);
    }

    @Example
    public void testUnsignedLongRoundTripSaturate1with1byte() {
        assertRoundTrip(
                1L,
                out -> out.writeUnsignedLong(1, 1, OverflowMode.SATURATE),
                in -> in.readUnsignedLong(1),
                v -> 1L);
    }

    @Example
    public void testUnsignedLongRoundTripSaturate1with8byte() {
        assertRoundTrip(
                1L,
                out -> out.writeUnsignedLong(1, 8, OverflowMode.SATURATE),
                in -> in.readUnsignedLong(8),
                v -> 1L);
    }

    @Property
    public void testUnsignedLongError(
            @ForAll long value, @ForAll @IntRange(min = 1, max = 7) int bytes) {
        if (value > 0) {
            CondensedOutputStream.use(
                    out -> {
                        if (value > (1L << (bytes * 8)) - 1) {
                            Assertions.assertThatThrownBy(
                                            () ->
                                                    out.writeUnsignedLong(
                                                            value, bytes, OverflowMode.ERROR),
                                            "value too large")
                                    .isInstanceOf(IllegalArgumentException.class);
                        } else {
                            out.writeUnsignedLong(value, bytes, OverflowMode.ERROR);
                        }
                    },
                    false);
        }
    }

    @Example
    public void testUnsigned128FitsInto2Bytes() {
        CondensedOutputStream.use(out -> out.writeUnsignedLong(128, 2, OverflowMode.ERROR), false);
    }

    @Property
    public void testFloatRoundTrip(@ForAll @FloatRange float value) {
        assertRoundTrip(value, out -> out.writeFloat(value), CondensedInputStream::readFloat);
    }

    @Property
    public void testDoubleRoundTrip(@ForAll @DoubleRange double value) {
        assertRoundTrip(value, out -> out.writeDouble(value), CondensedInputStream::readDouble);
    }

    @Property
    public void testFlagsRoundTrip(@ForAll @Size(max = 8) boolean[] flags) {
        assertRoundTrip(
                flags,
                out -> out.writeFlags(flags),
                CondensedInputStream::readFlags,
                (a, b) -> {
                    for (int i = 0; i < Math.min(a.length, b.length); i++) {
                        if (a[i] != b[i]) {
                            return false;
                        }
                    }
                    return true;
                });
    }

    @Property
    public void testReadFlagsProducesEightElementArray(@ForAll @IntRange(max = 1 << 7) int value) {
        try (var in = new CondensedInputStream(new byte[] {(byte) value})) {
            boolean[] flags = in.readFlags();
            assertThat(flags).hasSize(8);
        }
    }

    @Test
    public void testReadUnsignedVarintOrEnd() {
        try (var in = new CondensedInputStream(new byte[] {0})) {
            assertThat(in.readUnsignedVarintOrEnd()).isEqualTo(0);
        }
        try (var in = new CondensedInputStream(new byte[] {})) {
            assertThat(in.readUnsignedVarintOrEnd()).isEqualTo(-1);
        }
    }

    @Property
    public void testStartMessage(
            @ForAll @StringLength(max = 10) String name, @ForAll String version)
            throws IOException {
        var bos = new ByteArrayOutputStream();
        new CondensedOutputStream(bos, new StartMessage(name, version)).close();
        try (var in = new CondensedInputStream(bos.toByteArray())) {
            assertNull(in.readNextInstance());
            assertTrue(in.getUniverse().hasStartMessage());
            var startMessage = in.getUniverse().getStartMessage();
            assertNotNull(startMessage);
            assertEquals(name, startMessage.generatorName());
            assertEquals(version, startMessage.generatorVersion());
        }
    }

    @Test
    public void testReadNullAfterClose() {
        try (var in = new CondensedInputStream(CondensedOutputStream.use(out -> {}, true))) {
            assertNull(in.readNextInstance());
            assertNull(in.readNextTypeMessageAndProcess());
            assertNull(in.readNextMessageAndProcess());
        }
    }

    @Property
    public void testBFloat16RoundTrip(@ForAll float value) {
        assertRoundTrip(
                value,
                out -> out.writeBFloat16(value),
                CondensedInputStream::readBFloat16,
                (a, b) -> {
                    // mantissa of 7 bits
                    double fraction = (1 / Math.pow(2, 7));
                    double allowedDiff = Math.max(Math.abs(a), Math.abs(b)) * fraction;
                    return Math.abs(a - b) <= allowedDiff;
                });
    }

    @Property
    public void testFloat16RoundTrip(@ForAll float value) {
        assertRoundTrip(
                value,
                out -> out.writeFloat16(value),
                CondensedInputStream::readFloat16,
                (a, b) -> {
                    // mantissa of 10 bits
                    double fraction = (1 / Math.pow(2, 10));
                    double allowedDiff = Math.max(Math.abs(a), Math.abs(b)) * fraction;
                    return Math.abs(a - b) <= allowedDiff;
                });
    }
}
