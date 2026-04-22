package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.bechberger.condensed.types.ArrayType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StringType;
import org.junit.jupiter.api.Test;

public class ReadListTest {

    private static ArrayType<Long, Long> intArrayType() {
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        return new ArrayType<>(100, intType);
    }

    private static ArrayType<String, String> stringArrayType() {
        var stringType = StringType.SPECIFIED_TYPE.getDefaultType(StringType.SPECIFIED_TYPE.id());
        return new ArrayType<>(101, stringType);
    }

    // --- Direct (non-lazy) ReadList tests ---

    @Test
    public void testDirectListSize() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        assertEquals(3, list.size());
        assertFalse(list.isEmpty());
    }

    @Test
    public void testDirectListGet() {
        var list = new ReadList<>(intArrayType(), List.of(10L, 20L, 30L));
        assertEquals(10L, list.get(0));
        assertEquals(20L, list.get(1));
        assertEquals(30L, list.get(2));
    }

    @Test
    public void testDirectListIsEmpty() {
        var list = new ReadList<>(intArrayType(), List.of());
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
    }

    @Test
    public void testDirectListContains() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        assertTrue(list.contains(2L));
        assertFalse(list.contains(99L));
    }

    @Test
    public void testDirectListContainsAll() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        assertTrue(list.containsAll(List.of(1L, 3L)));
        assertFalse(list.containsAll(List.of(1L, 99L)));
    }

    @Test
    public void testDirectListIterator() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        List<Long> result = new ArrayList<>();
        for (Long val : list) {
            result.add(val);
        }
        assertEquals(List.of(1L, 2L, 3L), result);
    }

    @Test
    public void testDirectListToArray() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L));
        Object[] arr = list.toArray();
        assertArrayEquals(new Object[] {1L, 2L}, arr);
    }

    @Test
    public void testDirectListToArrayTyped() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L));
        Long[] arr = list.toArray(new Long[0]);
        assertArrayEquals(new Long[] {1L, 2L}, arr);
    }

    @Test
    public void testDirectListIndexOf() {
        var list = new ReadList<>(intArrayType(), List.of(10L, 20L, 30L, 20L));
        assertEquals(1, list.indexOf(20L));
        assertEquals(-1, list.indexOf(99L));
    }

    @Test
    public void testDirectListLastIndexOf() {
        var list = new ReadList<>(intArrayType(), List.of(10L, 20L, 30L, 20L));
        assertEquals(3, list.lastIndexOf(20L));
        assertEquals(-1, list.lastIndexOf(99L));
    }

    @Test
    public void testDirectListListIterator() {
        var list = new ReadList<>(intArrayType(), List.of(5L, 10L));
        var it = list.listIterator();
        assertTrue(it.hasNext());
        assertEquals(5L, it.next());
        assertEquals(10L, it.next());
        assertFalse(it.hasNext());
    }

    @Test
    public void testDirectListListIteratorWithIndex() {
        var list = new ReadList<>(intArrayType(), List.of(5L, 10L, 15L));
        var it = list.listIterator(1);
        assertTrue(it.hasNext());
        assertEquals(10L, it.next());
    }

    @Test
    public void testDirectListSubList() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L, 4L));
        var sub = list.subList(1, 3);
        assertEquals(2, sub.size());
        assertEquals(2L, sub.get(0));
        assertEquals(3L, sub.get(1));
    }

    @Test
    public void testDirectListEnsureComplete() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L));
        var returned = list.ensureComplete();
        assertSame(list, returned);
        assertEquals(2, list.size());
    }

    @Test
    public void testDirectListToString() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        assertEquals("[1, 2, 3]", list.toString());
    }

    // --- Read-only operation tests ---

    @Test
    public void testReadOnlyAdd() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.add(2L));
    }

    @Test
    public void testReadOnlyAddAtIndex() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.add(0, 2L));
    }

    @Test
    public void testReadOnlyRemoveObject() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.remove((Object) 1L));
    }

    @Test
    public void testReadOnlyRemoveIndex() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
    }

    @Test
    public void testReadOnlySet() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.set(0, 2L));
    }

    @Test
    public void testReadOnlyClear() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, list::clear);
    }

    @Test
    public void testReadOnlyAddAll() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.addAll(List.of(2L)));
    }

    @Test
    public void testReadOnlyAddAllAtIndex() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.addAll(0, List.of(2L)));
    }

    @Test
    public void testReadOnlyRemoveAll() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.removeAll(List.of(1L)));
    }

    @Test
    public void testReadOnlyRetainAll() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> list.retainAll(List.of(1L)));
    }

    // --- Lazy (accessor-based) ReadList tests ---

    @Test
    public void testLazyListSize() {
        List<Integer> ids = List.of(0, 1, 2);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        assertEquals(3, list.size());
    }

    @Test
    public void testLazyListGet() {
        List<Integer> ids = List.of(0, 1, 2);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        assertEquals(0L, list.get(0));
        assertEquals(10L, list.get(1));
        assertEquals(20L, list.get(2));
    }

    @Test
    public void testLazyListGetWithNullId() {
        List<Integer> ids = new ArrayList<>();
        ids.add(0);
        ids.add(null);
        ids.add(2);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        assertEquals(0L, list.get(0));
        assertNull(list.get(1));
        assertEquals(20L, list.get(2));
    }

    @Test
    public void testLazyListEnsureComplete() {
        List<Integer> ids = List.of(0, 1, 2);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        list.ensureComplete();
        assertEquals(3, list.size());
        // All elements should now be resolved
        assertEquals(List.of(0L, 10L, 20L).toString(), list.toString());
    }

    @Test
    public void testLazyListContains() {
        List<Integer> ids = List.of(0, 1, 2);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        assertTrue(list.contains(10L));
        assertFalse(list.contains(99L));
    }

    @Test
    public void testLazyListIterator() {
        List<Integer> ids = List.of(0, 1, 2);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        List<Long> result = new ArrayList<>();
        for (Long val : list) {
            result.add(val);
        }
        assertEquals(List.of(0L, 10L, 20L), result);
    }

    @Test
    public void testLazyListGetPopulatesIncrementally() {
        // Accessing index 2 should populate indices 0, 1, 2
        List<Integer> ids = List.of(0, 1, 2, 3);
        int[] accessCount = {0};
        var list =
                new ReadList<>(
                        intArrayType(),
                        ids,
                        i -> {
                            accessCount[0]++;
                            return (long) (i * 10);
                        });
        // Access index 2 directly - should populate 0, 1, 2
        assertEquals(20L, list.get(2));
        assertEquals(3, accessCount[0]);
    }

    @Test
    public void testLazyListIsEmpty() {
        var emptyList = new ReadList<>(intArrayType(), List.of(), i -> (long) i);
        assertTrue(emptyList.isEmpty());
    }

    // --- Equals/HashCode tests ---

    @Test
    public void testEqualsDirectLists() {
        var list1 = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        var list2 = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        assertEquals(list1, list2);
    }

    @Test
    public void testEqualsDifferentDirectLists() {
        var list1 = new ReadList<>(intArrayType(), List.of(1L, 2L));
        var list2 = new ReadList<>(intArrayType(), List.of(1L, 3L));
        assertNotEquals(list1, list2);
    }

    @Test
    public void testEqualsLazyLists() {
        List<Integer> ids = List.of(0, 1, 2);
        var list1 = new ReadList<>(intArrayType(), ids, i -> (long) i);
        var list2 = new ReadList<>(intArrayType(), ids, i -> (long) i);
        assertEquals(list1, list2);
    }

    @Test
    public void testNotEqualsDirectVsLazy() {
        var direct = new ReadList<>(intArrayType(), List.of(1L, 2L));
        var lazy = new ReadList<>(intArrayType(), List.of(0, 1), i -> (long) (i + 1));
        assertNotEquals(direct, lazy);
    }

    @Test
    public void testEqualsWithNull() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertNotEquals(null, list);
    }

    @Test
    public void testHashCode() {
        var list1 = new ReadList<>(intArrayType(), List.of(1L, 2L));
        var list2 = new ReadList<>(intArrayType(), List.of(1L, 2L));
        assertEquals(list1.hashCode(), list2.hashCode());
    }

    // --- Completeness marking tests ---

    @Test
    public void testMarkAsComplete() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertFalse(list.isComplete());
        list.markAsComplete();
        assertTrue(list.isComplete());
    }

    @Test
    public void testCleanCompletenessMark() {
        // Use an empty list since cleanCompletenessMark iterates elements
        // for non-primitive types (ArrayType's specifiedType is non-primitive)
        var list = new ReadList<>(intArrayType(), List.of());
        list.markAsComplete();
        assertTrue(list.isComplete());
        list.cleanCompletenessMark();
        assertFalse(list.isComplete());
    }

    @Test
    public void testCleanCompletenessMarkWhenNotComplete() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        assertFalse(list.isComplete());
        // Should not throw, just return early
        list.cleanCompletenessMark();
        assertFalse(list.isComplete());
    }

    @Test
    public void testEnsureRecursivelyComplete() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L, 3L));
        var returned = list.ensureRecursivelyComplete();
        assertSame(list, returned);
        assertTrue(list.isComplete());
    }

    @Test
    public void testEnsureRecursivelyCompleteAlreadyComplete() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        list.markAsComplete();
        // Should return immediately since already complete
        var returned = list.ensureRecursivelyComplete();
        assertSame(list, returned);
    }

    // --- toPrettyString tests ---

    @Test
    public void testPrettyStringEmpty() {
        var list = new ReadList<>(intArrayType(), List.of());
        String pretty = list.toPrettyString();
        assertTrue(pretty.contains("["));
        assertTrue(pretty.contains("]"));
    }

    @Test
    public void testPrettyStringWithValues() {
        var list = new ReadList<>(intArrayType(), List.of(1L, 2L));
        String pretty = list.toPrettyString();
        assertTrue(pretty.contains("1"));
        assertTrue(pretty.contains("2"));
    }

    @Test
    public void testPrettyStringWithNullValues() {
        List<Integer> ids = new ArrayList<>();
        ids.add(null);
        ids.add(1);
        var list = new ReadList<>(intArrayType(), ids, i -> (long) (i * 10));
        String pretty = list.toPrettyString();
        assertTrue(pretty.contains("null"));
    }

    @Test
    public void testPrettyStringDepthZero() {
        var list = new ReadList<>(intArrayType(), List.of(1L));
        String pretty = list.toPrettyString(0);
        assertNotNull(pretty);
    }
}
