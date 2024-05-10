package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;

import java.time.Instant;
import me.bechberger.condensed.types.Reductions;

/**
 * Reductions for JFR types, used to reduce and inflate values for storage, based the {@link
 * Configuration} and {@link Universe}.
 *
 * <p>The main idea here is that these reductions place the code for reducing and inflating close to
 * each other and allow for the versioning of reductions, by just adding a new reduction enum value.
 */
public enum JFRReduction {
    NONE(
            Object.class,
            Object.class,
            new ReductionFunction<Object, Object>() {
                @Override
                public Object reduce(Configuration configuration, Universe universe, Object value) {
                    return value;
                }

                @Override
                public Object inflate(
                        Configuration configuration, Universe universe, Object reduced) {
                    return reduced;
                }
            }),
    TIMESTAMP_REDUCTION(
            Instant.class,
            Long.class,
            new ReductionFunction<Long, Instant>() {
                @Override
                public Long reduce(Configuration configuration, Universe universe, Instant value) {
                    long l = toNanoSeconds(value);
                    // difference from last in condensed time units
                    long reduced =
                            (long) (l / (1_000_000_000.0 / configuration.timeStampTicksPerSecond()))
                                    - (long)
                                            (universe.getLastStartTimeNanos()
                                                    / (1_000_000_000.0
                                                            / configuration
                                                                    .timeStampTicksPerSecond()));
                    universe.setLastStartTimeNanos(l);
                    return reduced;
                }

                @Override
                public Instant inflate(
                        Configuration configuration, Universe universe, Long reduced) {
                    // difference from last in condensed nanoseconds
                    long nanoSeconds =
                            (long)
                                    (reduced
                                            * (1_000_000_000.0
                                                    / configuration.timeStampTicksPerSecond()));
                    nanoSeconds += universe.getLastStartTimeNanos();
                    universe.setLastStartTimeNanos(nanoSeconds);
                    System.out.println("     " + nanoSeconds);
                    return Instant.ofEpochSecond(0, nanoSeconds);
                }
            });

    interface ReductionFunction<R, F> {
        R reduce(Configuration configuration, Universe universe, F value);

        F inflate(Configuration configuration, Universe universe, R reduced);
    }

    private final Class<?> valueClass;
    private final Class<?> reducedClass;
    private final ReductionFunction<?, ?> function;

    record JFRReductions(Configuration configuration, Universe universe) implements Reductions {

        @Override
        @SuppressWarnings("unchecked")
        public <R, F> R reduce(int id, F value) {
            return (R) JFRReduction.get(id).reduce(configuration, universe, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R, F> F inflate(int id, R reduced) {
            return (F) JFRReduction.get(id).inflate(configuration, universe, reduced);
        }
    }

    @SuppressWarnings("unchecked")
    <F, R> JFRReduction(
            Class<F> valueClass, Class<R> reducedClass, ReductionFunction<R, F> function) {
        this.valueClass = valueClass;
        this.reducedClass = reducedClass;
        this.function = function;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object reduce(Configuration configuration, Universe universe, Object value) {
        if (value == null) {
            return null;
        }
        if (valueClass.isInstance(value)) {
            return ((ReductionFunction) function).reduce(configuration, universe, value);
        }
        throw new IllegalArgumentException(
                "Value "
                        + value
                        + " of class "
                        + value.getClass()
                        + " is not of the expected type "
                        + valueClass);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object inflate(Configuration configuration, Universe universe, Object reduced) {
        if (reduced == null) {
            return null;
        }
        if (reducedClass.isInstance(reduced)) {
            return ((ReductionFunction) function).inflate(configuration, universe, reduced);
        }
        throw new IllegalArgumentException(
                "Reduced "
                        + reduced
                        + " of class "
                        + reduced.getClass()
                        + " is not of the expected type "
                        + reducedClass);
    }

    public static JFRReduction get(int id) {
        return values()[id];
    }
}
