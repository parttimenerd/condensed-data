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
        long totalNanos = duration.toNanos();
        if (totalNanos == 0) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        if (seconds >= 60) {
            // Use h/m/s for >= 1 minute
            return duration.toString()
                    .substring(2)
                    .replaceAll("(\\d[HMS])(?!$)", "$1 ")
                    .toLowerCase();
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
        long nanos = duration.toNanos();
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
            double hours = matcher.group(1) != null ? Double.parseDouble(matcher.group(1)) : 0;
            double minutes = matcher.group(2) != null ? Double.parseDouble(matcher.group(2)) : 0;
            double seconds = matcher.group(3) != null ? Double.parseDouble(matcher.group(3)) : 0;
            double millis = matcher.group(4) != null ? Double.parseDouble(matcher.group(4)) : 0;
            double micros = matcher.group(5) != null ? Double.parseDouble(matcher.group(5)) : 0;
            double nanos = matcher.group(6) != null ? Double.parseDouble(matcher.group(6)) : 0;

            long totalNanos = 0;
            totalNanos += Math.round(hours * 3_600_000_000_000L);
            totalNanos += Math.round(minutes * 60_000_000_000L);
            totalNanos += Math.round(seconds * 1_000_000_000L);
            totalNanos += Math.round(millis * 1_000_000L);
            totalNanos += Math.round(micros * 1_000L);
            totalNanos += Math.round(nanos);

            Duration result = Duration.ofNanos(totalNanos);
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
