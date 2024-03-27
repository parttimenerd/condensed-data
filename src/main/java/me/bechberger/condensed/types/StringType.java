package me.bechberger.condensed.types;

import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.condensed.CondensedOutputStream;

public class StringType extends CondensedType<String, String> {
    private static final String DEFAULT_ENCODING = "UTF-8";
    private final String encoding;

    public StringType(int id, String name, String description, String encoding) {
        super(id, name, description);
        this.encoding = encoding;
    }

    public StringType(int id, String encoding) {
        this(
                id,
                encoding.equals(DEFAULT_ENCODING) ? "string" : encoding.toLowerCase() + "-string",
                "A string of characters"
                        + (encoding.equals(DEFAULT_ENCODING) ? "" : " encoded in " + encoding),
                encoding);
    }

    public StringType(int id) {
        this(id, DEFAULT_ENCODING);
    }

    @Override
    public SpecifiedType<StringType> getSpecifiedType() {
        return SPECIFIED_TYPE;
    }

    @Override
    public void writeTo(CondensedOutputStream out, String value) {
        out.writeString(value, encoding);
    }

    @Override
    public String readFrom(CondensedInputStream in) {
        return in.readString(encoding);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && encoding.equals(((StringType) obj).encoding);
    }

    public static final SpecifiedType<StringType> SPECIFIED_TYPE =
            new SpecifiedType<>() {
                @Override
                public int id() {
                    return 3;
                }

                @Override
                public String name() {
                    return "string";
                }

                @Override
                public void writeInnerTypeSpecification(
                        CondensedOutputStream out, StringType typeInstance) {
                    out.writeString(typeInstance.encoding);
                }

                @Override
                public StringType readInnerTypeSpecification(
                        CondensedInputStream in, String name, String description) {
                    return in.getTypeCollection()
                            .addType(
                                    id ->
                                            new StringType(
                                                    id, name, description, in.readString(null)));
                }

                @Override
                public StringType getDefaultType(int id) {
                    return new StringType(id);
                }

                @Override
                public boolean isPrimitive() {
                    return true;
                }
            };
}
