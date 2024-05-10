package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.util.Pair;

public class UtilTest {

    @Test
    public void testZip() {
        assertEquals(
                List.of(new Pair<>(1, "a"), new Pair<>(2, "b"), new Pair<>(3, "c")),
                Util.zip(List.of(1, 2, 3), List.of("a", "b", "c")));
    }
}
