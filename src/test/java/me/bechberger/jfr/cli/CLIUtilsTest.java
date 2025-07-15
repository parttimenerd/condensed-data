package me.bechberger.jfr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
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
}
