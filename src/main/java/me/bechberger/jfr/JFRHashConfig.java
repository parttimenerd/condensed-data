package me.bechberger.jfr;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import jdk.jfr.consumer.*;
import me.bechberger.condensed.Universe;
import me.bechberger.condensed.Universe.HashAndEqualsConfig;
import me.bechberger.condensed.Universe.HashAndEqualsWrapper;

/**
 * No JFR object implements {@link Object::hashCode} and {@link Object::equals}, so we need to wrap
 * the ones that we put into caches so that the equality and hash code are not identity based.
 */
public class JFRHashConfig extends HashAndEqualsConfig {

    /** Objects that should be stored by reference in structs, not yet used */
    static final List<String> TYPES_TO_REF =
            List.of(
                    "jdk.types.Class",
                    "jdk.types.ClassLoader",
                    "jdk.types.Method",
                    "jdk.types.Module",
                    "jdk.types.StackFrame",
                    "jdk.types.Thread",
                    "jdk.types.ThreadGroup");

    static final List<String> NULLABLE_NON_REF_TYPES = List.of("jdk.types.StackTrace");

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

    private IdentityHashMap<RecordedFrame, Integer> methodHashCodeCache = new IdentityHashMap<>();

    static class StackFrameWrapper implements HashAndEqualsWrapper<RecordedFrame> {

        private IdentityHashMap<RecordedFrame, Integer> hashCodeCache;
        private RecordedFrame value;
        private final int maxCacheSize = 10000;
        private int hashCode;

        public StackFrameWrapper(
                RecordedFrame value, IdentityHashMap<RecordedFrame, Integer> hashCodeCache) {
            this.hashCodeCache = hashCodeCache;
            this.value = value;
            if (hashCodeCache.size() > maxCacheSize) {
                hashCodeCache.clear();
            }
            this.hashCode =
                    hashCodeCache.computeIfAbsent(
                            value,
                            f -> {
                                return Objects.hash(
                                        value.getLineNumber(),
                                        f.getBytecodeIndex(),
                                        f.getMethod().getType().getId(),
                                        f.getMethod().getName());
                            });
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
            if (obj instanceof StackFrameWrapper) {
                StackFrameWrapper other = (StackFrameWrapper) obj;
                if (other.value == value) {
                    return true;
                }
                if (other.hashCode() != hashCode()) {
                    return false;
                }
                var method = value.getMethod();
                var otherMethod = other.value.getMethod();
                if (method == otherMethod
                        && value.getBytecodeIndex() == other.value.getBytecodeIndex()) {
                    return true;
                }
                return value.getBytecodeIndex() == other.value.getBytecodeIndex()
                        && method.getType().getId() == otherMethod.getType().getId()
                        && method.getName().equals(otherMethod.getName())
                        && method.getDescriptor().equals(otherMethod.getDescriptor());
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
            return value.getName().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ThreadGroupWrapper
                    && (((ThreadGroupWrapper) obj).value == value
                            || ((ThreadGroupWrapper) obj).value.getName().equals(value.getName()));
        }
    }

    public JFRHashConfig() {
        put("jdk.types.Class", ClassWrapper::new);
        put("jdk.types.ClassLoader", ClassLoaderWrapper::new);
        put("jdk.types.Method", MethodWrapper::new);
        put(
                "jdk.types.StackFrame",
                (RecordedFrame f) -> new StackFrameWrapper(f, methodHashCodeCache));
        put("jdk.types.Thread", ThreadWrapper::new);
        put("jdk.types.ThreadGroup", ThreadGroupWrapper::new);
    }

    public boolean shouldBeAReference(String type) {
        return TYPES_TO_REF.contains(type);
    }

    public static Universe createUniverse(Configuration configuration) {
        return new Universe(
                configuration.useSpecificHashesAndRefs()
                        ? new JFRHashConfig()
                        : HashAndEqualsConfig.NONE);
    }
}
