package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;
import static me.bechberger.util.TimeUtil.clamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import me.bechberger.condensed.ReadStruct;
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
            new ReductionFunction<>() {
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
            new ReductionFunction<>() {
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
                    return Instant.ofEpochSecond(0, nanoSeconds);
                }
            }),
    TIMESPAN_REDUCTION(
            Duration.class,
            Long.class,
            new ReductionFunction<>() {
                @Override
                public Long reduce(Configuration configuration, Universe universe, Duration value) {
                    return clamp(value).toNanos();
                }

                @Override
                public Duration inflate(
                        Configuration configuration, Universe universe, Long reduced) {
                    return Duration.ofNanos(reduced);
                }
            });

    static class ReducedStackTrace {

        private final List<RecordedFrame> frames;
        private final boolean truncated;
        private final RecordedStackTrace backing;

        private ReducedStackTrace(RecordedStackTrace stackTrace) {
            this.frames = stackTrace.getFrames();
            this.truncated = stackTrace.isTruncated();
            this.backing = stackTrace;
        }

        private ReducedStackTrace(
                RecordedStackTrace backing, List<RecordedFrame> frames, boolean truncated) {
            this.frames = frames;
            this.truncated = truncated;
            this.backing = backing;
        }

        static ReducedStackTrace create(RecordedStackTrace stackTrace, int limit) {
            var frames = stackTrace.getFrames();
            if (frames.size() <= limit || limit == -1) {
                return new ReducedStackTrace(stackTrace);
            }
            return new ReducedStackTrace(
                    stackTrace, stackTrace.getFrames().subList(0, limit), true);
        }

        List<RecordedFrame> getFrames() {
            return frames;
        }

        boolean isTruncated() {
            return truncated;
        }

        @Override
        public int hashCode() {
            return backing.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ReducedStackTrace other)) {
                return false;
            }
            return other.backing == backing;
        }
    }

    interface ReductionFunction<R, F> {
        R reduce(Configuration configuration, Universe universe, F value);

        F inflate(Configuration configuration, Universe universe, R reduced);
    }

    interface StructReductionFunction<R, F> {
        R reduce(Configuration configuration, Universe universe, F value);

        ReadStruct inflate(Configuration configuration, Universe universe, ReadStruct reduced);
    }

    private final Class<?> valueClass;
    private final Class<?> reducedClass;
    private final ReductionFunction<?, ?> function;
    private final StructReductionFunction<?, ?> structFunction;

    record JFRReductions(Configuration configuration, Universe universe) implements Reductions {

        private static List<JFRReduction> values = new ArrayList<>();

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
        this.structFunction = null;
        JFRReductions.values.add(this);
    }

    @SuppressWarnings("unchecked")
    <F, R> JFRReduction(StructReductionFunction<R, F> function) {
        this.valueClass = null;
        this.reducedClass = null;
        this.function = null;
        this.structFunction = function;
        JFRReductions.values.add(this);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object reduce(Configuration configuration, Universe universe, Object value) {
        if (value == null) {
            return null;
        }
        if (structFunction != null) {
            return ((StructReductionFunction) structFunction)
                    .reduce(configuration, universe, value);
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
        if (structFunction != null) {
            if (!(reduced instanceof ReadStruct reducedStruct)) {
                throw new IllegalArgumentException(
                        "Reduced "
                                + reduced
                                + " of class "
                                + reduced.getClass()
                                + " is not a ReadStruct");
            }
            return ((StructReductionFunction) structFunction)
                    .inflate(configuration, universe, (ReadStruct) reduced);
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
        return JFRReductions.values.get(id);
    }
}
