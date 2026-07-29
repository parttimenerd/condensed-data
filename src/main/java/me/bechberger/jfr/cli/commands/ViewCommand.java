package me.bechberger.jfr.cli.commands;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import me.bechberger.condensed.CJFRFooter;
import me.bechberger.condensed.CJFRFooterReader;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.femtocli.annotations.*;
import me.bechberger.jfr.CombiningJFRReader;
import me.bechberger.jfr.JMCDependent;
import me.bechberger.jfr.cli.CLIUtils;
import me.bechberger.jfr.cli.EventFilter.EventFilterOptionMixin;
import me.bechberger.jfr.cli.FileOptionConverters.ExistingCJFROrJFRFileOrZipOrFolderConverter;
import me.bechberger.jfr.cli.JFRView;
import me.bechberger.jfr.cli.JFRView.JFRViewConfig;
import me.bechberger.jfr.cli.JFRView.PrintConfig;
import me.bechberger.jfr.cli.TruncateMode;
import me.bechberger.jfr.cli.query.NativeView;
import me.bechberger.jfr.cli.query.ViewPrecompute;

@Command(
        name = "view",
        description = {
            "View a named view or event type from a .cjfr or .jfr file as a table.",
            "",
            "Drop-in replacement for `jfr view`: the view/event name comes first, then the"
                    + " file(s). Example: `cjfr view gc-pauses recording.cjfr`.",
            "",
            "Named views (gc-pauses, hot-methods, allocation-by-site, …) are read from the"
                    + " running JVM's own view.ini, so the available set always matches the JDK"
                    + " you run cjfr on. Views that cannot be evaluated natively fall back to"
                    + " `jfr view` automatically."
        },
        mixinStandardHelpOptions = true)
// --events is inherited from EventFilterOptionMixin but is useless here: view only ever displays
// the single positional VIEW_OR_EVENT, so an extra type filter can't change the output.
@IgnoreOptions(exclude = {"--events"})
public class ViewCommand implements Callable<Integer> {

    /**
     * All positional args, mirroring {@code jfr view <view> <file...>}: the FIRST arg is the
     * view/event name, the remaining args are input files.
     */
    @Parameters(
            arity = "2..*",
            description =
                    "The view or event name (first argument), followed by one or more input .cjfr"
                            + " or .jfr files. A dotted event type (e.g. jdk.GCHeapSummary) is"
                            + " rendered natively as a per-event table. A dot-free name (e.g."
                            + " gc-configuration, hot-methods) is treated as a JDK named view:"
                            + " rendered natively when possible, otherwise via the JDK `jfr view`."
                            + " A dot-free name that is actually an event type present in the file"
                            + " is rendered as that event type instead.")
    private List<String> args = new ArrayList<>();

    // -1 means "not set by the user". The native renderer resolves it to DEFAULT_WIDTH; the
    // delegation path omits --width so `jfr view` uses its own per-view default.
    @Option(
            names = "--width",
            description = "Total table width in characters (10-1000). Default: 160.")
    private int width = -1;

    private static final int DEFAULT_WIDTH = 160;

    private int effectiveWidth() {
        return width == -1 ? DEFAULT_WIDTH : width;
    }

    @Option(
            names = "--truncate",
            description =
                    "How to truncate table cells that are too wide: 'beginning' (keep the end)"
                            + " or 'end' (keep the beginning, default).",
            defaultValue = "end")
    private String truncate = "end";

    // -1 means "not set by the user"; delegation omits --cell-height so jfr uses its own default.
    @Option(
            names = "--cell-height",
            description = "Maximum number of text lines per table cell (>= 1). Default: 1.")
    private int cellHeight = -1;

    private static final int DEFAULT_CELL_HEIGHT = 1;

    private int effectiveCellHeight() {
        return cellHeight == -1 ? DEFAULT_CELL_HEIGHT : cellHeight;
    }

    @Option(
            names = "--verbose",
            description =
                    "For named views, also print the underlying query that defines the view"
                            + " (delegates to the JDK `jfr view`).",
            defaultValue = "false")
    private boolean verbose;

    @Option(
            names = "--limit",
            description =
                    "For an event-type table, print at most this many events (-1 = no limit,"
                            + " the default). Ignored for named views.")
    private int limit = -1;

    @Mixin private EventFilterOptionMixin eventFilterOptionMixin;

    @Option(
            names = "--json",
            description =
                    "Output events as JSON instead of a table. Only supported for event types"
                            + " (e.g. jdk.GCHeapSummary), not for named views.",
            defaultValue = "false")
    private boolean json;

    /**
     * The event type names seen while scanning the inputs in {@link #collectMatches}. Stashed so
     * the JMC-dependent delegation path ({@link Impl}) can print a "did you mean" list if {@code
     * jfr view} also fails to resolve the dot-free name.
     */
    private Set<String> lastSeenTypes = Set.of();

    /** Returns the view/event name: the first positional arg (jfr view order). */
    private String viewName() {
        return args.get(0);
    }

    /** Returns the input file paths: all positional args except the first. */
    private List<Path> inputs() {
        var converter = new ExistingCJFROrJFRFileOrZipOrFolderConverter();
        var inputs = new ArrayList<Path>(args.size() - 1);
        for (String s : args.subList(1, args.size())) {
            inputs.add(converter.convert(s));
        }
        return inputs;
    }

    /** Raw input file strings (all positional args except the first). */
    private List<String> inputArgs() {
        return args.subList(1, args.size());
    }

    /**
     * A dotted name (e.g. {@code jdk.GCHeapSummary}) is unambiguously an event type by the JDK
     * naming convention, so it is always rendered natively. Dot-free names ({@code TestEvent},
     * {@code gc-configuration}, {@code all-views}, ...) are ambiguous: they may be a user-defined
     * event type present in the file OR one of the ~90 curated {@code jfr view} named views. Those
     * are resolved at runtime in {@link #call()}: native render is attempted first, and only if no
     * matching event type is found does the name fall through to JDK {@code jfr view} delegation.
     */
    private static boolean isDottedEventType(String name) {
        return name.contains(".");
    }

    @Override
    public Integer call() {
        try {
            if (args.size() < 2) {
                System.err.println(
                        "Error: a view/event name and at least one input file are required");
                return 2;
            }
            if (limit < -1) {
                System.err.println(
                        "Error: --limit must be >= 0 (or -1 for no limit), got: " + limit);
                return 2;
            }
            if (width != -1 && (width < 10 || width > 1000)) {
                System.err.println("Error: --width must be between 10 and 1000, got: " + width);
                return 2;
            }
            if (cellHeight != -1 && cellHeight < 1) {
                System.err.println("Error: --cell-height must be >= 1, got: " + cellHeight);
                return 2;
            }
            try {
                TruncateMode.fromCliValue(truncate);
            } catch (IllegalArgumentException e) {
                System.err.println(
                        "Error: --truncate must be 'beginning' or 'end', got: " + truncate);
                return 2;
            }
            String viewName = viewName();
            if (viewName.contains(",")) {
                System.err.println(
                        "Error: VIEW_OR_EVENT does not support comma-separated types."
                                + " view shows a single view or event type.");
                return 2;
            }

            // Fast path for named views: a dot-free name is never a JFR event type (those are
            // always
            // dotted, e.g. jdk.ObjectCount), so it can only be a named view. Render it natively
            // *before* the event-name scan below — tryNativeView reads only the view's required
            // event
            // types, whereas collectMatches would condense the entire recording looking for an
            // event
            // literally named e.g. "object-statistics" that cannot exist. On a large .jfr this
            // skips
            // condensing millions of irrelevant events. Guarded exactly as the post-scan native
            // path
            // (not --verbose, not --json, a known view); an empty result falls through to the scan.
            if (!isDottedEventType(viewName)
                    && !verbose
                    && !json
                    && NativeView.isKnownView(viewName)) {
                Optional<List<String>> lines = tryNativeView(viewName);
                if (lines.isPresent()) {
                    lines.get().forEach(System.out::println);
                    return 0;
                }
            }

            // Read the inputs once and collect every event whose type matches viewName (exact, then
            // case-insensitive). A dotted name is always an event type; a dot-free name is only
            // treated as an event type if it actually occurs in the file.
            var matches = collectMatches(viewName);

            if (!matches.events().isEmpty()) {
                return renderMatches(matches);
            }

            // No matching event type. For a dot-free name, fall through to the JDK `jfr view` so
            // the
            // curated named views (gc-configuration, hot-methods, ...) work on condensed files too.
            // This requires inflating any .cjfr inputs to a temporary .jfr first, which is
            // JMC-dependent, so it runs in the reflectively-loaded $Impl.
            // Named views are always kebab-case (contain '-'); a dot-free name without '-' is a
            // plain event type that simply wasn't found — fall through to reportNoEventType.
            if (!isDottedEventType(viewName) && viewName.contains("-")) {
                if (json) {
                    System.err.println(
                            "Error: --json is only supported for event types (e.g."
                                    + " jdk.GCHeapSummary), not for named views like '"
                                    + viewName
                                    + "'.");
                    return 2;
                }
                // Render the curated named views natively, skipping the .cjfr→.jfr inflation and
                // the
                // `jfr view` JVM fork. --verbose prints the view's underlying query, a jfr feature
                // we
                // don't reproduce, so it still delegates. Native render returns empty for views we
                // can't evaluate (FROM *, unsupported reducers), which fall through to delegation.
                if (!verbose && NativeView.isKnownView(viewName)) {
                    Optional<List<String>> lines = tryNativeView(viewName);
                    if (lines.isPresent()) {
                        lines.get().forEach(System.out::println);
                        return 0;
                    }
                }
                // Give Impl the types we already saw, so it can print a did-you-mean list if jfr
                // view also can't resolve the name (a typo'd event name, not a real named view).
                lastSeenTypes = matches.seenTypes();
                return CLIUtils.callImpl(this, "view");
            }

            return reportNoEventType(viewName, matches.seenTypes());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            return CLIUtils.printError(e);
        }
    }

    /** The events matching a requested type, plus the resolved name and all seen type names. */
    private record MatchResult(String eventName, List<ReadStruct> events, Set<String> seenTypes) {}

    /**
     * Read the inputs and collect events whose type equals {@code eventName} (exact match
     * preferred, otherwise case-insensitive). Returns an empty event list if none matched, so the
     * caller can decide whether to fall through to named-view delegation.
     */
    private MatchResult collectMatches(String eventName) throws Exception {
        var inputFiles = inputs();
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
        return new MatchResult(eventName, matchingEvents, seenTypes);
    }

    /**
     * Attempt to render a known named view natively. Returns the rendered lines, or empty to signal
     * the caller should fall through to {@code jfr view} delegation.
     *
     * <p>Fast path: if the view's required event types are all absent according to the {@code
     * .cjfr} footers (which carry per-type counts with no event scan), emit the native "No events
     * found" line without reading any events. Otherwise scan the inputs once for just the required
     * types and render from the in-memory events.
     */
    private Optional<List<String>> tryNativeView(String viewName) throws Exception {
        Optional<List<String>> reqOpt = NativeView.requiredEventTypes(viewName);
        if (reqOpt.isEmpty()) {
            return Optional.empty(); // FROM * or not natively evaluable → delegate
        }
        List<String> required = reqOpt.get();

        // Zero-scan fast path: a single .cjfr input whose footer carries a precomputed
        // exact-aggregate
        // for this view is rendered straight from the stored cells — no event read at all. Output
        // is
        // byte-identical to the event-based native render (same ViewRenderer, same values, same
        // Kinds).
        Optional<List<String>> served = tryFooterServed(viewName);
        if (served.isPresent()) {
            return served;
        }

        if (footerSaysAllAbsent(required)) {
            return NativeView.render(viewName, Map.of(), nativeOptions());
        }

        ReadEvents read = readRequiredEvents(required);
        Map<String, List<ReadStruct>> byType = NativeView.indexByType(read.events());
        return NativeView.render(viewName, byType, nativeOptions(), read.typeLabels());
    }

    /** Native render options mirroring the {@code jfr view} switches this command accepts. */
    private NativeView.Options nativeOptions() {
        boolean truncateBeginning = TruncateMode.fromCliValue(truncate) == TruncateMode.BEGIN;
        // -1 means the user did not pass --cell-height, so let each column fall back to its
        // view.ini
        // cell-height (null); an explicit value overrides the view for every column.
        Integer cell = cellHeight == -1 ? null : cellHeight;
        return new NativeView.Options(effectiveWidth(), cell, truncateBeginning);
    }

    /**
     * Serve a precomputed FORM view from the footer with zero event reads. Only fires for a single
     * {@code .cjfr} input whose footer has a {@code precomputedViews} entry for {@code viewName};
     * any other case (multiple inputs, no footer, missing entry) returns empty so the caller falls
     * back to the event-based native render.
     */
    private Optional<List<String>> tryFooterServed(String viewName) {
        List<Path> inputs = inputs();
        if (inputs.size() != 1) {
            return Optional.empty();
        }
        Optional<CJFRFooter> footerOpt = CJFRFooterReader.tryRead(inputs.get(0));
        if (footerOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<CJFRFooter.PrecomputedCell>> views = footerOpt.get().precomputedViews();
        if (views == null) {
            return Optional.empty();
        }
        List<CJFRFooter.PrecomputedCell> cells = views.get(viewName);
        if (cells == null) {
            return Optional.empty();
        }
        return ViewPrecompute.render(viewName, cells, new NativeView.Options(effectiveWidth()));
    }

    /**
     * True if every {@code required} type totals zero across all inputs AND every input has a
     * readable {@code .cjfr} footer. If any input lacks a footer (e.g. a plain {@code .jfr}), we
     * can't trust the counts, so return false and let the caller scan events.
     */
    private boolean footerSaysAllAbsent(List<String> required) {
        long[] totals = new long[required.size()];
        for (Path input : inputs()) {
            Optional<CJFRFooter> footerOpt = CJFRFooterReader.tryRead(input);
            if (footerOpt.isEmpty()) {
                return false;
            }
            Map<String, Long> counts = footerOpt.get().eventCounts();
            for (int i = 0; i < required.size(); i++) {
                totals[i] += counts.getOrDefault(required.get(i), 0L);
            }
        }
        for (long t : totals) {
            if (t > 0) return false;
        }
        return true;
    }

    /**
     * Read the inputs once, retaining only events whose fully-qualified type is in {@code
     * required}, and capture the recording's full type table as a name → {@code @Label} map. The
     * label map covers every event type present in the recording — including types with zero events
     * (e.g. {@code jdk.FileForce}) — which is why it is taken from the stream's type collection
     * rather than the read events; the {@code active-settings} view needs a target type's label
     * even when that type emitted no event.
     */
    private ReadEvents readRequiredEvents(List<String> required) throws Exception {
        Set<String> want = new HashSet<>(required);
        var jfrReader =
                CombiningJFRReader.fromPaths(
                        inputs(),
                        eventFilterOptionMixin.createFilter(),
                        !eventFilterOptionMixin.noReconstitution(),
                        // Lazy materialization: a named view reads only a handful of fields, so
                        // don't
                        // eagerly decode every event's reference tree (stack traces, methods,
                        // classes).
                        // ReadStruct.get() decodes references on demand; unread fields are never
                        // built.
                        true,
                        new me.bechberger.condensed.stats.NoopStatistic(),
                        want);
        List<ReadStruct> events = new ArrayList<>();
        ReadStruct struct;
        while ((struct = jfrReader.readNextEvent()) != null) {
            if (want.contains(struct.getType().getName())) {
                events.add(struct);
            }
        }
        return new ReadEvents(events, typeLabels(jfrReader));
    }

    /**
     * Build a type-name → {@code @Label} map for the native view layer. The authoritative source is
     * each input's {@code .cjfr} footer, whose {@code eventTypeLabels} covers every event type in
     * the recording — including zero-event types like {@code jdk.FileForce}, whose struct type is
     * never written to the stream. The stream's own type collection is a fallback for inputs
     * without that footer field (older files, or a raw {@code .jfr} condensed on the fly, whose
     * in-memory footer is not persisted): each condensed struct type stores its description as a
     * compact JSON array {@code ["Label","Description",…]}, from which {@link
     * me.bechberger.jfr.cli.query.NativeView#typeLabelOf} extracts the label.
     */
    private Map<String, String> typeLabels(CombiningJFRReader jfrReader) {
        Map<String, String> labels = new java.util.HashMap<>();
        var types = jfrReader.getInputStream().getTypeCollection().getTypes();
        for (var t : types) {
            String name = t.getName();
            if (name == null || labels.containsKey(name)) {
                continue;
            }
            labels.put(name, NativeView.typeLabelOf(t.getDescription(), name));
        }
        // Footer labels win: they include zero-event types and carry the real @Label verbatim.
        for (Path input : inputs()) {
            Optional<CJFRFooter> footerOpt = CJFRFooterReader.tryRead(input);
            if (footerOpt.isPresent()) {
                labels.putAll(footerOpt.get().eventTypeLabels());
            }
        }
        return labels;
    }

    /** Events retained for a native view plus the recording's type-name → {@code @Label} map. */
    private record ReadEvents(List<ReadStruct> events, Map<String, String> typeLabels) {}

    /** Print the "no event of type X" diagnostic with a did-you-mean list. */
    private Integer reportNoEventType(String eventName, Set<String> seenTypes) {
        System.err.println("No event of type " + eventName + " found.");
        var candidates = new LinkedHashSet<String>(NativeView.viewNames());
        candidates.addAll(seenTypes);
        if (candidates.isEmpty()) {
            System.err.println("No events found at all.");
        } else {
            System.err.println("Did you mean one of these:");
            candidates.stream()
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

    /** Native rendering of the collected matching events, honoring --json/--limit/--width/etc. */
    private Integer renderMatches(MatchResult matches) {
        var matchingEvents = new ArrayList<>(matches.events());
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
                                    effectiveWidth(),
                                    effectiveCellHeight(),
                                    TruncateMode.fromCliValue(truncate)));
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
        return 0;
    }

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
        var type = event.getType();
        for (var key : type.getFieldNames()) {
            Object value = event.get(key);
            // Some int fields use Integer.MIN_VALUE as a "not applicable" sentinel, e.g.
            // OldObjectSample.arrayElements ("... or minimum value for the type int if it is not
            // an array"). Emit JSON null (the idiomatic N/A) instead of the raw -2147483648, so
            // JSON consumers don't read it as a real array count. Matches `jfr print`'s "N/A"
            // and the table view's SentinelIntegerColumn.
            if (value instanceof Number n && n.longValue() == Integer.MIN_VALUE) {
                var field = type.getField(key);
                var desc = field == null ? null : field.description();
                if (desc != null && desc.contains("minimum value for the type int")) {
                    result.put(key, null);
                    continue;
                }
            }
            result.put(key, convertValue(value));
        }
        return result;
    }

    /**
     * JMC-dependent implementation for named views: inflate any .cjfr inputs to temporary .jfr
     * files and delegate to the JDK {@code jfr view} so the full set of curated views is available
     * on condensed recordings. Loaded via reflection by {@link CLIUtils#callImpl}.
     */
    @JMCDependent
    public static class Impl {
        public static Integer run(ViewCommand cmd) throws Exception {
            String viewName = cmd.viewName();

            // Resolve every input to a real .jfr path: .jfr passes through, .cjfr is inflated to a
            // temp file (deleted on JVM exit).
            List<Path> jfrInputs = new ArrayList<>();
            var converter = new ExistingCJFROrJFRFileOrZipOrFolderConverter();
            for (String s : cmd.inputArgs()) {
                Path resolved = converter.convert(s);
                if (s.endsWith(".jfr")) {
                    jfrInputs.add(resolved);
                } else {
                    var reader =
                            CombiningJFRReader.fromPaths(
                                    List.of(resolved),
                                    cmd.eventFilterOptionMixin.createFilter(),
                                    !cmd.eventFilterOptionMixin.noReconstitution());
                    Path tmp = me.bechberger.jfr.WritingJFRReader.toJFRFile(reader);
                    tmp.toFile().deleteOnExit();
                    jfrInputs.add(tmp);
                }
            }

            return delegateToJfrView(cmd, viewName, jfrInputs);
        }

        /**
         * Invoke the JDK {@code $JAVA_HOME/bin/jfr view <view> [opts] <file...>}.
         *
         * <p>Output is captured rather than inherited so we can detect jfr's "could not find a view
         * or an event type" case: jfr reports it on stderr but still exits 0, which would mask the
         * failure. When that happens the dot-free name was neither a present event type (we already
         * checked) nor a jfr named view, i.e. almost certainly a typo'd event name — so we suppress
         * jfr's flat message, print our own did-you-mean list, and return exit 1. Otherwise jfr's
         * output/stderr are forwarded verbatim and its exit code is returned.
         */
        private static Integer delegateToJfrView(ViewCommand cmd, String viewName, List<Path> files)
                throws Exception {
            if (Runtime.version().feature() < 21) {
                System.err.println(
                        "Error: 'cjfr view' for named views requires JDK 21 or later"
                                + " (running on JDK "
                                + Runtime.version().feature()
                                + "). Please re-run with a JDK 21+ installation.");
                return 2;
            }
            Path jfrBin = jfrBinary();
            if (jfrBin == null) {
                System.err.println(
                        "Error: could not locate the JDK 'jfr' tool (needed to render named views)."
                                + " Ensure JAVA_HOME points to a JDK that includes bin/jfr.");
                return 1;
            }
            List<String> command = new ArrayList<>();
            command.add(jfrBin.toString());
            command.add("view");
            if (cmd.verbose) {
                command.add("--verbose");
            }
            // Only forward --width/--cell-height when the user set them, so jfr falls back to its
            // own per-view defaults otherwise (mirrors `jfr view` exactly for the common case).
            if (cmd.width != -1) {
                command.add("--width");
                command.add(Integer.toString(cmd.width));
            }
            if (cmd.cellHeight != -1) {
                command.add("--cell-height");
                command.add(Integer.toString(cmd.cellHeight));
            }
            command.add("--truncate");
            command.add(cmd.truncate);
            command.add(viewName);
            for (Path f : files) {
                command.add(f.toString());
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(process.getInputStream().readAllBytes());
            String err = new String(process.getErrorStream().readAllBytes());
            int exit = process.waitFor();

            // jfr couldn't resolve the name: neither a curated view nor an event type it knows.
            // Since our native scan already found no matching event type either, treat it as a
            // mistyped event name and show the friendlier did-you-mean (jfr itself exits 0 here).
            if (err.contains("Could not find a view or an event type named")) {
                return cmd.reportNoEventType(viewName, cmd.lastSeenTypes);
            }

            System.out.print(out);
            System.err.print(err);
            return exit;
        }

        private static Path jfrBinary() {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                String exe =
                        System.getProperty("os.name", "").toLowerCase().contains("win")
                                ? "jfr.exe"
                                : "jfr";
                Path candidate = Path.of(javaHome, "bin", exe);
                if (java.nio.file.Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
