package me.bechberger.jfr.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import me.bechberger.jfr.cli.JFRCLI;
import org.junit.jupiter.api.Test;

public class GenerateCompletionCommandTest {

    /** Fail if a file option doesn't have a specific ending */
    @Test
    public void testNoFileOptionIsMissingEnding() throws Exception {
        new CommandExecuter("generate-completion", "--fail-on-missing").checkNoError().run();
    }

    @Test
    public void testGenerateCompletion() throws Exception {
        new CommandExecuter("generate-completion")
                .check(
                        (result, map) -> {
                            var out = result.output();
                            for (String command : JFRCLI.getVisibleCommands().keySet()) {
                                assertThat(out).contains(command);
                            }
                        })
                .run();
    }
}
