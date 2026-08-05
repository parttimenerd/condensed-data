package me.bechberger.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Utility class for formatting time durations and instant objects, and parsing the same format */
public class TimeUtil {
    /**
     * Convert a duration to a human-readable format
     *
     * <p>Based on <a href="https://stackoverflow.com/a/40487511/19040822">stackoverflow.com</a>
     */
    public static String formatDuration(Duration duration) {
        if (duration.isNegative()) {
            return "-" + formatDuration(duration.negated());
        }
        if (duration.isZero()) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        int nanoAdj = duration.getNano(); // always non-negative (0..999_999_999)
        if (seconds >= 60) {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            long millis = nanoAdj / 1_000_000L;
            long subMilliNanos = nanoAdj % 1_000_000L;
            StringBuilder sb = new StringBuilder();
            if (hours > 0) sb.append(hours).append("h");
            if (minutes > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(minutes).append("m");
            }
            boolean hasSubSeconds = millis > 0 || subMilliNanos > 0;
            if (secs > 0 || hasSubSeconds) {
                if (sb.length() > 0) sb.append(' ');
                if (hasSubSeconds) {
                    // Use ms precision for display (sub-ms is not human-readable in h/m/s context)
                    sb.append(String.format(Locale.ROOT, "%d.%03d", secs, millis));
                    String s = sb.toString();
                    // strip trailing zeros after the decimal point
                    s = s.replaceAll("(\\.[0-9]*?)0+$", "$1").replaceAll("\\.$", "");
                    sb.setLength(0);
                    sb.append(s);
                } else {
                    sb.append(secs);
                }
                sb.append('s');
            }
            return sb.length() == 0 ? "0s" : sb.toString();
        }
        if (seconds >= 1) {
            // Use seconds with ms precision
            long millis = duration.toMillis();
            double secs = millis / 1000.0;
            String formatted = String.valueOf(secs);
            // Remove trailing zeros after decimal point but keep at least one
            formatted = formatted.replaceAll("(\\.[0-9]*?)0+$", "$1");
            formatted = formatted.replaceAll("\\.$", ".0");
            return formatted + "s";
        }
        long nanos = nanoAdj;
        if (nanos >= 1_000_000) {
            // Use milliseconds
            double ms = nanos / 1_000_000.0;
            if (ms >= 100) {
                return String.format(Locale.ROOT, "%.1fms", ms);
            } else if (ms >= 10) {
                return String.format(Locale.ROOT, "%.2fms", ms);
            } else {
                return String.format(Locale.ROOT, "%.3fms", ms);
            }
        }
        if (nanos >= 1000) {
            // Use microseconds
            double us = nanos / 1000.0;
            if (us >= 100) {
                return String.format(Locale.ROOT, "%.1fus", us);
            } else if (us >= 10) {
                return String.format(Locale.ROOT, "%.2fus", us);
            } else {
                return String.format(Locale.ROOT, "%.3fus", us);
            }
        }
        return nanos + "ns";
    }

    public static String formatInstant(Instant instant) {
        ZonedDateTime dateTime = instant.atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
        return dateTime.format(formatter);
    }

    /**
     * Parse an instant from a string. If the string only contains time (HH:mm:ss or HH:mm), the
     * current date is used. Interpreted as local time and converted to UTC.
     *
     * @param time
     * @return
     */
    public static Instant parseInstant(String time) {
        time = time.strip();
        if (time.matches("\\d{1,2}:\\d{1,2}(:\\d{1,2})?")) {
            throw new IllegalArgumentException(
                    "Time-only format is not supported for '"
                            + time
                            + "'. Please include a date, e.g. yyyy-MM-ddTHH:mm:ss");
        }
        try {
            return Instant.parse(time);
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(time, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(time, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (Exception ignored) {
        }
        // Try parsing with UTC offset first (format from formatInstant)
        try {
            DateTimeFormatter withOffset = DateTimeFormatter.ofPattern("yyyy-MM-dd H:m:sXXX");
            ZonedDateTime zdt = ZonedDateTime.parse(time, withOffset);
            return zdt.toInstant();
        } catch (Exception e) {
            try {
                // Fall back to parsing without offset (user-typed input)
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd H:m:s");
                LocalDateTime dateTime = LocalDateTime.parse(time, formatter);
                return dateTime.atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception ignored) {
                throw new IllegalArgumentException(
                        "Invalid instant format: "
                                + time
                                + ". Use yyyy-MM-dd HH:mm:ss, yyyy-MM-ddTHH:mm:ss, or include a"
                                + " timezone offset.");
            }
        }
    }

    private static long parseUnitNanos(String value, long nanosPerUnit) {
        if (!value.contains(".")) {
            long whole = Long.parseLong(value);
            if (nanosPerUnit > 1 && Math.abs(whole) > Long.MAX_VALUE / nanosPerUnit) {
                return whole >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
            }
            return whole * nanosPerUnit;
        }
        return Math.round(Double.parseDouble(value) * nanosPerUnit);
    }

    private static long mulClamped(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return a >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long addClamped(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return (a > 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    public static Duration parseDuration(String duration) {
        boolean negative = false;
        String trimmed = duration.strip();
        if (trimmed.startsWith("-")) {
            negative = true;
            trimmed = trimmed.substring(1).strip();
        }
        Pattern pattern =
                Pattern.compile(
                        "(?i)\\s*(?:(\\d+(?:\\.\\d+)?)h)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)m)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)s)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)ms)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)us)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)ns)?\\s*");
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.matches() && (IntStream.range(1, 7).anyMatch(i -> matcher.group(i) != null))) {
            // Build total as (wholeSecs, subNanos) to avoid nanosecond overflow for large second
            // values
            long wholeSecs = 0;
            long subNanos = 0;
            if (matcher.group(1) != null) {
                String v = matcher.group(1);
                if (!v.contains(".")) {
                    long h = Long.parseLong(v);
                    wholeSecs = addClamped(wholeSecs, mulClamped(h, 3600L));
                } else wholeSecs = addClamped(wholeSecs, (long) (Double.parseDouble(v) * 3600));
            }
            if (matcher.group(2) != null) {
                String v = matcher.group(2);
                if (!v.contains(".")) {
                    long m = Long.parseLong(v);
                    wholeSecs = addClamped(wholeSecs, mulClamped(m, 60L));
                } else wholeSecs = addClamped(wholeSecs, (long) (Double.parseDouble(v) * 60));
            }
            if (matcher.group(3) != null) {
                String v = matcher.group(3);
                if (!v.contains(".")) {
                    wholeSecs = addClamped(wholeSecs, Long.parseLong(v));
                } else {
                    double d = Double.parseDouble(v);
                    wholeSecs = addClamped(wholeSecs, (long) d);
                    subNanos += Math.round((d - (long) d) * 1_000_000_000L);
                }
            }
            if (matcher.group(4) != null) subNanos += parseUnitNanos(matcher.group(4), 1_000_000L);
            if (matcher.group(5) != null) subNanos += parseUnitNanos(matcher.group(5), 1_000L);
            if (matcher.group(6) != null) subNanos += parseUnitNanos(matcher.group(6), 1L);

            Duration result = Duration.ofSeconds(wholeSecs, subNanos);
            return negative ? result.negated() : result;
        } else {
            throw new IllegalArgumentException("Invalid duration format: " + duration);
        }
    }

    /** all durations with more than 1 year are stored as 1 year, same with negative durations */
    public static final long MAX_DURATION_SECONDS = 60 * 60 * 24 * 365;

    /**
     * Clamp the duration to the range (-{@link #MAX_DURATION_SECONDS}, {@link
     * #MAX_DURATION_SECONDS})
     */
    public static Duration clamp(Duration duration) {
        if (duration.getSeconds() > MAX_DURATION_SECONDS) {
            return Duration.ofSeconds(MAX_DURATION_SECONDS);
        } else if (duration.getSeconds() < -MAX_DURATION_SECONDS) {
            return Duration.ofSeconds(-MAX_DURATION_SECONDS);
        } else {
            return duration;
        }
    }
}
