package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.BOOLEAN_ID;

import java.util.Objects;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

public class BooleanType extends CondensedType<Boolean, Boolean> {

    public BooleanType(int id, String name, String description) {
        super(id, name, description);
    }

    public BooleanType(int id) {
        this(id, "boolean", "A boolean value");
    }

    @Override
    public SpecifiedType<BooleanType> getSpecifiedType() {
        return SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, Boolean value) {
        out.write(value ? 1 : 0);
    }

    @Override
    public Boolean readFrom(CondensedInputStream in) {
        return in.read() != 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof BooleanType && super.equals(obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getName(), getDescription());
    }

    public static final SpecifiedType<BooleanType> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return BOOLEAN_ID;
                }

                @Override
                public String name() {
                    return "boolean";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, BooleanType typeInstance) {}

                @Override
                public BooleanType readInnerTypeSpecification(
                        CondensedInputStream in, int id, String name, String description) {
                    return (BooleanType)
                            in.getTypeCollection().addType(new BooleanType(id, name, description));
                }

                @Override
                public BooleanType getDefaultType(int id) {
                    return new BooleanType(id);
                }

                @Override
                public boolean isPrimitive() {
                    return true;
                }
            };
}
