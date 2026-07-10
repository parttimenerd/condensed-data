package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import me.bechberger.condensed.CJFRFooterReader;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integrity checks for the {@code inflate} command: a corrupted main stream must be
 * rejected by the whole-file CRC32, and {@code --ignore-integrity} must bypass the check.
 */
@InflaterRelated
public class InflateIntegrityTest {

    /** Flips one byte in the compressed main stream (below the footer) of a .cjfr file. */
    private static void corruptMainStreamByte(Path cjfr) throws Exception {
        OptionalLong footerStart = CJFRFooterReader.footerStart(cjfr);
        assertThat(footerStart).isPresent();
        long len = Files.size(cjfr);
        // pick an offset comfortably inside the main stream, past the uncompressed header
        long offset = Math.min(len - 1, Math.max(20, footerStart.getAsLong() / 2));
        byte[] bytes = Files.readAllBytes(cjfr);
        bytes[(int) offset] ^= (byte) 0xFF;
        Files.write(cjfr, bytes);
    }

    @Test
    public void testInflateFailsOnCorruptedMainStream() throws Exception {
        Path jfr = CommandTestUtil.getSampleJFRFile();
        new CommandExecuter("condense", "T/sample.jfr", "T/s.cjfr")
                .withFiles(jfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            Path cjfr = map.get("s.cjfr");
                            assertThat(cjfr).exists();
                            corruptMainStreamByte(cjfr);

                            var r =
                                    new CommandExecuter("inflate", "T/s.cjfr", "T/s.jfr")
                                            .withFiles(cjfr)
                                            .run();
                            assertThat(r.exitCode())
                                    .describedAs("inflate of corrupted file should fail")
                                    .isNotEqualTo(0);
                            assertThat(r.error()).containsIgnoringCase("integrity");
                        })
                .run();
    }

    @Test
    public void testInflateIgnoreIntegrityBypassesCorruption() throws Exception {
        Path jfr = CommandTestUtil.getSampleJFRFile();
        new CommandExecuter("condense", "T/sample.jfr", "T/s.cjfr")
                .withFiles(jfr)
                .checkNoError()
                .check(
                        (result, map) -> {
                            Path cjfr = map.get("s.cjfr");
                            corruptMainStreamByte(cjfr);

                            var r =
                                    new CommandExecuter(
                                                    "inflate",
                                                    "--ignore-integrity",
                                                    "T/s.cjfr",
                                                    "T/s.jfr")
                                            .withFiles(cjfr)
                                            .run();
                            // With the check bypassed the CRC no longer aborts the run; the
                            // command must not fail with an IntegrityCheckException.
                            assertThat(r.error()).doesNotContainIgnoringCase("integrity");
                        })
                .run();
    }
}
