package me.bechberger.jfr.cli.agent;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Communicating between the agent and the CLI
 *
 * <p>Problem when the agent is attached, it prints to the same output stream as the Java
 * application it's attached to, not the CLI
 *
 * <p>Idea: Create an output file that the agent writes to and the CLI reads from and a file that
 * contains the number of bytes already read.
 */
public class AgentIO {

    static class SingleLineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            var classParts = record.getSourceClassName().split("[$.]");
            return String.format(
                    "%1$tF %1$tT %2$s %3$s: %4$s%n",
                    record.getMillis(), // Timestamp
                    record.getLevel(), // Log level
                    classParts[classParts.length - 1], // Class name
                    formatMessage(record)); // Log message
        }
    }

    private static final String AGENT_IDENTIFIER = "jfr-condenser-agent";
    private static final Logger LOGGER = Logger.getLogger(AgentIO.class.getName());
    private final String agentIdentifier;
    private final long pid;
    private boolean logToFile;
    private final LogLevel logLevel;

    AgentIO(String agentIdentifier, long pid, boolean logToFile, LogLevel logLevel) {
        this.agentIdentifier = agentIdentifier;
        this.pid = pid;
        this.logToFile = logToFile;
        this.logLevel = logLevel;
    }

    AgentIO(long pid, boolean logToFile, LogLevel logLevel) {
        this(AGENT_IDENTIFIER, pid, logToFile, logLevel);
    }

    public enum LogLevel {
        SEVERE,
        WARNING,
        ALL
    }

    private static boolean defaultLogToFile = false;
    private static LogLevel defaultLogLevel = LogLevel.WARNING;
    private static AgentIO instance = null;

    private static void setLogToFile(boolean logToFile) {
        defaultLogToFile = logToFile;
        if (instance != null) {
            instance.logToFile = logToFile;
        }
    }

    public static void withLogToFile(boolean logToFile, Runnable r) {
        boolean oldDefaultLogToFile = defaultLogToFile;
        boolean oldInstanceLogToFile = instance != null && instance.logToFile;
        setLogToFile(logToFile);
        r.run();
        setLogToFile(oldDefaultLogToFile);
        if (instance != null) {
            instance.logToFile = oldInstanceLogToFile;
        }
    }

    public void setLogLevel(LogLevel level) {
        defaultLogLevel = level;
    }

    /** Get instance, assuming that this is called in the agent */
    public static AgentIO getAgentInstance() {
        if (instance == null) {
            instance =
                    new AgentIO(
                            AGENT_IDENTIFIER,
                            ProcessHandle.current().pid(),
                            defaultLogToFile,
                            defaultLogLevel);
        }
        return instance;
    }

    /** Create an instance of the agent IO for an agent attached to a JVM with the given pid */
    public static AgentIO getAgentInstance(long pid) {
        return new AgentIO(AGENT_IDENTIFIER, pid, defaultLogToFile, defaultLogLevel);
    }

    private Path getTmpFileName(String name) {
        return Path.of(
                System.getProperty("java.io.tmpdir"), agentIdentifier + "-" + pid + "-" + name);
    }

    public Path getOutputFile() {
        return getTmpFileName("out.log");
    }

    public Path getReadBytesFile() {
        return getTmpFileName("read.bytes");
    }

    public Path getIsClosedFile() {
        return getTmpFileName("is.closed");
    }

    public void writeSevereError(String message) {
        println("Severe Error: " + message);
    }

    public void writeInfo(String message) {
        if (logLevel == LogLevel.ALL) {
            println("Info: " + message);
        }
    }

    /** Write the output of the agent */
    public void writeOutput(String output) {
        if (!logToFile) {
            System.out.print(output);
            return;
        }
        try {
            Files.writeString(getOutputFile(), output, APPEND, CREATE);
            Files.deleteIfExists(getIsClosedFile());
        } catch (IOException e) {
            throw new RuntimeException("Could not write output", e);
        }
    }

    public void println(String output) {
        writeOutput(output + System.lineSeparator());
    }

    public void printf(String format, Object... args) {
        writeOutput(String.format(format, args));
    }

    public PrintStream createPrintStream() {
        return new PrintStream(
                new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        writeOutput(String.valueOf((char) b));
                    }

                    @Override
                    public void write(byte @NotNull [] b, int off, int len) throws IOException {
                        writeOutput(new String(b, off, len));
                    }
                });
    }

    /** Read the available output from the agent and advance the byte counter in the file */
    public @Nullable String readOutput() {
        try {
            // get bytes read
            long bytesRead =
                    Files.exists(getReadBytesFile())
                            ? Long.parseLong(Files.readString(getReadBytesFile()))
                            : 0;
            if (Files.exists(getOutputFile())) {
                String output;
                try (var input = Files.newInputStream(getOutputFile())) {
                    long skipped = input.skip(bytesRead);
                    if (skipped != bytesRead) {
                        LOGGER.warning(
                                "Could not skip "
                                        + bytesRead
                                        + " bytes, only "
                                        + skipped
                                        + " bytes skipped");
                    }
                    if (input.available() != 0) {
                        output = new String(input.readAllBytes());
                    } else {
                        output = null;
                    }
                }
                // write bytes read
                if (output != null) {
                    Files.writeString(
                            getReadBytesFile(),
                            String.valueOf(output.getBytes().length + bytesRead),
                            CREATE);
                }
                if (Files.exists(getIsClosedFile())) {
                    Files.deleteIfExists(getIsClosedFile());
                    Files.deleteIfExists(getOutputFile());
                    Files.deleteIfExists(getReadBytesFile());
                }
                return output;
            } else {
                return null;
            }
        } catch (IOException e) {
            LOGGER.severe("Could not read output file " + getOutputFile());
            return null;
        }
    }

    /** Close the output file */
    public void close() {
        try {
            Files.writeString(getIsClosedFile(), "", CREATE);
        } catch (IOException e) {
            throw new RuntimeException("Could not write is.closed file", e);
        }
    }
}
