package me.bechberger.condensed.types;

import java.util.Objects;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

/** A variable width integer type */
public class VarIntType extends CondensedType<Long> {

    private final boolean signed;

    public VarIntType(int id, String name, String description, boolean signed) {
        super(id, name, description);
        this.signed = signed;
    }

    public VarIntType(int id, boolean signed) {
        this(
                id,
                (signed ? "" : "u") + "varint",
                "A " + (signed ? "signed" : "unsigned") + " variable width integer",
                signed);
    }

    /** Creates the default signed varint type */
    public VarIntType(int id) {
        this(id, true);
    }

    public boolean isSigned() {
        return signed;
    }

    @Override
    public SpecifiedType<VarIntType> getSpecifiedType() {
        return SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, Long value) {
        Objects.requireNonNull(value, "Value must not be null");
        if (signed) {
            out.writeSignedVarInt(value);
        } else {
            out.writeUnsignedVarInt(value);
        }
    }

    @Override
    public Long readFrom(CondensedInputStream in) {
        if (signed) {
            return in.readSignedVarint();
        } else {
            return in.readUnsignedVarint();
        }
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && signed == ((VarIntType) obj).signed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), signed);
    }

    public static final SpecifiedType<VarIntType> SPECIFIED_TYPE =
            new SpecifiedType<>() {

                @Override
                public int id() {
                    return 1;
                }

                @Override
                public String name() {
                    return "VarInt";
                }

                @Override
                public VarIntType getDefaultType(int id) {
                    return new VarIntType(id);
                }

                @Override
                public boolean isPrimitive() {
                    return true;
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, VarIntType type) {
                    out.writeFlags(type.signed);
                }

                @Override
                public VarIntType readInnerTypeSpecification(
                        CondensedInputStream in, String name, String description) {
                    return in.getTypeCollection()
                            .addType(
                                    id -> new VarIntType(id, name, description, in.readFlags()[0]));
                }
            };

    @Override
    public String toString() {
        return "VarIntType{"
                + "id="
                + getId()
                + ", name='"
                + getName()
                + '\''
                + ", description='"
                + getDescription()
                + '\''
                + ", signed="
                + signed
                + '}';
    }
}
