package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.femtocli.annotations.*;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFROrJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.JFRView;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import me.bechberger.jfr.cli.TruncateMode;

@Command(
        name = "view",
        description = "View a specific event of a condensed JFR file as a table",
        mixinStandardHelpOptions = true)
public class ViewCommand implements Callable<Integer> {

    @Parameters(
            description = "The input .cjfr or .jfr file, can be a folder, or a zip",
            converter = ExistingCJFROrJFRFileOrZipOrFolderConverter.class)
    private Path inputFile;

    @Option(
            names = {"-i", "--inputs"},
            description = "Additional input files",
            converter = ExistingCJFROrJFRFileOrZipOrFolderConverter.class)
    private List<Path> inputFiles = new ArrayList<>();

    @Parameters(index = "1", description = "The event name", paramLabel = "EVENT_NAME", arity = "1")
    private String eventName;

    @Option(names = "--width", description = "Width of the table")
    private int width = 160;

    @Option(
            names = "--truncate",
            description = "How to truncate the output cells, 'beginning' or 'end'")
    private String truncate = "end";

    @Option(names = "--cell-height", description = "Height of the table cells")
    private int cellHeight = 1;

    @Option(
            names = "--limit",
            description =
                    "Limit the number of events of the given type to print, or -1 for no limit")
    private int limit = -1;

    @Mixin private EventFilterOptionMixin eventFilterOptionMixin;

    @Option(names = "--json", description = "Output events as JSON", defaultValue = "false")
    private boolean json;

    @Override
    public Integer call() {
        try {
            if (limit < -1) {
                System.err.println(
                        "Error: --limit must be >= 0 (or -1 for no limit), got: " + limit);
                return 2;
            }
            if (width < 10 || width > 1000) {
                System.err.println("Error: --width must be between 10 and 1000, got: " + width);
                return 2;
            }
            if (cellHeight < 1) {
                System.err.println("Error: --cell-height must be >= 1, got: " + cellHeight);
                return 2;
            }
            if (eventName.contains(",")) {
                System.err.println(
                        "Error: EVENT_NAME does not support comma-separated types."
                                + " Use --events instead.");
                return 2;
            }
            inputFiles.add(0, inputFile);
            // Ensure the positional EVENT_NAME is included in the --events filter
            // so it doesn't get filtered out at the reader level (Bugs 73/133/192)
            eventFilterOptionMixin.ensureEventTypeIncluded(eventName);
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
                            eventFilterOptionMixin.createFilter(),
                            !eventFilterOptionMixin.noReconstitution());
            var struct = jfrReader.readNextEvent();
            List<ReadStruct> matchingEvents = new ArrayList<>();
            List<ReadStruct> caseInsensitiveMatches = new ArrayList<>();
            Set<String> seenTypes = new HashSet<>();
            while (struct != null) {
                String typeName = struct.getType().getName();
                seenTypes.add(typeName);
                if (typeName.equals(eventName)) {
                    matchingEvents.add(struct);
                } else if (typeName.equalsIgnoreCase(eventName)) {
                    caseInsensitiveMatches.add(struct);
                }
                struct = jfrReader.readNextEvent();
            }
            // Case-insensitive fallback: if no exact match, use case-insensitive matches
            if (matchingEvents.isEmpty() && !caseInsensitiveMatches.isEmpty()) {
                matchingEvents = caseInsensitiveMatches;
                eventName = matchingEvents.get(0).getType().getName();
            }
            if (matchingEvents.isEmpty()) {
                System.err.println("No event of type " + eventName + " found.");
                if (seenTypes.isEmpty()) {
                    System.err.println("No events found at all.");
                } else {
                    System.err.println("Did you mean one of these events:");
                    seenTypes.stream()
                            .sorted(
                                    (a, b) -> {
                                        int distA = CLIUtils.editDistance(a, eventName);
                                        int distB = CLIUtils.editDistance(b, eventName);
                                        if (distA != distB) {
                                            return Integer.compare(distA, distB);
                                        }
                                        return a.compareTo(b);
                                    })
                            .limit(10)
                            .forEach(t -> System.err.println("  " + t));
                }
                return 1;
            }
            // Sort events by startTime for chronological display
            matchingEvents.sort(
                    Comparator.comparing(
                            s -> {
                                Object rawStart = s.get("startTime");
                                return rawStart instanceof Instant inst ? inst : Instant.MIN;
                            }));
            if (json) {
                List<Object> jsonEvents = new ArrayList<>();
                int count = 0;
                for (var event : matchingEvents) {
                    if (limit != -1 && count >= limit) {
                        break;
                    }
                    jsonEvents.add(eventToMap(event));
                    count++;
                }
                System.out.println(me.bechberger.util.json.PrettyPrinter.prettyPrint(jsonEvents));
            } else {
                var view =
                        new JFRView(
                                new JFRViewConfig(matchingEvents.get(0).getType()),
                                new PrintConfig(
                                        width, cellHeight, TruncateMode.fromCliValue(truncate)));
                for (var line : view.header()) {
                    System.out.println(line);
                }
                int count = 0;
                for (var event : matchingEvents) {
                    if (limit != -1 && count >= limit) {
                        break;
                    }
                    for (var line : view.rows(event)) {
                        System.out.println(line);
                    }
                    count++;
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            return CLIUtils.printError(e);
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Object convertValue(Object value) {
        if (value == null) return null;
        if (value instanceof ReadStruct rs) return eventToMap(rs);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (var item : list) result.add(convertValue(item));
            return result;
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        return value.toString();
    }

    private static java.util.LinkedHashMap<String, Object> eventToMap(ReadStruct event) {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (var key : event.getType().getFieldNames()) {
            result.put(key, convertValue(event.get(key)));
        }
        return result;
    }
}
