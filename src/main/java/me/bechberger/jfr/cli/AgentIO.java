package me.bechberger.jfr.cli;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.*;
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

    class SingleLineFormatter extends Formatter {
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
    private Level level = Level.WARNING;

    public AgentIO(String agentIdentifier, long pid) {
        this.agentIdentifier = agentIdentifier;
        this.pid = pid;
    }

    public AgentIO(long pid) {
        this(AGENT_IDENTIFIER, pid);
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

    public void setLogLevel(Level level) {
        this.level = level;
    }

    /** Returns a logger with the given name that writes to the output file */
    public Logger getLogger(String name) {
        var logger = Logger.getLogger(name);
        // remove all previous handlers
        for (var handler : logger.getHandlers()) {
            logger.removeHandler(handler);
        }
        logger.setLevel(level);
        logger.setUseParentHandlers(false);
        var consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new SingleLineFormatter());
        logger.addHandler(consoleHandler);
        var fileHandler =
                new Handler() {
                    @Override
                    public void publish(java.util.logging.LogRecord record) {
                        writeOutput(consoleHandler.getFormatter().format(record));
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        logger.addHandler(fileHandler);
        return logger;
    }

    /** Write the output of the agent */
    public void writeOutput(String output) {
        try {
            Files.writeString(getOutputFile(), output, APPEND, CREATE);
            Files.deleteIfExists(getIsClosedFile());
        } catch (IOException e) {
            throw new RuntimeException("Could not write output", e);
        }
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
