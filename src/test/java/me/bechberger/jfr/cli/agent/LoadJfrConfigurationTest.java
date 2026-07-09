package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LoadJfrConfigurationTest {

    @Test
    public void loadPredefinedByName() throws IOException, ParseException {
        var config = RecordingThread.loadJfrConfiguration("default");
        assertEquals("default", config.getName());
    }

    @Test
    public void loadPredefinedByNameWithJfcSuffix() throws IOException, ParseException {
        var config = RecordingThread.loadJfrConfiguration("default.jfc");
        assertEquals("default", config.getName());
    }

    @Test
    public void loadProfileByNameWithJfcSuffix() throws IOException, ParseException {
        var config = RecordingThread.loadJfrConfiguration("profile.jfc");
        assertEquals("profile", config.getName());
    }

    @Test
    public void loadFromAbsolutePath(@TempDir Path tmp) throws IOException, ParseException {
        Path jfc = copyPredefinedJfc("default", tmp.resolve("my-custom.jfc"));
        var config = RecordingThread.loadJfrConfiguration(jfc.toString());
        assertNotNull(config);
    }

    @Test
    public void unknownPredefinedNameThrows() {
        assertThrows(
                Exception.class,
                () -> RecordingThread.loadJfrConfiguration("nonexistent_config_xyz"));
    }

    @Test
    public void unknownPredefinedNameWithJfcSuffixThrows() {
        assertThrows(
                Exception.class,
                () -> RecordingThread.loadJfrConfiguration("nonexistent_config_xyz.jfc"));
    }

    /** Copies the content of a predefined JFC into dest by reading it via the JDK. */
    private static Path copyPredefinedJfc(String name, Path dest) throws IOException {
        // The JDK ships .jfc files as resources inside jdk.jfr module
        String resource = "/jdk/jfr/internal/jfc/jfc/" + name + ".jfc";
        try (InputStream in = jdk.jfr.Configuration.class.getResourceAsStream(resource)) {
            if (in == null) {
                // Fallback: ask the JDK for the predefined config and write its XML
                // (jdk.jfr doesn't expose the raw XML, so we copy from the known path)
                Path jfcInJdk = findJfcInJdk(name);
                Files.copy(jfcInJdk, dest);
            } else {
                Files.copy(in, dest);
            }
        }
        return dest;
    }

    private static Path findJfcInJdk(String name) throws IOException {
        String javaHome = System.getProperty("java.home");
        Path candidate = Path.of(javaHome, "lib", "jfr", name + ".jfc");
        if (Files.exists(candidate)) {
            return candidate;
        }
        throw new IOException("Cannot find " + name + ".jfc in JDK at " + javaHome);
    }
}
