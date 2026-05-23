package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Bug 252: Reconstituted events show raw nanosecond numbers for duration fields in view --json
 * output instead of proper ISO 8601 Duration strings (e.g. "PT0.000042S").
 *
 * <p>When combined events (like jdk.GCPhasePauseLevel1) are reconstituted from their condensed
 * form, the duration field is stored as a Long (nanoseconds) in the ReadStruct. The ViewCommand's
 * JSON serialization then outputs the raw number (e.g. 42) instead of the Duration format (e.g.
 * "PT0.000000042S") that non-reconstituted events use.
 */
@InflaterRelated
public class ReconstitutedDurationTest {

    @Test
    public void testReconstitutedEventDurationIsFormattedAsDuration() throws Exception {
        Path profileJfr = Path.of("profile.jfr");
        if (!Files.exists(profileJfr)) {
            System.err.println("Skipping: profile.jfr not found");
            return;
        }

        // Condense profile.jfr → test.cjfr
        new CommandExecuter("condense", "T/profile.jfr", "T/test.cjfr")
                .withFiles(profileJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("test.cjfr");

                            // View reconstituted GCPhasePauseLevel1 events as JSON
                            // These events go through the combiner/reconstitutor path
                            var viewResult =
                                    new CommandExecuter(
                                                    "view",
                                                    "T/test.cjfr",
                                                    "jdk.GCPhasePauseLevel1",
                                                    "--json",
                                                    "--limit",
                                                    "5")
                                            .withFiles(map.get("test.cjfr"))
                                            .checkNoError()
                                            .run();

                            String jsonOutput = viewResult.output();
                            assertThat(jsonOutput)
                                    .describedAs("Should have some events")
                                    .contains("startTime");

                            // Duration fields should be formatted as ISO 8601 Duration
                            // strings (e.g. "PT0.000042S"), NOT as raw numbers
                            assertThat(jsonOutput)
                                    .describedAs(
                                            "Duration should be formatted as ISO Duration string"
                                                    + " (e.g. PT0.000042S), not as raw nanoseconds."
                                                    + " Got: %s",
                                            jsonOutput)
                                    .contains("\"duration\": \"PT");
                        })
                .run();
    }
}
