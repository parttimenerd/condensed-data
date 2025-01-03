package me.bechberger.jfr.cli.commands;

import static me.bechberger.jfr.cli.EventTypesCompletionHelper.getApplicationsDir;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.assertj.core.util.Files;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

public class ListEventsForCompletionTest {

    @Test
    public void testWithoutFiles() throws Exception {
        new CommandExecuter("list-events-for-completion", "")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) ->
                                assertThat(result.output())
                                        .contains("jdk.GarbageCollection")
                                        .contains("jdk.CPULoad")
                                        .contains("jdk.ThreadPark")
                                        .startsWith("AnotherEvent"))
                .run();
    }

    @Test
    @Disabled
    public void testWithSampleFile() throws Exception {
        new CommandExecuter(
                        "list-events-for-completion", "T/", "--sort-by-count", "--timeout", "-1")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) ->
                                assertThat(result.output()).doesNotStartWith("AnotherEvent"))
                .run();
    }

    @Test
    @Disabled
    public void testWithSampleFileMixedWithOtherArgs() throws Exception {
        // create temp file
        var tmpFile = Files.newTemporaryFile();
        tmpFile.createNewFile();
        new CommandExecuter(
                        "list-events-for-completion",
                        "T/ " + tmpFile + " --help",
                        "--sort-by-count")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) ->
                                assertThat(result.output())
                                        .contains("jdk.GarbageCollection")
                                        .contains("jdk.ThreadPark")
                                        .doesNotStartWith("AnotherEvent"))
                .run();
    }

    @Test
    public void testWithOtherArgs() throws Exception {
        // create temp file
        var tmpFile = Files.newTemporaryFile();
        tmpFile.createNewFile();
        new CommandExecuter("list-events-for-completion", " --help")
                .withFiles(CommandTestUtil.getSampleCJFRFile())
                .checkNoError()
                .check(
                        (result, files) ->
                                assertThat(result.output())
                                        .contains("jdk.GarbageCollection")
                                        .contains("jdk.ThreadPark")
                                        .startsWith("AnotherEvent"))
                .run();
    }

    @Test
    @EnabledOnOs(value = {OS.MAC, OS.LINUX})
    public void testServer() throws Exception {
        var thread =
                new Thread(
                        () -> {
                            try {
                                new CommandExecuter(
                                                "list-events-for-completion",
                                                "--server",
                                                "--sort-by-count")
                                        .checkNoError()
                                        .run();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
        thread.start();
        // get port appDir.resolve("cjfr").resolve("port");
        var appDir = getApplicationsDir();
        assertThat(appDir).isNotNull();
        var portFile = appDir.resolve("cjfr").resolve("port");
        while (!java.nio.file.Files.exists(portFile)) {
            Thread.sleep(5);
        }
        var port = Integer.parseInt(java.nio.file.Files.readString(portFile));
        // call server at port
        var encodedUserDir =
                URLEncoder.encode(System.getProperty("user.dir"), StandardCharsets.UTF_8);
        var encodedCJFRFolder =
                URLEncoder.encode(
                        CommandTestUtil.getSampleCJFRFile().toString(), StandardCharsets.UTF_8);
        var request =
                HttpRequest.newBuilder()
                        .uri(
                                new URI(
                                        "http://localhost:"
                                                + port
                                                + "/list-events-for-completion?"
                                                + encodedUserDir
                                                + "&"
                                                + encodedCJFRFolder))
                        .GET()
                        .build();
        var client = HttpClient.newHttpClient();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.body())
                .contains("jdk.GarbageCollection")
                .doesNotStartWith("AnotherEvent")
                .contains("TestEvent");
        thread.interrupt();
        while (thread.isAlive()) {
            Thread.sleep(5);
        }
    }
}
