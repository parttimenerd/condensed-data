package me.bechberger.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import me.bechberger.condensed.Compression;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.Message.StartMessage;

/**
 * Runs the condenser on the JFR files from the <code>benchmark</code> folder and outputs a table on
 * the command line
 */
public class Benchmark {

    private static final Path BENCHMARK_FOLDER = Path.of("benchmark");
    private static final Path BENCHMARK_TIMES = BENCHMARK_FOLDER.resolve("benchmark_times.txt");

    private final List<Configuration> configurations;

    public record JFRFile(
            Path file,
            String name,
            String gcAlgorithm,
            String jfcFile,
            float runtime,
            long size,
            long compressedSize) {}

    public record SingleResult(Configuration configuration, float runtime, long size) {}

    public record Result(Benchmark.JFRFile jfrFile, List<SingleResult> configResults) {

        public SingleResult forConfiguration(Configuration configuration) {
            return configResults.stream()
                    .filter(sr -> sr.configuration().equals(configuration))
                    .findFirst()
                    .orElseThrow();
        }
    }

    public static String formatMemory(long bytes, int decimals) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(
                "%.0" + decimals + "f %sB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    public record TableColumnDescription<T>(String label, String format, Function<T, ?> getter) {

        static <T> TableColumnDescription<T> ofMemory(
                String label, Function<T, Long> getter, int decimals, boolean humanReadableMemory) {
            if (!humanReadableMemory) {
                return new TableColumnDescription<>(label, "%d", getter);
            }
            return new TableColumnDescription<>(
                    label, "%s", (T row) -> formatMemory(getter.apply(row), decimals));
        }

        public String getStringForRow(T row) {
            return String.format(format(), getter.apply(row));
        }
    }

    public record Table<T>(List<TableColumnDescription<T>> header, List<T> rows) {
        @Override
        public String toString() {
            // get the cells using the format string from the column headers
            List<List<String>> stringRows =
                    rows.stream()
                            .map(
                                    row ->
                                            header.stream()
                                                    .map(column -> column.getStringForRow(row))
                                                    .toList())
                            .toList();

            List<Integer> widthPerColumn =
                    IntStream.range(0, header.size())
                            .mapToObj(
                                    i ->
                                            Math.max(
                                                    header.get(i).label.length(),
                                                    stringRows.stream()
                                                            .mapToInt(row -> row.get(i).length())
                                                            .max()
                                                            .orElse(0)))
                            .toList();

            // add the header and underline it
            // make sure the header is as wide as the rows
            List<String> headerStrings =
                    IntStream.range(0, header.size())
                            .mapToObj(
                                    i ->
                                            String.format(
                                                    "%-" + widthPerColumn.get(i) + "s",
                                                    header.get(i).label))
                            .toList();

            StringBuilder result = new StringBuilder();
            result.append(String.join(" | ", headerStrings))
                    .append("\n")
                    .append(
                            headerStrings.stream()
                                    .map(s -> "-".repeat(s.length()))
                                    .collect(Collectors.joining(" | ")))
                    .append("\n");

            // add the rows
            for (List<String> stringRow : stringRows) {
                result.append(
                        IntStream.range(0, header.size())
                                .mapToObj(
                                        i ->
                                                String.format(
                                                        "%" + widthPerColumn.get(i) + "s",
                                                        stringRow.get(i)))
                                .collect(Collectors.joining(" | "))
                                .concat("\n"));
            }
            return result.toString();
        }

        public String toCSV() {
            return header.stream()
                            .map(TableColumnDescription::label)
                            .collect(Collectors.joining(","))
                    + "\n"
                    + rows.stream()
                            .map(
                                    row ->
                                            header.stream()
                                                    .map(column -> column.getter().apply(row))
                                                    .map(Object::toString)
                                                    .collect(Collectors.joining(",")))
                            .collect(Collectors.joining("\n"));
        }
    }

    public record TableConfig(boolean humanReadableMemory, boolean onlyPerHour) {}

    public record Results(List<Result> results) {

        public List<Configuration> configurations() {
            return results.stream()
                    .flatMap(r -> r.configResults().stream())
                    .map(SingleResult::configuration)
                    .distinct()
                    .collect(Collectors.toList());
        }

        public Table<Result> toTable(TableConfig tconf) {
            List<TableColumnDescription<Result>> header = new ArrayList<>();
            // file, runtime, original size, compressed size
            header.add(new TableColumnDescription<>("JFR file", "%s", r -> r.jfrFile().name()));
            if (!tconf.onlyPerHour) {
                header.add(
                        new TableColumnDescription<>(
                                "runtime (s)", "%.2f", r -> r.jfrFile.runtime()));
                header.add(
                        TableColumnDescription.ofMemory(
                                "original", r -> r.jfrFile().size(), 3, tconf.humanReadableMemory));
                header.add(
                        TableColumnDescription.ofMemory(
                                "compressed",
                                r -> r.jfrFile().compressedSize(),
                                3,
                                tconf.humanReadableMemory));
            }
            // add per-hour column
            header.add(
                    TableColumnDescription.ofMemory(
                            tconf.onlyPerHour ? "original per-hour" : "per-hour",
                            r -> {
                                var runtime = r.jfrFile().runtime();
                                var size = r.jfrFile().size();
                                return (long) (size / runtime * 3600f);
                            },
                            1,
                            tconf.humanReadableMemory));
            if (!tconf.onlyPerHour) {
                // add % column
                header.add(
                        new TableColumnDescription<>(
                                "%",
                                "%.2f%%",
                                r -> {
                                    var originalSize = r.jfrFile().size();
                                    var compressedSize = r.jfrFile().compressedSize;
                                    return (float) compressedSize / originalSize * 100;
                                }));
            }
            // add per-hour column
            header.add(
                    TableColumnDescription.ofMemory(
                            tconf.onlyPerHour ? "compressed per-hour" : "per-hour",
                            r -> {
                                var runtime = r.jfrFile().runtime();
                                var size = r.jfrFile().compressedSize;
                                return (long) (size / runtime * 3600f);
                            },
                            1,
                            tconf.humanReadableMemory));
            // add the following for each configuration: runtime, size
            configurations()
                    .forEach(
                            config -> {
                                if (!tconf.onlyPerHour) {
                                    header.add(
                                            new TableColumnDescription<>(
                                                    config.name(),
                                                    "%.2f s",
                                                    r -> r.forConfiguration(config).runtime()));
                                    header.add(
                                            TableColumnDescription.ofMemory(
                                                    "size",
                                                    r -> r.forConfiguration(config).size(),
                                                    3,
                                                    tconf.humanReadableMemory));
                                    header.add(
                                            new TableColumnDescription<>(
                                                    "%",
                                                    "%.2f%%",
                                                    r -> {
                                                        var originalSize = r.jfrFile().size();
                                                        var compressedSize =
                                                                r.forConfiguration(config).size();
                                                        return (float) compressedSize
                                                                / originalSize
                                                                * 100;
                                                    }));
                                }
                                header.add(
                                        TableColumnDescription.ofMemory(
                                                tconf.onlyPerHour
                                                        ? config.name() + " per-hour"
                                                        : "per-hour",
                                                r -> {
                                                    var runtime = r.jfrFile().runtime();
                                                    var size = r.forConfiguration(config).size();
                                                    return (long) (size / runtime * 3600f);
                                                },
                                                1,
                                                tconf.humanReadableMemory));
                            });
            return new Table<>(header, results);
        }
    }

    private final List<JFRFile> jfrFiles;

    public Benchmark(List<Configuration> configurations, String fileRegex) {
        this.configurations = configurations;
        if (configurations.isEmpty()) {
            throw new IllegalArgumentException("No configurations provided");
        }
        if (!Files.exists(BENCHMARK_FOLDER)) {
            throw new IllegalArgumentException(
                    "Benchmark folder does not exist, run this from the project root");
        }
        this.jfrFiles = readJFRFiles(fileRegex);
    }

    private List<JFRFile> readJFRFiles(String fileRegex) {
        // content of benchmark_times.txt:
        // benchmark_file.jfr, gc_algo, jfc, gc_runtime in seconds, compressed size in bytes
        // e.g.:
        // 2021-09-01T14-00-00Z_gc_g1_1.jfr, 123.456, 123456789

        try {
            return Files.readAllLines(BENCHMARK_TIMES).stream()
                    .map(line -> line.split(","))
                    .map(
                            parts -> {
                                if (parts.length != 5) {
                                    throw new IllegalArgumentException(
                                            "Invalid line: " + String.join(",", parts));
                                }
                                var jfrFile = BENCHMARK_FOLDER.resolve(parts[0]);
                                var gcAlgorithm = parts[1];
                                var jfcFile = parts[2];
                                var runtime = Float.parseFloat(parts[3]);
                                long size;
                                try {
                                    size = Files.size(jfrFile);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                var compressedSize = Long.parseLong(parts[4]);
                                return new JFRFile(
                                        jfrFile,
                                        jfrFile.getFileName().toString(),
                                        gcAlgorithm,
                                        jfcFile,
                                        runtime,
                                        size,
                                        compressedSize);
                            })
                    .filter(jfrFile -> jfrFile.name().matches(fileRegex + ".*"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static SingleResult benchmark(
            Configuration configuration,
            JFRFile jfrFile,
            boolean keepCondensedFile,
            boolean inflateCondensedFile,
            boolean keepInflatedFile) {
        try {
            var cjfrFile =
                    jfrFile.file.resolveSibling(
                            jfrFile.file.getFileName() + "_" + configuration.name() + ".cjfr");
            long start;
            try (var out =
                    new CondensedOutputStream(
                            Files.newOutputStream(cjfrFile),
                            StartMessage.DEFAULT.compress(Compression.DEFAULT))) {
                start = System.nanoTime();
                var basicJFRWriter = new BasicJFRWriter(out, configuration);
                basicJFRWriter.processJFRFile(jfrFile.file);
            }
            var result =
                    new SingleResult(
                            configuration,
                            (System.nanoTime() - start) / 1_000_000_000f,
                            Files.size(cjfrFile));
            if (!keepCondensedFile) {
                Files.delete(cjfrFile);
            }
            if (inflateCondensedFile) {
                try (var in = new CondensedInputStream(Files.newInputStream(cjfrFile))) {
                    Path inflated =
                            cjfrFile.resolveSibling(cjfrFile.getFileName() + ".inflated.jfr");
                    WritingJFRReader.toJFRFile(new BasicJFRReader(in), inflated);
                    if (!keepInflatedFile) {
                        Files.delete(inflated);
                    }
                }
            }
            System.out.println(
                    "Benchmarked "
                            + jfrFile.name()
                            + " with "
                            + configuration.name()
                            + " in "
                            + result.runtime()
                            + "s, size: "
                            + result.size()
                            + " bytes");
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Results runBenchmarks(
            boolean keepCondensedFile, boolean inflateCondensedFile, boolean keepInflatedFile) {
        return new Results(
                jfrFiles.stream()
                        .map(
                                jfrFile -> {
                                    var configResults =
                                            configurations.parallelStream()
                                                    .map(
                                                            configuration ->
                                                                    benchmark(
                                                                            configuration,
                                                                            jfrFile,
                                                                            keepCondensedFile,
                                                                            inflateCondensedFile,
                                                                            keepInflatedFile))
                                                    .collect(Collectors.toList());
                                    return new Result(jfrFile, configResults);
                                })
                        .collect(Collectors.toList()));
    }
}
