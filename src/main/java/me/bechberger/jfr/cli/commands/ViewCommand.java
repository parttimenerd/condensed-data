package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.JFRView;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import me.bechberger.jfr.cli.TruncateMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "view",
        description = "View a specific event of a condensed JFR file as a table",
        mixinStandardHelpOptions = true)
public class ViewCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "The event name", paramLabel = "EVENT_NAME", arity = "1")
    private String eventName;

    @Parameters(index = "1..*", description = "The input .cjfr files, can be folders, or zips")
    private List<Path> inputFiles;

    @Option(names = "--width", description = "Width of the table")
    private int width = 160;

    @Option(
            names = "--truncate",
            description = "How to truncate the output cells, 'begining' or 'end'")
    private String truncate = "end";

    @Option(names = "--cell-height", description = "Height of the table cells")
    private int cellHeight = 1;

    @Option(
            names = "--limit",
            description =
                    "Limit the number of events of the given type to print, or -1 for no limit")
    private int limit = -1;

    @Mixin private EventFilterOptionMixin eventFilterOptionMixin;

    @Override
    public Integer call() {
        try {
            var jfrReader =
                    CombiningJFRReader.fromPaths(
                            inputFiles,
                            eventFilterOptionMixin.createFilter(),
                            eventFilterOptionMixin.noReconstitution());
            var struct = jfrReader.readNextEvent();
            JFRView view = null;
            int count = 0;
            while (struct != null) {
                if (struct.getType().getName().equals(eventName)) {
                    if (view == null) {
                        view =
                                new JFRView(
                                        new JFRViewConfig(struct.getType()),
                                        new PrintConfig(
                                                width,
                                                cellHeight,
                                                TruncateMode.valueOf(truncate.toUpperCase())));
                        for (var line : view.header()) {
                            System.out.println(line);
                        }
                    }
                    for (var line : view.rows(struct)) {
                        System.out.println(line);
                    }
                    count++;
                    if (limit != -1 && count >= limit) {
                        break;
                    }
                }
                struct = jfrReader.readNextEvent();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }
}
