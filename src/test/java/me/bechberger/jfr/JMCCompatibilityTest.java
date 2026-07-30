package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.JFRReader;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jmc.common.IDescribable;
import org.openjdk.jmc.common.item.IAccessorKey;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

/**
 * Verifies that inflated JFR files can be loaded without errors by the JMC parser. Catches bugs
 * where cjfr produces JFR that the JDK {@code jfr} tool accepts but JMC rejects.
 *
 * <p>Requires {@code profile.jfr} to be present in the working directory (skipped otherwise).
 */
public class JMCCompatibilityTest {

    // Field IDs whose Integer.MIN_VALUE is the documented JFR "not an array" sentinel
    private static final Set<String> KNOWN_MIN_VALUE_INT_FIELDS = Set.of("arrayElements");

    // Threshold for detecting non-sentinel values that have been mangled into sentinel range
    private static final long MAX_REAL_DURATION_SECONDS =
            2L * me.bechberger.util.TimeUtil.MAX_DURATION_SECONDS;

    // JMC reader version incompatibilities: warnings that originate from the JMC parser's
    // hardcoded struct definitions not knowing about fields added in newer JDK versions.
    // These are NOT cjfr bugs — the inflated JFR is valid; the JMC reader is simply older.
    //
    // "virtual" field: added to java.lang.Thread in Java 21 for virtual threads.
    // JMC's JfrThread struct (StructTypes.java) predates Java 21 and has no "virtual" field,
    // so it logs a WARNING whenever it encounters a Java 21+ recording.
    private static final Set<String> KNOWN_JMC_READER_WARNINGS =
            Set.of("Could not find field with name 'virtual' in reader for 'thread'");

    static List<Configuration> configurations() {
        return List.of(
                Configuration.LOSSLESS,
                Configuration.LOSSLESS,
                Configuration.DEFAULT,
                Configuration.REDUCED);
    }

    /**
     * Load a JFR file through the JMC parser. Returns a list of error/warning messages, empty if
     * the file parsed cleanly.
     *
     * <p>Reads all field values on every event to trigger any lazy-parse exceptions and detect
     * anomalous values that suggest encode/decode bugs.
     */
    public static List<String> parseWithJMC(Path jfrFile) {
        List<String> errors = new ArrayList<>();

        Logger jmcLogger = Logger.getLogger("org.openjdk.jmc.flightrecorder");
        Handler capturingHandler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                            String rawMsg = record.getMessage();
                            // Skip warnings that are known JMC reader version limitations,
                            // not cjfr bugs (e.g. "virtual" field unknown to JMC < Java 21)
                            if (rawMsg != null
                                    && KNOWN_JMC_READER_WARNINGS.stream()
                                            .anyMatch(rawMsg::contains)) {
                                return;
                            }
                            String msg = "[" + record.getLevel() + "] " + rawMsg;
                            Throwable ex = record.getThrown();
                            if (ex != null) {
                                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                ex.printStackTrace(new PrintStream(buf));
                                msg += "\n" + buf;
                            }
                            errors.add(msg);
                        }
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        Level originalLevel = jmcLogger.getLevel();
        jmcLogger.addHandler(capturingHandler);
        jmcLogger.setLevel(Level.ALL);

        try {
            IItemCollection events = JfrLoaderToolkit.loadEvents(jfrFile.toFile());
            for (IItemIterable iterable : events) {
                IType<IItem> type = iterable.getType();
                String typeId = type.getIdentifier();
                Map<IAccessorKey<?>, ? extends IDescribable> keys = type.getAccessorKeys();

                for (IItem item : iterable) {
                    for (Map.Entry<IAccessorKey<?>, ? extends IDescribable> e : keys.entrySet()) {
                        String fieldId = e.getKey().getIdentifier();
                        IMemberAccessor<?, IItem> accessor = type.getAccessor(e.getKey());
                        Object val;
                        try {
                            val = accessor.getMember(item);
                        } catch (Exception ex) {
                            errors.add(
                                    "Exception reading "
                                            + typeId
                                            + "."
                                            + fieldId
                                            + ": "
                                            + ex.getMessage());
                            continue;
                        }
                        checkFieldValue(errors, typeId, fieldId, val);
                    }
                }
            }
        } catch (Exception e) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(buf));
            errors.add("Fatal parse error: " + buf);
        } finally {
            jmcLogger.removeHandler(capturingHandler);
            jmcLogger.setLevel(originalLevel);
        }

        return errors;
    }

    /**
     * Checks a single field value for anomalies that suggest encode/decode bugs:
     *
     * <ul>
     *   <li>Duration with extremely large absolute value (un-rendered sentinel)
     *   <li>Integer.MIN_VALUE in a field that is not the documented "arrayElements" sentinel
     * </ul>
     */
    private static void checkFieldValue(
            List<String> errors, String typeId, String fieldId, Object val) {
        if (val == null) return;

        // IQuantity covers @Timespan fields — check for sentinel-range durations
        if (val instanceof IQuantity qty) {
            try {
                if (qty.getType() == UnitLookup.TIMESPAN) {
                    long nanos = qty.longValueIn(UnitLookup.NANOSECOND);
                    // Long.MIN_VALUE / Long.MAX_VALUE are documented JFR "N/A" / "Forever"
                    // sentinels for @Timespan fields (e.g. ThreadPark.timeout, GCConfiguration
                    // .pauseTarget, ActiveRecording.maxAge). These are always valid and preserved
                    // across a lossless roundtrip.
                    if (nanos == Long.MIN_VALUE || nanos == Long.MAX_VALUE) return;
                    long secs = Duration.ofNanos(nanos).getSeconds();
                    // Quantization via varint may shift sentinels slightly off exact values.
                    // If a value is in the sentinel range but NOT exactly a sentinel, it suggests
                    // a corruption (a real value was mangled into sentinel range).
                    if (secs < -MAX_REAL_DURATION_SECONDS || secs > MAX_REAL_DURATION_SECONDS) {
                        errors.add(
                                "Suspicious duration in "
                                        + typeId
                                        + "."
                                        + fieldId
                                        + " = "
                                        + val
                                        + " (nanos="
                                        + nanos
                                        + ") — not an exact sentinel but in sentinel range");
                    }
                }
            } catch (Exception ignored) {
                // Some IQuantity types can't convert to nanoseconds — skip
            }
        }

        // Integer.MIN_VALUE in non-sentinel fields indicates a broken int sentinel
        if (val instanceof Integer iv && iv == Integer.MIN_VALUE) {
            if (!KNOWN_MIN_VALUE_INT_FIELDS.contains(fieldId)) {
                errors.add(
                        "Unexpected Integer.MIN_VALUE in "
                                + typeId
                                + "."
                                + fieldId
                                + " (broken sentinel?)");
            }
        }
    }

    @TempDir Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    void inflatedProfileJfrIsJMCCompatible(Configuration config) throws Exception {
        Path profileJfr = Path.of("profile.jfr");
        Assumptions.assumeTrue(Files.exists(profileJfr), "profile.jfr not found — skipping");

        // Condense profile.jfr
        ByteArrayOutputStream cjfrBytes = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(cjfrBytes, StartMessage.DEFAULT)) {
            BasicJFRWriter writer = new BasicJFRWriter(out, config);
            try (RecordingFile recording = new RecordingFile(profileJfr)) {
                // Pre-register all event types so ActiveSetting id remapping works for types
                // whose first event hasn't appeared yet when ActiveSetting events are processed.
                writer.registerEventTypes(recording.readEventTypes());
                while (recording.hasMoreEvents()) {
                    writer.processEvent(recording.readEvent());
                }
            }
            writer.close();
        }

        // Inflate to JFR
        Path jfrPath = tempDir.resolve(config.name() + "-inflated.jfr");
        try (CondensedInputStream in = new CondensedInputStream(cjfrBytes.toByteArray())) {
            JFRReader reader = new BasicJFRReader(in);
            WritingJFRReader.toJFRFile(reader, jfrPath);
        }

        // Parse with JMC — must produce no errors or anomalous values
        List<String> errors = parseWithJMC(jfrPath);
        if (!errors.isEmpty()) {
            fail(
                    "JMC parse errors for configuration '"
                            + config.name()
                            + "':\n"
                            + String.join("\n---\n", errors));
        }
    }

    /**
     * Runs lossless roundtrip on benchmark recordings and checks JMC compatibility. Covers
     * multi-chunk recordings with diverse GC types.
     */
    @Test
    void benchmarkRecordingsAreJMCCompatibleAfterLosslessRoundtrip() throws Exception {
        var benchmarkFiles =
                List.of(
                        "benchmark/renaissance-movie-lens_default_G1.jfr",
                        "benchmark/renaissance-als_default_G1.jfr",
                        "benchmark/renaissance-dotty_gc_details_SerialGC.jfr",
                        "benchmark/renaissance-all_gc_ZGC.jfr",
                        "benchmark/renaissance-neo4j-analytics_default_G1.jfr");

        boolean anyRan = false;
        List<String> allErrors = new ArrayList<>();

        for (String srcPath : benchmarkFiles) {
            Path src = Path.of(srcPath);
            if (!Files.exists(src)) continue;
            anyRan = true;

            ByteArrayOutputStream cjfrBytes = new ByteArrayOutputStream();
            try (CondensedOutputStream out =
                    new CondensedOutputStream(cjfrBytes, StartMessage.DEFAULT)) {
                BasicJFRWriter writer = new BasicJFRWriter(out, Configuration.LOSSLESS);
                try (RecordingFile recording = new RecordingFile(src)) {
                    writer.registerEventTypes(recording.readEventTypes());
                    while (recording.hasMoreEvents()) {
                        writer.processEvent(recording.readEvent());
                    }
                }
                writer.close();
            }

            Path jfrPath =
                    tempDir.resolve(src.getFileName().toString().replace(".jfr", "-lossless.jfr"));
            try (CondensedInputStream in = new CondensedInputStream(cjfrBytes.toByteArray())) {
                WritingJFRReader.toJFRFile(new BasicJFRReader(in), jfrPath);
            }

            List<String> errors = parseWithJMC(jfrPath);
            if (!errors.isEmpty()) {
                allErrors.add("=== " + srcPath + " ===\n" + String.join("\n---\n", errors));
            }
        }

        Assumptions.assumeTrue(anyRan, "No benchmark recordings found — skipping");
        if (!allErrors.isEmpty()) {
            fail(
                    "JMC parse errors in benchmark lossless roundtrips:\n"
                            + String.join("\n\n", allErrors));
        }
    }

    /**
     * Verifies that array-valued type-level annotations (e.g. @Category) survive the
     * condense→inflate round-trip. Without the fix these were silently dropped, causing JMC's
     * Event Type Tree to be empty for inflated recordings.
     */
    @Test
    void categoryAnnotationsSurviveRoundTrip() throws Exception {
        Path src = Path.of("profile.jfr");
        Assumptions.assumeTrue(Files.exists(src), "profile.jfr not found — skipping");

        ByteArrayOutputStream cjfrBytes = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(cjfrBytes, StartMessage.DEFAULT)) {
            BasicJFRWriter writer = new BasicJFRWriter(out, Configuration.LOSSLESS);
            try (RecordingFile recording = new RecordingFile(src)) {
                writer.registerEventTypes(recording.readEventTypes());
                while (recording.hasMoreEvents()) {
                    writer.processEvent(recording.readEvent());
                }
            }
            writer.close();
        }

        Path inflated = tempDir.resolve("category-test.jfr");
        try (CondensedInputStream in = new CondensedInputStream(cjfrBytes.toByteArray())) {
            WritingJFRReader.toJFRFile(new BasicJFRReader(in), inflated);
        }

        // Check that event types which have events in this recording have @Category preserved
        try (RecordingFile rf = new RecordingFile(inflated)) {
            List<jdk.jfr.EventType> types = rf.readEventTypes();
            var missingCategory = new ArrayList<String>();
            // Only check event types that we know have @Category in the original JFR
            try (RecordingFile orig = new RecordingFile(src)) {
                var origCategory =
                        orig.readEventTypes().stream()
                                .filter(t -> t.getCategoryNames() != null && !t.getCategoryNames().isEmpty())
                                .map(jdk.jfr.EventType::getName)
                                .collect(java.util.stream.Collectors.toSet());
                for (var t : types) {
                    if (!origCategory.contains(t.getName())) continue;
                    if (t.getCategoryNames() == null || t.getCategoryNames().isEmpty()) {
                        missingCategory.add(t.getName());
                    }
                }
            }
            if (!missingCategory.isEmpty()) {
                fail("@Category lost after round-trip for: " + missingCategory);
            }
        }
    }

    /**
     * Verifies that @Label and @Description survive the condense→inflate round-trip for all event
     * types that have events in the recording. Also checks that the footer's eventTypeLabels map
     * (used by {@code cjfr view} on raw .cjfr files) is populated for zero-event types.
     */
    @Test
    void eventTypeAnnotationsSurviveRoundTrip() throws Exception {
        Path src = Path.of("profile.jfr");
        Assumptions.assumeTrue(Files.exists(src), "profile.jfr not found — skipping");

        ByteArrayOutputStream cjfrBytes = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(cjfrBytes, StartMessage.DEFAULT)) {
            BasicJFRWriter writer = new BasicJFRWriter(out, Configuration.LOSSLESS);
            try (RecordingFile recording = new RecordingFile(src)) {
                writer.registerEventTypes(recording.readEventTypes());
                while (recording.hasMoreEvents()) {
                    writer.processEvent(recording.readEvent());
                }
            }
            writer.close();
        }

        // Read the footer: it must contain labels for ALL event types, including zero-event types
        var footer = me.bechberger.condensed.CJFRFooterReader.tryRead(cjfrBytes.toByteArray());
        if (footer.isPresent()) {
            var footerLabels = footer.get().eventTypeLabels();
            try (RecordingFile orig = new RecordingFile(src)) {
                var missing = new ArrayList<String>();
                for (var et : orig.readEventTypes()) {
                    if (et.getLabel() != null
                            && !et.getLabel().isEmpty()
                            && !footerLabels.containsKey(et.getName())) {
                        missing.add(et.getName() + " (@Label=" + et.getLabel() + ")");
                    }
                }
                if (!missing.isEmpty()) {
                    fail("Footer missing @Label for event types: " + missing);
                }
            }
        }

        // Inflate and check @Label/@Category survive in the inflated JFR
        Path inflated = tempDir.resolve("annotation-test.jfr");
        try (me.bechberger.condensed.CondensedInputStream in =
                new me.bechberger.condensed.CondensedInputStream(cjfrBytes.toByteArray())) {
            WritingJFRReader.toJFRFile(new BasicJFRReader(in), inflated);
        }

        try (RecordingFile orig = new RecordingFile(src);
                RecordingFile inflatedRf = new RecordingFile(inflated)) {
            var origByName =
                    orig.readEventTypes().stream()
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            jdk.jfr.EventType::getName,
                                            t -> t,
                                            (a, b) -> a));
            var missingLabel = new ArrayList<String>();
            var missingCategory2 = new ArrayList<String>();
            for (var t : inflatedRf.readEventTypes()) {
                var o = origByName.get(t.getName());
                if (o == null) continue;
                if (o.getLabel() != null
                        && !o.getLabel().isEmpty()
                        && (t.getLabel() == null || t.getLabel().isEmpty())) {
                    missingLabel.add(t.getName());
                }
                if (o.getCategoryNames() != null
                        && !o.getCategoryNames().isEmpty()
                        && (t.getCategoryNames() == null || t.getCategoryNames().isEmpty())) {
                    missingCategory2.add(t.getName());
                }
            }
            if (!missingLabel.isEmpty()) {
                fail("@Label lost after round-trip for: " + missingLabel);
            }
            if (!missingCategory2.isEmpty()) {
                fail("@Category lost after round-trip for: " + missingCategory2);
            }
        }
    }
}
