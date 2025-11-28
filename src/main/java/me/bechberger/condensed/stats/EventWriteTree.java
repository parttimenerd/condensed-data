package me.bechberger.condensed.stats;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * This records how many bytes are written under the context of a specific type, supports nesting
 */
public class EventWriteTree {

    public static final WriteCause STRING_CAUSE = new WriteCause.SingleWriteCause("string");
    public static final WriteCause ROOT_CAUSE = new WriteCause.SingleWriteCause("root");
    public static final WriteCause STARTMESSAGE_CAUSE =
            new WriteCause.SingleWriteCause("StartMessage");
    public static final WriteCause TYPEDEFINITION_CAUSE =
            new WriteCause.SingleWriteCause("TypeDefinition");

    private final @Nullable EventWriteTree parent;
    private final WriteCause cause;
    private long directBytesWritten;
    private long count;

    private final Map<WriteCause, EventWriteTree> children = new HashMap<>();

    public EventWriteTree(EventWriteTree parent, WriteCause cause) {
        this.parent = Objects.requireNonNull(parent);
        this.cause = cause;
    }

    private EventWriteTree() {
        this.parent = null;
        this.cause = ROOT_CAUSE;
    }

    public static EventWriteTree createRoot() {
        return new EventWriteTree();
    }

    @Override
    public String toString() {
        if (isRoot()) {
            return "EventWriteTree{root}";
        }
        return "EventWriteTree{"
                + cause
                + ", directBytesWritten="
                + directBytesWritten
                + ", count="
                + count
                + '}';
    }

    public WriteCause getCause() {
        return cause;
    }

    public String getCauseName() {
        return cause.getName();
    }

    public EventWriteTree getOrCreateChild(WriteCause cause) {
        return children.computeIfAbsent(cause, c -> new EventWriteTree(this, c));
    }

    public @Nullable EventWriteTree getParent() {
        return parent;
    }

    public Collection<EventWriteTree> getChildren() {
        return children.values();
    }

    public long getCount() {
        return count;
    }

    public long getDirectBytesWritten() {
        return directBytesWritten;
    }

    public boolean isRoot() {
        return cause == ROOT_CAUSE;
    }

    public long computeOverallBytesWritten() {
        long total = directBytesWritten;
        for (EventWriteTree child : children.values()) {
            total += child.computeOverallBytesWritten();
        }
        return total;
    }

    public void record(long bytes) {
        this.directBytesWritten += bytes;
        this.count += 1;
    }
}
