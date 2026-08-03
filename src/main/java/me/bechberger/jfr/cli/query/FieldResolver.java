package me.bechberger.jfr.cli.query;

import java.util.List;
import me.bechberger.condensed.ReadStruct;

/**
 * Resolves a dotted field path (e.g. {@code duration}, {@code stackTrace.topFrame.method.name})
 * against a {@link ReadStruct}, traversing nested structs. Returns the raw value (unformatted) or
 * {@code null} if any segment is missing.
 *
 * <p>In a join query the leading segment of a path may be a FROM alias rather than a field; the
 * {@link QueryEvaluator} strips the alias before calling here, so this class only ever sees real
 * field paths relative to a single event struct.
 *
 * <p>{@code eventType} is a synthetic pseudo-field: every event exposes {@code eventType.label}
 * (the type's human label, e.g. "Young Garbage Collection") and {@code eventType.name} (the type
 * name, e.g. "jdk.YoungGarbageCollection"). These are not stored fields; they come from the event's
 * own type metadata, mirroring {@code jfr view}'s {@code EventType} accessor.
 */
final class FieldResolver {

    private FieldResolver() {}

    /** Resolve {@code parts} against {@code event}; traverses nested structs part-by-part. */
    static Object resolve(ReadStruct event, List<String> parts) {
        Object current = event;
        for (int i = 0; i < parts.size(); i++) {
            if (!(current instanceof ReadStruct s)) {
                return null;
            }
            String part = parts.get(i);
            if ("eventType".equals(part)) {
                return resolveEventType(s, parts.subList(i + 1, parts.size()));
            }
            // Synthetic StackTrace.topFrame: jfr exposes the first frame of the trace as topFrame,
            // even though the stored struct only has {truncated, frames[]}. Resolve it to
            // frames[0].
            if ("topFrame".equals(part) && !s.hasField("topFrame") && s.hasField("frames")) {
                Object frames = s.get("frames");
                if (frames instanceof List<?> list && !list.isEmpty()) {
                    current = list.get(0);
                    continue;
                }
                return null;
            }
            // Synthetic StackTrace.topNotInitFrame: jfr's accessor for the topmost frame whose
            // method
            // is NOT a constructor/initializer (<init>/<clinit>). Used by exception-by-site, where
            // the
            // top frames are the Throwable subclass constructor chain (Error.<init>, …); the "site"
            // is
            // the first real caller below them (e.g. MethodHandleNatives.resolve). Falls back to
            // the
            // first frame if every frame is an init frame.
            if ("topNotInitFrame".equals(part)
                    && !s.hasField("topNotInitFrame")
                    && s.hasField("frames")) {
                Object frames = s.get("frames");
                if (frames instanceof List<?> list && !list.isEmpty()) {
                    current = firstNotInitFrame(list);
                    continue;
                }
                return null;
            }
            // Synthetic StackTrace.topApplicationFrame: jfr's accessor for the topmost frame that
            // is
            // application (non-JDK/system) code. Used by memory-leaks-by-site to attribute a leaked
            // allocation to the first frame the user actually wrote, skipping the JDK plumbing
            // above
            // it (ClassLoader.defineClass, HashMap.put, Arrays.copyOf, …). Unlike topNotInitFrame,
            // this returns null (rendered "N/A") when *every* frame is JDK/system code — matching
            // jfr, which groups all-JDK traces under a single N/A row.
            if ("topApplicationFrame".equals(part)
                    && !s.hasField("topApplicationFrame")
                    && s.hasField("frames")) {
                Object frames = s.get("frames");
                if (frames instanceof List<?> list && !list.isEmpty()) {
                    Object frame = firstApplicationFrame(list);
                    if (frame == null) {
                        return null;
                    }
                    current = frame;
                    continue;
                }
                return null;
            }
            // Synthetic state field for ExecutionSample/NativeMethodSample: the condenser drops
            // this field because it is always STATE_RUNNABLE (only runnable threads are sampled).
            // Reinstate it here so `cjfr view jdk.ExecutionSample` shows the Thread State column.
            if ("state".equals(part)
                    && !s.hasField("state")
                    && isExecutionSampleType(s.getType().getName())) {
                current = "STATE_RUNNABLE";
                continue;
            }
            if (!s.hasField(part)) {
                return null;
            }
            current = s.get(part);
        }
        return current;
    }

    /**
     * The first frame whose method name is not {@code <init>} or {@code <clinit>}, or the first
     * frame if all are initializers. A frame is a struct with a {@code method} struct that has a
     * {@code name}.
     */
    private static Object firstNotInitFrame(List<?> frames) {
        for (Object f : frames) {
            if (!(f instanceof ReadStruct frame)) continue;
            ReadStruct method = frame.hasField("method") ? frame.getStruct("method") : null;
            String name =
                    method != null && method.hasField("name") ? asString(method.get("name")) : null;
            if (name != null && !"<init>".equals(name) && !"<clinit>".equals(name)) {
                return frame;
            }
        }
        return frames.get(0);
    }

    /**
     * The first frame whose method's declaring class is application (non-JDK/system) code, or
     * {@code null} if every frame is JDK/system code. A frame is a struct with a {@code method}
     * struct that has a {@code type} (the declaring {@code RecordedClass}) with a {@code name}.
     * "System" is approximated by the well-known runtime package prefixes ({@code java.}, {@code
     * javax.}, {@code jdk.}, {@code sun.}, {@code com.sun.}); everything else — including
     * third-party libraries like {@code org.openjdk.jmc.*} or {@code org.tukaani.*} — counts as
     * application code, matching how jfr attributes a leak to the first frame outside the runtime.
     */
    private static Object firstApplicationFrame(List<?> frames) {
        for (Object f : frames) {
            if (!(f instanceof ReadStruct frame)) continue;
            ReadStruct method = frame.hasField("method") ? frame.getStruct("method") : null;
            ReadStruct type =
                    method != null && method.hasField("type") ? method.getStruct("type") : null;
            String className =
                    type != null && type.hasField("name") ? asString(type.get("name")) : null;
            if (className != null && !isSystemClass(className)) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Whether the event type name is one of the sampled-thread types that always have
     * STATE_RUNNABLE.
     */
    private static boolean isExecutionSampleType(String typeName) {
        return "jdk.ExecutionSample".equals(typeName) || "jdk.NativeMethodSample".equals(typeName);
    }

    /** Whether a fully-qualified class name belongs to a JDK/runtime package. */
    static boolean isSystemClass(String className) {
        String fqcn = className.replace('/', '.');
        return fqcn.startsWith("java.")
                || fqcn.startsWith("javax.")
                || fqcn.startsWith("jdk.")
                || fqcn.startsWith("sun.")
                || fqcn.startsWith("com.sun.");
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * Resolve the synthetic {@code eventType} pseudo-field's trailing accessor. {@code .label} →
     * the type's human label; {@code .name} → the fully-qualified type name; bare {@code eventType}
     * → the label (jfr renders the type by its label).
     */
    private static Object resolveEventType(ReadStruct event, List<String> rest) {
        var type = event.getType();
        String name = type.getName();
        String accessor = rest.isEmpty() ? "label" : rest.get(0);
        return switch (accessor) {
            case "name" -> name;
            case "label" -> typeLabel(type.getDescription(), name);
            default -> null;
        };
    }

    /**
     * Returns true if the condensed type description carries a {@code @jdk.jfr.Experimental}
     * annotation (stored as a JSON string in the third element of the description array).
     */
    static boolean typeIsExperimental(String description) {
        return description != null && description.contains("jdk.jfr.Experimental");
    }

    /**
     * Extract the human label from a condensed type description. The description is a JSON array
     * {@code ["label", "description"]}; we pull the first element. Falls back to the type name if
     * the description is absent or not in that shape.
     */
    static String typeLabel(String description, String fallbackName) {
        if (description == null || description.isEmpty()) {
            return fallbackName;
        }
        // Description is a compact JSON array like ["Young Garbage Collection","..."]. Extract the
        // first quoted string without a full JSON parse (the shape is fixed and internal).
        int firstQuote = description.indexOf('"');
        if (firstQuote < 0) {
            return fallbackName;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < description.length(); i++) {
            char c = description.charAt(i);
            if (c == '\\' && i + 1 < description.length()) {
                sb.append(description.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        if (sb.length() == 0) return fallbackName;
        String label = sb.toString();
        // Append "(Experimental)" if the @Experimental annotation is present in the description.
        if (description.contains("jdk.jfr.Experimental")) {
            label += " (Experimental)";
        }
        return label;
    }
}
