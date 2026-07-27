package me.bechberger.jfr.cli.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link FieldResolver#isSystemClass}, the package-prefix rule that drives the
 * synthetic {@code stackTrace.topApplicationFrame} accessor (Bug 294). memory-leaks-by-site
 * attributes a leak to the first frame outside the JDK/runtime; getting this classification wrong
 * either collapses every trace into one N/A row (too aggressive) or blames JDK plumbing (too lax).
 */
class FieldResolverTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "java.lang.ClassLoader",
                "java.util.HashMap",
                "javax.crypto.Cipher",
                "jdk.internal.loader.BuiltinClassLoader",
                "jdk.internal.loader.ClassLoaders$AppClassLoader",
                "sun.nio.ch.FileChannelImpl",
                "com.sun.crypto.provider.AESCipher"
            })
    void systemClassesAreExcluded(String className) {
        assertTrue(FieldResolver.isSystemClass(className), className + " should be a system class");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "me.bechberger.jfr.cli.JFRCLI",
                "me.bechberger.condensed.CondensedInputStream",
                "org.openjdk.jmc.flightrecorder.writer.LEB128ByteArrayWriter",
                "org.tukaani.xz.ArrayCache",
                "com.example.App"
            })
    void applicationClassesAreIncluded(String className) {
        assertFalse(
                FieldResolver.isSystemClass(className),
                className + " should count as application code");
    }

    @Test
    void internalSlashSeparatorsAreNormalized() {
        // Frame class names can arrive in JVM-internal form (slash-separated); the prefix check
        // must normalize before comparing, or java/lang/... would be misclassified as application.
        assertTrue(FieldResolver.isSystemClass("java/lang/ClassLoader"));
        assertFalse(FieldResolver.isSystemClass("org/tukaani/xz/ArrayCache"));
    }

    @Test
    void javaxAndComSunAreNotConfusedWithLookalikes() {
        // "javaxyz" and "com.sundry" share a prefix substring but are NOT javax./com.sun.
        assertFalse(FieldResolver.isSystemClass("javaxyz.Foo"));
        assertFalse(FieldResolver.isSystemClass("com.sundry.Bar"));
    }
}
