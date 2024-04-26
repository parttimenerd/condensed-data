package me.bechberger.jfr;

import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.Message.ReadInstance;
import me.bechberger.condensed.ReadStruct;
import org.jetbrains.annotations.Nullable;

/** Read JFR data from a {@link CondensedInputStream} which was written by {@link BasicJFRWriter} */
public class BasicJFRReader {

    private final CondensedInputStream in;
    private Configuration configuration = Configuration.DEFAULT;
    private Universe universe = new Universe();
    private boolean closed = false;

    public BasicJFRReader(CondensedInputStream in) {
        this.in = in;
    }

    public @Nullable ReadStruct readNextEvent() {
        if (closed) {
            return null;
        }
        var msg = in.readNextInstance();
        if (msg == null) {
            closed = true;
            return null;
        }
        while (isUniverseType(msg) || isConfigurationType(msg)) {
            if (isUniverseType(msg)) {
                processUniverse(msg);
            } else {
                processConfiguration(msg);
            }
            msg = in.readNextInstance();
            if (msg == null) {
                closed = true;
                return null;
            }
        }
        return (ReadStruct) msg.value();
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
}
