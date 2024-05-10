package me.bechberger.condensed.types;

/**
 * Collection of reductions.
 *
 * <p>Each struct type and struct field can specify a specific reduction that transforms the value
 * to a reduced form and back.
 */
public interface Reductions {

    Reductions NONE =
            new Reductions() {
                @Override
                @SuppressWarnings("unchecked")
                public <R, F> R reduce(int id, F value) {
                    return (R) value;
                }

                @Override
                @SuppressWarnings("unchecked")
                public <R, F> F inflate(int id, R reduced) {
                    return (F) reduced;
                }
            };

    /**
     * Returns the passed value with the reduction of the given id
     *
     * <p>Id zero is reserved for no reduction
     */
    <R, F> R reduce(int id, F value);

    /**
     * Inflates the passed value with the reduction of the given id
     *
     * <p>Id zero is reserved for no reduction
     */
    <R, F> F inflate(int id, R reduced);
}
