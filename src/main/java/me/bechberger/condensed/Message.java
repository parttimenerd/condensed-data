package me.bechberger.condensed;

import me.bechberger.condensed.types.CondensedType;

/** Messages on the {@link CondensedOutputStream} and {@link CondensedInputStream} */
public sealed interface Message {

    /** Defines a type */
    record CondensedTypeMessage(CondensedType<?, ?> type) implements Message {}

    /** Starts the stream */
    record StartMessage(int version, String generatorName, String generatorVersion)
            implements Message {
        /** Used mainly for testing purposes */
        public static final StartMessage DEFAULT = new StartMessage("Unknown", "Unknown Version");

        StartMessage(String generatorName, String generatorVersion) {
            this(Constants.VERSION, generatorName, generatorVersion);
        }
    }

    /** Regular message */
    record ReadInstance<T, R>(CondensedType<T, R> type, R value) implements Message {}
}
