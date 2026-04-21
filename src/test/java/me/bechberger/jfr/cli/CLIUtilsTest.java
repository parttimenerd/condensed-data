package me.bechberger.jfr.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import me.bechberger.jfr.Configuration;
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

    // --- editDistance tests ---

    @Test
    public void testEditDistanceIdentical() {
        assertThat(CLIUtils.editDistance("abc", "abc")).isEqualTo(0);
    }

    @Test
    public void testEditDistanceEmpty() {
        assertThat(CLIUtils.editDistance("", "")).isEqualTo(0);
        assertThat(CLIUtils.editDistance("abc", "")).isEqualTo(3);
        assertThat(CLIUtils.editDistance("", "abc")).isEqualTo(3);
    }

    @Test
    public void testEditDistanceInsertion() {
        assertThat(CLIUtils.editDistance("abc", "abcd")).isEqualTo(1);
    }

    @Test
    public void testEditDistanceDeletion() {
        assertThat(CLIUtils.editDistance("abcd", "abc")).isEqualTo(1);
    }

    @Test
    public void testEditDistanceSubstitution() {
        assertThat(CLIUtils.editDistance("abc", "aXc")).isEqualTo(1);
    }

    @Test
    public void testEditDistanceComplex() {
        assertThat(CLIUtils.editDistance("kitten", "sitting")).isEqualTo(3);
    }

    // --- splitArgs edge cases ---

    @Test
    public void testSplitArgsTrailingBackslash() {
        var result = CLIUtils.splitArgs("abc\\");
        assertThat(result).isNotEmpty();
        assertThat(result.get(result.size() - 1)).endsWith("\\");
    }

    @Test
    public void testSplitArgsTrailingBackslashAlone() {
        var result = CLIUtils.splitArgs("\\");
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("\\");
    }

    // --- userErrorMessage tests ---

    @Test
    public void testUserErrorMessageSimple() {
        var ex = new RuntimeException("test message");
        assertThat(CLIUtils.userErrorMessage(ex)).isEqualTo("test message");
    }

    @Test
    public void testUserErrorMessageNested() {
        var cause = new IllegalArgumentException("root cause");
        var wrapper = new RuntimeException("wrapper", cause);
        assertThat(CLIUtils.userErrorMessage(wrapper)).isEqualTo("root cause");
    }

    @Test
    public void testUserErrorMessageNullMessage() {
        var ex = new RuntimeException((String) null);
        assertThat(CLIUtils.userErrorMessage(ex)).contains("RuntimeException");
    }

    @Test
    public void testUserErrorMessageBlankMessage() {
        var ex = new RuntimeException("  ");
        assertThat(CLIUtils.userErrorMessage(ex)).contains("RuntimeException");
    }

    @Test
    public void testUserErrorMessageInvocationTargetException() throws Exception {
        var cause = new IllegalArgumentException("inner cause");
        var wrapper = new java.lang.reflect.InvocationTargetException(cause, "invocation wrapper");
        assertThat(CLIUtils.userErrorMessage(wrapper)).isEqualTo("inner cause");
    }

    // --- combineArgs tests ---

    @Test
    public void testCombineArgsWithSpaces() {
        assertThat(CLIUtils.combineArgs(List.of("hello world"))).isEqualTo("\"hello world\"");
    }

    @Test
    public void testCombineArgsEmpty() {
        assertThat(CLIUtils.combineArgs(List.of(""))).isEqualTo("\"\"");
    }

    @Test
    public void testCombineArgsMixed() {
        String combined = CLIUtils.combineArgs(List.of("--flag", "with space", "normal"));
        assertThat(combined).isEqualTo("--flag \"with space\" normal");
    }

    @Test
    public void testConfigurationConverterReturnsKnownConfiguration() {
        assertThat(new CLIUtils.ConfigurationConverter().convert("reasonable-default"))
                .isEqualTo(Configuration.REASONABLE_DEFAULT);
    }

    @Test
    public void testConfigurationConverterRejectsUnknownConfiguration() {
        assertThatThrownBy(() -> new CLIUtils.ConfigurationConverter().convert("unknown-config"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown generatorConfiguration");
    }

    @Test
    public void testByteSizeConverterParsesKilobytes() {
        assertThat(new CLIUtils.ByteSizeConverter().convert("1kB")).isEqualTo(1024L);
    }

    @Test
    public void testDurationConverterParsesMilliseconds() {
        assertThat(new CLIUtils.DurationConverter().convert("250ms").toMillis()).isEqualTo(250L);
    }

    @Test
    public void testDurationConverterParsesSeconds() {
        assertThat(new CLIUtils.DurationConverter().convert("2s").getSeconds()).isEqualTo(2L);
    }

    @Test
    public void testHasInflaterRelatedClassesDoesNotThrow() {
        assertThat(CLIUtils.hasInflaterRelatedClasses()).isIn(true, false);
    }
}
