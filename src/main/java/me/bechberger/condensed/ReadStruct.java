package me.bechberger.condensed;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import me.bechberger.condensed.types.StructType;
import me.bechberger.condensed.types.StructType.Field;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A read-only struct that loads its values lazily */
public class ReadStruct implements Map<String, Object>, ReadContainer<ReadStruct> {
    private final StructType<?, ReadStruct> type;
    private final Map<String, Object> map;
    private final @Nullable BiFunction<Field<?, ?, ?>, Integer, Object> accessor;
    private final @Nullable Map<String, @Nullable Integer> idsOrNull;

    private ReadStruct(
            StructType<?, ReadStruct> type,
            Map<String, Object> map,
            @Nullable BiFunction<Field<?, ?, ?>, Integer, Object> accessor,
            @Nullable Map<String, @Nullable Integer> idsOrNull) {
        this.type = type;
        this.map = map;
        this.accessor = accessor;
        this.idsOrNull = idsOrNull;
    }

    public ReadStruct(
            StructType<?, ReadStruct> type,
            Map<String, Object> others,
            @NotNull Map<String, @Nullable Integer> idsOrNull,
            @NotNull BiFunction<Field<?, ?, ?>, Integer, Object> accessor) {
        this.type = type;
        this.map = others;
        this.accessor = accessor;
        this.idsOrNull = idsOrNull;
    }

    public ReadStruct(StructType<?, ReadStruct> type, Map<String, Object> map) {
        this.type = type;
        this.map = map;
        this.accessor = null;
        this.idsOrNull = null;
        for (var field : type.getFieldNames()) {
            if (!map.containsKey(field)) {
                throw new IllegalArgumentException("Missing field " + field);
            }
        }
    }

    @Override
    public int size() {
        return type.size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && type.hasField((String) key);
    }

    private @Nullable Object getDirectly(String key) {
        if (idsOrNull == null) {
            return map.get(key);
        }
        Integer id = idsOrNull.get(key);
        if (id == null) {
            return null;
        }
        assert accessor != null;
        return Objects.requireNonNull(accessor.apply(type.getField(key), id));
    }

    @Override
    public ReadStruct ensureComplete() {
        if (accessor != null && map.size() < type.size()) {
            for (var field : type.getFieldNames()) {
                if (!map.containsKey(field)) {
                    map.put(field, getDirectly(field));
                }
            }
        }
        return this;
    }

    @Override
    public ReadStruct ensureRecursivelyComplete(IdentityHashMap<Object, Void> checked) {
        ensureComplete();
        for (var field : type.getFields()) {
            if (!field.type().getSpecifiedType().isPrimitive()) {
                map.put(
                        field.name(),
                        CompletableContainer.ensureRecursivelyComplete(
                                map.get(field.name()), checked));
            }
        }
        return this;
    }

    @Override
    public boolean containsValue(Object value) {
        ensureComplete();
        return map.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        if (!(key instanceof String) || !type.hasField((String) key)) {
            return null;
        }
        if (!map.containsKey(key)) {
            assert accessor != null;
            map.put((String) key, getDirectly((String) key));
        }
        return map.get(key);
    }

    public Object getOrThrow(Object key) {
        Object value = get(key);
        if (value == null) {
            throw new NoSuchElementException("No such key: " + key);
        }
        return value;
    }

    public <T> T get(Class<T> clazz, String key) {
        return clazz.cast(get(key));
    }

    public <T> T get(String key, Class<T> clazz) {
        return clazz.cast(get(key));
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        return (List<T>) get(List.class, key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T, V> List<Map.Entry<T, V>> asMapEntryList(String key) {
        var val = getOrThrow(key);
        if (!(val instanceof List)) {
            throw new AssertionError("Expected list got " + val.getClass());
        }
        return (List<Map.Entry<T, V>>)
                ((List) val)
                        .stream()
                                .map(
                                        v -> {
                                            if (!(v instanceof Map)
                                                    || !((Map<?, ?>) v).containsKey("key")
                                                    || !((Map<?, ?>) v).containsKey("value")) {
                                                throw new AssertionError(
                                                        "Expected mapped pair (key, value), but got"
                                                                + " "
                                                                + v);
                                            }
                                            return Map.entry(
                                                    ((Map<?, ?>) v).get("key"),
                                                    ((Map<?, ?>) v).get("value"));
                                        })
                                .collect(Collectors.toList());
    }

    public ReadStruct getStruct(String key) {
        return get(ReadStruct.class, key);
    }

    @Nullable
    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public void putAll(@NotNull Map<? extends String, ?> m) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Read-only");
    }

    @NotNull
    @Override
    public Set<String> keySet() {
        return new HashSet<>(type.getFieldNames());
    }

    @NotNull
    @Override
    public Collection<Object> values() {
        ensureComplete();
        return map.values();
    }

    @NotNull
    @Override
    public Set<Entry<String, Object>> entrySet() {
        ensureComplete();
        return map.entrySet();
    }

    public StructType<?, ReadStruct> getType() {
        return type;
    }

    @Override
    public boolean equals(Object obj) {
        ensureComplete();
        return (obj instanceof ReadStruct
                        && type.equals(((ReadStruct) obj).type)
                        && map.equals(((ReadStruct) obj).map))
                || (obj instanceof Map && map.equals(obj));
    }

    @Override
    public int hashCode() {
        ensureComplete();
        return Objects.hash(type, map);
    }

    @Override
    public String toString() {
        return map.toString();
    }

    protected ReadStruct copy() {
        return new ReadStruct(type, new HashMap<>(map), accessor, idsOrNull);
    }

    public boolean hasField(String name) {
        return type.hasField(name);
    }

    @Override
    public String toPrettyString(
            int indent, IdentityHashMap<Object, Void> alreadyPrintedInPath, int depth) {
        StringBuilder sb = new StringBuilder();
        String indentString = " ".repeat(indent);
        String innerIndentString = " ".repeat(indent + 2);
        sb.append("{\n");
        for (var field : type.getFields()) {
            sb.append(innerIndentString).append(field.name()).append(": ");
            Object value = get(field.name());
            if (value instanceof CompletableContainer) {
                if (alreadyPrintedInPath.containsKey(value) || depth == 0) {
                    sb.append("...\n");
                } else {
                    alreadyPrintedInPath.put(value, null);
                    sb.append(
                            ((CompletableContainer<?>) value)
                                    .toPrettyString(indent + 2, alreadyPrintedInPath, depth - 1)
                                    .strip());
                    sb.append("\n");
                    alreadyPrintedInPath.remove(value);
                }
            } else {
                sb.append(value).append("\n");
            }
        }
        sb.append(indentString).append("}");
        return sb.toString();
    }
}
