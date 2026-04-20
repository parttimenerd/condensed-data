package me.bechberger.jfr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CLIUtilsTest {

    static Stream<Arguments> splitArgsArguments() {
        return Stream.of(
                Arguments.of("", List.of()),
                Arguments.of("1", List.of("1")),
                Arguments.of("1 2", List.of("1", "2")),
                Arguments.of("1  2", List.of("1", "2")),
                Arguments.of(" 1 2 ", List.of("1", "2")),
                Arguments.of("\"\"", List.of("")),
                Arguments.of("\"1 2\"", List.of("1 2")),
                Arguments.of("\"1 2\" 3", List.of("1 2", "3")),
                Arguments.of("\"1 2\" \"3 \\\"4\"", List.of("1 2", "3 \"4")));
    }

    @ParameterizedTest
    @MethodSource("splitArgsArguments")
    public void testSplitArgs(String argsString, List<String> splitArgs) {
        assertThat(CLIUtils.splitArgs(argsString)).isEqualTo(splitArgs);
    }

    @ParameterizedTest
    @MethodSource("splitArgsArguments")
    public void testSplitArgsRoundTrip(String argsString, List<String> splitArgs) {
        assertThat(CLIUtils.splitArgs(CLIUtils.combineArgs(CLIUtils.splitArgs(argsString))))
                .isEqualTo(splitArgs);
    }

    /**
     * Bug: splitArgs consumes backslashes that precede non-quote characters. The backslash sets
     * escapeNext=true, then the next char is appended without the backslash. This means
     * "hello\world" becomes ["helloworld"] — the backslash is silently dropped.
     *
     * <p>On Windows or in file paths containing backslashes, this corrupts the argument.
     */
    @Test
    public void testSplitArgsBackslashPreserved() {
        // A literal backslash followed by 'w' should preserve the backslash
        assertThat(CLIUtils.splitArgs("hello\\world")).isEqualTo(List.of("hello\\world"));
    }

    /**
     * Bug: combineArgs does not escape backslashes, so splitArgs(combineArgs(args)) drops them.
     * Round-trip: ["hello\world"] -> combineArgs -> "hello\world" -> splitArgs -> ["helloworld"]
     */
    @Test
    public void testCombineArgsSplitArgsBackslashRoundtrip() {
        var original = List.of("hello\\world");
        var combined = CLIUtils.combineArgs(original);
        var split = CLIUtils.splitArgs(combined);
        assertThat(split).isEqualTo(original);
    }

    /**
     * Bug: combineArgs does not escape backslashes, so a Windows-style path like "C:\Users\test" is
     * corrupted on roundtrip.
     */
    @Test
    public void testCombineArgsSplitArgsWindowsPath() {
        var original = List.of("C:\\Users\\test");
        var combined = CLIUtils.combineArgs(original);
        var split = CLIUtils.splitArgs(combined);
        assertThat(split).isEqualTo(original);
    }

    @Test
    public void testParseInstantSupportsIsoLocalDateTime() {
        Instant parsed =
                CLIUtils.InstantConverter.class
                        .cast(new CLIUtils.InstantConverter())
                        .convert("2025-12-05T12:12:21");
        assertThat(parsed).isNotNull();
    }
}
