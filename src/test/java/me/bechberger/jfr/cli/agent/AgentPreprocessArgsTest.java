package me.bechberger.jfr.cli.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class AgentPreprocessArgsTest {

    @Test
    public void testPreprocessArgsWithNull() {
        var result = Agent.preprocessArgs(null);
        assertEquals("", result.args());
        assertFalse(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithBlank() {
        var result = Agent.preprocessArgs("   ");
        assertEquals("", result.args());
        assertFalse(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithOnlyLogToFile() {
        var result = Agent.preprocessArgs("--logToFile");
        assertEquals("", result.args());
        assertTrue(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithMixedArguments() {
        var result = Agent.preprocessArgs("start,test.cjfr,--logToFile,--verbose");
        assertEquals("start,test.cjfr,--verbose", result.args());
        assertTrue(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithoutLogToFile() {
        var result = Agent.preprocessArgs("start,test.cjfr,--verbose");
        assertEquals("start,test.cjfr,--verbose", result.args());
        assertFalse(result.logToFile());
    }

    @Test
    public void testPreprocessArgsWithWhitespaceAroundLogToFile() {
        var result = Agent.preprocessArgs("start, --logToFile ,test.cjfr");
        assertEquals("start,test.cjfr", result.args());
        assertTrue(result.logToFile());
    }

    @Test
    public void testPreprocessArgsPreservesEmptySegments() {
        var result = Agent.preprocessArgs("start,,--logToFile,");
        assertEquals("start,,", result.args());
        assertTrue(result.logToFile());
    }
}
