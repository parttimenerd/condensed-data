package me.bechberger.jfr;

import java.util.Objects;
import java.util.Set;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.*;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.Universe.HashAndEqualsWrapper;
import me.bechberger.jfr.UnsafeRecordedObjectAccessor.FieldAccessor;
import me.bechberger.jfr.UnsafeRecordedObjectAccessor.IntFieldAccessor;

/**
 * No JFR object implements {@link Object::hashCode} and {@link Object::equals}, so we need to wrap
 * the ones that we put into caches so that the equality and hash code are not identity based.
 */
public class JFRHashConfig extends HashAndEqualsConfig {

    record ClassWrapper(RecordedClass value) implements HashAndEqualsWrapper<RecordedClass> {

        @Override
        public int hashCode() {
            return Long.hashCode(value.getId());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ClassWrapper
                    && ((ClassWrapper) obj).value.getId() == value.getId();
        }
    }

    record ClassLoaderWrapper(RecordedClassLoader value)
            implements HashAndEqualsWrapper<RecordedClassLoader> {

        @Override
        public int hashCode() {
            return Objects.hashCode(value.getName());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ClassLoaderWrapper
                    && (((ClassLoaderWrapper) obj).value == value
                            || Objects.equals(
                                    ((ClassLoaderWrapper) obj).value.getName(), value.getName()));
        }
    }

    record MethodWrapper(RecordedMethod value) implements HashAndEqualsWrapper<RecordedMethod> {

        @Override
        public int hashCode() {
            return Objects.hash(value.getType().getId(), value.getName(), value.getDescriptor());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MethodWrapper) {
                RecordedMethod other = ((MethodWrapper) obj).value;
                return other == value
                        || (value.getType().getId() == other.getType().getId()
                                && Objects.equals(value.getName(), other.getName())
                                && Objects.equals(value.getDescriptor(), other.getDescriptor()));
            }
            return false;
        }
    }

    /** Currently not used */
    static class StackFrameWrapper implements HashAndEqualsWrapper<RecordedFrame> {

        private final RecordedFrame value;
        private final int hashCode;
        // Snapshot comparison-relevant data at construction time to avoid
        // stale reads from JFR's reusable internal buffers
        private final int lineNumber;
        private final int bytecodeIndex;
        private final long classId;
        private final String methodName;
        private final String methodDescriptor;
        private final String frameType;

        private static final IntFieldAccessor LINE_NUMBER =
                UnsafeRecordedObjectAccessor.intField("lineNumber", -1);
        private static final IntFieldAccessor BYTECODE_INDEX =
                UnsafeRecordedObjectAccessor.intField("bytecodeIndex", -1);
        private static final FieldAccessor<RecordedMethod> METHOD =
                UnsafeRecordedObjectAccessor.field("method", null);
        private static final FieldAccessor<String> FRAME_TYPE =
                UnsafeRecordedObjectAccessor.field("type", null);

        public StackFrameWrapper(RecordedFrame value) {
            this.value = value;
            this.lineNumber = LINE_NUMBER.get(value);
            this.bytecodeIndex = BYTECODE_INDEX.get(value);
            var method = METHOD.get(value);
            this.classId = method.getType().getId();
            this.methodName = method.getName();
            this.methodDescriptor = method.getDescriptor();
            this.frameType = FRAME_TYPE.get(value);
            int h = 31 * lineNumber + bytecodeIndex;
            h = 31 * h + Long.hashCode(classId);
            h = 31 * h + methodName.hashCode();
            h = 31 * h + (frameType != null ? frameType.hashCode() : 0);
            this.hashCode = h;
        }

        @Override
        public RecordedFrame value() {
            return value;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof StackFrameWrapper other) {
                if (other.hashCode() != hashCode()) {
                    return false;
                }
                return bytecodeIndex == other.bytecodeIndex
                        && classId == other.classId
                        && lineNumber == other.lineNumber
                        && methodName.equals(other.methodName)
                        && methodDescriptor.equals(other.methodDescriptor)
                        && Objects.equals(frameType, other.frameType);
            }
            return false;
        }
    }

    record ThreadWrapper(RecordedThread value) implements HashAndEqualsWrapper<RecordedThread> {

        @Override
        public int hashCode() {
            return Long.hashCode(value.getId() * 31);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ThreadWrapper
                    && ((ThreadWrapper) obj).value.getId() == value.getId();
        }
    }

    // same for ThreadGroup
    record ThreadGroupWrapper(RecordedThreadGroup value)
            implements HashAndEqualsWrapper<RecordedThreadGroup> {

        @Override
        public int hashCode() {
            return Objects.hashCode(value.getName());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ThreadGroupWrapper
                    && (((ThreadGroupWrapper) obj).value == value
                            || Objects.equals(
                                    ((ThreadGroupWrapper) obj).value.getName(), value.getName()));
        }
    }

    public JFRHashConfig() {
        put("java.lang.Class", ClassWrapper::new);
        put("jdk.types.ClassLoader", ClassLoaderWrapper::new);
        put("jdk.types.Method", MethodWrapper::new);
        put("jdk.types.StackFrame", StackFrameWrapper::new);
        put("java.lang.Thread", ThreadWrapper::new);
        put("jdk.types.ThreadGroup", ThreadGroupWrapper::new);
    }

    private static final Set<String> PRIMITIVE_TYPES =
            Set.of("int", "long", "float", "double", "boolean", "char", "byte", "short");

    public static boolean isPrimitive(String type) {
        return PRIMITIVE_TYPES.contains(type);
    }

    public static boolean isPrimitiveStructOrArray(ValueDescriptor field, int depth) {
        return depth > 0
                && field.getFields().stream()
                        .allMatch(
                                f ->
                                        f.getFields().isEmpty()
                                                || isPrimitiveStructOrArray(f, depth - 1));
    }

    public static EmbeddingType getEmbeddingType(ValueDescriptor field) {
        if (field.getTypeName().equals("java.lang.String")) {
            return EmbeddingType.REFERENCE_PER_TYPE;
        }
        // StackFrames benefit hugely from reference embedding: 99.97% are duplicates
        if (field.getTypeName().equals("jdk.types.StackFrame")) {
            return EmbeddingType.REFERENCE;
        }
        if (field.getFields().isEmpty()) {
            return EmbeddingType.INLINE;
        }
        if (isPrimitiveStructOrArray(field, 2)) {
            return EmbeddingType.NULLABLE_INLINE;
        }
        return EmbeddingType.REFERENCE;
    }
}
