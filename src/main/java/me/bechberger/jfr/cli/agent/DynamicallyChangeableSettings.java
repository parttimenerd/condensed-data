package me.bechberger.jfr.cli.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import me.bechberger.jfr.cli.CLIUtils.ByteSizeConverter;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import picocli.CommandLine.Option;

/** All these settings can be changed during the execution of the agent, via commands */
public class DynamicallyChangeableSettings {

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(List<String> messages) {
            super(String.join("\n ", messages));
        }
    }

    @Option(
            names = "--max-duration",
            description = "The maximum duration of each individual recording, 0 for unlimited, when rotating files",
            defaultValue = "0s",
            converter = DurationConverter.class)
    public volatile Duration maxDuration;

    @Option(
            names = "--max-size",
            description =
                    "The maximum size of the recording file (or the individual files when"
                            + " rotating files)",
            defaultValue = "0B",
            converter = ByteSizeConverter.class)
    public volatile long maxSize;

    @Option(
            names = "--max-files",
            description = "The maximum number of files to keep, when rotating files",
            defaultValue = "10")
    public volatile int maxFiles;

    @Option(
            names = "--new-names",
            description = "When rotating files, use new names instead of reusing old ones",
            defaultValue = "false")
    public volatile boolean newNames;

    @Option(
            names = "--duration",
            description = "The duration of the whole recording, 0 for unlimited",
            defaultValue = "0s",
            converter = DurationConverter.class)
    public volatile Duration duration;

    /** Validate the current settings, throw {@link ValidationException} if invalid */
    public void validate(boolean rotating) {
        List<String> errors = new ArrayList<>();
        if (maxFiles < 0) {
            errors.add("Max files must be at least 0");
        }
        if (maxSize < 0) {
            errors.add("Max size must be at least 0");
        }
        if (maxDuration.toMillis() < 0) {
            errors.add("Max duration must be at least 0ms");
        }
        if (maxSize > 0 && maxSize < 1024) {
            errors.add("Max size must be at least 1kB or 0 (no limit)");
        }
        if (maxDuration.toMillis() > 0 && maxDuration.toMillis() < 1) {
            errors.add("Max duration must be at least 1ms or 0 (no limit)");
        }
        if (duration.toMillis() > 0 && duration.toMillis() < 1) {
            errors.add("Duration must be at least 1ms or 0 (no limit)");
        }
        if (!rotating) {
            if (maxDuration.toMillis() != 0) {
                errors.add("Max duration can only be set when rotating files");
            }
            if (maxSize != 0) {
                errors.add("Max size can only be set when rotating files");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}