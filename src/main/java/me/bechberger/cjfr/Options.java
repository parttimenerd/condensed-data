package me.bechberger.cjfr;

import java.util.Set;
import java.util.function.Predicate;
import me.bechberger.jfr.BasicJFRReader;
import org.jetbrains.annotations.Nullable;

/** Configuration for opening a {@link CJFRFile} or {@link CJFRFiles}. */
public final class Options {

    static final Options DEFAULT = new Options(true, true, null);

    final boolean reconstitute;
    final boolean verifyIntegrity;
    final @Nullable Predicate<String> eventTypeFilter;

    private Options(
            boolean reconstitute,
            boolean verifyIntegrity,
            @Nullable Predicate<String> eventTypeFilter) {
        this.reconstitute = reconstitute;
        this.verifyIntegrity = verifyIntegrity;
        this.eventTypeFilter = eventTypeFilter;
    }

    public static Options defaults() {
        return DEFAULT;
    }

    /**
     * Control whether combined events are reconstituted into their original individual events.
     * Default is {@code true}.
     */
    public Options withReconstitution(boolean reconstitute) {
        return new Options(reconstitute, this.verifyIntegrity, this.eventTypeFilter);
    }

    /**
     * Skip the CRC32 integrity check on the main stream. Useful for reading truncated or
     * in-progress recordings.
     */
    public Options skipIntegrityCheck() {
        return new Options(this.reconstitute, false, this.eventTypeFilter);
    }

    /**
     * Only yield events whose type name matches the given predicate. For example:
     *
     * <pre>{@code
     * Options.defaults().withEventFilter(name -> name.startsWith("jdk.GC"))
     * }</pre>
     */
    public Options withEventFilter(Predicate<String> eventTypeFilter) {
        return new Options(this.reconstitute, this.verifyIntegrity, eventTypeFilter);
    }

    /**
     * Only yield events whose type name is in the given set. Convenience for {@link
     * #withEventFilter(Predicate)}.
     */
    public Options withEventTypes(Set<String> eventTypes) {
        return withEventFilter(eventTypes::contains);
    }

    BasicJFRReader.Options toReaderOptions() {
        return BasicJFRReader.Options.DEFAULT.withReconstitute(reconstitute);
    }

    boolean acceptsType(String typeName) {
        return eventTypeFilter == null || eventTypeFilter.test(typeName);
    }
}
