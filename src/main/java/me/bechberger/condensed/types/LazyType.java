package me.bechberger.condensed.types;

import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LazyType<T, R> implements Supplier<CondensedType<T, R>> {
    private final int id;
    private final @Nullable Supplier<CondensedType<T, R>> supplier;
    private final String name;
    private CondensedType<T, R> type;

    public LazyType(int id, @NotNull Supplier<CondensedType<T, R>> supplier, String name) {
        this.id = id;
        this.supplier = supplier;
        this.name = name;
    }

    public LazyType(CondensedType<T, R> type) {
        this.id = type.getId();
        this.supplier = null;
        this.name = type.getName();
        this.type = type;
    }

    @Override
    public CondensedType<T, R> get() {
        if (type == null) {
            assert supplier != null;
            type = supplier.get();
        }
        return type;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        var other = (LazyType<?, ?>) obj;
        if (supplier != null || other.supplier != null) {
            return id == other.id;
        }
        return id == other.id && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, id);
    }

    @Override
    public String toString() {
        return type == null ? name : type.toString();
    }

    public int getId() {
        return id;
    }
}
