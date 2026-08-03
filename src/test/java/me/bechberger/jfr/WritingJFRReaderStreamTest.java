package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import me.bechberger.condensed.CondensedInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

class WritingJFRReaderStreamTest {

    private static final Path PROFILE_CJFR = Path.of("profile_default.cjfr");

    @Test
    void toJFRStreamProducesSameBytesAsToJFRFile() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(PROFILE_CJFR), "profile_default.cjfr not present, skipping");
        Path tmp;
        try (var cin = new CondensedInputStream(java.nio.file.Files.newInputStream(PROFILE_CJFR))) {
            tmp = WritingJFRReader.toJFRFile(new BasicJFRReader(cin));
        }
        byte[] expected = java.nio.file.Files.readAllBytes(tmp);
        java.nio.file.Files.deleteIfExists(tmp);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var cin = new CondensedInputStream(java.nio.file.Files.newInputStream(PROFILE_CJFR))) {
            WritingJFRReader.toJFRStream(new BasicJFRReader(cin), baos);
        }
        byte[] actual = baos.toByteArray();

        assertArrayEquals(
                expected, actual, "toJFRStream output must be byte-identical to toJFRFile output");
    }

    @Test
    void toJFRStreamOutputIsValidJFR() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(PROFILE_CJFR), "profile_default.cjfr not present, skipping");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var cin = new CondensedInputStream(java.nio.file.Files.newInputStream(PROFILE_CJFR))) {
            WritingJFRReader.toJFRStream(new BasicJFRReader(cin), baos);
        }
        byte[] bytes = baos.toByteArray();
        assertArrayEquals(
                new byte[] {'F', 'L', 'R', 0},
                new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]},
                "Output must start with JFR magic bytes");
        assertTrue(bytes.length > 1024, "Output should be a non-trivial JFR file");
    }

    @Test
    void toJFRStreamIsLoadableByJMC() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(PROFILE_CJFR), "profile_default.cjfr not present, skipping");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var cin = new CondensedInputStream(java.nio.file.Files.newInputStream(PROFILE_CJFR))) {
            WritingJFRReader.toJFRStream(new BasicJFRReader(cin), baos);
        }
        IItemCollection events =
                JfrLoaderToolkit.loadEvents(new ByteArrayInputStream(baos.toByteArray()));
        assertTrue(events.hasItems(), "JMC must parse at least one event from the inflated stream");
    }
}
