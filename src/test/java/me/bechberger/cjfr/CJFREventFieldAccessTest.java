package me.bechberger.cjfr;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import jdk.jfr.*;
import jdk.jfr.consumer.RecordingStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.jfr.BasicJFRWriter;
import me.bechberger.jfr.Configuration;
import org.junit.jupiter.api.Test;

/** Tests for {@link CJFREvent} field access methods. */
public class CJFREventFieldAccessTest {

    @Name("FieldTestEvent")
    @Label("Field Test Event")
    static class FieldTestEvent extends Event {
        @Label("Int Field")
        int intField;

        @Label("Long Field")
        long longField;

        @Label("String Field")
        String stringField;

        @Label("Bool Field")
        boolean boolField;

        FieldTestEvent(int i, long l, String s, boolean b) {
            this.intField = i;
            this.longField = l;
            this.stringField = s;
            this.boolField = b;
        }
    }

    static byte[] writeFieldTestEvent() throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var out = new CondensedOutputStream(bos, StartMessage.DEFAULT)) {
            var writer = new BasicJFRWriter(out, Configuration.LOSSLESS);
            try (var rs = new RecordingStream()) {
                rs.enable("FieldTestEvent");
                var done = new boolean[] {false};
                rs.onEvent(
                        "FieldTestEvent",
                        ev -> {
                            writer.processEvent(ev);
                            done[0] = true;
                            rs.close();
                        });
                rs.startAsync();
                new FieldTestEvent(42, 123456789L, "hello", true).commit();
                // Wait for the event to be processed
                long deadline = System.currentTimeMillis() + 5000;
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
            }
        }
        return bos.toByteArray();
    }

    private static CJFREvent readFirstFieldTestEvent() throws Exception {
        byte[] bytes = writeFieldTestEvent();
        var f = CJFRFile.open(new ByteArrayInputStream(bytes));
        CJFREvent e;
        while ((e = f.readEvent()) != null) {
            if (e.getEventType().getName().equals("FieldTestEvent")) return e;
        }
        throw new AssertionError("No FieldTestEvent found in stream");
    }

    @Test
    void getInt_returnsValue() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getInt("intField")).isEqualTo(42);
    }

    @Test
    void getLong_returnsValue() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getLong("longField")).isEqualTo(123456789L);
    }

    @Test
    void getString_returnsValue() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getString("stringField")).isEqualTo("hello");
    }

    @Test
    void getBoolean_returnsValue() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getBoolean("boolField")).isTrue();
    }

    @Test
    void getStartTime_returnsInstant() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getStartTime()).isNotNull().isInstanceOf(Instant.class);
    }

    @Test
    void hasField_trueForDeclaredField() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.hasField("intField")).isTrue();
        assertThat(e.hasField("stringField")).isTrue();
    }

    @Test
    void hasField_falseForUndeclaredField() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.hasField("nonExistentField")).isFalse();
    }

    @Test
    void getFieldNames_containsDeclaredFields() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getFieldNames()).contains("intField", "longField", "stringField", "boolField");
    }

    @Test
    void getValue_returnsRawValue() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        Object val = e.getValue("intField");
        assertThat(val).isNotNull();
        assertThat(((Number) val).intValue()).isEqualTo(42);
    }

    @Test
    void getRawStruct_notNull() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getRawStruct()).isNotNull();
    }

    @Test
    void getEventType_matchesEvent() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.getEventType().getName()).isEqualTo("FieldTestEvent");
        assertThat(e.getEventType().getLabel()).isEqualTo("Field Test Event");
    }

    @Test
    void toString_containsTypeName() throws Exception {
        CJFREvent e = readFirstFieldTestEvent();
        assertThat(e.toString()).contains("FieldTestEvent");
    }
}
