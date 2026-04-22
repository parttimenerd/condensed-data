package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

public class ReducedJFRTypesTest {

    /**
     * Bug: In ReducedTypeDefinition.getRemovedFields(), the second filter uses .filter(f ->
     * !ignoreJFRHandledFields) where the lambda parameter f is never used. This makes the filter
     * act as an all-or-nothing gate: - ignoreJFRHandledFields=true → ALL fields are filtered out
     * (empty list) - ignoreJFRHandledFields=false → ALL fields pass through
     *
     * <p>Currently the only caller uses ignoreJFRHandledFields=false, so the bug is dormant. But
     * the unused lambda parameter 'f' indicates the filter was likely intended to be per-field
     * (checking some property of each RemovedField), not a blanket gate.
     *
     * <p>This test verifies that when ignoreJFRHandledFields=true, the result should NOT
     * necessarily be completely empty — it should filter per-field based on whether each field is
     * JFR-handled.
     */
    @Test
    public void testGetRemovedFieldsWithIgnoreJFRHandledDoesNotReturnEmptyForAll() {
        // Create a configuration that would cause all StackFrame fields to be removed
        var config = Configuration.REDUCED_DEFAULT;

        // When ignoreJFRHandledFields=false, all 3 fields should be returned
        Set<String> removedFalse =
                ReducedJFRTypes.getRemovedFields("jdk.types.StackFrame", config, false);
        assertFalse(
                removedFalse.isEmpty(),
                "With ignoreJFRHandledFields=false, removed fields should be returned");
        assertTrue(removedFalse.contains("bytecodeIndex"));
        assertTrue(removedFalse.contains("lineNumber"));
        assertTrue(removedFalse.contains("type"));

        // When ignoreJFRHandledFields=true, the current buggy code returns an empty set
        // because .filter(f -> !ignoreJFRHandledFields) evaluates to .filter(f -> false)
        // which filters out EVERYTHING regardless of the field.
        Set<String> removedTrue =
                ReducedJFRTypes.getRemovedFields("jdk.types.StackFrame", config, true);

        // The lambda parameter f is unused — the filter is:
        //   .filter(f -> !true) = .filter(f -> false)
        // This always produces an empty list, which is likely NOT the intended behavior
        // for a per-field filter. The f parameter was meant to be used.
        assertEquals(
                removedFalse,
                removedTrue,
                "With ignoreJFRHandledFields=true, the filter should be per-field, not an"
                        + " all-or-nothing gate. Currently .filter(f -> !ignoreJFRHandledFields)"
                        + " doesn't use f at all, returning empty set when"
                        + " ignoreJFRHandledFields=true.");
    }
}
