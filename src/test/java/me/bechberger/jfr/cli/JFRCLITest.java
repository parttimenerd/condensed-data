package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

public class JFRCLITest {

    @Test
    public void testSubCommandNames() {
        assertEquals(
                List.of("condense", "inflate", "benchmark", "agent", "summary", "view"),
                JFRCLI.subCommandNames());
    }

    @Test
    public void testBuilderIsCreated() {
        assertNotNull(JFRCLI.builder());
    }

    @Test
    public void testExecuteHelpReturnsSuccess() {
        assertEquals(0, JFRCLI.execute(new String[] {"--help"}));
    }

    @Test
    public void testExecuteWithoutArgsReturnsSuccess() {
        assertEquals(0, JFRCLI.execute(new String[0]));
    }

    @Test
    public void testExecuteInvalidOptionReturnsFailure() {
        int code = JFRCLI.execute(new String[] {"--definitely-unknown-option"});
        assertNotEquals(0, code);
    }
}
