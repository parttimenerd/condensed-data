package me.bechberger.condensed.stats;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generates flamegraphs from an {@link EventWriteTree}
 * <p>
 * Inspired by <a href="https://github.com/parttimenerd/tiny-profiler/blob/main/src/main/java/me/bechberger/Store.java">Tiny Profiler</a>
 */
public class FlamegraphGenerator {

    public enum Metric {
        BYTES,
        EVENTS
    }

    private final EventWriteTree root;
    private final Map<EventWriteTree, Long> computedBytes = new HashMap<>();

    public FlamegraphGenerator(EventWriteTree root) {
        this.root = root;
    }

    private long computeOverallBytesWritten(EventWriteTree tree) {
        if (!computedBytes.containsKey(tree)) {
            long total = tree.getDirectBytesWritten();
            for (var child : tree.getChildren()) {
                total += computeOverallBytesWritten(child);
            }
            computedBytes.put(tree, total);
        }
        return computedBytes.get(tree);
    }

    private void writeJSON(PrintStream s, EventWriteTree tree) {
        s.printf("{ \"name\": \"%s\", \"value\": %d, \"children\": [", tree.getCauseName(), computeOverallBytesWritten(tree));
        for (var child : tree.getChildren()) {
            writeJSON(s, child);
            s.print(",");
        }
        s.print("]}");
    }

    public void writeHTML(PrintStream s) {
        s.print("""
                    <head>
                      <link rel="stylesheet" type="text/css" href="https://cdn.jsdelivr.net/npm/d3-flame-graph@4.1.3/dist/d3-flamegraph.css">
                      <link rel="stylesheet" type="text/css" href="misc/d3-flamegraph.css">
                    </head>
                    <body>
                      <div id="chart"></div>
                      <script type="text/javascript" src="https://d3js.org/d3.v7.js"></script>
                      <script type="text/javascript" src="misc/d3.v7.js"></script>
                      <script type="text/javascript" src="https://cdn.jsdelivr.net/npm/d3-flame-graph@4.1.3/dist/d3-flamegraph.js"></script>
                      <script type="text/javascript" src="misc/d3-flamegraph.js"></script>
                      <script type="text/javascript">
                      var chart = flamegraph().width(window.innerWidth);
                      d3.select("#chart").datum(""");
        writeJSON(s, root);
        s.print("""
                    ).call(chart);
                      window.onresize = () => chart.width(window.innerWidth);
                      </script>
                    </body>
                    """);
    }

    public void writeHTML(Path filePath) throws Exception {
        try (PrintStream s = new PrintStream(filePath.toFile())) {
            writeHTML(s);
        }
    }

    /**
     * This prints a table sorted by overall bytes written per event type to the given PrintStream.
     * <code>
     *     Type Name       | Direct Bytes | Overall Bytes | Event Count | Overall Bytes/Event | Percentage of Total
     * </code>
     * @param s
     */
    public void writeTable(PrintStream s) {
        long totalBytes = computeOverallBytesWritten(root);

        // Aggregate entries by type name
        Map<String, AggregatedStats> aggregated = new HashMap<>();
        for (var entry : computedBytes.entrySet()) {
            EventWriteTree tree = entry.getKey();
            String typeName = tree.getCauseName();
            long overallBytes = entry.getValue();
            long directBytes = tree.getDirectBytesWritten();
            long count = tree.getCount();

            aggregated.compute(typeName, (k, existing) -> {
                if (existing == null) {
                    return new AggregatedStats(directBytes, overallBytes, count);
                } else {
                    return new AggregatedStats(
                        existing.directBytes + directBytes,
                        existing.overallBytes + overallBytes,
                        existing.count + count
                    );
                }
            });
        }

        s.printf("%-40s | %13s | %13s | %11s | %20s | %20s%n", "Type Name", "Direct Bytes", "Overall Bytes", "Count", "Overall Bytes/Message", "Percentage of Total");
        s.println("---------------------------------------------------------------------------------------------------------------");
        aggregated.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().overallBytes, a.getValue().overallBytes))
                .forEach(entry -> {
                    String typeName = entry.getKey();
                    AggregatedStats stats = entry.getValue();
                    double overallBytesPerEvent = stats.count > 0 ? (double) stats.overallBytes / stats.count : 0.0;
                    double percentageOfTotal = totalBytes > 0 ? (double) stats.overallBytes * 100.0 / totalBytes : 0.0;
                    s.printf(
                            "%-40s | %13d | %13d | %11d | %20.2f | %20.2f%%%n",
                            typeName,
                            stats.directBytes,
                            stats.overallBytes,
                            stats.count,
                            overallBytesPerEvent,
                            percentageOfTotal);
                });
    }

    /**
     * Returns a JSONArray with table data sorted by overall bytes written per event type.
     * Each entry contains: typeName, directBytes, overallBytes, count, overallBytesPerMessage, percentageOfTotal
     *
     * @return JSONArray with event type statistics
     */
    public JSONArray toJSON() {
        long totalBytes = computeOverallBytesWritten(root);

        // Aggregate entries by type name
        Map<String, AggregatedStats> aggregated = new HashMap<>();
        for (var entry : computedBytes.entrySet()) {
            EventWriteTree tree = entry.getKey();
            String typeName = tree.getCauseName();
            long overallBytes = entry.getValue();
            long directBytes = tree.getDirectBytesWritten();
            long count = tree.getCount();

            aggregated.compute(typeName, (k, existing) -> {
                if (existing == null) {
                    return new AggregatedStats(directBytes, overallBytes, count);
                } else {
                    return new AggregatedStats(
                        existing.directBytes + directBytes,
                        existing.overallBytes + overallBytes,
                        existing.count + count
                    );
                }
            });
        }

        JSONArray result = new JSONArray();
        aggregated.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().overallBytes, a.getValue().overallBytes))
                .forEach(entry -> {
                    String typeName = entry.getKey();
                    AggregatedStats stats = entry.getValue();
                    double overallBytesPerEvent = stats.count > 0 ? (double) stats.overallBytes / stats.count : 0.0;
                    double percentageOfTotal = totalBytes > 0 ? (double) stats.overallBytes * 100.0 / totalBytes : 0.0;

                    JSONObject obj = new JSONObject();
                    obj.put("typeName", typeName);
                    obj.put("directBytes", stats.directBytes);
                    obj.put("overallBytes", stats.overallBytes);
                    obj.put("count", stats.count);
                    obj.put("overallBytesPerMessage", overallBytesPerEvent);
                    obj.put("percentageOfTotal", percentageOfTotal);

                    result.put(obj);
                });

        return result;
    }

    private record AggregatedStats(long directBytes, long overallBytes, long count) {}
}