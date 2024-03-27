package me.bechberger.condensed;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.types.CondensedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Universe {

    /**
     * Cache that stores values per value type
     *
     * @param <T> type of the values to cache
     */
    static class WritingCachePerType<T> {
        private final int size;
        private final Map<T, Integer> cache = new HashMap<>();
        private final ArrayDeque<T> cacheOrder = new ArrayDeque<>();
        private int lastId = -1;

        public WritingCachePerType(int size) {
            this.size = size;
        }

        /** Put value into cache, write out if needed, return id */
        public int get(T value, Consumer<T> writer) {
            if (cache.containsKey(value)) {
                return cache.get(value);
            } else {
                if (size != -1 && cache.size() >= size) {
                    cache.remove(cacheOrder.removeFirst());
                }
                int id = ++lastId;
                cache.put(value, id);
                cacheOrder.add(value);
                writer.accept(value);
                return id;
            }
        }
    }

    /**
     * Cache that stores values per value type and per embedding type
     *
     * @param <T>
     */
    static class WritingCachePerTypePerEmbeddingType<T> {
        private final int size;
        private final Map<CondensedType<?>, Map<T, Integer>> cache = new HashMap<>();
        private final ArrayDeque<Entry<Map<T, Integer>, T>> cacheOrder = new ArrayDeque<>();
        private final Map<CondensedType<?>, Integer> lastIds = new HashMap<>();

        public WritingCachePerTypePerEmbeddingType(int size) {
            this.size = size;
        }

        /** Put value into cache per embbeding type, write out if needed, return id */
        public int get(T value, Consumer<T> writer, CondensedType<?> embeddingType) {
            Map<T, Integer> typeCache = cache.computeIfAbsent(embeddingType, k -> new HashMap<>());
            if (typeCache.containsKey(value)) {
                return typeCache.get(value);
            } else {
                if (size != -1 && cacheOrder.size() >= size) {
                    var e = cacheOrder.removeFirst();
                    e.getKey().remove(e.getValue());
                }
                if (!lastIds.containsKey(embeddingType)) {
                    lastIds.put(embeddingType, -1);
                }
                int id = lastIds.get(embeddingType) + 1;
                lastIds.put(embeddingType, id);
                typeCache.put(value, id);
                cacheOrder.add(Map.entry(typeCache, value));
                writer.accept(value);
                return id;
            }
        }
    }

    /** The type of embedding for a field's value */
    public enum EmbeddingType {
        /** Embed a value directly */
        INLINE,
        /**
         * Store the value in a cache and reference it with an index per-value type, might be null
         */
        REFERENCE,
        /**
         * Store the value in a cache and reference it with an index per-value and per-embedding
         * type, might be null
         */
        REFERENCE_PER_TYPE;

        private final boolean isNullable;

        EmbeddingType() {
            isNullable = name().contains("NULLABLE");
        }

        public boolean isNullable() {
            return isNullable;
        }

        public static EmbeddingType valueOf(int value) {
            return values()[value];
        }
    }

    /**
     * Collection of caches for writing data out per type
     *
     * @see WritingCachePerType
     * @see WritingCachePerTypePerEmbeddingType
     */
    public static class WritingCaches {
        private final int sizePerCache;
        private final Map<CondensedType<?>, WritingCachePerType<?>> caches = new HashMap<>();
        private final Map<CondensedType<?>, WritingCachePerTypePerEmbeddingType<?>>
                embeddingCaches = new HashMap<>();

        public WritingCaches(int sizePerCache) {
            this.sizePerCache = sizePerCache;
        }

        @SuppressWarnings("unchecked")
        private <T> WritingCachePerType<T> getCache(CondensedType<T> type) {
            return (WritingCachePerType<T>)
                    caches.computeIfAbsent(type, k -> new WritingCachePerType<>(sizePerCache));
        }

        @SuppressWarnings("unchecked")
        private <T> WritingCachePerTypePerEmbeddingType<T> getEmbeddingCache(
                CondensedType<T> type) {
            return (WritingCachePerTypePerEmbeddingType<T>)
                    embeddingCaches.computeIfAbsent(
                            type, k -> new WritingCachePerTypePerEmbeddingType<>(sizePerCache));
        }

        /**
         * Store the given value in the cache, returning the id of the value, emit the value using
         * the given writer if not yet present
         *
         * @param type type of the value that contains the passed value
         * @param value value to store in the cache
         * @param writer writer to emit the value if it is not yet present
         * @return index of the value in the cache
         * @param <T> type of the value to cache
         */
        public <T> int get(CondensedType<T> type, T value, Consumer<T> writer) {
            return getCache(type).get(value, writer);
        }

        /**
         * Store the given value in the cache (per embedding type), returning the id of the value,
         * emit the value using the given writer if not yet present
         *
         * @param type type of the value that contains the passed value
         * @param embeddingType type of the value that contains the passed value
         * @param value value to store in the cache
         * @param writer writer to emit the value if it is not yet present
         * @return index of the value in the cache
         * @param <T> type of the value to cache
         */
        public <T> int get(
                CondensedType<T> type,
                T value,
                Consumer<T> writer,
                CondensedType<?> embeddingType) {
            return getEmbeddingCache(type).get(value, writer, embeddingType);
        }
    }

    /**
     * Used when reading data in via {@see CondensedInputStream}, caches values per type
     *
     * @param <T> type of the values to cache
     */
    public static class ReadingCachePerType<T> {

        private final List<T> values = new ArrayList<>();

        /**
         * Put the given value into the cache, returning the id of the value
         *
         * @param value value to put into the cache
         */
        public void put(T value) {
            values.add(value);
        }

        /**
         * Get the value with the given id from the cache
         *
         * @param id id of the value to get
         * @return value with the given id
         */
        public T get(int id) {
            if (id < 0 || id >= values.size()) {
                throw new IllegalArgumentException("Invalid id: " + id);
            }
            return values.get(id);
        }
    }

    /**
     * Used when reading data in via {@see CondensedInputStream}, caches values per type and per
     * embedding type
     *
     * @param <T> type of the values to cache
     */
    public static class ReadingCachePerTypePerEmbeddingType<T> {

        private final Map<CondensedType<?>, List<T>> values = new HashMap<>();

        /**
         * Put the given value into the cache, returning the id of the value
         *
         * @param embeddingType type of the value that contains the passed value
         * @param value value to put into the cache
         */
        public void put(CondensedType<?> embeddingType, T value) {
            values.computeIfAbsent(embeddingType, k -> new ArrayList<>()).add(value);
        }

        /**
         * Get the value with the given id from the cache
         *
         * @param embeddingType type of the value that contains the passed value
         * @param id id of the value to get
         * @return value with the given id
         */
        public T get(CondensedType<?> embeddingType, int id) {
            if (!values.containsKey(embeddingType)) {
                throw new IllegalArgumentException("Invalid type: " + embeddingType);
            }
            List<T> list = values.get(embeddingType);
            if (id < 0 || id >= list.size()) {
                throw new IllegalArgumentException("Invalid id: " + id);
            }
            return list.get(id);
        }
    }

    public static class ReadingCaches {
        private final Map<CondensedType<?>, ReadingCachePerType<?>> caches = new HashMap<>();
        private final Map<CondensedType<?>, ReadingCachePerTypePerEmbeddingType<?>>
                embeddingCaches = new HashMap<>();

        @SuppressWarnings("unchecked")
        private <T> ReadingCachePerType<T> getCache(CondensedType<T> type) {
            return (ReadingCachePerType<T>)
                    caches.computeIfAbsent(type, k -> new ReadingCachePerType<>());
        }

        @SuppressWarnings("unchecked")
        private <T> ReadingCachePerTypePerEmbeddingType<T> getEmbeddingCache(
                CondensedType<T> type) {
            return (ReadingCachePerTypePerEmbeddingType<T>)
                    embeddingCaches.computeIfAbsent(
                            type, k -> new ReadingCachePerTypePerEmbeddingType<>());
        }

        public <T> void put(CondensedType<T> type, T value) {
            getCache(type).put(value);
        }

        public <T> void put(CondensedType<T> type, CondensedType<?> embeddingType, T value) {
            getEmbeddingCache(type).put(embeddingType, value);
        }

        public <T> T get(CondensedType<T> type, int id) {
            return getCache(type).get(id);
        }

        public <T> T get(CondensedType<T> type, CondensedType<?> embeddingType, int id) {
            return getEmbeddingCache(type).get(embeddingType, id);
        }
    }

    private @Nullable StartMessage startMessage;
    private static final int DEFAULT_SIZE = 100000;
    private final WritingCaches writingCaches;
    private final ReadingCaches readingCaches = new ReadingCaches();

    public Universe(int cacheSize) {
        writingCaches = new WritingCaches(cacheSize);
    }

    public Universe() {
        this(DEFAULT_SIZE);
    }

    void setStartMessage(@NotNull StartMessage startMessage) {
        this.startMessage = startMessage;
    }

    public @Nullable StartMessage getStartMessage() {
        return startMessage;
    }

    public boolean hasStartMessage() {
        return startMessage != null;
    }

    public WritingCaches getWritingCaches() {
        return writingCaches;
    }

    public ReadingCaches getReadingCaches() {
        return readingCaches;
    }
}
