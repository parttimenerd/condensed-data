package me.bechberger.jfr.cli.commands;

import static me.bechberger.jfr.cli.EventTypesCompletionHelper.getApplicationsDir;
import static me.bechberger.jfr.cli.EventTypesCompletionHelper.getTypesAndCount;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.EventTypesCompletionHelper.PossibleTypes.DefaultTypes;
import me.bechberger.jfr.cli.EventTypesCompletionHelper.PossibleTypes.FoundTypes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Not yet used, but might be helpful later to improve the autocompletion */
@Command(
        name = "list-events-for-completion",
        description = "List event types for completion based on the passed command arguments",
        hidden = true,
        mixinStandardHelpOptions = true)
public class ListEventsForCompletion implements Runnable {

    @Parameters(arity = "0", description = "The command to generate completions for")
    String arguments = "";

    @Option(
            names = "--timeout",
            description = "Timeout in milliseconds for the computation",
            defaultValue = "1000")
    int timeout;

    @Option(
            names = "--sort-by-count",
            description = "Sort the output by count of events",
            defaultValue = "false")
    boolean sortByCount;

    @Option(names = "--delimiter", description = "Delimiter for printing", defaultValue = "\n")
    String delimiter;

    @Option(
            names = "--server",
            description =
                    "Run as server (and ignore arguments), writes the port to $APPDATA/cjfr/port"
                        + " and removes this file after the exit. First GET argument is the current"
                        + " directory, rest are the arguments.",
            defaultValue = "false")
    boolean server;

    @Override
    public void run() {
        if (server) {
            runAsServer();
            return;
        }
        var counts =
                getTypesAndCount(
                        CLIUtils.parseCommandLine(arguments),
                        true,
                        timeout,
                        System.getProperty("user.dir"));
        List<String> sorted;
        if (!sortByCount || counts instanceof DefaultTypes) {
            sorted =
                    counts.types().stream()
                            .sorted(Comparator.comparing(String::toLowerCase))
                            .toList();
        } else {
            sorted =
                    ((FoundTypes) counts)
                            .typesWithCounts().entrySet().stream()
                                    .sorted(Comparator.comparingInt(e -> -e.getValue()))
                                    .map(Entry::getKey)
                                    .toList();
        }
        System.out.println(String.join(delimiter, sorted));
    }

    private void runAsServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();
            var appDir = getApplicationsDir();
            if (appDir == null) {
                System.err.println("Could not determine the application directory");
                System.exit(1);
            }
            Path portFile = appDir.resolve("cjfr").resolve("port");
            Files.createDirectories(portFile.getParent());
            Files.writeString(portFile, String.valueOf(port));

            server.createContext(
                    "/list-events-for-completion",
                    exchange -> {
                        if ("GET".equals(exchange.getRequestMethod())) {
                            String query = exchange.getRequestURI().getQuery();
                            List<String> args =
                                    query != null ? List.of(query.split("&")) : List.of();
                            if (args.isEmpty()) {
                                exchange.sendResponseHeaders(400, -1); // Bad Request
                                return;
                            }
                            var counts =
                                    getTypesAndCount(
                                            args.subList(1, args.size()),
                                            true,
                                            timeout,
                                            args.get(0));
                            List<String> sorted;
                            if (!sortByCount || counts instanceof DefaultTypes) {
                                sorted =
                                        counts.types().stream()
                                                .sorted(Comparator.comparing(String::toLowerCase))
                                                .toList();
                            } else {
                                sorted =
                                        ((FoundTypes) counts)
                                                .typesWithCounts().entrySet().stream()
                                                        .sorted(
                                                                Comparator.comparingInt(
                                                                        e -> -e.getValue()))
                                                        .map(Entry::getKey)
                                                        .toList();
                            }
                            String response = String.join(delimiter, sorted);
                            exchange.sendResponseHeaders(200, response.getBytes().length);
                            try (var os = exchange.getResponseBody()) {
                                os.write(response.getBytes());
                            }
                        } else {
                            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                        }
                    });

            server.start();
            System.out.println("Server started on port " + port);
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        server.stop(0);
                                        try {
                                            Files.deleteIfExists(portFile);
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }));
            while (true) {
                Thread.sleep(100);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {

        }
    }
}
