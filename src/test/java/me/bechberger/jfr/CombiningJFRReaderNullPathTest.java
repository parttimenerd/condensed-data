package me.bechberger.jfr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.bechberger.jfr.cli.EventFilter;
import org.junit.jupiter.api.Test;

/**
 * Tests for Bug 254: CombiningJFRReader.readersForPath() throws NullPointerException when
 * path.toFile().listFiles() returns null (e.g., path is not a readable directory or I/O error).
 */
public class CombiningJFRReaderNullPathTest {

    /**
     * Verifies that passing a non-existent directory path to fromPaths does not throw NPE. Before
     * the fix, Objects.requireNonNull(path.toFile().listFiles()) would throw NPE because
     * listFiles() returns null for non-existent paths. After the fix, it returns an empty list
     * which results in a proper IllegalArgumentException ("No cjfr files given").
     */
    @Test
    public void testNonExistentDirectoryDoesNotThrowNPE() {
        Path nonExistent = Path.of("tmp/non-existent-dir-" + System.nanoTime());
        assertFalse(Files.exists(nonExistent));

        // Before fix: NullPointerException from Objects.requireNonNull
        // After fix: IllegalArgumentException ("No cjfr files given") — not NPE
        var ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                CombiningJFRReader.fromPaths(
                                        List.of(nonExistent),
                                        (EventFilter.EventFilterInstance) null,
                                        true));
        assertThat(ex.getMessage()).contains("No cjfr files given");
    }

    /**
     * Verifies that an empty directory is handled gracefully (no matching files found, but no
     * crash).
     */
    @Test
    public void testEmptyDirectoryReturnsNoReaders() throws Exception {
        Path emptyDir = Files.createTempDirectory("combining-reader-empty-test");
        try {
            // An empty directory with no .cjfr/.jfr files should throw IllegalArgumentException
            // ("No cjfr files given") rather than NPE
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            CombiningJFRReader.fromPaths(
                                    List.of(emptyDir),
                                    (EventFilter.EventFilterInstance) null,
                                    true));
        } finally {
            Files.deleteIfExists(emptyDir);
        }
    }
}
