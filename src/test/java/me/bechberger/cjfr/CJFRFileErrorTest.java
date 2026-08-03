package me.bechberger.cjfr;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link CJFRFile} error handling and edge cases. */
public class CJFRFileErrorTest {

    @Test
    void open_nonExistentFile_throwsIOException(@TempDir Path tmp) {
        Path missing = tmp.resolve("missing.cjfr");
        assertThrows(IOException.class, () -> CJFRFile.open(missing));
    }

    @Test
    void open_emptyFile_isTruncated(@TempDir Path tmp) throws IOException {
        Path empty = tmp.resolve("empty.cjfr");
        Files.write(empty, new byte[0]);
        try (CJFRFile f = CJFRFile.open(empty)) {
            // Truncated / empty file should not throw; readEvent returns null
            CJFREvent e = f.readEvent();
            assertNull(e);
            assertTrue(f.isTruncated() || !f.hasMoreEvents());
        }
    }

    @Test
    void open_randomBytes_isTruncatedOrReturnsNoEvents(@TempDir Path tmp) throws IOException {
        byte[] garbage = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        Path file = tmp.resolve("garbage.cjfr");
        Files.write(file, garbage);
        try (CJFRFile f = CJFRFile.open(file)) {
            // Should not throw — the reader tolerates errors silently
            List<CJFREvent> events = f.readAllEvents();
            assertTrue(events.isEmpty() || f.isTruncated());
        }
    }

    @Test
    void open_nonCjfrJfrFile_isTruncatedOrEmpty(@TempDir Path tmp) throws IOException {
        // A real .jfr file is not a .cjfr stream; the reader should fail gracefully
        Path jfr = Path.of("profile.jfr");
        Assumptions.assumeTrue(Files.exists(jfr), "profile.jfr not found — skipping");

        Path copy = tmp.resolve("wrong.cjfr");
        Files.copy(jfr, copy);
        try (CJFRFile f = CJFRFile.open(copy)) {
            List<CJFREvent> events = f.readAllEvents();
            // The reader won't produce valid events from a raw JFR stream
            assertTrue(events.isEmpty() || f.isTruncated());
        }
    }

    @Test
    void readEvent_afterClose_doesNotCrash(@TempDir Path tmp) throws Exception {
        byte[] bytes = CJFRFileTest.writeCjfrBytes(1);
        Path file = tmp.resolve("test.cjfr");
        Files.write(file, bytes);
        CJFRFile f = CJFRFile.open(file);
        f.close();
        // Reading after close: may throw IOException, or return null — should not crash JVM
        try {
            f.readEvent();
        } catch (Exception ignored) {
            // acceptable
        }
    }

    @Test
    void getFormatVersion_minusOneWhenNoFooter() throws Exception {
        byte[] bytes = CJFRFileTest.writeCjfrBytes(1);
        try (CJFRFile f = CJFRFile.open(new java.io.ByteArrayInputStream(bytes))) {
            // In-memory stream has no footer
            assertThat(f.getFormatVersion()).isEqualTo(-1);
        }
    }
}
