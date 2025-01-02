package me.bechberger.jfr.cli.commands;

import java.util.List;
import java.util.concurrent.Callable;
import me.bechberger.jfr.Benchmark;
import me.bechberger.jfr.Benchmark.TableConfig;
import me.bechberger.jfr.Configuration;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationConverter;
import me.bechberger.jfr.cli.CLIUtils.ConfigurationIterable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "benchmark",
        description = "Run the benchmarks on all files in the benchmark folder (for development)",
        mixinStandardHelpOptions = true)
public class BenchmarkCommand implements Callable<Integer> {
    @Option(
            names = {"-k", "--keep-condensed-file"},
            description = "Keep the condensed file")
    private boolean keepCondensedFile = false;

    @Option(
            names = {"-i", "--inflate-condensed-file"},
            description = "Inflate the condensed file")
    private boolean inflateCondensedFile = false;

    @Option(
            names = {"-I", "--keep-inflated-file"},
            description = "Keep the inflated file")
    private boolean keepInflatedFile = false;

    @Option(names = "--csv", description = "Output results as CSV")
    private boolean csv = false;

    @Option(names = "--only-per-hour", description = "Only show per-hour columns")
    private boolean onlyPerHour = false;

    @Option(names = "--regexp", description = "Regular expression to filter files")
    private String regexp = ".*";

    @Option(
            names = {"-c", "--configuration"},
            description =
                    "The generatorConfiguration to use, possible values:"
                            + " ${COMPLETION-CANDIDATES}",
            completionCandidates = ConfigurationIterable.class,
            converter = ConfigurationConverter.class,
            arity = "1..*")
    private List<Configuration> configurations =
            Configuration.configurations.values().stream().sorted().toList();

    public Integer call() {
        var results =
                new Benchmark(configurations, regexp)
                        .runBenchmarks(keepCondensedFile, inflateCondensedFile, keepInflatedFile);
        if (csv) {
            System.out.println(results.toTable(new TableConfig(false, onlyPerHour)).toCSV());
        } else {
            System.out.println(results.toTable(new TableConfig(true, onlyPerHour)));
        }
        return 0;
    }
}
