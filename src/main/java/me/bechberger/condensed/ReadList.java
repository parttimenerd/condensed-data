package me.bechberger.condensed;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import me.bechberger.condensed.types.ArrayType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ReadList<T> implements List<T>, ReadContainer<List<T>> {
    private final ArrayType<?, T> type;
    private final @Nullable List<@Nullable Integer> idsOrNull;
    private final List<T> list;
    private final @Nullable IntFunction<T> accessor;

    public ReadList(
            ArrayType<?, T> type,
            @NotNull List<@Nullable Integer> idsOrNull,
            @NotNull IntFunction<T> accessor) {
        this.type = type;
        this.idsOrNull = idsOrNull;
        this.list = new ArrayList<>();
        this.accessor = accessor;
    }

    public ReadList(ArrayType<?, T> type, List<T> list) {
        this.type = type;
        this.idsOrNull = null;
        this.list = list;
        this.accessor = null;
    }

    private T getDirectly(int index) {
        if (idsOrNull == null) {
            return list.get(index);
        }
        Integer id = idsOrNull.get(index);
        if (id == null) {
            return null;
        }
        assert accessor != null;
        return accessor.apply(id);
    }

    @Override
    public ReadList<T> ensureComplete() {
        if (accessor != null && idsOrNull != null && list.size() < idsOrNull.size()) {
            for (int i = list.size(); i < idsOrNull.size(); i++) {
                list.add(getDirectly(i));
            }
        }
        return this;
    }

    @Override
    public ReadList<T> ensureRecursivelyComplete(IdentityHashMap<Object, Void> checked) {
        ensureComplete();
        if (!type.getSpecifiedType().isPrimitive()) {
            list.replaceAll(t -> CompletableContainer.ensureRecursivelyComplete(t, checked));
        }
        return this;
    }

    @Override
    public int size() {
        return idsOrNull == null ? list.size() : idsOrNull.size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        ensureComplete();
        return list.contains(o);
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        ensureComplete();
        return list.iterator();
    }

    @NotNull
    @Override
    public Object @NotNull [] toArray() {
        ensureComplete();
        return list.toArray();
    }

    @Override
    public <E> E @NotNull [] toArray(E @NotNull [] a) {
        ensureComplete();
        return list.toArray(a);
    }

    @Override
    public boolean add(Object o) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        ensureComplete();
        return new HashSet<>(list).containsAll(c);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends T> c) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public boolean addAll(int index, @NotNull Collection<? extends T> c) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public T get(int index) {
        if (accessor == null) {
            return list.get(index);
        }
        assert idsOrNull != null;
        if (idsOrNull.get(index) == null) {
            return null;
        }
        while (list.size() <= index) {
            list.add(getDirectly(list.size()));
        }
        return list.get(index);
    }

    @Override
    public T set(int index, T element) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public void add(int index, T element) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public T remove(int index) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int indexOf(Object o) {
        ensureComplete();
        return list.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        ensureComplete();
        return list.lastIndexOf(o);
    }

    @NotNull
    @Override
    public ListIterator<T> listIterator() {
        ensureComplete();
        return list.listIterator();
    }

    @NotNull
    @Override
    public ListIterator<T> listIterator(int index) {
        ensureComplete();
        return list.listIterator(index);
    }

    @NotNull
    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        ensureComplete();
        return new ReadList<>(type, list.subList(fromIndex, toIndex));
    }

    @Override
    public String toString() {
        return list.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReadList<?> readList = (ReadList<?>) o;
        if ((accessor == null) == (readList.accessor != null)) {
            return false;
        }
        if (accessor == null) {
            return list.equals(readList.list);
        }
        assert idsOrNull != null;
        return idsOrNull.equals(readList.idsOrNull);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, idsOrNull, list);
    }

    @Override
    public String toPrettyString(
            int indent, IdentityHashMap<Object, Void> alreadyPrintedInPath, int depth) {
        StringBuilder sb = new StringBuilder();
        String indentString = " ".repeat(indent);
        String innerIndentString = " ".repeat(indent + 2);
        sb.append(indentString).append("[\n");
        for (int i = 0; i < size(); i++) {
            sb.append(innerIndentString);
            T element = get(i);
            if (element == null) {
                sb.append("null");
            } else if (element instanceof CompletableContainer) {
                if (alreadyPrintedInPath.containsKey(element) || depth == 0) {
                    sb.append("...");
                } else {
                    alreadyPrintedInPath.put(element, null);
                    sb.append(
                            ((CompletableContainer<?>) element)
                                    .toPrettyString(indent + 2, alreadyPrintedInPath, depth - 1));
                    alreadyPrintedInPath.remove(element);
                }
            } else {
                sb.append(element);
            }
            sb.append(",\n");
        }
        sb.append(indentString).append("]");
        return sb.toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public <K, V> List<Map.Entry<K, V>> asMapEntryList() {
        return (List<Map.Entry<K, V>>)
                ((List) this)
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
}
