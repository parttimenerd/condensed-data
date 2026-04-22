package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

public class EventCompletionCandidatesTest {

    private static EventCompletionCandidates newInstanceViaReflection() throws Exception {
        Constructor<EventCompletionCandidates> ctor =
                EventCompletionCandidates.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    public void testIteratorReturnsValues() throws Exception {
        var candidates = newInstanceViaReflection();
        var iterator = candidates.iterator();
        assertNotNull(iterator);
        assertTrue(iterator.hasNext());
    }

    @Test
    public void testIteratorIsSorted() throws Exception {
        var candidates = newInstanceViaReflection();
        var values = new ArrayList<String>();
        candidates.iterator().forEachRemaining(values::add);
        assertFalse(values.isEmpty());

        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        assertEquals(sorted, values);
    }
}
