package me.bechberger.jfr.cli;

import static me.bechberger.util.MemoryUtil.parseMemory;
import static me.bechberger.util.TimeUtil.parseDuration;

import java.time.Duration;
import java.util.Iterator;
import me.bechberger.jfr.Configuration;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.ITypeConverter;

public class CLIUtils {
    public static class ConfigurationIterable implements Iterable<String> {
        @NotNull
        @Override
        public Iterator<String> iterator() {
            return Configuration.configurations.values().stream()
                    .map(Configuration::name)
                    .sorted()
                    .iterator();
        }
    }

    public static class ConfigurationConverter implements ITypeConverter<Configuration> {
        public ConfigurationConverter() {}

        @Override
        public Configuration convert(String value) {
            if (!Configuration.configurations.containsKey(value)) {
                throw new IllegalArgumentException(
                        "Unknown generatorConfiguration: "
                                + value
                                + " use one of "
                                + Configuration.configurations.keySet());
            }
            return Configuration.configurations.get(value);
        }
    }

    public static class ByteSizeConverter implements ITypeConverter<Long> {
        @Override
        public Long convert(String value) {
            return parseMemory(value);
        }
    }

    public static class DurationConverter implements ITypeConverter<Duration> {
        @Override
        public Duration convert(String value) {
            return parseDuration(value);
        }
    }
}
