package me.bechberger.jfr.cli.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The closed set of aggregate functions used by JDK {@code view.ini} (stable across JDK 21–27).
 *
 * <p>Each function is a {@link Reducer}: it {@link Reducer#accept accepts} the raw per-row value of
 * its argument expression and, at the end of a group, {@link Reducer#result produces} the
 * aggregated raw value (which the renderer then formats). Registration is explicit in {@link
 * #reducer}; the percentile family ({@code P90}/{@code P95}/{@code P99}/{@code P999}) is handled by
 * a single parameterized branch so new percentiles are a one-line addition.
 *
 * <p>To add a function: add a case to {@link #reducer} returning a {@code Supplier<Reducer>}. Value
 * <em>formatting</em> is not this class's concern — reducers return raw {@link Number}/{@link
 * Instant}/{@link Duration}/{@link Object} values and the renderer formats them per the field type.
 */
final class Aggregators {

    private Aggregators() {}

    /** A per-group accumulator over the raw values of one aggregate's argument expression. */
    interface Reducer {
        void accept(Object value);

        Object result();
    }

    /** True if {@code fn} (case-insensitive) is a known aggregate function name. */
    static boolean isAggregate(String fn) {
        return KNOWN.contains(fn.toUpperCase(Locale.ROOT));
    }

    /**
     * True if {@code fn} is {@code LAST_BATCH} — a row-set restriction, not a plain reducer. The
     * reducer alone cannot express it: {@link QueryEvaluator} must first restrict the events fed to
     * it to the final periodic-emission batch (those sharing the global maximum {@code startTime}).
     */
    static boolean isLastBatch(String fn) {
        return "LAST_BATCH".equals(fn.toUpperCase(Locale.ROOT));
    }

    private static final Set<String> KNOWN =
            Set.of(
                    "LAST",
                    "FIRST",
                    "LAST_BATCH",
                    "COUNT",
                    "SUM",
                    "AVG",
                    "MIN",
                    "MAX",
                    "MEDIAN",
                    "P90",
                    "P95",
                    "P99",
                    "P999",
                    "DIFF",
                    "UNIQUE",
                    "SET");

    /**
     * A fresh reducer for {@code fn}. Throws {@link QueryParseException} for an unknown function so
     * the caller can delegate the view.
     */
    static Supplier<Reducer> reducer(String fn) {
        String f = fn.toUpperCase(Locale.ROOT);
        return switch (f) {
            case "COUNT" -> CountReducer::new;
            case "SUM" -> SumReducer::new;
            case "AVG" -> AvgReducer::new;
            case "MIN" -> () -> new MinMaxReducer(true);
            case "MAX" -> () -> new MinMaxReducer(false);
            case "FIRST" -> () -> new FirstLastReducer(true);
            case "LAST" -> () -> new FirstLastReducer(false);
            // LAST_BATCH: the events reaching the reducer are already restricted by
            // QueryEvaluator to the final periodic-emission batch and iterated in chronological
            // (stable startTime) order. jfr's representative within that batch is the LAST event
            // seen — e.g. memory-leaks-by-* reports the most-recently-allocated sample per group,
            // not the first — so this is LAST-like over the already-filtered subset.
            case "LAST_BATCH" -> () -> new FirstLastReducer(false);
            case "MEDIAN" -> () -> new PercentileReducer(50);
            case "P90" -> () -> new PercentileReducer(90);
            case "P95" -> () -> new PercentileReducer(95);
            case "P99" -> () -> new PercentileReducer(99);
            case "P999" -> () -> new PercentileReducer(99.9);
            case "DIFF" -> DiffReducer::new;
            case "UNIQUE" -> UniqueReducer::new;
            case "SET" -> SetReducer::new;
            default -> throw new QueryParseException("unknown aggregate '" + fn + "'", 0);
        };
    }

    // ── numeric coercion ──────────────────────────────────────────────────────

    /** Coerce a raw value to a double for numeric aggregation; instants/durations use nanos. */
    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Instant i) return i.getEpochSecond() * 1_000_000_000.0 + i.getNano();
        if (v instanceof Duration d) return d.toNanos();
        return 0;
    }

    private static boolean isInstant(Object v) {
        return v instanceof Instant;
    }

    // ── reducers ────────────────────────────────────────────────────────────

    private static final class CountReducer implements Reducer {
        private long n = 0;

        public void accept(Object value) {
            n++;
        }

        public Object result() {
            return n;
        }
    }

    private static final class SumReducer implements Reducer {
        private double sum = 0;
        private boolean any = false;
        private boolean duration = false;

        public void accept(Object value) {
            if (value == null) return;
            any = true;
            duration = value instanceof Duration;
            sum += toDouble(value);
        }

        public Object result() {
            if (!any) return null;
            if (duration) return Duration.ofNanos((long) sum);
            return sum == Math.rint(sum) ? (Object) (long) sum : (Object) sum;
        }
    }

    private static final class AvgReducer implements Reducer {
        private double sum = 0;
        private long n = 0;
        private boolean instant = false;

        public void accept(Object value) {
            if (value == null) return;
            instant = isInstant(value) || value instanceof Duration;
            sum += toDouble(value);
            n++;
        }

        public Object result() {
            if (n == 0) return null;
            double avg = sum / n;
            // Durations/timespans aggregate to a Duration (nanos); plain numbers stay double.
            // Round to the nearest nanosecond (not truncate): jfr keeps the fractional-nanosecond
            // average and rounds it when formatting, so e.g. 833ns/6 = 138.83ns must render as
            // 0.000139 ms, not 0.000138 ms (ICBufferFull in vm-operations).
            return instant ? (Object) Duration.ofNanos(Math.round(avg)) : (Object) avg;
        }
    }

    private static final class MinMaxReducer implements Reducer {
        private final boolean min;
        private Object best = null;
        private double bestD;

        MinMaxReducer(boolean min) {
            this.min = min;
        }

        public void accept(Object value) {
            if (value == null) return;
            double d = toDouble(value);
            if (best == null || (min ? d < bestD : d > bestD)) {
                best = value;
                bestD = d;
            }
        }

        public Object result() {
            return best;
        }
    }

    private static final class FirstLastReducer implements Reducer {
        private final boolean first;
        private Object value = null;
        private boolean seen = false;

        FirstLastReducer(boolean first) {
            this.first = first;
        }

        public void accept(Object v) {
            if (first) {
                if (!seen) {
                    value = v;
                    seen = true;
                }
            } else {
                value = v;
            }
        }

        public Object result() {
            return value;
        }
    }

    private static final class PercentileReducer implements Reducer {
        private final double pct;
        private final List<Double> values = new ArrayList<>();
        private boolean instant = false;

        PercentileReducer(double pct) {
            this.pct = pct;
        }

        public void accept(Object value) {
            if (value == null) return;
            instant = isInstant(value) || value instanceof Duration;
            values.add(toDouble(value));
        }

        public Object result() {
            if (values.isEmpty()) return null;
            values.sort(null);
            int n = values.size();
            // jfr interpolates percentiles with the Excel PERCENTILE.EXC method: the 1-based rank
            // is
            // pct/100 * (n+1); the value is a linear interpolation between the two sorted samples
            // that
            // straddle that rank. Verified on a large clean dataset (gc-pauses, n=8600): Median →
            // 2.1675 ms, P90 → 8.2705 ms, P95 → 18.97 ms, P99 → 95.99 ms all match jfr's rendered
            // values. Ranks at or beyond the ends clamp to the first/last sample. NOTE: jfr's P99.9
            // and its small-N percentiles diverge (jfr extrapolates above the max and its small-N
            // output is even internally inconsistent, e.g. P95 > Max); those cannot be reproduced
            // without reading GPLv2 jdk.jfr.internal.query source, so they are accepted
            // degradations.
            double idx0 = pct / 100.0 * (n + 1) - 1; // 0-based fractional index
            double v;
            if (idx0 <= 0) {
                v = values.get(0);
            } else if (idx0 >= n - 1) {
                v = values.get(n - 1);
            } else {
                int lo = (int) Math.floor(idx0);
                double frac = idx0 - lo;
                v = values.get(lo) + frac * (values.get(lo + 1) - values.get(lo));
            }
            // jfr truncates the selected percentile to whole nanoseconds before formatting.
            return instant ? (Object) Duration.ofNanos((long) v) : (Object) v;
        }
    }

    private static final class DiffReducer implements Reducer {
        private Object firstValue = null;
        private Object lastValue = null;
        private boolean any = false;
        private boolean temporal = false;

        public void accept(Object value) {
            if (value == null) return;
            if (!any) firstValue = value;
            lastValue = value;
            any = true;
            temporal = isInstant(value) || value instanceof Duration;
        }

        public Object result() {
            if (!any) return null;
            // jfr's DIFFERENCE is the signed change across the group in iteration order: the last
            // observed value minus the first. Over monotonic fields (elapsed startTime, cumulative
            // exception counts) this matches an unsigned max−min, but over a field that can fall
            // (object-statistics' totalSize shrinking after GC) it must stay signed and can be
            // negative — jfr renders "-1.3 GB". Over timestamps/durations the difference is an
            // elapsed Duration; over a plain numeric field it stays a number, matching jfr's
            // operand-typed result (a count difference renders as "0", not "0 s").
            if (!temporal) {
                long diff = (long) (toDouble(lastValue) - toDouble(firstValue));
                return diff;
            }
            // For Instants: use Duration.between to avoid double-precision loss when converting
            // epoch-nanos (~1.7e21) to double (only ~1e15 exact int range); subtraction of two
            // large similar doubles would corrupt the sub-second difference by ~100ms+.
            if (lastValue instanceof Instant lastInst && firstValue instanceof Instant firstInst) {
                return Duration.between(firstInst, lastInst);
            }
            // Duration subtraction (same precision issue avoided by staying in long-nanos domain)
            if (lastValue instanceof Duration lastD && firstValue instanceof Duration firstD) {
                return lastD.minus(firstD);
            }
            long diff = (long) (toDouble(lastValue) - toDouble(firstValue));
            return Duration.ofNanos(diff);
        }
    }

    private static final class UniqueReducer implements Reducer {
        private final Set<Object> seen = new HashSet<>();

        public void accept(Object value) {
            if (value != null) seen.add(value);
        }

        public Object result() {
            return (long) seen.size();
        }
    }

    // JFR's SET() keeps distinct values by *object identity*, not value equality. In the JFR SDK
    // pool, the same class used across multiple periodic re-emissions of the same invocation maps
    // to the same RecordedClass instance — deduplication by identity collapses those copies.
    // Different invocation times produce distinct pool entries (different instances), so they are
    // kept separate even when the class name is the same string.
    private static final class SetReducer implements Reducer {
        // IdentityHashMap used as a set — key = value, value = ignored marker.
        private final java.util.IdentityHashMap<Object, Boolean> seen =
                new java.util.IdentityHashMap<>();
        private final List<Object> insertion = new ArrayList<>();

        public void accept(Object value) {
            if (value != null && seen.put(value, Boolean.TRUE) == null) {
                insertion.add(value);
            }
        }

        public Object result() {
            return new ArrayList<>(insertion);
        }
    }
}
