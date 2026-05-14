package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

@InflaterRelated
public class InflateCorruptionTest {

    /**
     * Bug 16: Inflate produces corrupt JFR files (StackTrace constant pool). Round-trip: condense
     * profile.jfr → inflate → re-condense should work.
     */
    @Test
    public void testInflateRoundTripWithProfileJfr() throws Exception {
        Path profileJfr = Path.of("profile.jfr");
        if (!Files.exists(profileJfr)) {
            System.err.println("Skipping: profile.jfr not found");
            return;
        }

        // Steps: condense → inflate → read with RecordingFile → re-condense
        new CommandExecuter("condense", "T/profile.jfr", "T/test.cjfr")
                .withFiles(profileJfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            assertThat(map).containsKey("test.cjfr");
                            // Inflate
                            var inflateResult =
                                    new CommandExecuter("inflate", "T/test.cjfr", "T/inflated.jfr")
                                            .withFiles(map.get("test.cjfr"))
                                            .checkNoError()
                                            .check(
                                                    (r2, m2) -> {
                                                        Path inflatedFile = m2.get("inflated.jfr");
                                                        assertThat(inflatedFile).exists();

                                                        // Read with JDK RecordingFile
                                                        int[] count = {0};
                                                        try (RecordingFile rf =
                                                                new RecordingFile(inflatedFile)) {
                                                            while (rf.hasMoreEvents()) {
                                                                rf.readEvent();
                                                                count[0]++;
                                                            }
                                                        } catch (Exception e) {
                                                            throw new RuntimeException(
                                                                    "Failed to read inflated JFR: "
                                                                            + e.getMessage(),
                                                                    e);
                                                        }
                                                        assertThat(count[0])
                                                                .describedAs(
                                                                        "events in inflated file")
                                                                .isGreaterThan(0);

                                                        // Re-condense
                                                        var r3 =
                                                                new CommandExecuter(
                                                                                "condense",
                                                                                "T/inflated.jfr",
                                                                                "T/recondensed.cjfr")
                                                                        .withFiles(inflatedFile)
                                                                        .run();
                                                        assertThat(r3.exitCode())
                                                                .describedAs(
                                                                        "Re-condense error: %s",
                                                                        r3.error())
                                                                .isEqualTo(0);
                                                    })
                                            .run();
                        })
                .run();
    }
}
