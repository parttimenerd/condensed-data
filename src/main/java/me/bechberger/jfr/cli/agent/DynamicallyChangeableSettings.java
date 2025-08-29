package me.bechberger.jfr.cli.agent;

import java.time.Duration;
import me.bechberger.jfr.cli.CLIUtils.ByteSizeConverter;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import picocli.CommandLine.Option;

/** All these settings can be changed during the execution of the agent, via commands */
public class DynamicallyChangeableSettings {

    @Option(
            names = "--max-duration",
            description = "The maximum duration of the recording",
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

    @Option(names = "--new-names", description = "When rotating files, use new names instead of reusing old ones", defaultValue = "false")
    public volatile boolean newNames;
}