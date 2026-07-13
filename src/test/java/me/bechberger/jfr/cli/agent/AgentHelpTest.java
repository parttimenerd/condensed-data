package me.bechberger.jfr.cli.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.RunResult;
import org.junit.jupiter.api.Test;

/**
 * Fast, attach-free tests of the agent's launch-time help rendering, driven directly through
 * femtocli's {@link FemtoCli#runAgentCaptured}. Complements {@link AgentTest}, which exercises the
 * heavyweight real-JVM attach path.
 */
public class AgentHelpTest {

    @Test
    public void startHelpUsageLineIsClean() {
        RunResult res = FemtoCli.runAgentCaptured(new Agent(), "start,help");
        assertEquals(0, res.exitCode(), () -> "stderr: " + res.err());
        // The subcommand usage line must not leak the raw "-javaagent:..." jar name.
        assertThat(res.out()).startsWith("Usage: agent,start,");
        assertThat(res.out()).doesNotContain("-javaagent:condensed-agent.jar,start");
    }

    @Test
    public void startHelpShowsConfigOption() {
        RunResult res = FemtoCli.runAgentCaptured(new Agent(), "start,help");
        assertEquals(0, res.exitCode(), () -> "stderr: " + res.err());
        assertThat(res.out()).contains("config=<jfrConfig>");
    }

    @Test
    public void startHelpCondenserConfigListsValues() {
        RunResult res = FemtoCli.runAgentCaptured(new Agent(), "start,help");
        assertEquals(0, res.exitCode(), () -> "stderr: " + res.err());
        // Reworded description: no internal jargon, and it lists the valid presets.
        assertThat(res.out()).doesNotContain("generatorConfiguration");
        assertThat(res.out()).contains("reasonable-default");
    }

    @Test
    public void emptyArgsPrintHelp() {
        RunResult res = FemtoCli.runAgentCaptured(new Agent(), "");
        assertEquals(0, res.exitCode(), () -> "stderr: " + res.err());
        assertThat(res.out()).contains("Commands:");
        assertThat(res.out()).contains("start");
    }

    @Test
    public void unknownCommandPrintsErrorAndHelp() {
        RunResult res = FemtoCli.runAgentCaptured(new Agent(), "bogus");
        assertThat(res.err() + res.out()).contains("Unexpected parameter");
        assertThat(res.err() + res.out()).contains("Commands:");
    }
}
