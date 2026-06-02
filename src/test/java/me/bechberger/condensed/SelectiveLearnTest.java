package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Test class for selective learn mode experiments. */
public class SelectiveLearnTest {

    @Test
    void testCountOne() throws IOException {
        var cos = new CountingOutputStream(new ByteArrayOutputStream());
        cos.write(1);
        assertEquals(1, cos.writtenBytes());
    }

    @Test
    void testCountTwo() throws IOException {
        var cos = new CountingOutputStream(new ByteArrayOutputStream());
        cos.write(1);
        cos.write(2);
        assertEquals(2, cos.writtenBytes());
    }
}
