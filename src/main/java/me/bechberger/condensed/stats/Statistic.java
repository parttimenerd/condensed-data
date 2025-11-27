package me.bechberger.condensed.stats;

import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StringType;
import org.jetbrains.annotations.NotNull;

import java.util.Stack;

/**
 * Statistics about the written data
 */
public class Statistic {
    private final SubStatistic customTypes = new SubStatistic(WriteMode.TYPE);
    private final SubStatistic instanceMessages = new SubStatistic(WriteMode.INSTANCE);
    private final SubStatistic other = new SubStatistic(WriteMode.OTHER);

    {
        other.count = 1;
    }

    /**
     * Type context for the current write
     */
    private final Stack<CondensedType<?, ?>> typeStack = new Stack<>();

    private int bytes = 0;
    private WriteMode mode = WriteMode.OTHER;

    private EventWriteTree context = EventWriteTree.createRoot();

    @Override
    public String toString() {
        return "Statistic{"
               + "customTypes="
               + customTypes
               + ", instanceMessages="
               + instanceMessages
               + ", other="
               + other
               + ", bytes="
               + bytes
               + '}';
    }

    public String toPrettyString() {
        // print as aligned table, columns: mode, count, bytes, strings, stringBytes, last row
        // is total, first row is header
        return String.format(
                """
                        %-10s %10s %10s %10s %10s %10s %10s %10s
                        %-10s %10d %10d %10d %10d %10d %10d %10d
                        %-10s %10d %10d %10d %10d %10d %10d %10d
                        %-10s %10d %10d %10d %10d %10d %10d %10d
                        %-10s %10d %10d %10d %10d %10d %10d %10d\
                        """,
                "mode",
                "count",
                "bytes",
                "strings",
                "stringBytes",
                "<= 10",
                "<= 100",
                "<= 1000",
                "types",
                customTypes.count,
                customTypes.bytes,
                customTypes.strings,
                customTypes.stringBytes,
                customTypes.stringLe10Bytes,
                customTypes.stringLe100Bytes,
                customTypes.stringLe1000Bytes,
                "instance",
                instanceMessages.count,
                instanceMessages.bytes,
                instanceMessages.strings,
                instanceMessages.stringBytes,
                instanceMessages.stringLe10Bytes,
                instanceMessages.stringLe100Bytes,
                instanceMessages.stringLe1000Bytes,
                "other",
                other.count,
                other.bytes,
                other.strings,
                other.stringBytes,
                other.stringLe10Bytes,
                other.stringLe100Bytes,
                other.stringLe1000Bytes,
                "total",
                customTypes.count + instanceMessages.count + other.count,
                bytes,
                customTypes.strings + instanceMessages.strings + other.strings,
                customTypes.stringBytes + instanceMessages.stringBytes + other.stringBytes,
                customTypes.stringLe10Bytes
                + instanceMessages.stringLe10Bytes
                + other.stringLe10Bytes,
                customTypes.stringLe100Bytes
                + instanceMessages.stringLe100Bytes
                + other.stringLe100Bytes,
                customTypes.stringLe1000Bytes
                + instanceMessages.stringLe1000Bytes
                + other.stringLe1000Bytes);
    }

    public void setModeAndCount(WriteMode mode) {
        this.mode = mode;
        switch (mode) {
            case TYPE -> customTypes.count++;
            case INSTANCE -> instanceMessages.count++;
            case OTHER -> other.count++;
        }
    }

    private @NotNull SubStatistic getSubStatistic() {
        return switch (mode) {
            case TYPE -> customTypes;
            case INSTANCE -> instanceMessages;
            case OTHER -> other;
        };
    }

    public void record(int bytes) {
        this.bytes += bytes;
        getSubStatistic().bytes += bytes;
        context.record(bytes);
    }

    public void recordString(int bytes) {
        getSubStatistic().strings++;
        getSubStatistic().stringBytes += bytes;
        if (bytes <= 10) {
            getSubStatistic().stringLe10Bytes += bytes;
        } else if (bytes <= 100) {
            getSubStatistic().stringLe100Bytes += bytes;
        } else if (bytes <= 1000) {
            getSubStatistic().stringLe1000Bytes += bytes;
        }
    }

    public int getBytes() {
        return bytes;
    }

    public void pushWriteCauseContext(WriteCause cause) {
        context = context.getOrCreateChild(cause);
    }

    public void popWriteCauseContext() {
        context = context.getParent();
    }

    public EventWriteTree getContextRoot() {
        return context;
    }

    public static class WriteCauseContext implements AutoCloseable {
        private final Statistic statistic;

        public WriteCauseContext(Statistic statistic, WriteCause cause) {
            this.statistic = statistic;
            this.statistic.pushWriteCauseContext(cause);
        }

        @Override
        public void close() {
            this.statistic.popWriteCauseContext();
        }
    }

    public WriteCauseContext withWriteCauseContext(WriteCause cause) {
        return new WriteCauseContext(this, cause);
    }
}