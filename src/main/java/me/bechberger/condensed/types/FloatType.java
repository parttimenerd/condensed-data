package me.bechberger.condensed.types;

import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

/** A floating point type */
public class FloatType extends CondensedType<Float, Float> {

    public FloatType(int id, String name, String description) {
        super(id, name, description);
    }

    public FloatType(int id) {
        this(id, "float", "A floating point number");
    }

    @Override
    public SpecifiedType<FloatType> getSpecifiedType() {
        return SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, Float value) {
        out.writeFloat(value);
    }

    @Override
    public Float readFrom(CondensedInputStream in) {
        return in.readFloat();
    }

    public static final SpecifiedType<FloatType> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return 2;
                }

                @Override
                public String name() {
                    return "float";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, FloatType typeInstance) {}

                @Override
                public FloatType readInnerTypeSpecification(
                        CondensedInputStream in, String name, String description) {
                    return in.getTypeCollection()
                            .addType(id -> new FloatType(id, name, description));
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
