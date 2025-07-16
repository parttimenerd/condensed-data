package me.bechberger.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Pattern pattern = Pattern.compile("(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?");
        Matcher matcher = pattern.matcher(duration);
        if (matcher.matches()) {
            long hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
            long minutes = matcher.group(2) != null ? Long.parseLong(matcher.group(2)) : 0;
            long seconds = matcher.group(3) != null ? Long.parseLong(matcher.group(3)) : 0;
            return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
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