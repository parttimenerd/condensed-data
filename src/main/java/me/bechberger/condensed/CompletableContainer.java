package me.bechberger.condensed;

import java.util.IdentityHashMap;

/**
 * A container that can be completed lazily
 * <p>
 * To avoid infinite recursion, it records directly in each instance whether it has been marked complete.
 */
public interface CompletableContainer<T> {

    T ensureComplete();

    T ensureRecursivelyComplete();

    default String toPrettyString() {
        return toPrettyString(5);
    }

    default String toPrettyString(int depth) {
        return toPrettyString(0, new IdentityHashMap<>(), depth);
    }

    String toPrettyString(
            int indent, IdentityHashMap<Object, Void> alreadyPrintedInPath, int depth);

    /**
     * Recursively ensures that all nested CompletableContainers are complete, meaning that they resolved all
     * their lazy data.
     *
     * @param t the object to ensure completeness for
     */
    @SuppressWarnings("unchecked")
    static <T> T ensureRecursivelyComplete(T t) {
        if (t instanceof CompletableContainer) {
            var cont = (CompletableContainer<T>) t;
            return ((CompletableContainer<T>) t).ensureRecursivelyComplete();
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    static <T> CompletableContainer<T> ensureRecursivelyComplete(CompletableContainer<T> t) {
        if (t.isComplete()) {
            return t;
        }
        t.markAsComplete();
        return (CompletableContainer<T>)t.ensureRecursivelyComplete();
    }

    /** Whether this container is marked recursively complete */
    boolean isComplete();

    void markAsComplete();

    void cleanCompletenessMark();

    @SuppressWarnings("unchecked")
    static <T> void cleanRecursivenessMark(T t) {
        if (t instanceof CompletableContainer) {
            ((CompletableContainer<T>) t).cleanCompletenessMark();
        }
    }
}