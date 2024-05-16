package me.bechberger.condensed.types;

import static me.bechberger.condensed.types.TypeCollection.FLOAT_ID;

import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

/** A floating point type */
// TODO: fix, as Float16 isn't useful for Memory yet, as largest normal number is 65504.0, use
// bfloat16 instead
// TODO: define own float16 and float8 type, and maybe varfloat16 (like varint but based on floats)
public class FloatType extends CondensedType<Float, Float> {

    public enum Type {
        FLOAT32(32),
        /** Same range as float32 but less precision */
        BFLOAT16(16);

        public final int width;

        Type(int width) {
            this.width = width;
        }

        public static Type fromId(int id) {
            return values()[id];
        }

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    private final Type type;

    public FloatType(int id, String name, String description, Type type) {
        super(id, name, description);
        this.type = type;
    }

    public FloatType(int id, String name, String description) {
        this(id, name, description, Type.FLOAT32);
    }

    public FloatType(int id) {
        this(id, "float", "A floating point number", Type.FLOAT32);
    }

    public FloatType(int id, Type type) {
        this(id, type.toString(), "", type);
    }

    @Override
    public SpecifiedType<FloatType> getSpecifiedType() {
        return SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, Float value) {
        switch (type) {
            case FLOAT32 -> out.writeFloat(value);
            case BFLOAT16 -> out.writeBFloat16(value);
        }
    }

    @Override
    public Float readFrom(CondensedInputStream in) {
        return switch (type) {
            case FLOAT32 -> in.readFloat();
            case BFLOAT16 -> in.readBFloat16();
        };
    }

    public static final SpecifiedType<FloatType> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return FLOAT_ID;
                }

                @Override
                public String name() {
                    return "float";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, FloatType typeInstance) {
                    out.writeSingleByte(typeInstance.type.ordinal());
                }

                @Override
                public FloatType readInnerTypeSpecification(
                        CondensedInputStream in, int id, String name, String description) {
                    return (FloatType)
                            in.getTypeCollection()
                                    .addType(
                                            new FloatType(
                                                    id,
                                                    name,
                                                    description,
                                                    Type.fromId((int) in.readUnsignedLong(1))));
                }

                @Override
                public FloatType getDefaultType(int id) {
                    return new FloatType(id);
                }

                @Override
                public boolean isPrimitive() {
                    return true;
                }
            };
}
