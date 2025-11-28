package me.bechberger.condensed.stats;

import java.util.Objects;
import me.bechberger.condensed.types.CondensedType;

public sealed interface WriteCause {
    String getName();

    record SingleWriteCause(String name) implements WriteCause {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj
                    || (obj != null
                            && getClass() == obj.getClass()
                            && Objects.equals(name, ((SingleWriteCause) obj).name));
        }
    }

    record TypeWriteCause(CondensedType<?, ?> type) implements WriteCause {
        @Override
        public String getName() {
            return type.getName();
        }

        @Override
        public int hashCode() {
            return Objects.hash(type);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TypeWriteCause other = (TypeWriteCause) obj;
            return Objects.equals(type, other.type);
        }
    }

    public static final WriteCause Start = new SingleWriteCause("Start");
    public static final WriteCause TypeSpecification = new SingleWriteCause("TypeSpecification");
    public static final WriteCause String = new SingleWriteCause("String");
}