package me.bechberger.condensed;

import me.bechberger.condensed.types.CondensedType;

/** Messages on the {@link CondensedOutputStream} and {@link CondensedInputStream} */
public sealed interface Message {

    /** Defines a type */
    record CondensedTypeMessage(CondensedType<?, ?> type) implements Message {}

    /** Starts the stream */
    record StartMessage(
            int version, String generatorName, String generatorVersion, Compression compression)
            implements Message {
        /** Used mainly for testing purposes */
        public static final StartMessage DEFAULT = new StartMessage("Unknown", "Unknown Version");

        StartMessage(String generatorName, String generatorVersion) {
            this(generatorName, generatorVersion, Compression.NONE);
        }

        StartMessage(String generatorName, String generatorVersion, Compression compression) {
            this(Constants.VERSION, generatorName, generatorVersion, compression);
        }

        public StartMessage compress(Compression compression) {
            return new StartMessage(version, generatorName, generatorVersion, compression);
        }
    }

    /** Regular message */
    record ReadInstance<T, R>(CondensedType<T, R> type, R value) implements Message {
        @Override
        public String toString() {
            return value instanceof ReadStruct struct ? struct.toPrettyString() : value.toString();
        }
    }
}
