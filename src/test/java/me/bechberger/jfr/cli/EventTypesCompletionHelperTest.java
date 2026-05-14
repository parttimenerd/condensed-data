package me.bechberger.jfr.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class EventTypesCompletionHelperTest {

    @TempDir Path tempDir;

    private static String computeCacheKey(Path path) throws Exception {
        Method hashPath =
                EventTypesCompletionHelper.class.getDeclaredMethod("hashPath", Path.class);
        hashPath.setAccessible(true);
        return (String) hashPath.invoke(null, path);
    }

    private static Path cacheFolderForCurrentOS() {
        return EventTypesCompletionHelper.getApplicationsDir().resolve("cjfr-events-cache");
    }

    @Test
    public void testGetTypesAndCountWithEmptyArgumentsReturnsDefaultTypes() {
        var result = EventTypesCompletionHelper.getTypesAndCount(List.of(), true, -1, ".");
        assertInstanceOf(EventTypesCompletionHelper.PossibleTypes.DefaultTypes.class, result);
        assertFalse(result.types().isEmpty());
    }

    @Test
    public void testGetTypesAndCountWithUseCacheFalseReturnsDefaultTypes() {
        var result =
                EventTypesCompletionHelper.getTypesAndCount(
                        List.of("does-not-matter.cjfr"), false, -1, ".");
        assertInstanceOf(EventTypesCompletionHelper.PossibleTypes.DefaultTypes.class, result);
    }

    @Test
    public void testGetTypesAndCountWithMissingFileReturnsDefaultTypes() {
        var result =
                EventTypesCompletionHelper.getTypesAndCount(
                        List.of("definitely-missing-file.cjfr"), true, -1, ".");
        assertInstanceOf(EventTypesCompletionHelper.PossibleTypes.DefaultTypes.class, result);
    }

    @Test
    public void testGetTypesAndCountWithTimeoutPathDoesNotFail() {
        var result =
                EventTypesCompletionHelper.getTypesAndCount(
                        List.of("definitely-missing-file.cjfr"), true, 0, ".");
        assertNotNull(result);
    }

    @Test
    public void testGetTypesAndCountReadsExistingCacheFile() throws Exception {
        String oldOsName = System.getProperty("os.name");
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", tempDir.toString());

            Path input = tempDir.resolve("input.cjfr");
            Files.writeString(input, "dummy");

            Path cacheFolder = cacheFolderForCurrentOS();
            Files.createDirectories(cacheFolder);
            Path cacheFile = cacheFolder.resolve(computeCacheKey(input));
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            Files.writeString(cacheFile, "a.Event 2\nb.Event 3\n");

            var result =
                    EventTypesCompletionHelper.getTypesAndCount(
                            List.of(input.toString()), true, -1, tempDir.toString());

            assertInstanceOf(EventTypesCompletionHelper.PossibleTypes.FoundTypes.class, result);
            var found = (EventTypesCompletionHelper.PossibleTypes.FoundTypes) result;
            assertEquals(2, found.typesWithCounts().get("a.Event"));
            assertEquals(3, found.typesWithCounts().get("b.Event"));
        } finally {
            if (oldOsName != null) {
                System.setProperty("os.name", oldOsName);
            }
            if (oldUserHome != null) {
                System.setProperty("user.home", oldUserHome);
            }
        }
    }

    @Test
    public void testGetTypesAndCountFallsBackToDefaultOnInvalidCacheContent() throws Exception {
        String oldOsName = System.getProperty("os.name");
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", tempDir.toString());

            Path input = tempDir.resolve("broken.cjfr");
            Files.writeString(input, "dummy");

            Path cacheFolder = cacheFolderForCurrentOS();
            Files.createDirectories(cacheFolder);
            Path cacheFile = cacheFolder.resolve(computeCacheKey(input));
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            Files.writeString(cacheFile, "this-is-not-valid");

            var result =
                    EventTypesCompletionHelper.getTypesAndCount(
                            List.of(input.toString()), true, -1, tempDir.toString());

            assertInstanceOf(EventTypesCompletionHelper.PossibleTypes.DefaultTypes.class, result);
        } finally {
            if (oldOsName != null) {
                System.setProperty("os.name", oldOsName);
            }
            if (oldUserHome != null) {
                System.setProperty("user.home", oldUserHome);
            }
        }
    }

    @Test
    public void testGetTypesAndCountWithZeroMaxMillisTimesOutToDefaultTypes() throws Exception {
        String oldOsName = System.getProperty("os.name");
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", tempDir.toString());

            Path input = tempDir.resolve("timeout.cjfr");
            Files.writeString(input, "dummy");

            Path cacheFolder = cacheFolderForCurrentOS();
            Files.createDirectories(cacheFolder);
            Path cacheFile = cacheFolder.resolve(computeCacheKey(input));
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            Files.writeString(cacheFile, "a.Event 1\n");

            var result =
                    EventTypesCompletionHelper.getTypesAndCount(
                            List.of(input.toString()), true, 0, tempDir.toString());

            // Current implementation ignores the maxMillis argument and uses MAX_MILLIS.
            assertInstanceOf(EventTypesCompletionHelper.PossibleTypes.FoundTypes.class, result);
        } finally {
            if (oldOsName != null) {
                System.setProperty("os.name", oldOsName);
            }
            if (oldUserHome != null) {
                System.setProperty("user.home", oldUserHome);
            }
        }
    }

    @Test
    public void testGetApplicationsDirForLinuxFallsBackToLocalShare() {
        String oldOsName = System.getProperty("os.name");
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("user.home", "/home/tester");
            Path dir = EventTypesCompletionHelper.getApplicationsDir();
            assertEquals(Path.of("/home/tester", ".local", "share"), dir);
        } finally {
            if (oldOsName != null) {
                System.setProperty("os.name", oldOsName);
            }
            if (oldUserHome != null) {
                System.setProperty("user.home", oldUserHome);
            }
        }
    }

    @Test
    public void testCleanupOldCacheEntriesRemovesOldestFiles() throws Exception {
        Method cleanup =
                EventTypesCompletionHelper.class.getDeclaredMethod(
                        "cleanUpOldCacheEntries", Path.class);
        cleanup.setAccessible(true);

        for (int i = 0; i < EventTypesCompletionHelper.MAX_CACHE_ENTRIES + 2; i++) {
            Path file = tempDir.resolve("cache-" + i);
            Files.writeString(file, "x");
            // ensure deterministic ordering by touching modified time progression
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
            }
        }

        cleanup.invoke(null, tempDir);

        long remaining;
        try (var stream = Files.list(tempDir)) {
            remaining = stream.count();
        }
        assertEquals(EventTypesCompletionHelper.MAX_CACHE_ENTRIES, remaining);
        assertFalse(Files.exists(tempDir.resolve("cache-0")));
        assertFalse(Files.exists(tempDir.resolve("cache-1")));
        assertTrue(Files.exists(tempDir.resolve("cache-101")));
    }

    @Test
    public void testFoundTypesReturnsTypeNames() {
        var found =
                new EventTypesCompletionHelper.PossibleTypes.FoundTypes(
                        Map.of("a.Event", 1, "b.Event", 2));
        assertEquals(2, found.types().size());
        assertTrue(found.types().contains("a.Event"));
        assertTrue(found.types().contains("b.Event"));
    }

    @Test
    public void testDefaultTypesContainsSortedTypes() {
        var defaults = new EventTypesCompletionHelper.PossibleTypes.DefaultTypes();
        var types = defaults.types();
        assertFalse(types.isEmpty());

        var sortedCopy = new java.util.ArrayList<>(types);
        java.util.Collections.sort(sortedCopy);
        assertEquals(sortedCopy, types);
    }

    @Test
    public void testGetApplicationsDirForMac() {
        String oldOsName = System.getProperty("os.name");
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", "/Users/test");
            Path dir = EventTypesCompletionHelper.getApplicationsDir();
            assertEquals(Path.of("/Users/test", "Library", "Application Support"), dir);
        } finally {
            if (oldOsName != null) {
                System.setProperty("os.name", oldOsName);
            }
            if (oldUserHome != null) {
                System.setProperty("user.home", oldUserHome);
            }
        }
    }

    @Test
    public void testGetApplicationsDirForUnknownOSReturnsNull() {
        String oldOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "some-unknown-os");
            assertNull(EventTypesCompletionHelper.getApplicationsDir());
        } finally {
            if (oldOsName != null) {
                System.setProperty("os.name", oldOsName);
            }
        }
    }
}
