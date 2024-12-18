package me.bechberger.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Cache}
 *
 * <p>The previous implementation did not work as expected, so we had to write our own and test it
 */
public class CacheTest {

    @Test
    public void testPutAndRemove() {
        List<Entry<String, Integer>> removed = new ArrayList<>();
        Cache<String, Integer> cache =
                new Cache<>(2) {
                    @Override
                    public void onRemove(String key, Integer value) {
                        removed.add(Map.entry(key, value));
                    }
                };
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        assertEquals(2, cache.get("b"));
        assertEquals(3, cache.get("c"));
        assertNull(cache.get("a"));
        assertEquals(List.of(Map.entry("a", 1)), removed);
    }

    @Test
    public void testPutWithoutRemove() {
        List<Integer> removed = new ArrayList<>();
        Cache<String, Integer> cache =
                new Cache<>(2) {
                    @Override
                    public void onRemove(String key, Integer value) {
                        removed.add(value);
                    }
                };
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(List.of(), removed);
        assertEquals(2, cache.size());
        assertTrue(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
        assertFalse(cache.containsKey("c"));
    }

    @Test
    public void testRemoveAll() {
        List<Integer> removed = new ArrayList<>();
        Cache<String, Integer> cache =
                new Cache<>(2) {
                    @Override
                    public void onRemove(String key, Integer value) {
                        removed.add(value);
                    }
                };
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.clear();
        assertEquals(List.of(1, 2, 3), removed);
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
    }

    @Test
    public void testPutWithoutRemoveBecauseKeyExists() {
        List<Integer> removed = new ArrayList<>();
        Cache<String, Integer> cache =
                new Cache<>(2) {
                    @Override
                    public void onRemove(String key, Integer value) {
                        removed.add(value);
                    }
                };
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 3);
        assertEquals(3, cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(List.of(), removed);
        assertEquals(2, cache.size());
        assertTrue(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
    }

    @Test
    public void testOnRemoveKeysAndValue() {
        List<Map.Entry<Integer, Integer>> removed = new ArrayList<>();
        Cache<Integer, Integer> cache =
                new Cache<>(2) {
                    @Override
                    public void onRemove(Integer key, Integer value) {
                        removed.add(Map.entry(key, value));
                    }
                };
        cache.put(2, 3);
        cache.put(4, 5);
        assertEquals(3, cache.get(2));
        assertEquals(0, removed.size());
        cache.put(6, 7);
        assertEquals(1, removed.size());
        assertEquals(Map.entry(2, 3), removed.get(0));
        cache.clear();
        assertEquals(3, removed.size());
        assertEquals(Map.entry(2, 3), removed.get(0));
        assertEquals(Map.entry(4, 5), removed.get(1));
        assertEquals(Map.entry(6, 7), removed.get(2));
    }
}
