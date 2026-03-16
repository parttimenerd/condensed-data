package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.bechberger.condensed.Util.Pair;
import org.junit.jupiter.api.Test;

public class UtilTest {

    @Test
    public void testZip() {
        assertEquals(
                List.of(new Pair<>(1, "a"), new Pair<>(2, "b"), new Pair<>(3, "c")),
                Util.zip(List.of(1, 2, 3), List.of("a", "b", "c")));
    }
}
