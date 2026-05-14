package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;
import static me.bechberger.util.TimeUtil.clamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.Reductions;
import org.jetbrains.annotations.Nullable;

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
                    long reduced;
                    if (configuration.timeStampTicksPerSecond() == 1_000_000_000L) {
                        // Default config uses nanosecond ticks, so direct long subtraction is
                        // exact.
                        reduced = l - universe.getLastStartTimeNanos();
                    } else {
                        reduced =
                                (long)
                                                (l
                                                        / (1_000_000_000.0
                                                                / configuration
                                                                        .timeStampTicksPerSecond()))
                                        - (long)
                                                (universe.getLastStartTimeNanos()
                                                        / (1_000_000_000.0
                                                                / configuration
                                                                        .timeStampTicksPerSecond()));
                    }
                    universe.setLastStartTimeNanos(l);
                    return reduced;
                }

                @Override
                public Instant inflate(
                        Configuration configuration, Universe universe, Long reduced) {
                    // difference from last in condensed nanoseconds
                    long nanoSeconds;
                    if (configuration.timeStampTicksPerSecond() == 1_000_000_000L) {
                        nanoSeconds = universe.getLastStartTimeNanos() + reduced;
                    } else {
                        nanoSeconds =
                                (long)
                                                (reduced
                                                        * (1_000_000_000.0
                                                                / configuration
                                                                        .timeStampTicksPerSecond()))
                                        + universe.getLastStartTimeNanos();
                    }
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
            }),
    DATA_AMOUNT_BYTES_REDUCTION(
            Long.class,
            Long.class,
            new ReductionFunction<>() {
                private static final long ALIGNMENT = 8L;
                private static final long COMPRESSED_RANGE_SIZE = 1L << 60;
                private static final long NON_NEGATIVE_FALLBACK_COUNT =
                        Long.MAX_VALUE - (COMPRESSED_RANGE_SIZE - 1);

                private long countAlignedLt(long value) {
                    if (value <= 0) {
                        return 0;
                    }
                    return ((value - 1) >>> 3) + 1;
                }

                @Override
                public Long reduce(Configuration configuration, Universe universe, Long value) {
                    if (value >= 0 && value % ALIGNMENT == 0) {
                        return value / ALIGNMENT;
                    }

                    // Map all remaining values bijectively into the complement of [0, 2^60).
                    long rank;
                    if (value >= 0) {
                        rank = value - countAlignedLt(value);
                    } else {
                        long negativeIndex = value - Long.MIN_VALUE;
                        rank = NON_NEGATIVE_FALLBACK_COUNT + negativeIndex;
                    }
                    return COMPRESSED_RANGE_SIZE + rank;
                }

                @Override
                public Long inflate(Configuration configuration, Universe universe, Long reduced) {
                    if (reduced >= 0 && reduced < COMPRESSED_RANGE_SIZE) {
                        return reduced * ALIGNMENT;
                    }

                    long rank = reduced - COMPRESSED_RANGE_SIZE;
                    if (Long.compareUnsigned(rank, NON_NEGATIVE_FALLBACK_COUNT) < 0) {
                        long block = Long.divideUnsigned(rank, 7);
                        long remainder = Long.remainderUnsigned(rank, 7);
                        return block * ALIGNMENT + (remainder + 1);
                    }

                    long negativeIndex = rank - NON_NEGATIVE_FALLBACK_COUNT;
                    return Long.MIN_VALUE + negativeIndex;
                }
            });

    static class ReducedStackTrace {

        // Snapshot of a frame's comparison-relevant data, taken at construction time
        // to avoid stale reads from JFR's reusable internal buffers
        private record FrameSnapshot(
                int lineNumber,
                int bytecodeIndex,
                long classId,
                String methodName,
                String methodDescriptor,
                @Nullable String frameType) {}

        private final List<RecordedFrame> frames;
        private final List<FrameSnapshot> frameSnapshots;
        private final boolean truncated;
        private final int contentHash;

        private ReducedStackTrace(RecordedStackTrace stackTrace) {
            this.frames = stackTrace.getFrames();
            this.truncated = stackTrace.isTruncated();
            this.frameSnapshots = snapshotFrames(this.frames);
            this.contentHash = computeContentHash();
        }

        private ReducedStackTrace(
                RecordedStackTrace stackTrace, List<RecordedFrame> frames, boolean truncated) {
            this.frames = frames;
            this.truncated = truncated;
            this.frameSnapshots = snapshotFrames(this.frames);
            this.contentHash = computeContentHash();
        }

        private static List<FrameSnapshot> snapshotFrames(List<RecordedFrame> frames) {
            return frames.stream()
                    .map(
                            f ->
                                    new FrameSnapshot(
                                            f.getLineNumber(),
                                            f.getBytecodeIndex(),
                                            f.getMethod().getType().getId(),
                                            f.getMethod().getName(),
                                            f.getMethod().getDescriptor(),
                                            f.getType()))
                    .toList();
        }

        private int computeContentHash() {
            int h = Boolean.hashCode(truncated);
            for (var f : frameSnapshots) {
                h = h * 31 + Long.hashCode(f.classId);
                h = h * 31 + f.methodName.hashCode();
                h = h * 31 + f.methodDescriptor.hashCode();
                h = h * 31 + f.lineNumber;
                h = h * 31 + f.bytecodeIndex;
                h = h * 31 + (f.frameType != null ? f.frameType.hashCode() : 0);
            }
            return h;
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
            return contentHash;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ReducedStackTrace other)) {
                return false;
            }
            if (other.contentHash != contentHash || other.truncated != truncated) {
                return false;
            }
            if (other.frameSnapshots.size() != frameSnapshots.size()) {
                return false;
            }
            for (int i = 0; i < frameSnapshots.size(); i++) {
                var a = frameSnapshots.get(i);
                var b = other.frameSnapshots.get(i);
                if (a.lineNumber != b.lineNumber
                        || a.bytecodeIndex != b.bytecodeIndex
                        || a.classId != b.classId
                        || !a.methodName.equals(b.methodName)
                        || !a.methodDescriptor.equals(b.methodDescriptor)
                        || !Objects.equals(a.frameType, b.frameType)) {
                    return false;
                }
            }
            return true;
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

    private final @Nullable Class<?> valueClass;
    private final @Nullable Class<?> reducedClass;
    private final @Nullable ReductionFunction<?, ?> function;
    private final @Nullable StructReductionFunction<?, ?> structFunction;

    record JFRReductions(Configuration configuration, Universe universe) implements Reductions {

        private static final List<JFRReduction> values = new ArrayList<>();

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

    <F, R> JFRReduction(
            Class<F> valueClass, Class<R> reducedClass, ReductionFunction<R, F> function) {
        this.valueClass = valueClass;
        this.reducedClass = reducedClass;
        this.function = function;
        this.structFunction = null;
        JFRReductions.values.add(this);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public @Nullable Object reduce(Configuration configuration, Universe universe, Object value) {
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    public @Nullable Object inflate(
            Configuration configuration, Universe universe, Object reduced) {
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
            return structFunction.inflate(configuration, universe, reducedStruct);
        }
        assert reducedClass != null;
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
