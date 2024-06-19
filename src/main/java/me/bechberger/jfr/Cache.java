package me.bechberger.jfr;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/** A cache that removes the oldest entry when full */
public abstract class Cache<K, V> {
    private final Map<K, V> backing;
    private final Queue<K> keys;
    private final int maxSize;

    public Cache(int maxSize) {
        this.maxSize = maxSize;
        this.backing = new HashMap<>();
        this.keys = new LinkedList<>();
    }

    /** Called when an entry is removed from the cache */
    public abstract void onRemove(K key, V value);

    /**
     * Puts a new entry into the cache, removing the oldest if the cache is full, calling {@link
     * #onRemove(K, V)} for the removed entry
     */
    public void put(K key, V value) {
        if (backing.size() >= maxSize) {
            K oldest = keys.poll();
            backing.remove(oldest);
            onRemove(oldest, value);
        }
        keys.add(key);
        backing.put(key, value);
    }

    public V get(K key) {
        return backing.get(key);
    }

    /** Removes all entries and calls {@link #onRemove(K, V)} for each */
    public void removeAll() {
        for (K key : keys) {
            onRemove(key, backing.get(key));
        }
        backing.clear();
        keys.clear();
    }
}
