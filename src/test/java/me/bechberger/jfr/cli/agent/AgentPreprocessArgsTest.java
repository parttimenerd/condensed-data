package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class AgentPreprocessArgsTest {

    @Test
    public void testPreprocessArgsWithNull() {
        var result = Agent.preprocessArgs(null);
        assertArrayEquals(new String[0], result.argv());
        assertFalse(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithBlank() {
        var result = Agent.preprocessArgs("   ");
        assertArrayEquals(new String[0], result.argv());
        assertFalse(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithOnlyLogToFile() {
        var result = Agent.preprocessArgs("--logToFile");
        assertArrayEquals(new String[0], result.argv());
        assertTrue(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithMixedArguments() {
        var result = Agent.preprocessArgs("start,test.cjfr,--logToFile,--verbose");
        assertArrayEquals(new String[]{"start", "test.cjfr", "--verbose"}, result.argv());
        assertTrue(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithoutLogToFile() {
        var result = Agent.preprocessArgs("start,test.cjfr,--verbose");
        assertArrayEquals(new String[]{"start", "test.cjfr", "--verbose"}, result.argv());
        assertFalse(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithWhitespaceAroundTokens() {
        // whitespace around tokens is trimmed by the proper parser
        var result = Agent.preprocessArgs("start , --logToFile , test.cjfr");
        assertArrayEquals(new String[]{"start", "test.cjfr"}, result.argv());
        assertTrue(result.logToFile());
    }
}
