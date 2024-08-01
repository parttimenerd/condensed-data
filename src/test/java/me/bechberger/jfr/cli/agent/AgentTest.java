package me.bechberger.jfr.cli.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palantir.humanreadabletypes.HumanReadableByteCount;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import one.profiler.AsyncProfilerLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** <b>Tests assume the condensed-data.jar to be built first</b> */
public class AgentTest {

    @BeforeEach
    public void setup() {
        // delete test-dir folder if it exists
        try {
            var testDir = Path.of("test-dir");
            if (Files.exists(testDir)) {
                try (var files = Files.walk(testDir)) {
                    files.map(Path::toFile).forEach(java.io.File::delete);
                }
                Files.delete(testDir);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testHelp() {
        var output = runAgent("help");
        assertTrue(output.contains("Usage:"));
    }

    @Test
    public void testBasicRun() throws InterruptedException {
        var output = runAgent("start,test-dir/recording.cjfr,verbose");
        assertThat(output).startsWith("Condensed recording to ");
        System.out.println(output);

        // check status
        var status = runAgent("status");
        var lines = status.split("\n");
        assertEquals(lines[0], "Recording running");
        for (int i = 1; i < lines.length; i++) {
            assertThat(lines[i]).contains(": ");
        }
        Thread.sleep(1000);
        status = runAgent("status");
        System.out.println(status);
        // check that the current-size-uncompressed property is memory and larger than 1000 bytes
        assertThat(status).contains("current-size-uncompressed: ");
        // TODO: fix parsing and memory formatting
        var bytes =
                HumanReadableByteCount.valueOf(
                                status.split("current-size-uncompressed: ")[1]
                                        .split("\n")[0]
                                        .toLowerCase()
                                        .replaceAll(",[0-9]+", ""))
                        .toBytes();
        assertThat(bytes).isGreaterThan(1000);

        output = runAgent("stop");
        System.out.println(output);
        assertThat(Path.of("test-dir/recording.cjfr"))
                .exists()
                .satisfies(
                        (Consumer<Path>)
                                path -> {
                                    try {
                                        assertThat(Files.size(path)).isGreaterThan(1000);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
    }

    /** Attaches an agent to the current JVM and records the output */
    String runAgent(String args) {
        var out = System.out;
        var newOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(newOut));
        try {
            var pid = ProcessHandle.current().pid();
            AsyncProfilerLoader.executeJattach(
                    pid + "", "load", "instrument", "false", "target/condensed-data.jar=" + args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.setOut(out);
        return newOut.toString();
    }
}
