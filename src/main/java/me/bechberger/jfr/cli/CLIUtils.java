package me.bechberger.jfr.cli;

import com.palantir.humanreadabletypes.HumanReadableByteCount;
import com.palantir.humanreadabletypes.HumanReadableDuration;
import java.time.Duration;
import java.util.Iterator;
import me.bechberger.jfr.Configuration;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.ITypeConverter;

public class CLIUtils {
    static class ConfigurationIterable implements Iterable<String> {
        @NotNull
        @Override
        public Iterator<String> iterator() {
            return Configuration.configurations.values().stream()
                    .map(Configuration::name)
                    .sorted()
                    .iterator();
        }
    }

    static class ConfigurationConverter implements ITypeConverter<Configuration> {
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

    static class ByteSizeConverter implements ITypeConverter<Long> {
        @Override
        public Long convert(String value) {
            return HumanReadableByteCount.valueOf(value).toBytes();
        }
    }

    static class DurationConverter implements ITypeConverter<Duration> {
        @Override
        public Duration convert(String value) {
            return HumanReadableDuration.valueOf(value).toJavaDuration();
        }
    }
}