package me.bechberger.condensed;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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

    public interface HashAndEqualsWrapper<V> {
        V value();

        int hashCode();
    }

    static class WritingCachePerTypeWithCustomHash<T> extends WritingCachePerType<T> {

        private final Function<T, HashAndEqualsWrapper<T>> wrapperFactory;
        private final WritingCachePerType<HashAndEqualsWrapper<T>> cache;

        public WritingCachePerTypeWithCustomHash(
                int size, Function<T, HashAndEqualsWrapper<T>> wrapperFactory) {
            super(0);
            this.wrapperFactory = wrapperFactory;
            this.cache = new WritingCachePerType<>(size);
        }

        @Override
        public int get(T value, Consumer<T> writer) {
            return cache.get(
                    wrapperFactory.apply(value), wrapper -> writer.accept(wrapper.value()));
        }
    }

    /**
     * Cache that stores values per value type and per embedding type
     *
     * @param <T>
     */
    static class WritingCachePerTypePerEmbeddingType<T> {
        private final int size;
        private final Map<CondensedType<?, ?>, Map<T, Integer>> cache = new HashMap<>();
        private final ArrayDeque<Entry<Map<T, Integer>, T>> cacheOrder = new ArrayDeque<>();
        private final Map<CondensedType<?, ?>, Integer> lastIds = new HashMap<>();

        public WritingCachePerTypePerEmbeddingType(int size) {
            this.size = size;
        }

        /** Put value into cache per embbeding type, write out if needed, return id */
        public int get(T value, Consumer<T> writer, CondensedType<?, ?> embeddingType) {
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

    static class WritingCachePerTypePerEmbeddingTypeWithCustomHash<T>
            extends WritingCachePerTypePerEmbeddingType<T> {

        private final Function<T, HashAndEqualsWrapper<T>> wrapperFactory;
        private final WritingCachePerTypePerEmbeddingType<HashAndEqualsWrapper<T>> cache;

        public WritingCachePerTypePerEmbeddingTypeWithCustomHash(
                int size, Function<T, HashAndEqualsWrapper<T>> wrapperFactory) {
            super(0);
            this.wrapperFactory = wrapperFactory;
            this.cache = new WritingCachePerTypePerEmbeddingType<>(size);
        }

        @Override
        public int get(T value, Consumer<T> writer, CondensedType<?, ?> embeddingType) {
            return cache.get(
                    wrapperFactory.apply(value),
                    wrapper -> writer.accept(wrapper.value()),
                    embeddingType);
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

    public static class HashAndEqualsConfig {

        public static final HashAndEqualsConfig NONE = new HashAndEqualsConfig();

        private final Map<String, Function<?, HashAndEqualsWrapper<?>>> wrapperFactories =
                new HashMap<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> void put(String name, Function<T, HashAndEqualsWrapper<T>> factory) {
            wrapperFactories.put(name, (Function<?, HashAndEqualsWrapper<?>>) (Function) factory);
        }

        public <T> void put(
                CondensedType<T, ?> type, Function<T, HashAndEqualsWrapper<T>> factory) {
            put(type.getName(), factory);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> Optional<Function<T, HashAndEqualsWrapper<T>>> getWrapperFactory(
                CondensedType<T, ?> type) {
            return Optional.ofNullable(
                    (Function<T, HashAndEqualsWrapper<T>>)
                            (Function) wrapperFactories.get(type.getName()));
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
        private final HashAndEqualsConfig hashAndEqualsConfig;
        private final Map<CondensedType<?, ?>, WritingCachePerType<?>> caches = new HashMap<>();
        private final Map<CondensedType<?, ?>, WritingCachePerTypePerEmbeddingType<?>>
                embeddingCaches = new HashMap<>();

        public WritingCaches(HashAndEqualsConfig config, int sizePerCache) {
            this.hashAndEqualsConfig = config;
            this.sizePerCache = sizePerCache;
        }

        @SuppressWarnings("unchecked")
        private <T, R> WritingCachePerType<T> getCache(CondensedType<T, R> type) {
            return (WritingCachePerType<T>)
                    caches.computeIfAbsent(
                            type,
                            k ->
                                    hashAndEqualsConfig
                                            .getWrapperFactory(type)
                                            .map(
                                                    factory ->
                                                            (WritingCachePerType<T>)
                                                                    new WritingCachePerTypeWithCustomHash<>(
                                                                            sizePerCache, factory))
                                            .orElseGet(
                                                    () -> new WritingCachePerType<>(sizePerCache)));
        }

        @SuppressWarnings("unchecked")
        private <T, R> WritingCachePerTypePerEmbeddingType<T> getEmbeddingCache(
                CondensedType<T, R> type) {
            return (WritingCachePerTypePerEmbeddingType<T>)
                    embeddingCaches.computeIfAbsent(
                            type,
                            k ->
                                    hashAndEqualsConfig
                                            .getWrapperFactory(type)
                                            .map(
                                                    factory ->
                                                            (WritingCachePerTypePerEmbeddingType<T>)
                                                                    new WritingCachePerTypePerEmbeddingTypeWithCustomHash<>(
                                                                            sizePerCache, factory))
                                            .orElseGet(
                                                    () ->
                                                            new WritingCachePerTypePerEmbeddingType<>(
                                                                    sizePerCache)));
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
        public <T, R> int get(CondensedType<T, R> type, T value, Consumer<T> writer) {
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
        public <T, R> int get(
                CondensedType<T, R> type,
                T value,
                Consumer<T> writer,
                CondensedType<?, ?> embeddingType) {
            return getEmbeddingCache(type).get(value, writer, embeddingType);
        }

        public boolean isEmpty() {
            return caches.isEmpty() && embeddingCaches.isEmpty();
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
         * @param reader value to put into the cache
         */
        public int put(Supplier<T> reader) {
            // mimic writing cache behavior
            int id = values.size();
            values.add(null);
            values.set(id, reader.get());
            return id;
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

        private final Map<CondensedType<?, ?>, List<T>> values = new HashMap<>();

        /**
         * Put the given value into the cache, returning the id of the value
         *
         * @param embeddingType type of the value that contains the passed value
         * @param reader value to put into the cache
         */
        public int put(CondensedType<?, ?> embeddingType, Supplier<T> reader) {
            var list = values.computeIfAbsent(embeddingType, k -> new ArrayList<>());
            int id = list.size();
            list.add(null);
            list.set(id, reader.get());
            return id;
        }

        /**
         * Get the value with the given id from the cache
         *
         * @param embeddingType type of the value that contains the passed value
         * @param id id of the value to get
         * @return value with the given id
         */
        public T get(CondensedType<?, ?> embeddingType, int id) {
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
        private final Map<CondensedType<?, ?>, ReadingCachePerType<?>> caches = new HashMap<>();
        private final Map<CondensedType<?, ?>, ReadingCachePerTypePerEmbeddingType<?>>
                embeddingCaches = new HashMap<>();

        @SuppressWarnings("unchecked")
        private <T, R> ReadingCachePerType<R> getCache(CondensedType<T, R> type) {
            return (ReadingCachePerType<R>)
                    caches.computeIfAbsent(type, k -> new ReadingCachePerType<>());
        }

        @SuppressWarnings("unchecked")
        private <T, R> ReadingCachePerTypePerEmbeddingType<R> getEmbeddingCache(
                CondensedType<T, R> type) {
            return (ReadingCachePerTypePerEmbeddingType<R>)
                    embeddingCaches.computeIfAbsent(
                            type, k -> new ReadingCachePerTypePerEmbeddingType<>());
        }

        public <T, R> int put(CondensedType<T, R> type, Supplier<R> reader) {
            return getCache(type).put(reader);
        }

        public <T, R> int put(
                CondensedType<T, R> type, CondensedType<?, ?> embeddingType, Supplier<R> reader) {
            return getEmbeddingCache(type).put(embeddingType, reader);
        }

        public <T, R> R get(CondensedType<T, R> type, int id) {
            return getCache(type).get(id);
        }

        public <T, R> R get(CondensedType<T, R> type, CondensedType<?, ?> embeddingType, int id) {
            return getEmbeddingCache(type).get(embeddingType, id);
        }
    }

    private @Nullable StartMessage startMessage;

    public static final int DEFAULT_SIZE = 20000;
    private WritingCaches writingCaches;
    private final ReadingCaches readingCaches = new ReadingCaches();

    public Universe(HashAndEqualsConfig config, int cacheSize) {
        writingCaches = new WritingCaches(config, cacheSize);
    }

    public Universe(HashAndEqualsConfig hashAndEqualsConfig) {
        this(hashAndEqualsConfig, DEFAULT_SIZE);
    }

    public Universe() {
        this(HashAndEqualsConfig.NONE);
    }

    public Universe(int cacheSize) {
        this(HashAndEqualsConfig.NONE, cacheSize);
    }

    void setHashAndEqualsConfig(HashAndEqualsConfig config) {
        if (!writingCaches.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot change config after writing caches have been created");
        }
        writingCaches = new WritingCaches(config, DEFAULT_SIZE);
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
