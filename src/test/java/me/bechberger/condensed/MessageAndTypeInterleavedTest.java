package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.VarIntType;
import net.jqwik.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test writing multiple messages with different types and types interleaved */
public class MessageAndTypeInterleavedTest {

    record Write(boolean writeValue, long value, int typeIndex) {}

    @Provide
    static Stream<Arguments> writes() {
        Random random = new Random(1337);
        return IntStream.range(0, 200)
                .mapToObj(
                        i -> {
                            var length = i / 10;
                            List<Write> writes = new ArrayList<>();
                            int countTypes = 0;
                            for (int j = 0; j < length; j++) {
                                boolean writeValue = random.nextInt(3) < 2;
                                if (countTypes == 0) {
                                    writeValue = false;
                                }
                                if (!writeValue) {
                                    countTypes++;
                                }
                                writes.add(
                                        writeValue
                                                ? new Write(
                                                        true,
                                                        random.nextLong(),
                                                        random.nextInt(countTypes))
                                                : new Write(false, 0, countTypes - 1));
                            }
                            return Arguments.arguments(writes, random.nextInt(2) == 0);
                        });
    }

    /** Test writing multiple messages with different types and types interleaved */
    @ParameterizedTest
    @MethodSource("writes")
    void testMultipleMessages(List<Write> writes, boolean checkTypeMessage) {
        List<VarIntType> types = new ArrayList<>();
        AtomicReference<VarIntType> curType = new AtomicReference<>();
        byte[] data =
                CondensedOutputStream.use(
                        out -> {
                            for (var write : writes) {
                                if (!write.writeValue) {
                                    curType.set(
                                            out.writeAndStoreType(
                                                    i ->
                                                            new VarIntType(
                                                                    i,
                                                                    write.typeIndex + "",
                                                                    "",
                                                                    true)));
                                    types.add(curType.get());
                                } else {
                                    out.writeMessage(types.get(write.typeIndex), write.value);
                                }
                            }
                        },
                        true);
        try (var in = new CondensedInputStream(data)) {
            for (var write : writes) {
                if (!write.writeValue) {
                    if (checkTypeMessage) {
                        VarIntType result =
                                (VarIntType) (CondensedType<?>) in.readNextTypeMessageAndProcess();
                        assertEquals(types.get(write.typeIndex), result);
                    }
                } else {
                    var msg = in.readNextInstance();
                    assertNotNull(msg);
                    assertEquals(write.value, (Long) msg.value());
                    assertEquals(types.get(write.typeIndex), msg.type());
                }
            }
            assertNull(in.readNextInstance());
        }
    }
}
