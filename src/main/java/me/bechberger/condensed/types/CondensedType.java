package me.bechberger.condensed.types;

import java.util.Objects;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

public abstract class CondensedType<T> {

    private final int id;
    private final String name;
    private final String description;

    public CondensedType(int id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** Abstract type */
    public abstract SpecifiedType<?> getSpecifiedType();

    /** Write the type specification to the stream (excluding the header) */
    public abstract void writeTo(CondensedOutputStream out, T value);

    /** Read the type specification from the stream (excluding the header) */
    public abstract T readFrom(CondensedInputStream in);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CondensedType<?> that = (CondensedType<?>) o;

        if (id != that.id) return false;
        if (!Objects.equals(name, that.name)) return false;
        return Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{"
                + "id="
                + id
                + ", name='"
                + name
                + '\''
                + ", description='"
                + description
                + '\''
                + '}';
    }
}
