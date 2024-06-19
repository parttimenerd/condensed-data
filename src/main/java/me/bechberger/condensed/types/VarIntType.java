package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.VAR_INT_ID;

import java.util.Objects;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

/** A variable width integer type */
public class VarIntType extends CondensedType<Long, Long> {

    private final boolean signed;

    /** Value is stored scaled */
    private final long multiplier;

    public VarIntType(int id, String name, String description, boolean signed, long multiplier) {
        super(id, name, description);
        this.signed = signed;
        this.multiplier = multiplier;
    }

    public VarIntType(int id, String name, String description, boolean signed) {
        this(id, name, description, signed, 1);
    }

    public VarIntType(int id, boolean signed) {
        this(
                id,
                (signed ? "" : "u") + "varint",
                "A " + (signed ? "signed" : "unsigned") + " variable width integer",
                signed);
    }

    public VarIntType(int id, boolean signed, long multiplier) {
        this(
                id,
                (signed ? "" : "u") + "varint",
                "A "
                        + (signed ? "signed" : "unsigned")
                        + " variable width integer"
                        + (multiplier != 1 ? " with multiplier " + multiplier : ""),
                signed,
                multiplier);
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
            out.writeSignedVarInt(value / multiplier);
        } else {
            out.writeUnsignedVarInt(value / multiplier);
        }
    }

    @Override
    public Long readFrom(CondensedInputStream in) {
        if (signed) {
            return in.readSignedVarint() * multiplier;
        } else {
            return in.readUnsignedVarint() * multiplier;
        }
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && signed == ((VarIntType) obj).signed
                && multiplier == ((VarIntType) obj).multiplier;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), signed, multiplier);
    }

    public static final SpecifiedType<VarIntType> SPECIFIED_TYPE =
            new SpecifiedType<>() {

                @Override
                public int id() {
                    return VAR_INT_ID;
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
                    out.writeUnsignedVarInt(type.multiplier);
                }

                @Override
                public VarIntType readInnerTypeSpecification(
                        CondensedInputStream in, int id, String name, String description) {
                    return (VarIntType)
                            in.getTypeCollection()
                                    .addType(
                                            new VarIntType(
                                                    id,
                                                    name,
                                                    description,
                                                    in.readFlags()[0],
                                                    in.readUnsignedVarint()));
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
                + (multiplier != 1 ? ", multiplier=" + multiplier : "")
                + '}';
    }
}
