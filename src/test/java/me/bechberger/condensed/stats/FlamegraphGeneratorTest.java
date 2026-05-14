package me.bechberger.condensed.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

public class FlamegraphGeneratorTest {

    private EventWriteTree buildSimpleTree() {
        var root = EventWriteTree.createRoot();
        var child1 = root.getOrCreateChild(new WriteCause.SingleWriteCause("TypeA"));
        child1.record(100);
        child1.record(50);
        var child2 = root.getOrCreateChild(new WriteCause.SingleWriteCause("TypeB"));
        child2.record(200);
        return root;
    }

    /**
     * Bug: writeTable() header has 6 column titles but data rows format 7 values. The "Overall
     * Percentage of Total" column appears in data but has no header.
     */
    @Test
    public void testWriteTableHeaderMatchesDataColumnCount() {
        var root = buildSimpleTree();
        var gen = new FlamegraphGenerator(root);

        var baos = new ByteArrayOutputStream();
        gen.writeTable(new PrintStream(baos));
        var output = baos.toString();
        var lines = output.split("\n");
        assertTrue(lines.length >= 3, "Should have header, separator, and at least one data row");

        String headerLine = lines[0];
        String dataLine = lines[2]; // first data row after separator

        // Count pipe-separated columns
        int headerCols = headerLine.split("\\|").length;
        int dataCols = dataLine.split("\\|").length;

        assertEquals(
                headerCols,
                dataCols,
                "Header column count ("
                        + headerCols
                        + ") should match data column count ("
                        + dataCols
                        + ")\nHeader: "
                        + headerLine
                        + "\nData:   "
                        + dataLine);
    }

    /**
     * Bug: writeJSON() (called internally by writeHTML) interpolates getCauseName() directly into
     * JSON string via String.format("%s") without escaping quotes or backslashes. If a cause name
     * contains '"', the output is invalid JSON.
     */
    @Test
    public void testWriteHTMLEscapesSpecialCharactersInJSON() {
        var root = EventWriteTree.createRoot();
        var child = root.getOrCreateChild(new WriteCause.SingleWriteCause("Type\"With\\Quotes"));
        child.record(42);

        var gen = new FlamegraphGenerator(root);
        var baos = new ByteArrayOutputStream();
        gen.writeHTML(new PrintStream(baos));
        var html = baos.toString();

        // The embedded JSON in the HTML should have properly escaped names
        // Currently: "name": "Type"With\Quotes" — invalid JSON
        // Expected: "name": "Type\"With\\Quotes" — properly escaped
        assertFalse(
                html.contains("\"Type\"With"),
                "Unescaped quotes in JSON name should not appear in HTML output");
    }

    // ========== Passing tests ==========

    @Test
    public void testWriteTableProducesOutput() {
        var root = buildSimpleTree();
        var gen = new FlamegraphGenerator(root);
        var baos = new ByteArrayOutputStream();
        gen.writeTable(new PrintStream(baos));
        String output = baos.toString();
        assertTrue(output.contains("Type Name"), "Should contain header");
        assertTrue(output.contains("TypeA"), "Should contain TypeA");
        assertTrue(output.contains("TypeB"), "Should contain TypeB");
    }

    @Test
    public void testToJSONContainsEntries() {
        var root = buildSimpleTree();
        var gen = new FlamegraphGenerator(root);
        var jsonList = gen.toJSON();
        assertFalse(jsonList.isEmpty(), "JSON output should not be empty");
    }

    @Test
    public void testWriteHTMLContainsD3() {
        var root = buildSimpleTree();
        var gen = new FlamegraphGenerator(root);
        var baos = new ByteArrayOutputStream();
        gen.writeHTML(new PrintStream(baos));
        String html = baos.toString();
        assertTrue(html.contains("d3"), "HTML should reference d3.js");
        assertTrue(html.contains("flamegraph"), "HTML should reference flamegraph");
    }

    @Test
    public void testFilteredTreeKeepsOnlyMatchingChildren() {
        var root = buildSimpleTree(); // has TypeA (150 bytes) and TypeB (200 bytes)
        var filtered = root.filtered(java.util.Set.of("TypeA"));

        var gen = new FlamegraphGenerator(filtered);
        var baos = new ByteArrayOutputStream();
        gen.writeTable(new PrintStream(baos));
        String output = baos.toString();
        assertTrue(output.contains("TypeA"), "Filtered tree should contain TypeA");
        assertFalse(output.contains("TypeB"), "Filtered tree should NOT contain TypeB");
    }

    @Test
    public void testFilteredTreeEmptyFilter() {
        var root = buildSimpleTree();
        var filtered = root.filtered(java.util.Set.of("NonExistent"));

        var gen = new FlamegraphGenerator(filtered);
        var baos = new ByteArrayOutputStream();
        gen.writeTable(new PrintStream(baos));
        String output = baos.toString();
        assertFalse(output.contains("TypeA"), "Filtered tree should not contain TypeA");
        assertFalse(output.contains("TypeB"), "Filtered tree should not contain TypeB");
    }
}
