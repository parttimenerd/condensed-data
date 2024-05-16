package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.INT_ID;

import java.util.Objects;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;

/** A fixed width integer type */
public class IntType extends CondensedType<Long, Long> {
    /** Width in bytes */
    private final int width;

    private final boolean signed;
    private final OverflowMode overflowMode;

    public IntType(
            int id,
            String name,
            String description,
            int width,
            boolean signed,
            OverflowMode overflowMode) {
        super(id, name, description);
        this.width = width;
        this.signed = signed;
        this.overflowMode = overflowMode;
    }

    public IntType(int id, int width, boolean signed, OverflowMode overflowMode) {
        this(
                id,
                (signed ? "" : "u") + "int" + width,
                "A " + (signed ? "signed" : "unsigned") + " integer with " + width + " bytes",
                width,
                signed,
                overflowMode);
    }

    /** Creates the default signed int32 type */
    public IntType(int id) {
        this(id, 4, true, OverflowMode.ERROR);
    }

    @Override
    public SpecifiedType<IntType> getSpecifiedType() {
        return SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, Long value) {
        Objects.requireNonNull(value, "Value must not be null");
        if (signed) {
            out.writeSignedLong(value, width, overflowMode);
        } else {
            out.writeUnsignedLong(value, width, overflowMode);
        }
    }

    @Override
    public Long readFrom(CondensedInputStream in) {
        if (signed) {
            return in.readSignedLong(width);
        } else {
            return in.readUnsignedLong(width);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IntType other)) {
            return false;
        }
        return super.equals(obj)
                && width == other.width
                && signed == other.signed
                && overflowMode == other.overflowMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), width, signed, overflowMode);
    }

    /** The specified type for {@link IntType} */
    public static final SpecifiedType<IntType> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return INT_ID;
                }

                @Override
                public String name() {
                    return "int";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, IntType typeInstance) {
                    out.writeSingleByte(typeInstance.width);
                    out.writeFlags(
                            typeInstance.signed, typeInstance.overflowMode == OverflowMode.ERROR);
                }

                @Override
                public IntType readInnerTypeSpecification(
                        CondensedInputStream in, int id, String name, String description) {
                    int width = (int) in.readUnsignedLong(1);
                    boolean[] flags = in.readFlags();
                    return (IntType)
                            in.getTypeCollection()
                                    .addType(
                                            new IntType(
                                                    id,
                                                    width,
                                                    flags[0],
                                                    flags[1]
                                                            ? OverflowMode.ERROR
                                                            : OverflowMode.SATURATE));
                }

                @Override
                public IntType getDefaultType(int id) {
                    if (id != id()) {
                        throw new IllegalArgumentException("Wrong id " + id);
                    }
                    return new IntType(id);
                }

                @Override
                public boolean isPrimitive() {
                    return true;
                }
            };

    @Override
    public String toString() {
        return "IntType(id="
                + getId()
                + ", name="
                + getName()
                + ", description="
                + getDescription()
                + ", width="
                + width
                + ", signed="
                + signed
                + ", overflowMode="
                + overflowMode
                + ")";
    }
}
