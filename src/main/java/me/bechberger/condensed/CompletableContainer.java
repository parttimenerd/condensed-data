package me.bechberger.condensed;

import java.util.IdentityHashMap;

public interface CompletableContainer<T> {

    T ensureComplete();

    T ensureRecursivelyComplete(IdentityHashMap<Object, Void> checked);

    default String toPrettyString() {
        return toPrettyString(5);
    }

    default String toPrettyString(int depth) {
        return toPrettyString(0, new IdentityHashMap<>(), depth);
    }

    String toPrettyString(
            int indent, IdentityHashMap<Object, Void> alreadyPrintedInPath, int depth);

    default T ensureRecursivelyComplete() {
        return ensureRecursivelyComplete(new IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
    static <T> T ensureRecursivelyComplete(T t) {
        return ensureRecursivelyComplete(t, new IdentityHashMap<>());
    }

    @SuppressWarnings("unchecked")
    static <T> T ensureRecursivelyComplete(T t, IdentityHashMap<Object, Void> checked) {
        if (t instanceof CompletableContainer) {
            if (checked.containsKey(t)) {
                return t;
            }
            checked.put(t, null);
            return ((CompletableContainer<T>) t).ensureRecursivelyComplete(checked);
        }
        return t;
    }
}
