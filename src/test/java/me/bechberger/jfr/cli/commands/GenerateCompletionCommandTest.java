package me.bechberger.jfr.cli.commands;

import org.junit.jupiter.api.Test;

public class GenerateCompletionCommandTest {

    @Test
    public void testNoFileOptionIsMissingEnding() throws Exception {
        new CommandExecuter("generate-completion", "--fail-on-missing").checkNoError().run();
    }
}
