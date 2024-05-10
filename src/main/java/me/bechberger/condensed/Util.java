package me.bechberger.condensed;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.openjdk.jmc.common.util.Pair;

public class Util {

    /**
     * Convert a float to a half precision float, copied from <a
     * href="https://github.com/Craigacp/onnxruntime/blob/291e6e8cbbfd288752c493afb60cf2415c54b698/java/src/main/java/ai/onnxruntime/OrtUtil.java">onnxruntime/OrtUtil</a>
     */
    static short floatToFp16(float input) {
        // Port of MLAS_Float2Half from onnxruntime/core/mlas/inc/mlas_float16.h
        int bits = Float.floatToIntBits(input);
        final int F32_INFINITY = Float.floatToIntBits(Float.POSITIVE_INFINITY);
        final int F16_MAX = (127 + 16) << 23;
        final int DENORM_MAGIC = ((127 - 15) + (23 - 10) + 1) << 23;
        final int SIGN_MASK = 0x80000000;
        final int ROUNDING_CONST = ((15 - 127) << 23) + 0xfff;

        int sign = bits & SIGN_MASK;
        // mask out sign bit
        bits ^= sign;

        short output;
        if (bits >= F16_MAX) {
            // Inf or NaN (all exponent bits set)
            output = (bits > F32_INFINITY) ? (short) 0x7e00 : (short) 0x7c00;
        } else {
            if (bits < (113 << 23)) {
                // Subnormal or zero
                // use a magic value to align our 10 mantissa bits at the bottom of
                // the float. as long as FP addition is round-to-nearest-even this
                // just works.
                float tmp = Float.intBitsToFloat(bits) + Float.intBitsToFloat(DENORM_MAGIC);

                // and one integer subtract of the bias later, we have our final float!
                output = (short) (Float.floatToIntBits(tmp) - DENORM_MAGIC);
            } else {
                int mant_odd = (bits >> 13) & 1; // resulting mantissa is odd

                // update exponent, rounding bias part 1
                bits += ROUNDING_CONST;
                // rounding bias part 2
                bits += mant_odd;
                // take the bits!
                output = (short) (bits >> 13);
            }
        }

        // Add the sign back in
        output = (short) (output | ((short) (sign >> 16)));

        return output;
    }

    /**
     * Convert a half precision float to a float, copied from <a
     * href="https://github.com/Craigacp/onnxruntime/blob/291e6e8cbbfd288752c493afb60cf2415c54b698/java/src/main/java/ai/onnxruntime/OrtUtil.java">onnxruntime/OrtUtil</a>
     */
    static float fp16ToFloat(short input) {
        // Port of MLAS_Half2Float from onnxruntime/core/mlas/inc/mlas_float16.h
        final int MAGIC = 113 << 23;
        // exponent mask after shift
        final int SHIFTED_EXP = 0x7c00 << 13;

        // exponent/mantissa bits
        int bits = (input & 0x7fff) << 13;
        // just the exponent
        final int exp = SHIFTED_EXP & bits;
        // exponent adjust
        bits += (127 - 15) << 23;

        // handle exponent special cases
        if (exp == SHIFTED_EXP) {
            // Inf/NaN?
            // extra exp adjust
            bits += (128 - 16) << 23;
        } else if (exp == 0) {
            // Zero/Denormal?
            // extra exp adjust
            bits += (1 << 23);
            // renormalize
            float tmp = Float.intBitsToFloat(bits) - Float.intBitsToFloat(MAGIC);
            bits = Float.floatToIntBits(tmp);
        }

        // sign bit
        bits |= (input & 0x8000) << 16;

        return Float.intBitsToFloat(bits);
    }

    /**
     * Converts a float into bf16. May not produce correct values for subnormal floats.
     *
     * <p>Source: <a
     * href="https://github.com/Craigacp/onnxruntime/blob/291e6e8cbbfd288752c493afb60cf2415c54b698/java/src/main/java/ai/onnxruntime/OrtUtil.java">onnxruntime/OrtUtil</a>
     *
     * <p>Rounds to nearest even.
     *
     * @param input The float input.
     * @return A bfloat16 value which is closest to the float.
     */
    public static short floatToBf16(float input) {
        int bits = Float.floatToIntBits(input);
        int lsb = (bits >> 16) & 1;
        int roundingBias = 0x7fff + lsb;
        bits += roundingBias;
        return (short) (bits >> 16);
    }

    /**
     * Converts a bf16 value stored in a short into a float value.
     *
     * <p>Source: <a
     * href="https://github.com/Craigacp/onnxruntime/blob/291e6e8cbbfd288752c493afb60cf2415c54b698/java/src/main/java/ai/onnxruntime/OrtUtil.java">onnxruntime/OrtUtil</a>
     *
     * @param input A uint16_t representing a bfloat16 value.
     * @return A float.
     */
    public static float bf16ToFloat(short input) {
        int bits = input << 16;
        return Float.intBitsToFloat(bits);
    }

    public static boolean equalUnderBf16Conversion(long first, long second) {
        return floatToBf16((float) first) == floatToBf16((float) second);
    }

    public static <T1, T2> List<Pair<T1, T2>> zip(List<T1> list1, List<T2> list2) {
        return IntStream.range(0, Math.min(list1.size(), list2.size()))
                .mapToObj(i -> new Pair<>(list1.get(i), list2.get(i)))
                .toList();
    }

    public static long toNanoSeconds(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000 + instant.getNano();
    }
}
