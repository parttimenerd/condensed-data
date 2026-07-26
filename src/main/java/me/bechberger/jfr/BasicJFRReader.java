package me.bechberger.jfr;

import java.time.Instant;
import java.util.*;
import me.bechberger.JFRReader;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.ReadInstance;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.RIOException;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.stats.Statistic;
import me.bechberger.condensed.types.Reductions;
import me.bechberger.jfr.JFREventCombiner.JFREventReadStructReconstitutor;
import org.jetbrains.annotations.Nullable;

/** Read JFR data from a {@link CondensedInputStream} which was written by {@link BasicJFRWriter} */
public class BasicJFRReader implements JFRReader {

    private final CondensedInputStream in;
    private Configuration configuration = Configuration.DEFAULT;
    private final Universe universe = new Universe();
    private final @Nullable JFREventReadStructReconstitutor reconstitutor;

    /**
     * Ignore {@link RIOException.UnexpectedEOFException}, this might be due to the condensed not
     * being finished
     */
    private final boolean ignoreCloseErrors;

    /** See {@link Options#reconstituteOnlyEventTypes}. */
    private final @Nullable Set<String> reconstituteOnlyEventTypes;

    private final Queue<ReadStruct> eventsToEmit = new ArrayDeque<>();
    private final Map<String, Integer> combinedEventCount = new HashMap<>();
    private boolean closed = false;
    private boolean isTruncated = false;

    public static class Options {
        final boolean reconstitute;
        final boolean ignoreCloseErrors;
        final boolean skipRecursiveCompletion;

        /**
         * If non-null, combined events are only reconstituted when their output event type is in
         * this set; combined events producing an unneeded type are dropped BEFORE the expensive
         * expand. Null = reconstitute everything (default).
         */
        final @Nullable Set<String> reconstituteOnlyEventTypes;

        public static Options DEFAULT = new Options(true, true, false, null);

        private Options(
                boolean reconstitute,
                boolean ignoreCloseErrors,
                boolean skipRecursiveCompletion,
                @Nullable Set<String> reconstituteOnlyEventTypes) {
            this.reconstitute = reconstitute;
            this.ignoreCloseErrors = ignoreCloseErrors;
            this.skipRecursiveCompletion = skipRecursiveCompletion;
            this.reconstituteOnlyEventTypes = reconstituteOnlyEventTypes;
        }

        public Options withReconstitute(boolean reconstitute) {
            return new Options(
                    reconstitute,
                    this.ignoreCloseErrors,
                    this.skipRecursiveCompletion,
                    this.reconstituteOnlyEventTypes);
        }

        public Options withIgnoreCloseErrors(boolean ignoreCloseErrors) {
            return new Options(
                    this.reconstitute,
                    ignoreCloseErrors,
                    this.skipRecursiveCompletion,
                    this.reconstituteOnlyEventTypes);
        }

        public Options withSkipRecursiveCompletion(boolean skipRecursiveCompletion) {
            return new Options(
                    this.reconstitute,
                    this.ignoreCloseErrors,
                    skipRecursiveCompletion,
                    this.reconstituteOnlyEventTypes);
        }

        public Options withReconstituteOnlyEventTypes(
                @Nullable Set<String> reconstituteOnlyEventTypes) {
            return new Options(
                    this.reconstitute,
                    this.ignoreCloseErrors,
                    this.skipRecursiveCompletion,
                    reconstituteOnlyEventTypes);
        }
    }

    public BasicJFRReader(CondensedInputStream in) {
        this(in, Options.DEFAULT);
    }

    public BasicJFRReader(CondensedInputStream in, Options options) {
        this.in = in;
        this.reconstitutor = options.reconstitute ? new JFREventReadStructReconstitutor(in) : null;
        this.ignoreCloseErrors = options.ignoreCloseErrors;
        this.reconstituteOnlyEventTypes = options.reconstituteOnlyEventTypes;
        in.setSkipRecursiveCompletion(options.skipRecursiveCompletion);
    }

    public void enableFullStatistics() {
        in.enableFullStatistics();
    }

    public void setStatistics(Statistic statistics) {
        in.setStatistics(statistics);
    }

    public Statistic getStatistics() {
        return in.getStatistics();
    }

    private ReadInstance<?, ?> alreadReadNextInstance = null;

    public void readTillFirstEvent() {
        try {
            ReadInstance<?, ?> msg;
            while ((msg = in.readNextInstance()) != null) {
                if (isUniverseType(msg)) {
                    processUniverse(msg);
                } else if (isConfigurationType(msg)) {
                    processConfiguration(msg);
                } else {
                    // We reached the first event
                    in.setReductions(new JFRReduction.JFRReductions(configuration, universe));
                    alreadReadNextInstance = msg;
                    return;
                }
            }
        } catch (RIOException.UnexpectedEOFException e) {
            isTruncated = true;
            if (ignoreCloseErrors) {
                closed = true;
            } else {
                throw e;
            }
        } catch (RIOException e) {
            if (ignoreCloseErrors) {
                isTruncated = true;
                closed = true;
            } else {
                throw e;
            }
        }
    }

    @Override
    public @Nullable ReadStruct readNextEvent() {
        try {
            ReadInstance<?, ?> msg = alreadReadNextInstance;
            if (alreadReadNextInstance == null) {
                if (!eventsToEmit.isEmpty()) {
                    return eventsToEmit.poll();
                }
                if (closed) {
                    return null;
                }
                msg = in.readNextInstance();
                if (msg == null) {
                    closed = true;
                    return null;
                }
            } else {
                alreadReadNextInstance = null; // reset for next call
            }
            while (isUniverseType(msg) || isConfigurationType(msg)) {
                if (isUniverseType(msg)) {
                    processUniverse(msg);
                } else {
                    processConfiguration(msg);
                }
                if (in.getReductions() == Reductions.NONE) {
                    in.setReductions(new JFRReduction.JFRReductions(configuration, universe));
                }
                msg = in.readNextInstance();
                if (msg == null) {
                    closed = true;
                    return null;
                }
            }
            if (configuration == null) {
                throw new IllegalStateException(
                        "Configuration and Universe must be read before events");
            }
            var event = (ReadStruct) msg.value();
            if (reconstitutor != null && reconstitutor.isCombinedEvent(event)) {
                // Skip the expensive expand when the events this combined struct would produce are
                // a
                // type the caller doesn't need (e.g. a named view whose FROM excludes this type).
                if (reconstituteOnlyEventTypes != null) {
                    String outputType = reconstitutor.outputEventTypeName(event);
                    if (outputType != null && !reconstituteOnlyEventTypes.contains(outputType)) {
                        return readNextEvent();
                    }
                }
                combinedEventCount.put(
                        event.getType().getName(),
                        combinedEventCount.getOrDefault(event.getType().getName(), 0) + 1);
                eventsToEmit.addAll(reconstitutor.reconstitute(getInputStream(), event));
                return readNextEvent(); // comes in handy when the combined events
            }
            return event;
        } catch (RIOException.UnexpectedEOFException e) {
            isTruncated = true;
            if (ignoreCloseErrors) {
                closed = true;
                return null;
            } else {
                throw e;
            }
        } catch (RIOException e) {
            if (ignoreCloseErrors) {
                isTruncated = true;
                closed = true;
                return null;
            } else {
                throw e;
            }
        }
    }

    public boolean isTruncated() {
        return isTruncated;
    }

    public Map<String, Integer> getCombinedEventCount() {
        return Collections.unmodifiableMap(combinedEventCount);
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    private static boolean isUniverseType(ReadInstance<?, ?> msg) {
        return msg.type().getName().equals(Universe.class.getCanonicalName());
    }

    private static boolean isConfigurationType(ReadInstance<?, ?> msg) {
        return msg.type().getName().equals(Configuration.class.getCanonicalName());
    }

    private void processUniverse(ReadInstance<?, ?> msg) {
        universe.update(
                ReadStructUtil.createInstanceFromReadStruct(
                        Universe.class, ((ReadStruct) msg.value()).ensureComplete()));
    }

    private void processConfiguration(ReadInstance<?, ?> msg) {
        configuration =
                ReadStructUtil.createInstanceFromReadStruct(
                        Configuration.class, ((ReadStruct) msg.value()).ensureComplete());
    }

    public Universe getUniverse() {
        return universe;
    }

    @Override
    public CondensedInputStream getInputStream() {
        return in;
    }

    @Override
    public StartMessage getStartMessage() {
        return getInputStream().getUniverse().getStartMessage();
    }

    @Override
    public Instant getStartTime() {
        return Instant.ofEpochSecond(0, getUniverse().getStartTimeNanos());
    }

    @Override
    public Instant getEndTime() {
        return Instant.ofEpochSecond(0, getUniverse().getLastStartTimeNanos());
    }

    @Override
    public long getGmtOffsetMillis() {
        return getUniverse().getGmtOffsetMillis();
    }
}
