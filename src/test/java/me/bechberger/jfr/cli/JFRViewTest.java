package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.BasicJFRReader;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests the underlying JFRView works */
public class JFRViewTest {

    @Name("TestEvent")
    @Label("Label")
    @Description("Description")
    @StackTrace
    static class TestEvent extends Event {
        @Label("Label")
        int number;

        @Label("Memory")
        @DataAmount
        long memory = Runtime.getRuntime().freeMemory();

        @Label("String")
        String string = "Hello" + memory;

        TestEvent(int number) {
            this.number = number;
        }
    }

    private static ReadStruct testEventStruct;
    private static ReadStruct gcEventStruct;

    @BeforeAll
    static void initJFRStructs() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (CondensedOutputStream out =
                new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
            BasicJFRWriter basicJFRWriter = new BasicJFRWriter(out);
            AtomicBoolean hadGC = new AtomicBoolean(false);
            try (RecordingStream rs = new RecordingStream()) {
                rs.onEvent(
                        "TestEvent",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            rs.close();
                        });
                rs.enable("jdk.GarbageCollection");
                rs.onEvent(
                        "jdk.GarbageCollection",
                        event -> {
                            basicJFRWriter.processEvent(event);
                            hadGC.set(true);
                        });
                rs.startAsync();
                while (!hadGC.get()) {
                    var x = new byte[1024 * 1024 * 1024];
                    System.gc();
                }
                TestEvent testEvent = new TestEvent(1);
                testEvent.commit();
                rs.awaitTermination();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try (CondensedInputStream in = new CondensedInputStream(outputStream.toByteArray())) {
            var reader = new BasicJFRReader(in);
            var events = reader.readAll();
            for (var event : events) {
                switch (event.getType().getName()) {
                    case "TestEvent" -> testEventStruct = event;
                    case "jdk.GarbageCollection" -> gcEventStruct = event;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testHeader() {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        for (var line : view.header()) {
            if (line.length() > config.width()) {
                fail();
            }
        }
        assertEquals(
                """
                TestEvent

                Start Time Duration   Event Thread    Stack Trace                                       Number     Memory     String                                          \s
                ---------- ---------- --------------- ------------------------------------------------- ---------- ---------- -------------------------------------------------
                """
                        .strip(),
                String.join("\n", view.header()).strip());
    }

    @Test
    public void testNotEnoughSpace() {
        var config = new PrintConfig(10, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        assertEquals(
                """
                TestEvent

                Start Time Duration   Event Thread     Number     Memory    \s
                ---------- ---------- ---------------  ---------- ----------
                """
                        .strip(),
                String.join("\n", view.header()).strip());
    }

    @Test
    public void testRows() {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        var content = view.rows(testEventStruct);
        assertEquals(1, content.size());
        var line = content.get(0);
        System.err.println(line);
        assertTrue(line.contains(".bechberger.jfr.cli.JFRViewTest.initJFRStructs "));
        assertTrue(line.contains(" Hello"));
        assertTrue(line.contains("main"));
    }

    @Test
    public void testMultiLineCells() {
        var config = new PrintConfig(160, 2, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(testEventStruct.getType()), config);
        var content = view.rows(testEventStruct);
        System.err.println(content);
        assertEquals(2, content.size());
    }

    @Test
    public void testGCEvent() {
        var config = new PrintConfig(160, 1, TruncateMode.END);
        JFRView view = new JFRView(new JFRViewConfig(gcEventStruct.getType()), config);
        var content = view.rows(gcEventStruct);
        assertEquals(1, content.size());
        System.err.println(String.join("\n", view.header()));
        System.err.println(content.get(0));
        System.out.println(gcEventStruct);
    }
}