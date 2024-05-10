package me.bechberger.condensed.types;

import static me.bechberger.condensed.Universe.EmbeddingType.INLINE;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.Universe.ReadingCaches;
import me.bechberger.condensed.Universe.WritingCaches;

/**
 * A type that can be written to and read from a condensed stream
 *
 * @param <T> The type that is written
 * @param <R> The type that is read
 */
public abstract class CondensedType<T, R> {

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
    public abstract SpecifiedType<? extends CondensedType<T, R>> getSpecifiedType();

    /** Write the type specification to the stream (excluding the header) */
    public abstract void writeTo(CondensedOutputStream out, T value);

    /**
     * Write the given value to the stream, using the specified embedding type and caching if needed
     *
     * @see EmbeddingType
     */
    public void writeTo(
            CondensedOutputStream out,
            T value,
            CondensedType<?, ?> embeddingType,
            EmbeddingType embedding) {
        if (embedding == INLINE) {
            Objects.requireNonNull(value, "Value must not be null");
            if (value instanceof Integer && this instanceof IntType) {
                ((IntType) this).writeTo(out, (long) (Integer) value);
                return;
            }
            writeTo(out, value);
            return;
        }
        WritingCaches caches = out.getUniverse().getWritingCaches();
        AtomicBoolean cached = new AtomicBoolean(true);
        Consumer<T> writer =
                v -> {
                    out.writeUnsignedVarInt(1);
                    writeTo(out, v);
                    cached.set(false);
                };
        if (value == null) {
            out.writeUnsignedVarInt(0);
        } else {
            var index =
                    switch (embedding) {
                        case REFERENCE -> caches.get(this, value, writer);
                        case REFERENCE_PER_TYPE -> caches.get(this, value, writer, embeddingType);
                        default ->
                                throw new IllegalArgumentException(
                                        "Invalid embedding type: " + embedding);
                    };
            if (cached.get()) {
                out.writeUnsignedVarInt(index + 2);
            }
        }
    }

    /** Read the type specification from the stream (excluding the header) */
    public abstract R readFrom(CondensedInputStream in);

    public R readFrom(
            CondensedInputStream in, CondensedType<?, ?> embeddingType, EmbeddingType embedding) {
        if (embedding == INLINE) {
            return readFrom(in);
        }
        var index = readReference(in, embeddingType, embedding);
        return getViaReference(in, embeddingType, embedding, index);
    }

    public R getViaReference(
            CondensedInputStream in,
            CondensedType<?, ?> embeddingType,
            EmbeddingType embedding,
            int index) {
        if (index == -1) {
            return null;
        }
        ReadingCaches caches = in.getUniverse().getReadingCaches();
        return switch (embedding) {
            case REFERENCE -> caches.get(this, index);
            case REFERENCE_PER_TYPE -> caches.get(this, embeddingType, index);
            default -> throw new IllegalArgumentException("Invalid embedding type: " + embedding);
        };
    }

    /** Returns the reference index or -1 if null */
    public int readReference(
            CondensedInputStream in, CondensedType<?, ?> embeddingType, EmbeddingType embedding) {
        assert embedding == EmbeddingType.REFERENCE
                || embedding == EmbeddingType.REFERENCE_PER_TYPE;
        ReadingCaches caches = in.getUniverse().getReadingCaches();
        var index = (int) in.readUnsignedVarint();
        switch (index) {
            case 0 -> {
                return -1;
            }
            case 1 -> {
                var value = readFrom(in);
                return switch (embedding) {
                    case REFERENCE -> caches.put(this, value);
                    case REFERENCE_PER_TYPE -> caches.put(this, embeddingType, value);
                    default ->
                            throw new IllegalArgumentException(
                                    "Invalid embedding type: " + embedding);
                };
            }
            default -> {
                return index - 2;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof LazyType<?, ?>) {
            return ((LazyType<?, ?>) o).getId() == id;
        }
        if (o == null || getClass() != o.getClass()) return false;

        CondensedType<?, ?> that = (CondensedType<?, ?>) o;

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

    public String toPrettyString(int indent) {
        return " ".repeat(indent) + this;
    }

    public String toPrettyString() {
        return toPrettyString(0);
    }
}
