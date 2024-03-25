package me.bechberger.condensed;

import me.bechberger.condensed.types.CondensedType;

public sealed interface Message {
    record CondensedTypeMessage(CondensedType<?> type) implements Message {}

    record StartMessage(int version, String generatorName, String generatorVersion)
            implements Message {
        /** Used mainly for testing purposes */
        static final StartMessage DEFAULT = new StartMessage("Unknown", "Unknown Version");

        StartMessage(String generatorName, String generatorVersion) {
            this(Constants.VERSION, generatorName, generatorVersion);
        }
    }

    record Instance<T>(CondensedType<T> type, T value) implements Message {}
}
