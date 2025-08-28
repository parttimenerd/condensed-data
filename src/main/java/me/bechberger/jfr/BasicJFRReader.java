package me.bechberger.jfr;

import java.time.Instant;
import java.util.*;
import me.bechberger.JFRReader;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.ReadInstance;
import me.bechberger.condensed.Message.StartMessage;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.Reductions;
import me.bechberger.jfr.JFREventCombiner.JFREventReadStructReconstitutor;
import org.jetbrains.annotations.Nullable;

/** Read JFR data from a {@link CondensedInputStream} which was written by {@link BasicJFRWriter} */
public class BasicJFRReader implements JFRReader {

    private final CondensedInputStream in;
    private Configuration configuration = Configuration.DEFAULT;
    private final Universe universe = new Universe();
    private final @Nullable JFREventReadStructReconstitutor reconstitutor;
    private final Queue<ReadStruct> eventsToEmit = new ArrayDeque<>();
    private final Map<String, Integer> combinedEventCount = new HashMap<>();
    private boolean closed = false;

    public BasicJFRReader(CondensedInputStream in) {
        this(in, true);
    }

    public BasicJFRReader(CondensedInputStream in, boolean reconstitute) {
        this.in = in;
        this.reconstitutor = reconstitute ? new JFREventReadStructReconstitutor(in) : null;
    }

    private ReadInstance<?, ?> alreadReadNextInstance = null;

    public void readTillFirstEvent() {
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
    }

    @Override
    public @Nullable ReadStruct readNextEvent() {
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
        event.ensureRecursivelyComplete();
        if (reconstitutor != null && reconstitutor.isCombinedEvent(event)) {
            combinedEventCount.put(
                    event.getType().getName(),
                    combinedEventCount.getOrDefault(event.getType().getName(), 0) + 1);
            eventsToEmit.addAll(reconstitutor.reconstitute(getInputStream(), event));
            return readNextEvent(); // comes in handy when the combined events
        }
        return event;
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
                TypeUtil.createInstanceFromReadStruct(Universe.class, (ReadStruct) msg.value()));
    }

    private void processConfiguration(ReadInstance<?, ?> msg) {
        configuration =
                TypeUtil.createInstanceFromReadStruct(
                        Configuration.class, (ReadStruct) msg.value());
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
}