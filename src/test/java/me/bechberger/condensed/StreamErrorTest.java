package me.bechberger.condensed;

import me.bechberger.condensed.RIOException.NoStartStringException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StreamErrorTest {

    @Test
    public void testStreamHasNoStartMessage() {
        try (CondensedInputStream in = new CondensedInputStream(new byte[] {1})) {
            Assertions.assertThrows(NoStartStringException.class, in::readNextMessageAndProcess);
        }
    }

    @Test
    public void testBrokenStartMessage() {
        try (CondensedInputStream in = new CondensedInputStream(new byte[] {1, 2, 3, 4})) {
            Assertions.assertThrows(NoStartStringException.class, in::readNextMessageAndProcess);
        }
    }

    @Test
    public void testStreamEndEarly() {
        try (CondensedInputStream in = new CondensedInputStream(new byte[] {1})) {
            Assertions.assertThrows(RIOException.class, () -> in.readUnsignedLong(8));
        }
    }
}
