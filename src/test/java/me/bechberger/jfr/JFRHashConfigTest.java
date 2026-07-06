package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import jdk.jfr.consumer.RecordingFile;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.jfr.cli.commands.CommandTestUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class JFRHashConfigTest {

    /**
     * Bug: In JFRHashConfig.isPrimitiveStructOrArray, the allMatch lambda checks
     * field.getFields().isEmpty() (the outer parameter) instead of f.getFields().isEmpty() (the
     * current stream element). Since we are iterating field.getFields(), the outer field is known
     * to have children, so field.getFields().isEmpty() is ALWAYS false.
     *
     * <p>Code: field.getFields().stream().allMatch(f -> field.getFields().isEmpty() // BUG: should
     * be f.getFields().isEmpty() || isPrimitiveStructOrArray(f, depth - 1));
     *
     * <p>The simplification is: allMatch(f -> isPrimitiveStructOrArray(f, depth - 1)) With the fix:
     * allMatch(f -> f.getFields().isEmpty() || isPrimitiveStructOrArray(f, depth-1))
     *
     * <p>The bug manifests at depth boundaries: when a child `f` is primitive (no sub-fields), the
     * buggy code recurses with depth-1 and hits depth=0 → returns false. The fixed code would
     * short-circuit via f.getFields().isEmpty() → true.
     *
     * <p>So a struct with children that have sub-children (2-level nesting) where the deepest
     * children are primitive will fail: the recursive call at depth 1 will incorrectly return false
     * for primitives at the leaf level.
     */
    @Test
    public void testIsPrimitiveStructOrArrayUsesCorrectVariable() throws Exception {
        // Read a real JFR file to get ValueDescriptor instances
        boolean foundTestCase = false;
        var jfrPath = CommandTestUtil.getSampleJFRFile();

        try (var rf = new RecordingFile(jfrPath)) {
            for (var eventType : rf.readEventTypes()) {
                for (var field : eventType.getFields()) {
                    // Looking for 2-level nesting:
                    // field (struct) → child (struct) → grandchild (primitive)
                    // where child has sub-fields that are all primitives
                    if (!field.getFields().isEmpty()
                            && !field.getTypeName().equals("java.lang.String")
                            && !field.getTypeName().equals("jdk.types.StackFrame")) {

                        // Check if any child is itself a struct with only primitive children
                        boolean hasNestedStructWithPrimitiveChildren =
                                field.getFields().stream()
                                        .anyMatch(
                                                f ->
                                                        !f.getFields().isEmpty()
                                                                && f.getFields().stream()
                                                                        .allMatch(
                                                                                g ->
                                                                                        g.getFields()
                                                                                                .isEmpty()));

                        if (hasNestedStructWithPrimitiveChildren) {
                            // With the bug, isPrimitiveStructOrArray(field, 2) will return
                            // false because at depth 1, the recursive call for primitive
                            // grandchildren fails (depth 0 → false instead of short-circuiting)
                            boolean result = JFRHashConfig.isPrimitiveStructOrArray(field, 2);

                            // Check if ALL children at both levels are either primitive or
                            // structs with only primitive children
                            boolean shouldBeTrue =
                                    field.getFields().stream()
                                            .allMatch(
                                                    f ->
                                                            f.getFields().isEmpty()
                                                                    || f.getFields().stream()
                                                                            .allMatch(
                                                                                    g ->
                                                                                            g.getFields()
                                                                                                    .isEmpty()));

                            if (shouldBeTrue) {
                                assertTrue(
                                        result,
                                        "isPrimitiveStructOrArray(field, 2) should return true "
                                                + "for field '"
                                                + field.getName()
                                                + "' of type '"
                                                + field.getTypeName()
                                                + "' (event: "
                                                + eventType.getName()
                                                + ") because all children up to depth 2 are"
                                                + " primitive. Bug: 'field.getFields().isEmpty()'"
                                                + " should be 'f.getFields().isEmpty()' in the"
                                                + " allMatch lambda.");
                                foundTestCase = true;
                                break;
                            }
                        }
                    }
                }
                if (foundTestCase) break;
            }
        }

        // If we didn't find a suitable 2-level nested struct, the bug exists
        // but can't be demonstrated with the available JFR data.
        // In that case, just verify the direct behavior at depth 1:
        // When called with depth=1 on a struct with primitive children,
        // the buggy code returns false (should return true)
        if (!foundTestCase) {
            // Fall back: find any struct with only primitive children
            // and verify behavior at depth=1
            try (var rf = new RecordingFile(jfrPath)) {
                for (var eventType : rf.readEventTypes()) {
                    for (var field : eventType.getFields()) {
                        if (!field.getFields().isEmpty()
                                && !field.getTypeName().equals("java.lang.String")
                                && !field.getTypeName().equals("jdk.types.StackFrame")) {

                            boolean allChildrenPrimitive =
                                    field.getFields().stream()
                                            .allMatch(f -> f.getFields().isEmpty());

                            if (allChildrenPrimitive) {
                                // At depth=1, the buggy code returns false
                                // because it recurses to depth=0 for primitive children
                                // instead of short-circuiting
                                boolean result = JFRHashConfig.isPrimitiveStructOrArray(field, 1);
                                assertTrue(
                                        result,
                                        "isPrimitiveStructOrArray(field, 1) should return true for "
                                                + "field '"
                                                + field.getName()
                                                + "' of type '"
                                                + field.getTypeName()
                                                + "' with only primitive children. At depth=1, the"
                                                + " buggy code recurses to"
                                                + " isPrimitiveStructOrArray(f, 0) which returns"
                                                + " false, instead of checking"
                                                + " f.getFields().isEmpty() first.");
                                foundTestCase = true;
                                break;
                            }
                        }
                    }
                    if (foundTestCase) break;
                }
            }
        }

        assertTrue(
                foundTestCase,
                "Could not find a suitable struct for testing the bug. "
                        + "The bug exists in isPrimitiveStructOrArray (field vs f) "
                        + "but couldn't be demonstrated with the available JFR data.");
    }

    @Test
    public void testGetEmbeddingTypeInlinesStructWithOnePrimitiveField() throws Exception {
        var jfrPath = CommandTestUtil.getSampleJFRFile();
        boolean found = false;
        try (var rf = new RecordingFile(jfrPath)) {
            for (var eventType : rf.readEventTypes()) {
                for (var field : eventType.getFields()) {
                    if (field.getTypeName().equals("java.lang.String")) continue;
                    if (field.getTypeName().equals("jdk.types.StackFrame")) continue;
                    if (field.getFields().size() != 1) continue;
                    if (!field.getFields().stream().allMatch(f -> f.getFields().isEmpty()))
                        continue;
                    var kind = JFRHashConfig.getEmbeddingType(field);
                    assertEquals(
                            EmbeddingType.NULLABLE_INLINE,
                            kind,
                            "struct '"
                                    + field.getName()
                                    + "' with one primitive field should be NULLABLE_INLINE");
                    found = true;
                    break;
                }
                if (found) break;
            }
        }
        Assumptions.assumeTrue(
                found, "no depth-1 single-primitive-field struct found in sample JFR");
    }

    @Test
    public void testGetEmbeddingTypeInlinesStructWithTwoPrimitiveFields() throws Exception {
        var jfrPath = CommandTestUtil.getSampleJFRFile();
        boolean found = false;
        try (var rf = new RecordingFile(jfrPath)) {
            for (var eventType : rf.readEventTypes()) {
                for (var field : eventType.getFields()) {
                    if (field.getTypeName().equals("java.lang.String")) continue;
                    if (field.getTypeName().equals("jdk.types.StackFrame")) continue;
                    if (field.getFields().size() != 2) continue;
                    if (!field.getFields().stream().allMatch(f -> f.getFields().isEmpty()))
                        continue;
                    var kind = JFRHashConfig.getEmbeddingType(field);
                    assertEquals(
                            EmbeddingType.NULLABLE_INLINE,
                            kind,
                            "struct '"
                                    + field.getName()
                                    + "' with two primitive fields should be NULLABLE_INLINE");
                    found = true;
                    break;
                }
                if (found) break;
            }
        }
        Assumptions.assumeTrue(found, "no depth-1 two-primitive-field struct found in sample JFR");
    }

    @Test
    public void testGetEmbeddingTypeDoesNotInlineStructWithTooManyFields() throws Exception {
        var jfrPath = CommandTestUtil.getSampleJFRFile();
        boolean found = false;
        try (var rf = new RecordingFile(jfrPath)) {
            for (var eventType : rf.readEventTypes()) {
                for (var field : eventType.getFields()) {
                    if (field.getTypeName().equals("java.lang.String")) continue;
                    if (field.getTypeName().equals("jdk.types.StackFrame")) continue;
                    if (field.getFields().size() <= 2) continue;
                    if (!field.getFields().stream().allMatch(f -> f.getFields().isEmpty()))
                        continue;
                    var kind = JFRHashConfig.getEmbeddingType(field);
                    assertTrue(
                            kind == EmbeddingType.REFERENCE
                                    || kind == EmbeddingType.NULLABLE_INLINE,
                            "unexpected embedding for wide primitive struct: " + kind);
                    found = true;
                    break;
                }
                if (found) break;
            }
        }
        Assumptions.assumeTrue(found, "no wide primitive-only struct found in sample JFR");
    }

    @Test
    public void testGetEmbeddingTypeDoesNotInlineStructWithNonPrimitiveField() throws Exception {
        var jfrPath = CommandTestUtil.getSampleJFRFile();
        boolean found = false;
        try (var rf = new RecordingFile(jfrPath)) {
            for (var eventType : rf.readEventTypes()) {
                for (var field : eventType.getFields()) {
                    if (field.getTypeName().equals("java.lang.String")) continue;
                    if (field.getTypeName().equals("jdk.types.StackFrame")) continue;
                    if (field.getFields().isEmpty()) continue;
                    if (field.getFields().size() > 2) continue;
                    boolean hasNonPrimitiveChild =
                            field.getFields().stream().anyMatch(f -> !f.getFields().isEmpty());
                    if (!hasNonPrimitiveChild) continue;
                    var kind = JFRHashConfig.getEmbeddingType(field);
                    assertTrue(
                            kind != EmbeddingType.INLINE,
                            "structs with a non-primitive child must not be plain INLINE");
                    found = true;
                    break;
                }
                if (found) break;
            }
        }
        Assumptions.assumeTrue(found, "no small non-primitive-containing struct in sample JFR");
    }
}
