package me.bechberger.util;

import static me.bechberger.condensed.Util.toNanoSeconds;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedObject;
import org.junit.jupiter.api.Assertions;
import org.openjdk.jmc.common.util.Pair;

public class Asserters {

    public static final long NANOSECONDS_PER_SECOND = 1_000_000_000;
    public static final long ALLOWED_NANOSECOND_DIFFERENCE = 500;

    /**
     * Durations cannot be compared using equals, as their nanosecond values can differ due to
     * rounding errors.
     */
    public static void assertEquals(Duration expected, Duration actual) {
        assertEquals(expected, actual, NANOSECONDS_PER_SECOND);
    }

    public static void assertEqualsJFRNanos(long expected, long actual, String message) {
        assertEqualsJFRNanos(expected, actual, NANOSECONDS_PER_SECOND, message);
    }

    public static void assertEqualsJFRNanos(long expected, long actual) {
        assertEqualsJFRNanos(expected, actual, NANOSECONDS_PER_SECOND);
    }

    /** Compare durations with a condensation factor */
    public static void assertEquals(Duration expected, Duration actual, long ticksPerSecond) {
        assertEqualsJFRNanos(expected.toNanos(), actual.toNanos(), ticksPerSecond);
    }

    public static void assertEqualsJFRNanos(
            long expected, long actual, long ticksPerSecond, String message) {
        var diff = Math.abs(expected - actual);
        var maxDiff =
                Math.max(NANOSECONDS_PER_SECOND / ticksPerSecond, ALLOWED_NANOSECOND_DIFFERENCE);
        assertTrue(diff <= maxDiff, message);
    }

    public static void assertEqualsJFRNanos(long expected, long actual, long ticksPerSecond) {
        assertEqualsJFRNanos(
                expected,
                actual,
                ticksPerSecond,
                "Expected "
                        + expected
                        + " but was "
                        + actual
                        + " which is more than "
                        + Math.max(NANOSECONDS_PER_SECOND / ticksPerSecond, ALLOWED_NANOSECOND_DIFFERENCE)
                        + " nanoseconds off");
    }

    public static void assertEquals(
            Instant expected, Instant actual, long ticksPerSecond, String message) {
        assertEqualsJFRNanos(
                toNanoSeconds(expected), toNanoSeconds(actual), ticksPerSecond, message);
    }

    public static void assertEquals(Instant expected, Instant actual, long ticksPerSecond) {
        assertEquals(
                expected, actual, ticksPerSecond, "Expected " + expected + " but was " + actual);
    }

    public static void assertEquals(
            RecordedObject expected, RecordedObject actual, String message) {
        assertEquals(expected, actual, message, new HashSet<>(), new AccessPath());
    }

    public static void assertEquals(RecordedObject expected, RecordedObject actual) {
        assertEquals(expected, actual, "Expected " + expected + " but was " + actual);
    }

    record AccessPathEntry(String field, Object expectedValue, Object actualValue) {}

    record AccessPath(List<AccessPathEntry> path) {
        public AccessPath() {
            this(new ArrayList<>());
        }

        public AccessPath add(String field, Object expectedValue, Object actualValue) {
            var newPath = new ArrayList<>(this.path);
            newPath.add(new AccessPathEntry(field, expectedValue, actualValue));
            return new AccessPath(newPath);
        }

        public AccessPath add(int index, Object value) {
            var newPath = new ArrayList<>(this.path);
            newPath.add(new AccessPathEntry("[" + index + "]", value, value));
            return new AccessPath(newPath);
        }

        @Override
        public String toString() {
            return path.stream().map(p -> p.field).collect(Collectors.joining("."));
        }

        /**
         * Check if the path already contains a value, because the {@link
         * me.bechberger.jfr.WritingJFRReader} can't handle self-recursive objects
         */
        public boolean alreadyContains(Object value) {
            return path.stream()
                    .anyMatch(p -> p.expectedValue.equals(value) || p.actualValue.equals(value));
        }

        public boolean checkForPotentialRecursion(int subLength) {
            if (path.size() <= subLength) {
                return false;
            }
            List<AccessPathEntry> subPath = path.subList(0, subLength);
            // check if this subPath is present anywhere else in the path
            for (int i = 0; i < path.size() - subLength; i++) {
                if (path.subList(i, i + subLength).equals(subPath)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static void assertEquals(
            RecordedObject expected,
            RecordedObject actual,
            String message,
            Set<Pair<RecordedObject, RecordedObject>> currentlyChecking,
            AccessPath path) {
        currentlyChecking.add(new Pair<>(expected, actual));
        for (var field : expected.getFields()) {
            var expectedValue = expected.getValue(field.getName());
            if (path.alreadyContains(expectedValue)) {
                // Skip self-recursive objects
                continue;
            }
            assertTrue(
                    actual.hasField(field.getName()),
                    message
                            + ": Field "
                            + field.getName()
                            + " not found in actual "
                            + " in path "
                            + path);
            var currentPath =
                    path.add(field.getName(), expectedValue, actual.getValue(field.getName()));
            String pathPart = " in path " + currentPath;
            var actualValue = actual.getValue(field.getName());
            if (path.alreadyContains(actualValue)) {
                // Skip self-recursive objects
                continue;
            }
            if ((expectedValue == null) == (actualValue != null)
                    && currentPath.checkForPotentialRecursion(1)) {
                return;
            }
            if (expectedValue instanceof RecordedObject) {
                assertInstanceOf(
                        RecordedObject.class,
                        actualValue,
                        message
                                + ": Expected "
                                + expectedValue
                                + " but was "
                                + actualValue
                                + pathPart);
                if (currentlyChecking.contains(
                        new Pair<>((RecordedObject) expectedValue, (RecordedObject) actualValue))) {
                    continue;
                }
                currentlyChecking.add(
                        new Pair<>((RecordedObject) expectedValue, (RecordedObject) actualValue));
                assertEquals(
                        (RecordedObject) expectedValue,
                        (RecordedObject) actualValue,
                        message,
                        currentlyChecking,
                        currentPath);
            } else if (expectedValue instanceof List<?>) {
                assertInstanceOf(
                        List.class,
                        actualValue,
                        message + ": Expected " + expectedValue + " but was " + actualValue);
                var expectedList = (List<?>) expectedValue;
                var actualList = (List<?>) actualValue;
                Assertions.assertEquals(
                        expectedList.size(),
                        actualList.size(),
                        message
                                + ": Expected "
                                + expectedValue
                                + " but was "
                                + actualValue
                                + pathPart);
                for (int i = 0; i < expectedList.size(); i++) {
                    var iterPath = currentPath.add(i, expectedList.get(i));
                    var expectedElement = expectedList.get(i);
                    var actualElement = actualList.get(i);
                    var pathPath = " in path " + path;
                    if (expectedElement instanceof RecordedObject) {
                        assertInstanceOf(
                                RecordedObject.class,
                                actualElement,
                                message
                                        + ": Expected "
                                        + expectedElement
                                        + " but was "
                                        + actualElement
                                        + pathPath);
                        if (currentlyChecking.contains(
                                new Pair<>(
                                        (RecordedObject) expectedElement,
                                        (RecordedObject) actualElement))) {
                            continue;
                        }
                        currentlyChecking.add(
                                new Pair<>(
                                        (RecordedObject) expectedElement,
                                        (RecordedObject) actualElement));
                        assertEquals(
                                (RecordedObject) expectedElement,
                                (RecordedObject) actualElement,
                                message,
                                currentlyChecking,
                                iterPath);
                    } else {
                        Assertions.assertEquals(
                                expectedElement,
                                actualElement,
                                message
                                        + ": Expected "
                                        + expectedElement
                                        + " but was "
                                        + actualElement
                                        + pathPath);
                    }
                }
            } else {
                Assertions.assertEquals(
                        expectedValue,
                        actualValue,
                        message
                                + ": Expected "
                                + expectedValue
                                + " but was "
                                + actualValue
                                + pathPart);
            }
        }
    }
}
