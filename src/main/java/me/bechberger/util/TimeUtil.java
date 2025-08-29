package me.bechberger.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        return duration.toString().substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase();
    }

    public static String formatInstant(Instant instant) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return dateTime.format(formatter);
    }

    public static Instant parseInstant(String time) {
        if (time.matches("\\d{1,2}:\\d{1,2}:\\d{1,2}")) { // parse HH:mm:ss
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd " + time));
        } else if (time.matches("\\d{1,2}:\\d{1,2}")) { // parse HH:mm
            time =
                    LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd " + time + ":00"));
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd H:m:s");
        LocalDateTime dateTime = LocalDateTime.parse(time, formatter);
        return dateTime.atZone(ZoneId.of("UTC")).toInstant();
    }

    public static Duration parseDuration(String duration) {
        Pattern pattern =
                Pattern.compile(
                        "(?i)\\s*(?:(\\d+(?:\\.\\d+)?)h)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)m)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)s)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)ms)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)us)?\\s*"
                                + "(?:(\\d+(?:\\.\\d+)?)ns)?\\s*");
        Matcher matcher = pattern.matcher(duration);
        if (matcher.matches() && (IntStream.range(1, 7).anyMatch(i -> matcher.group(i) != null))) {
            double hours = matcher.group(1) != null ? Double.parseDouble(matcher.group(1)) : 0;
            double minutes = matcher.group(2) != null ? Double.parseDouble(matcher.group(2)) : 0;
            double seconds = matcher.group(3) != null ? Double.parseDouble(matcher.group(3)) : 0;
            double millis = matcher.group(4) != null ? Double.parseDouble(matcher.group(4)) : 0;
            double micros = matcher.group(5) != null ? Double.parseDouble(matcher.group(5)) : 0;
            double nanos = matcher.group(6) != null ? Double.parseDouble(matcher.group(6)) : 0;

            long totalNanos = 0;
            totalNanos += (long) (hours * 3_600_000_000_000L);
            totalNanos += (long) (minutes * 60_000_000_000L);
            totalNanos += (long) (seconds * 1_000_000_000L);
            totalNanos += (long) (millis * 1_000_000L);
            totalNanos += (long) (micros * 1_000L);
            totalNanos += (long) (nanos);

            return Duration.ofNanos(totalNanos);
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
