package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.types.IntType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import org.junit.jupiter.api.Test;

public class ReadStructTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StructType<?, ReadStruct> createType(String... fieldNames) {
        var intType = IntType.SPECIFIED_TYPE.getDefaultType(IntType.SPECIFIED_TYPE.id());
        List<Field<Object, ?, ?>> fields = new ArrayList<>();
        for (String name : fieldNames) {
            fields.add(new Field<>(name, "", intType, o -> null, EmbeddingType.INLINE));
        }
        return new StructType<>(200, "testStruct", List.copyOf(fields));
    }

    private static ReadStruct makeStruct(Map<String, Object> values, String... fieldNames) {
        var type = createType(fieldNames);
        return new ReadStruct(type, new HashMap<>(values));
    }

    // --- Basic operations ---

    @Test
    public void testSize() {
        var struct = makeStruct(Map.of("a", 1L, "b", 2L), "a", "b");
        assertEquals(2, struct.size());
    }

    @Test
    public void testIsEmpty() {
        var emptyType = createType();
        var struct = new ReadStruct(emptyType, new HashMap<>());
        assertTrue(struct.isEmpty());
    }

    @Test
    public void testIsNotEmpty() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertFalse(struct.isEmpty());
    }

    @Test
    public void testContainsKey() {
        var struct = makeStruct(Map.of("a", 1L, "b", 2L), "a", "b");
        assertTrue(struct.containsKey("a"));
        assertTrue(struct.containsKey("b"));
        assertFalse(struct.containsKey("c"));
        assertFalse(struct.containsKey(42)); // non-string key
    }

    @Test
    public void testGet() {
        var struct = makeStruct(Map.of("a", 1L, "b", 2L), "a", "b");
        assertEquals(1L, struct.get("a"));
        assertEquals(2L, struct.get("b"));
        assertNull(struct.get("nonexistent"));
        assertNull(struct.get(42));
    }

    @Test
    public void testGetTyped() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertEquals(1L, struct.get("a", Long.class));
        assertEquals(1L, struct.get(Long.class, "a"));
    }

    @Test
    public void testGetOrThrow() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertEquals(1L, struct.getOrThrow("a"));
    }

    @Test
    public void testGetOrThrowMissing() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertThrows(NoSuchElementException.class, () -> struct.getOrThrow("nonexistent"));
    }

    @Test
    public void testContainsValue() {
        var struct = makeStruct(Map.of("a", 1L, "b", 2L), "a", "b");
        assertTrue(struct.containsValue(1L));
        assertFalse(struct.containsValue(99L));
    }

    @Test
    public void testKeySet() {
        var struct = makeStruct(Map.of("a", 1L, "b", 2L), "a", "b");
        assertEquals(Set.of("a", "b"), struct.keySet());
    }

    @Test
    public void testValues() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertTrue(struct.values().contains(1L));
    }

    @Test
    public void testEntrySet() {
        var struct = makeStruct(Map.of("x", 42L), "x");
        var entries = struct.entrySet();
        assertEquals(1, entries.size());
        var entry = entries.iterator().next();
        assertEquals("x", entry.getKey());
        assertEquals(42L, entry.getValue());
    }

    @Test
    public void testHasField() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertTrue(struct.hasField("a"));
        assertFalse(struct.hasField("missing"));
    }

    // --- Read-only operations ---

    @Test
    public void testPutThrows() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertThrows(UnsupportedOperationException.class, () -> struct.put("a", 2L));
    }

    @Test
    public void testRemoveThrows() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertThrows(UnsupportedOperationException.class, () -> struct.remove("a"));
    }

    @Test
    public void testPutAllThrows() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertThrows(UnsupportedOperationException.class, () -> struct.putAll(Map.of("b", 2L)));
    }

    @Test
    public void testClearThrows() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertThrows(UnsupportedOperationException.class, struct::clear);
    }

    // --- Constructor validation ---

    @Test
    public void testConstructorMissingFieldThrows() {
        var type = createType("a", "b");
        assertThrows(IllegalArgumentException.class, () -> new ReadStruct(type, Map.of("a", 1L)));
    }

    // --- Lazy loading ---

    @Test
    public void testLazyLoading() {
        var type = createType("a", "b");
        Map<String, Object> direct = new HashMap<>();
        direct.put("a", 1L);
        Map<String, Integer> ids = new HashMap<>();
        ids.put("b", 42);
        var struct = new ReadStruct(type, direct, ids, (field, id) -> (long) id * 10);

        // "a" is in direct map
        assertEquals(1L, struct.get("a"));
        // "b" should be resolved lazily
        assertEquals(420L, struct.get("b"));
    }

    @Test
    public void testLazyLoadingWithNullId() {
        var type = createType("a");
        Map<String, Object> direct = new HashMap<>();
        Map<String, Integer> ids = new HashMap<>();
        ids.put("a", null);
        var struct = new ReadStruct(type, direct, ids, (field, id) -> "resolved");

        assertNull(struct.get("a"));
    }

    @Test
    public void testEnsureComplete() {
        var type = createType("a", "b");
        Map<String, Object> direct = new HashMap<>();
        direct.put("a", 1L);
        Map<String, Integer> ids = new HashMap<>();
        ids.put("b", 5);
        var struct = new ReadStruct(type, direct, ids, (field, id) -> 100L);

        struct.ensureComplete();
        assertEquals(1L, struct.get("a"));
        assertEquals(100L, struct.get("b"));
    }

    @Test
    public void testEnsureRecursivelyComplete() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertFalse(struct.isComplete());
        struct.ensureRecursivelyComplete();
        assertTrue(struct.isComplete());
    }

    @Test
    public void testEnsureRecursivelyCompleteAlreadyComplete() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        struct.markAsComplete();
        // Should return immediately
        var returned = struct.ensureRecursivelyComplete();
        assertSame(struct, returned);
    }

    // --- Completeness marking ---

    @Test
    public void testMarkAsComplete() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertFalse(struct.isComplete());
        struct.markAsComplete();
        assertTrue(struct.isComplete());
    }

    @Test
    public void testCleanCompletenessMark() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        struct.markAsComplete();
        struct.cleanCompletenessMark();
        assertFalse(struct.isComplete());
    }

    @Test
    public void testCleanCompletenessMarkWhenAlreadyClean() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        // Should not throw
        struct.cleanCompletenessMark();
        assertFalse(struct.isComplete());
    }

    // --- Equals / hashCode ---

    @Test
    public void testEquals() {
        var struct1 = makeStruct(Map.of("a", 1L), "a");
        var struct2 = makeStruct(Map.of("a", 1L), "a");
        assertEquals(struct1, struct2);
    }

    @Test
    public void testNotEqualsDifferentValues() {
        var struct1 = makeStruct(Map.of("a", 1L), "a");
        var struct2 = makeStruct(Map.of("a", 2L), "a");
        assertNotEquals(struct1, struct2);
    }

    @Test
    public void testEqualsWithMap() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertEquals(struct, Map.of("a", 1L));
    }

    @Test
    public void testHashCodeConsistency() {
        var struct1 = makeStruct(Map.of("a", 1L), "a");
        var struct2 = makeStruct(Map.of("a", 1L), "a");
        assertEquals(struct1.hashCode(), struct2.hashCode());
    }

    // --- toString ---

    @Test
    public void testToString() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertNotNull(struct.toString());
        assertTrue(struct.toString().contains("1"));
    }

    // --- toPrettyString ---

    @Test
    public void testToPrettyString() {
        var struct = makeStruct(Map.of("a", 1L, "b", 2L), "a", "b");
        String pretty = struct.toPrettyString();
        assertNotNull(pretty);
        assertTrue(pretty.contains("a:"));
        assertTrue(pretty.contains("b:"));
    }

    @Test
    public void testToPrettyStringWithDepth() {
        var struct = makeStruct(Map.of("x", 42L), "x");
        String pretty = struct.toPrettyString(0);
        assertNotNull(pretty);
    }

    // --- copy ---

    @Test
    public void testCopy() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        var copy = struct.copy();
        assertEquals(struct, copy);
        assertNotSame(struct, copy);
    }

    // --- getType ---

    @Test
    public void testGetType() {
        var struct = makeStruct(Map.of("a", 1L), "a");
        assertNotNull(struct.getType());
        assertEquals("testStruct", struct.getType().getName());
    }
}
