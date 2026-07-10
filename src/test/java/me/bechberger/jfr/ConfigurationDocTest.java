package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Semantic checks for {@link Configuration#toFlagTable()} — the Markdown table embedded in {@code
 * docs/configurations.md}. Asserts the table's <em>content</em> matches reflection over the record
 * components, never a brittle byte-for-byte comparison against the doc file.
 */
public class ConfigurationDocTest {

    private static final List<Configuration> PRESETS =
            List.of(
                    Configuration.DEFAULT,
                    Configuration.LOSSLESS,
                    Configuration.REASONABLE_DEFAULT,
                    Configuration.REDUCED_DEFAULT);

    /** Every boolean record component becomes exactly one table row. */
    @Test
    public void testTableHasRowPerBooleanFlag() {
        String table = Configuration.toFlagTable();
        for (var c : Configuration.class.getRecordComponents()) {
            if (c.getType() == boolean.class) {
                assertTrue(
                        table.contains(c.getName()),
                        "flag table should mention boolean flag " + c.getName());
            }
        }
    }

    /** Every preset becomes exactly one column header. */
    @Test
    public void testTableHasColumnPerPreset() {
        String table = Configuration.toFlagTable();
        for (var preset : PRESETS) {
            assertTrue(
                    table.contains(preset.name()),
                    "flag table should have a column for preset " + preset.name());
        }
    }

    /** Each cell's rendered value matches the actual boolean read reflectively from the preset. */
    @Test
    public void testTableCellsMatchReflection() throws Exception {
        String[] lines = Configuration.toFlagTable().split("\n");
        // header line lists presets in a known order; find the column index of each preset
        String header = lines[0];
        String[] headerCells = splitRow(header);
        int[] presetCol = new int[PRESETS.size()];
        for (int p = 0; p < PRESETS.size(); p++) {
            presetCol[p] = indexOfCell(headerCells, PRESETS.get(p).name());
            assertTrue(presetCol[p] >= 0, "missing column for " + PRESETS.get(p).name());
        }
        // for every boolean flag row, verify each preset cell against reflection
        for (var c : Configuration.class.getRecordComponents()) {
            if (c.getType() != boolean.class) {
                continue;
            }
            String row = findRow(lines, c.getName());
            assertNotNull(row, "missing row for flag " + c.getName());
            String[] cells = splitRow(row);
            for (int p = 0; p < PRESETS.size(); p++) {
                boolean expected = readBoolean(PRESETS.get(p), c.getName());
                String cell = cells[presetCol[p]].trim();
                assertEquals(
                        expected ? "yes" : "no",
                        cell,
                        "cell mismatch for flag "
                                + c.getName()
                                + " / preset "
                                + PRESETS.get(p).name());
            }
        }
    }

    private static boolean readBoolean(Configuration config, String field)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        return (boolean) Configuration.class.getMethod(field).invoke(config);
    }

    private static String[] splitRow(String row) {
        // strip leading/trailing pipe, then split on pipe
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.split("\\|");
    }

    private static int indexOfCell(String[] cells, String value) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i].trim().equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private static String findRow(String[] lines, String flagName) {
        for (String line : lines) {
            String[] cells = splitRow(line);
            if (cells.length > 0 && cells[0].trim().equals(flagName)) {
                return line;
            }
        }
        return null;
    }
}
