package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AgentIOTest {

    @TempDir Path tempDir;

    private String previousTmpDir;

    private void useTempDir() {
        previousTmpDir = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", tempDir.toString());
    }

    private static void resetAgentIOStatics() throws Exception {
        Field instance = AgentIO.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        Field defaultLogToFile = AgentIO.class.getDeclaredField("defaultLogToFile");
        defaultLogToFile.setAccessible(true);
        defaultLogToFile.setBoolean(null, false);

        Field defaultLogLevel = AgentIO.class.getDeclaredField("defaultLogLevel");
        defaultLogLevel.setAccessible(true);
        defaultLogLevel.set(null, AgentIO.LogLevel.WARNING);
    }

    private AgentIO newAgentIO(boolean logToFile) {
        return new AgentIO("test-agent", 12345L, logToFile, AgentIO.LogLevel.ALL);
    }

    private AgentIO newFileBackedAgentIOInTempDir() {
        useTempDir();
        return newAgentIO(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (previousTmpDir != null) {
            System.setProperty("java.io.tmpdir", previousTmpDir);
        }
        resetAgentIOStatics();
    }

    @Test
    public void testSingleLineFormatterIncludesClassNameAndMessage() {
        var formatter = new AgentIO.SingleLineFormatter();
        var record = new LogRecord(Level.WARNING, "hello world");
        record.setSourceClassName("me.bechberger.jfr.cli.agent.AgentIO");
        String formatted = formatter.format(record);
        assertTrue(formatted.contains("WARNING"));
        assertTrue(formatted.contains("AgentIO"));
        assertTrue(formatted.contains("hello world"));
    }

    @Test
    public void testWriteOutputWritesToFile() throws Exception {
        useTempDir();
        var io = newAgentIO(true);

        io.writeOutput("hello");

        assertEquals("hello", Files.readString(io.getOutputFile()));
    }

    @Test
    public void testReadOutputReturnsWrittenOutputAndTracksBytes() throws Exception {
        useTempDir();
        var io = newAgentIO(true);
        io.writeOutput("hello");

        assertEquals("hello", io.readOutput());
        assertEquals("5", Files.readString(io.getReadBytesFile()));
        assertNull(io.readOutput());
    }

    @Test
    public void testReadOutputReturnsOnlyNewBytes() throws Exception {
        useTempDir();
        var io = newAgentIO(true);
        io.writeOutput("hello");
        assertEquals("hello", io.readOutput());

        io.writeOutput(" world");

        assertEquals(" world", io.readOutput());
    }

    @Test
    public void testCloseCreatesMarkerAndReadOutputCleansFiles() throws Exception {
        var io = newFileBackedAgentIOInTempDir();
        io.writeOutput("hello");
        io.close();

        assertTrue(Files.exists(io.getIsClosedFile()));
        assertEquals("hello", io.readOutput());
        assertFalse(Files.exists(io.getOutputFile()));
        assertFalse(Files.exists(io.getReadBytesFile()));
        assertFalse(Files.exists(io.getIsClosedFile()));
    }

    @Test
    public void testReadOutputAfterCloseDoesNotDeleteExitCode() {
        var io = newFileBackedAgentIOInTempDir();
        io.writeOutput("hello");
        io.writeExitCode(7);
        io.close();

        assertEquals("hello", io.readOutput());
        // AgentCommand reads output first and exit code afterwards.
        assertEquals(7, io.readExitCode());
    }

    @Test
    public void testPrintlnAppendsLineSeparator() throws Exception {
        useTempDir();
        var io = newAgentIO(true);

        io.println("hello");

        assertEquals("hello" + System.lineSeparator(), Files.readString(io.getOutputFile()));
    }

    @Test
    public void testPrintfFormatsArguments() throws Exception {
        useTempDir();
        var io = newAgentIO(true);

        io.printf("%s=%d", "value", 3);

        assertEquals("value=3", Files.readString(io.getOutputFile()));
    }

    @Test
    public void testCreatePrintStreamWritesOutput() throws Exception {
        useTempDir();
        var io = newAgentIO(true);

        try (var ps = io.createPrintStream()) {
            ps.print("abc");
            ps.flush();
        }

        assertEquals("abc", Files.readString(io.getOutputFile()));
    }

    @Test
    public void testWriteSevereErrorPrefixesMessage() throws Exception {
        useTempDir();
        var io = newAgentIO(true);

        io.writeSevereError("boom");

        assertTrue(Files.readString(io.getOutputFile()).contains("Severe Error: boom"));
    }

    @Test
    public void testWriteInfoOnlyLogsForAllLevel() throws Exception {
        useTempDir();
        var io = new AgentIO("test-agent", 12345L, true, AgentIO.LogLevel.ALL);
        io.writeInfo("info");
        assertTrue(Files.readString(io.getOutputFile()).contains("Info: info"));
    }

    @Test
    public void testWriteInfoDoesNothingForWarningLevel() throws Exception {
        useTempDir();
        var io = new AgentIO("test-agent", 12345L, true, AgentIO.LogLevel.WARNING);
        io.writeInfo("info");
        assertFalse(Files.exists(io.getOutputFile()));
    }

    @Test
    public void testWriteOutputUsesStderrWhenLogToFileDisabled() throws Exception {
        useTempDir();
        var io = newAgentIO(false);
        var previous = System.err;
        var output = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(output, true));
            io.writeOutput("hello");
        } finally {
            System.setErr(previous);
        }
        assertEquals("hello", output.toString());
    }

    @Test
    public void testGetAgentInstanceUsesCurrentPid() throws Exception {
        useTempDir();
        AgentIO.withLogToFile(true, () -> {});
        var io = AgentIO.getAgentInstance();
        assertTrue(io.getOutputFile().toString().contains("jfr-condenser-agent"));
    }

    @Test
    public void testGetAgentInstanceForPidUsesCustomPid() throws Exception {
        useTempDir();
        var io = AgentIO.getAgentInstance(77L);
        assertTrue(io.getOutputFile().toString().contains("-77-"));
    }

    @Test
    public void testWithLogToFileRestoresState() throws Exception {
        useTempDir();
        resetAgentIOStatics();

        AgentIO.withLogToFile(true, () -> AgentIO.getAgentInstance().writeOutput("a"));
        var outputFile = AgentIO.getAgentInstance().getOutputFile();
        assertTrue(Files.exists(outputFile));

        var previous = System.err;
        var output = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(output, true));
            AgentIO.getAgentInstance().writeOutput("b");
        } finally {
            System.setErr(previous);
        }
        assertEquals("b", output.toString());
    }

    @Test
    public void testExitCodeWriteAndRead() {
        useTempDir();
        var io = newAgentIO(true);
        io.writeExitCode(1);
        assertEquals(1, io.readExitCode());
        // second read returns 0 (file was deleted)
        assertEquals(0, io.readExitCode());
    }

    @Test
    public void testExitCodeDefaultsToZero() {
        useTempDir();
        var io = newAgentIO(true);
        assertEquals(0, io.readExitCode());
    }
}
