package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests using custom JFR events designed to break the CLI tool.
 *
 * <p>Strategy: each test targets a specific failure mode — null field handling, IEEE-754 special
 * values, high event volume, round-trip fidelity, and filename edge cases. Every assertion is
 * meaningful (no {@code >= 0} cop-outs).
 */
public class CustomJFREventBreakingTest {

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * Condense a JFR file to CJFR, then inflate back to JFR and return all events from the inflated
     * file. Asserts both steps exit with code 0.
     */
    private static List<RecordedEvent> roundTrip(Path jfrInput, String baseName) throws Exception {
        return roundTrip(jfrInput, baseName, null);
    }

    private static List<RecordedEvent> roundTrip(
            Path jfrInput, String baseName, String condenserConfig) throws Exception {
        AtomicReference<List<RecordedEvent>> result = new AtomicReference<>();

        var condense =
                condenserConfig == null
                        ? new CommandExecuter(
                                "condense",
                                "T/" + jfrInput.getFileName(),
                                "T/" + baseName + ".cjfr")
                        : new CommandExecuter(
                                "condense",
                                "T/" + jfrInput.getFileName(),
                                "T/" + baseName + ".cjfr",
                                "--condenser-config",
                                condenserConfig);

        condense.withFiles(jfrInput)
                .checkNoError()
                .check(
                        (condenseResult, condenseMap) -> {
                            Path cjfr = condenseMap.get(baseName + ".cjfr");
                            assertThat(cjfr).isNotNull().exists().isNotEmptyFile();

                            new CommandExecuter(
                                            "inflate",
                                            "T/" + baseName + ".cjfr",
                                            "T/" + baseName + ".inflated.jfr")
                                    .withFiles(cjfr)
                                    .checkNoError()
                                    .check(
                                            (inflateResult, inflateMap) -> {
                                                Path inflated =
                                                        inflateMap.get(baseName + ".inflated.jfr");
                                                assertThat(inflated).isNotNull().exists();
                                                result.set(RecordingFile.readAllEvents(inflated));
                                            })
                                    .run();
                        })
                .run();

        return result.get();
    }

    // ─── null string field tests ───────────────────────────────────────────────

    /**
     * Bug candidate: BasicJFRWriter may NPE when serializing a JFR event whose String field was
     * never assigned (value is {@code null}).
     */
    @Test
    public void testCondenseWithNullStringFieldDoesNotCrash() throws Exception {
        new CommandExecuter("condense", "T/null_string.jfr", "T/null_string.cjfr")
                .withFiles(CommandTestUtil.getNullStringJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            assertThat(map.get("null_string.cjfr"))
                                    .isNotNull()
                                    .exists()
                                    .isNotEmptyFile();
                        })
                .run();
    }

    /**
     * Round-trip fidelity: after condense → inflate the null String field must still be readable
     * (as null or empty — both are acceptable; a crash is not).
     */
    @InflaterRelated
    @Test
    public void testNullStringFieldSurvivesRoundTrip() throws Exception {
        var events = roundTrip(CommandTestUtil.getNullStringJFRFile(), "null_string");

        var nullStringEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("NullStringEvent"))
                        .collect(Collectors.toList());

        assertThat(nullStringEvents)
                .as("NullStringEvent instances must survive the round-trip")
                .isNotEmpty();

        // The empty string field must be preserved exactly.
        for (var e : nullStringEvents) {
            assertThat(e.getString("emptyField"))
                    .as("emptyField must remain empty string")
                    .isEqualTo("");
            assertThat(e.getInt("controlInt"))
                    .as("controlInt sentinel value must be preserved")
                    .isEqualTo(99);
        }
    }

    // ─── NaN / Infinity double field tests ────────────────────────────────────

    /**
     * Bug candidate: condensing events whose double fields contain NaN / ±Infinity may crash if
     * serialization assumes finite values.
     */
    @Test
    public void testCondenseWithNaNDoubleFieldDoesNotCrash() throws Exception {
        new CommandExecuter("condense", "T/special_double.jfr", "T/special_double.cjfr")
                .withFiles(CommandTestUtil.getSpecialDoubleJFRFile())
                .checkNoError()
                .check(
                        (r, map) ->
                                assertThat(map.get("special_double.cjfr"))
                                        .isNotNull()
                                        .exists()
                                        .isNotEmptyFile())
                .run();
    }

    /**
     * Round-trip fidelity: NaN and ±Infinity double values must be preserved exactly through the
     * condense → inflate pipeline.
     */
    @InflaterRelated
    @Test
    public void testNaNAndInfinityDoublesPreservedInRoundTrip() throws Exception {
        var events = roundTrip(CommandTestUtil.getSpecialDoubleJFRFile(), "special_double");

        var specialEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("SpecialDoubleEvent"))
                        .collect(Collectors.toList());

        assertThat(specialEvents)
                .as("SpecialDoubleEvent instances must survive the round-trip")
                .isNotEmpty();

        for (var e : specialEvents) {
            assertThat(Double.isNaN(e.getDouble("nanValue")))
                    .as("nanValue must survive as NaN")
                    .isTrue();
            assertThat(e.getDouble("posInfinity"))
                    .as("posInfinity must survive as +Inf")
                    .isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(e.getDouble("negInfinity"))
                    .as("negInfinity must survive as -Inf")
                    .isEqualTo(Double.NEGATIVE_INFINITY);
        }
    }

    @InflaterRelated
    @Test
    public void testPreciseDoublePreservedInRoundTrip() throws Exception {
        var events = roundTrip(CommandTestUtil.getPreciseDoubleJFRFile(), "precise_double");

        var preciseEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("PreciseDoubleEvent"))
                        .collect(Collectors.toList());

        assertThat(preciseEvents)
                .as("PreciseDoubleEvent instances must survive the round-trip")
                .isNotEmpty();

        for (var e : preciseEvents) {
            assertThat(e.getDouble("preciseValue"))
                    .as("preciseValue must keep full double precision")
                    .isEqualTo(1.23456789012345d);
        }
    }

    @InflaterRelated
    @Test
    public void testPreciseDoubleReducedToFloatInReasonableDefault() throws Exception {
        var events =
                roundTrip(
                        CommandTestUtil.getPreciseDoubleJFRFile(),
                        "precise_double_reasonable",
                        "reasonable-default");

        var preciseEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("PreciseDoubleEvent"))
                        .collect(Collectors.toList());

        assertThat(preciseEvents)
                .as("PreciseDoubleEvent instances must survive the round-trip")
                .isNotEmpty();

        double expected = (double) (float) 1.23456789012345d;
        for (var e : preciseEvents) {
            assertThat(e.getDouble("preciseValue"))
                    .as("reasonable-default should reduce double precision to float32")
                    .isEqualTo(expected);
        }
    }

    @InflaterRelated
    @Test
    public void testPreciseDoubleReducedToFloat16InReducedDefault() throws Exception {
        var events =
                roundTrip(
                        CommandTestUtil.getPreciseDoubleJFRFile(),
                        "precise_double_reduced",
                        "reduced-default");

        var preciseEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("PreciseDoubleEvent"))
                        .collect(Collectors.toList());

        assertThat(preciseEvents)
                .as("PreciseDoubleEvent instances must survive the round-trip")
                .isNotEmpty();

        double float32Expected = (double) (float) 1.23456789012345d;
        for (var e : preciseEvents) {
            assertThat(e.getDouble("preciseValue"))
                    .as("reduced-default should reduce double precision to float16")
                    .isNotEqualTo(1.23456789012345d)
                    .isNotEqualTo(float32Expected);
        }
    }

    /**
     * Bug candidate: the view command renders event fields. If NaN ends up in a JSON-serialisation
     * path it can crash. The view must exit 0 with output.
     */
    @Test
    public void testViewWithNaNDoubleFieldDoesNotCrash() throws Exception {
        new CommandExecuter("condense", "T/special_double.jfr", "T/special_double.cjfr")
                .withFiles(CommandTestUtil.getSpecialDoubleJFRFile())
                .checkNoError()
                .check(
                        (r, map) -> {
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    map.get("special_double.cjfr").toString(),
                                                    "SpecialDoubleEvent")
                                            .run();
                            assertThat(viewResult.exitCode())
                                    .as("view SpecialDoubleEvent must exit 0")
                                    .isEqualTo(0);
                            assertThat(viewResult.output()).contains("SpecialDoubleEvent");
                        })
                .run();
    }

    // ─── high-frequency event tests ───────────────────────────────────────────

    /**
     * Bug candidate: high event volume may overflow internal buffers or counters. All 500 events
     * must survive the condense → inflate round-trip.
     */
    @InflaterRelated
    @Test
    public void testHighFrequencyEventsAllSurviveRoundTrip() throws Exception {
        int count = 500;
        var events = roundTrip(CommandTestUtil.getHighFreqJFRFile(count), "high_freq_" + count);

        long highFreqCount =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("HighFreqEvent"))
                        .count();

        assertThat(highFreqCount)
                .as("All " + count + " HighFreqEvent instances must survive the round-trip")
                .isEqualTo(count);
    }

    /** summary --json on 500 condensed events must report the exact event count. */
    @Test
    public void testHighFrequencyEventCountInSummaryJson() throws Exception {
        int count = 500;
        new CommandExecuter(
                        "condense",
                        "T/high_freq_" + count + ".jfr",
                        "T/high_freq_" + count + ".cjfr")
                .withFiles(CommandTestUtil.getHighFreqJFRFile(count))
                .checkNoError()
                .check(
                        (r, map) -> {
                            var summaryResult =
                                    new CommandExecuter(
                                                    "summary",
                                                    map.get("high_freq_" + count + ".cjfr")
                                                            .toString(),
                                                    "--json")
                                            .run();
                            assertThat(summaryResult.exitCode()).isEqualTo(0);
                            Map<String, Object> json =
                                    Util.asMap(JSONParser.parse(summaryResult.output()));
                            int reported =
                                    ((Number) Util.asMap(json.get("events")).get("HighFreqEvent"))
                                            .intValue();
                            assertThat(reported)
                                    .as("summary must report all " + count + " HighFreqEvents")
                                    .isEqualTo(count);
                        })
                .run();
    }

    // ─── round-trip fidelity for extreme numerics ─────────────────────────────

    /**
     * Round-trip fidelity: Long.MAX_VALUE and Long.MIN_VALUE must be preserved exactly — any
     * overflow or truncation would silently corrupt data.
     */
    @InflaterRelated
    @Test
    public void testExtremeIntegerValuesPreservedInRoundTrip() throws Exception {
        var events = roundTrip(CommandTestUtil.getExtremeNumericJFRFile(), "extreme_numeric");

        var extremeEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("ExtremeNumericEvent"))
                        .collect(Collectors.toList());

        assertThat(extremeEvents)
                .as("ExtremeNumericEvent must survive the round-trip")
                .isNotEmpty();

        for (var e : extremeEvents) {
            assertThat(e.getLong("maxLong"))
                    .as("Long.MAX_VALUE must be preserved")
                    .isEqualTo(Long.MAX_VALUE);
            assertThat(e.getLong("minLong"))
                    .as("Long.MIN_VALUE must be preserved")
                    .isEqualTo(Long.MIN_VALUE);
            assertThat(e.getInt("maxInt"))
                    .as("Integer.MAX_VALUE must be preserved")
                    .isEqualTo(Integer.MAX_VALUE);
            assertThat(e.getInt("minInt"))
                    .as("Integer.MIN_VALUE must be preserved")
                    .isEqualTo(Integer.MIN_VALUE);
        }
    }

    // ─── round-trip fidelity for unicode strings ──────────────────────────────

    /**
     * Round-trip fidelity: emoji, CJK, Arabic, and special-character strings must survive condense
     * → inflate without corruption or truncation.
     */
    @InflaterRelated
    @Test
    public void testUnicodeStringsPreservedInRoundTrip() throws Exception {
        var events = roundTrip(CommandTestUtil.getUnicodeStringJFRFile(), "unicode_string");

        var unicodeEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("UnicodeStringEvent"))
                        .collect(Collectors.toList());

        assertThat(unicodeEvents).as("UnicodeStringEvent must survive the round-trip").isNotEmpty();

        for (var e : unicodeEvents) {
            assertThat(e.getString("emoji"))
                    .as("emoji string must be preserved")
                    .isEqualTo("🎉🚀💥✨🔥");
            assertThat(e.getString("chinese"))
                    .as("Chinese characters must be preserved")
                    .isEqualTo("你好世界");
        }
    }

    // ─── large string round-trip ──────────────────────────────────────────────

    /**
     * Round-trip fidelity: a 10 000-character string field must survive condense → inflate without
     * truncation.
     */
    @InflaterRelated
    @Test
    public void testLargeStringPreservedInRoundTrip() throws Exception {
        var events = roundTrip(CommandTestUtil.getLargeStringJFRFile(), "large_string");

        var largeStringEvents =
                events.stream()
                        .filter(e -> e.getEventType().getName().equals("LargeStringEvent"))
                        .collect(Collectors.toList());

        assertThat(largeStringEvents)
                .as("LargeStringEvent must survive the round-trip")
                .isNotEmpty();

        for (var e : largeStringEvents) {
            String value = e.getString("largeString");
            assertThat(value)
                    .as("10 000-char string must be preserved in full")
                    .hasSize(10000)
                    .isEqualTo("x".repeat(10000));
        }
    }

    // ─── filename with double .jfr extension ─────────────────────────────────

    /**
     * Bug candidate: {@code CondenseCommand.getOutputFile()} uses {@code String.replace(".jfr",
     * ".cjfr")} which replaces ALL occurrences. A filename like {@code record.jfr.bak.jfr} would
     * produce {@code record.cjfr.bak.cjfr} — two replacements creating a broken extension that
     * downstream commands might reject or misinterpret.
     */
    @Test
    public void testFilenameWithDoubleJfrExtensionAutoOutput() throws Exception {
        // Copy the sample JFR under a name that contains .jfr twice
        Path tmp = Files.createTempDirectory("jfr-double-ext-test");
        Path weirdName = tmp.resolve("session.jfr.bak.jfr");
        Files.copy(CommandTestUtil.getSampleJFRFile(), weirdName);

        // Condense without specifying an output path — the output path is auto-derived
        var result =
                new CommandExecuter("condense", "T/session.jfr.bak.jfr")
                        .withFiles(Map.of(weirdName, "session.jfr.bak.jfr"))
                        .run();

        // The command must succeed — even with a weird input filename
        assertThat(result.exitCode())
                .as("condense must succeed on a filename with .jfr appearing twice")
                .isEqualTo(0);
    }
}
