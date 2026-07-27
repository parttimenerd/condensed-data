package me.bechberger.jfr.cli.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import me.bechberger.jfr.cli.query.ViewQuery.Shape;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ViewRenderer} label resolution. */
class ViewRendererTest {

    /**
     * When an aggregate's field has no resolvable metadata label — typically because the field is
     * absent from the recording's event type (e.g. {@code LAST(dynamicCompilerThreadCount)} on a
     * JDK that lacks it) — the row label falls back to the raw field path rather than an empty
     * string, so the row stays identifiable ("dynamicCompilerThreadCount: N/A", not ": N/A").
     */
    @Test
    void aggregateWithUnresolvableFieldFallsBackToFieldPath() {
        ViewQuery query =
                Parser.parse(Shape.FORM, "SELECT LAST(dynamicCompilerThreadCount) FROM Foo");
        // Empty event map: no StructType is available, so metadata label resolution returns null
        // and the fallback path is exercised.
        List<String> labels = ViewRenderer.resolveLabels(query, Map.of());
        assertEquals(List.of("dynamicCompilerThreadCount"), labels);
    }
}
