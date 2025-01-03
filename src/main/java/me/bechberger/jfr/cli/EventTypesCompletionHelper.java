package me.bechberger.jfr.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.EventTypesCompletionHelper.PossibleTypes.DefaultTypes;
import me.bechberger.jfr.cli.EventTypesCompletionHelper.PossibleTypes.FoundTypes;
import org.jetbrains.annotations.Nullable;

public class EventTypesCompletionHelper {

    public static final int MAX_MILLIS = 300;
    public static final int MAX_CACHE_ENTRIES = 100;

    public sealed interface PossibleTypes {

        List<String> types();

        record FoundTypes(Map<String, Integer> typesWithCounts) implements PossibleTypes {
            @Override
            public List<String> types() {
                return new ArrayList<>(typesWithCounts.keySet());
            }
        }

        record DefaultTypes(List<String> types) implements PossibleTypes {
            DefaultTypes() {
                this(
                        FlightRecorder.getFlightRecorder().getEventTypes().stream()
                                .map(EventType::getName)
                                .sorted()
                                .collect(Collectors.toList()));
            }
        }
    }

    public static PossibleTypes getTypesAndCount(
            List<String> arguments, boolean useCache, int maxMillis, String workingDir) {
        if (arguments.isEmpty() || !useCache) {
            return new DefaultTypes();
        }
        if (maxMillis == -1) {
            try {
                return getTypesAndCountCached(arguments, workingDir);
            } catch (Exception e) {
                return new DefaultTypes();
            }
        }
        AtomicReference<PossibleTypes> result = new AtomicReference<>();
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                result.set(getTypesAndCountCached(arguments, workingDir));
                            } catch (Exception e) {
                                result.set(new DefaultTypes());
                            }
                        });
        thread.start();
        try {
            long start = System.currentTimeMillis();
            while (thread.isAlive() && System.currentTimeMillis() - start < MAX_MILLIS) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
        }
        if (thread.isAlive()) {
            System.out.println("Timeout reached, interrupting thread");
            thread.interrupt();
            return new DefaultTypes();
        }
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            return new DefaultTypes();
        }
        if (thread.isAlive()) {
            return new DefaultTypes();
        }
        return result.get();
    }

    private static PossibleTypes getTypesAndCountCached(List<String> arguments, String workingDir)
            throws Exception {
        Path cacheFolder = getCJFREventsCacheFolder();
        if (cacheFolder == null) {
            return new DefaultTypes();
        }
        Map<String, Integer> typesWithCounts = new HashMap<>();
        for (String possiblePath : arguments) {
            Path p =
                    possiblePath.startsWith("/")
                            ? Paths.get(possiblePath)
                            : Paths.get(workingDir, possiblePath);
            if (!Files.exists(p)) {
                continue;
            }
            getTypesAndCountCached(p)
                    .forEach(
                            (k, v) ->
                                    typesWithCounts.put(k, typesWithCounts.getOrDefault(k, 0) + v));
        }
        if (typesWithCounts.isEmpty()) {
            return new DefaultTypes();
        }
        cleanUpOldCacheEntries(cacheFolder);
        return new FoundTypes(typesWithCounts);
    }

    private static Map<String, Integer> getTypesAndCountCached(Path path) throws Exception {
        Path cacheFolder = getCJFREventsCacheFolder();
        if (cacheFolder == null) {
            return new HashMap<>();
        }
        Path cacheFile = cacheFolder.resolve(hashPath(path));
        if (!Files.exists(cacheFile)) {
            return obtainAndStoreTypesAndCount(path, cacheFile);
        }
        try (var lines = Files.lines(cacheFile)) {
            return lines.collect(
                    Collectors.toMap(s -> s.split(" ")[0], s -> Integer.parseInt(s.split(" ")[1])));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static Map<String, Integer> obtainAndStoreTypesAndCount(Path path, Path cacheFile)
            throws NoSuchAlgorithmException, IOException {
        Path cacheFolder = getCJFREventsCacheFolder();
        if (cacheFolder == null) {
            return new HashMap<>();
        }

        Map<String, Integer> typesWithCounts = new HashMap<>();
        try {
            var reader = CombiningJFRReader.fromPaths(List.of(path));

            ReadStruct struct;
            while ((struct = reader.readNextEvent()) != null) {
                var typeName = struct.getType().getName();
                typesWithCounts.put(typeName, typesWithCounts.getOrDefault(typeName, 0) + 1);
            }
        } catch (Exception ex) {
            return new HashMap<>();
        }
        if (!Files.exists(cacheFile)) {
            if (!Files.exists(cacheFolder)) {
                Files.createDirectories(cacheFolder);
            }
            Files.createFile(cacheFile);
        }
        try (var writer = Files.newBufferedWriter(cacheFile)) {
            for (var entry : typesWithCounts.entrySet()) {
                writer.write(entry.getKey() + " " + entry.getValue());
                writer.newLine();
            }
        } catch (Exception e) {
        }
        return typesWithCounts;
    }

    private static String hashPath(Path path) throws NoSuchAlgorithmException, IOException {
        // get the sha1 hash of the path and take the first 20 characters from base64 encoding
        return Base64.getEncoder()
                .encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest(
                                        (path.toString() + Files.getLastModifiedTime(path))
                                                .getBytes()))
                .substring(0, 20);
    }

    private static @Nullable Path getCJFREventsCacheFolder() {
        Path applicationsDir = getApplicationsDir();
        if (applicationsDir == null) {
            return null;
        }
        return applicationsDir.resolve("cjfr-events-cache");
    }

    private static void cleanUpOldCacheEntries(Path cacheFolder) {
        // remove old cache entries
        var cacheFiles = cacheFolder.toFile().listFiles();
        if (cacheFiles == null) {
            return;
        }
        Arrays.stream(cacheFiles)
                .sorted(Comparator.comparing(File::lastModified))
                .skip(MAX_CACHE_ENTRIES)
                .forEach(File::delete);
    }

    public static @Nullable Path getApplicationsDir() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.startsWith("linux")) {
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null && !xdgDataHome.isEmpty()) {
                return Paths.get(xdgDataHome);
            }
            return Paths.get(System.getProperty("user.home"), ".local", "share");
        } else if (os.startsWith("macosx") || os.startsWith("mac os x")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support");
        } else if (os.startsWith("windows")) {
            return Paths.get(System.getenv("APPDATA"));
        }
        return null;
    }
}
