package me.bechberger.condensed;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import me.bechberger.condensed.CondensedOutputStream.OverflowMode;
import me.bechberger.condensed.Universe.EmbeddingType;
import me.bechberger.condensed.types.*;
import me.bechberger.condensed.types.StructType.Field;
import net.jqwik.api.*;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.constraints.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/** Testing that type specifications can be written and read back */
public class TypeSpecificationTest {

    @Property
    @SuppressWarnings("rawtypes")
    public void testIntTypeRoundTrip(
            @ForAll @IntRange(min = 1, max = 8) int width,
            @ForAll boolean signed,
            @ForAll OverflowMode overflowMode) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out ->
                                        out.writeAndStoreType(
                                                id -> new IntType(id, width, signed, overflowMode)),
                                true))) {
            IntType result = (IntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new IntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, width, signed, overflowMode),
                    result);
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testScaledVarintTypeRoundTrip(
            @ForAll boolean signed,
            @ForAll @ByteRange(min = 1) byte multiplier,
            @ForAll long value) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out -> {
                                    var t =
                                            out.writeAndStoreType(
                                                    id ->
                                                            new VarIntType(
                                                                    id,
                                                                    "",
                                                                    "",
                                                                    signed,
                                                                    multiplier));
                                    out.writeMessage(t, value);
                                },
                                true))) {
            VarIntType result = (VarIntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new VarIntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, "", "", signed, multiplier),
                    result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value / multiplier * multiplier, msg.value());
        }
    }

    @Property
    public void testBooleanRoundTrip(@ForAll boolean value) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out -> {
                                    var type = out.writeAndStoreType(BooleanType::new);
                                    out.writeMessage(type, value);
                                },
                                true))) {
            BooleanType result = (BooleanType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new BooleanType(
                            TypeCollection.FIRST_CUSTOM_TYPE_ID, "boolean", "A boolean value"),
                    result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
            assertInstanceOf(BooleanType.class, msg.type());
            assertInstanceOf(Boolean.class, msg.value());
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void testIntType() {
        IntType intType =
                new IntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, 4, true, OverflowMode.ERROR);
        byte[] data =
                CondensedOutputStream.use(
                        out -> {
                            out.writeAndStoreType(
                                    i -> {
                                        assertEquals(i, intType.getId());
                                        return intType;
                                    });
                            intType.writeTo(out, -1L);
                            out.writeMessage(intType, -1000L);
                        },
                        true);
        try (var in = new CondensedInputStream(data)) {
            IntType result = (IntType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(intType, result);
            assertEquals(-1, intType.readFrom(in));
            var inst = in.readNextInstance();
            assertNotNull(inst);
            assertEquals(-1000L, inst.value());
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testVarIntTypeRoundTrip(
            @ForAll String name,
            @ForAll String description,
            @ForAll boolean signed,
            @ForAll long value) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out -> {
                                    var type =
                                            out.writeAndStoreType(
                                                    id ->
                                                            new VarIntType(
                                                                    id, name, description, signed));
                                    out.writeMessage(type, value);
                                },
                                true))) {
            VarIntType result = (VarIntType) (CondensedType) in.readNextTypeMessageAndProcess();

            assertEquals(
                    new VarIntType(TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description, signed),
                    result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testFloatTypeRoundTrip(
            @ForAll String name, @ForAll String description, @ForAll float value) {
        try (var in =
                new CondensedInputStream(
                        CondensedOutputStream.use(
                                out -> {
                                    var type =
                                            out.writeAndStoreType(
                                                    id -> new FloatType(id, name, description));
                                    out.writeMessage(type, value);
                                },
                                true))) {
            FloatType result = (FloatType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new FloatType(TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description), result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @Provide
    public Arbitrary<String> encodings() {
        return Arbitraries.of(Charset.defaultCharset().toString(), "UTF-8", "UTF-16");
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testStringRoundTrip(
            @ForAll String name,
            @ForAll String description,
            @ForAll("encodings") String encoding,
            @ForAll String value) {
        byte[] outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var type =
                                    out.writeAndStoreType(
                                            id -> new StringType(id, name, description, encoding));
                            out.writeMessage(type, value);
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            StringType result = (StringType) (CondensedType) in.readNextTypeMessageAndProcess();
            assertEquals(
                    new StringType(
                            TypeCollection.FIRST_CUSTOM_TYPE_ID, name, description, encoding),
                    result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @Property
    @SuppressWarnings("rawtypes")
    public void testIntArrayRoundTrip(
            @ForAll String name,
            @ForAll String description,
            @ForAll boolean useDefaultIntType,
            @ForAll @Size(max = 200) List<Integer> value,
            @ForAll EmbeddingType embeddingType) {
        AtomicReference<ArrayType<?, ?>> typeRef = new AtomicReference<>();
        Consumer<CondensedOutputStream> outWriter =
                out -> {
                    CondensedType<Long, Long> innerType;
                    if (useDefaultIntType) {
                        innerType = TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE);
                    } else {
                        innerType =
                                out.writeAndStoreType(
                                        id -> new IntType(id, 4, true, OverflowMode.ERROR));
                    }
                    var type =
                            out.writeAndStoreType(
                                    id ->
                                            new ArrayType<>(
                                                    id,
                                                    name,
                                                    description,
                                                    innerType,
                                                    embeddingType));
                    typeRef.set(type);
                    out.writeMessage(type, value.stream().map(Long::valueOf).toList());
                };
        byte[] outBytes = CondensedOutputStream.use(outWriter, true);
        try (var in = new CondensedInputStream(outBytes)) {
            if (!useDefaultIntType) {
                assertInstanceOf(IntType.class, in.readNextTypeMessageAndProcess());
            }
            ArrayType result = (ArrayType) in.readNextTypeMessageAndProcess();
            assertEquals(typeRef.get(), result);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value.stream().map(i -> (long) i).toList(), msg.value());
        }
    }

    @Property
    public void testNestedIntArray(@ForAll EmbeddingType embedding) {
        AtomicReference<ArrayType<List<Long>, List<Long>>> typeRef = new AtomicReference<>();
        List<List<Long>> value = List.of(List.of(1L, 2L), List.of(3L, 4L), List.of(), List.of(-5L));
        var outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var innerInnerType =
                                    TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE);
                            var innerType =
                                    out.writeAndStoreType(
                                            id -> new ArrayType<>(id, innerInnerType));
                            var type =
                                    out.writeAndStoreType(
                                            id -> new ArrayType<>(id, innerType, embedding));
                            typeRef.set(type);
                            out.writeMessage(type, value);
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            assertInstanceOf(ArrayType.class, in.readNextTypeMessageAndProcess());
            var actualType =
                    in
                            .<ArrayType<List<Long>, List<Long>>, ArrayType<List<Long>, List<Long>>>
                                    readNextTypeMessageAndProcess();
            assertEquals(typeRef.get(), actualType);
            var msg = in.readNextInstance();
            assertNotNull(msg);
            assertEquals(value, msg.value());
        }
    }

    @FunctionalInterface
    interface TypeCreator<T, C extends CondensedType<T, T>> {
        C create(CondensedOutputStream out);
    }

    @FunctionalInterface
    interface TypeCreatorForId<T, C extends CondensedType<T, T>> {
        C create(int id);
    }

    public record TypeCreatorAndValue<T, C extends CondensedType<T, T>>(
            TypeCreator<T, C> creator, Arbitrary<T> value) {
        static <T, C extends CondensedType<T, T>> TypeCreatorAndValue<T, C> forId(
                TypeCreatorForId<T, C> creator, Arbitrary<T> value) {
            return new TypeCreatorAndValue<>(out -> out.writeAndStoreType(creator::create), value);
        }
    }

    @Provide
    Arbitrary<TypeCreatorAndValue<Long, IntType>> intType(
            @ForAll @IntRange(min = 4, max = 8) int width, @ForAll boolean signed) {
        var longs = !signed ? Arbitraries.integers().greaterOrEqual(0) : Arbitraries.integers();
        return longs.map(
                l ->
                        TypeCreatorAndValue.forId(
                                id -> new IntType(id, width, signed, OverflowMode.ERROR),
                                longs.map(l2 -> (long) l2)));
    }

    @Provide
    Arbitrary<TypeCreatorAndValue<Long, VarIntType>> varIntType(@ForAll boolean signed) {
        var longs = Arbitraries.longs();
        return longs.map(
                l -> TypeCreatorAndValue.forId(id -> new VarIntType(id, "", "", signed), longs));
    }

    @Provide
    Arbitrary<TypeCreatorAndValue<Float, FloatType>> floatType() {
        return Arbitraries.floats()
                .map(f -> TypeCreatorAndValue.forId(FloatType::new, Arbitraries.floats()));
    }

    @Provide
    Arbitrary<TypeCreatorAndValue<String, StringType>> stringType() {
        return Arbitraries.strings()
                .map(s -> TypeCreatorAndValue.forId(StringType::new, Arbitraries.strings()));
    }

    @Provide
    Arbitrary<TypeCreatorAndValue<Boolean, BooleanType>> booleanType() {
        return Arbitraries.of(
                TypeCreatorAndValue.forId(BooleanType::new, Arbitraries.of(true, false)));
    }

    @Provide
    Arbitrary<TypeCreatorAndValue<String, StringType>> asciiStringType() {
        return Arbitraries.strings()
                .map(
                        s ->
                                TypeCreatorAndValue.forId(
                                        StringType::new, Arbitraries.strings().ascii().alpha()));
    }

    @Provide
    @SuppressWarnings({"rawtypes", "unchecked"})
    Arbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>> primitiveType() {
        return (Arbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>>)
                (Arbitrary)
                        Arbitraries.oneOf(
                                intType(
                                        Arbitraries.integers().between(4, 8).sample(),
                                        Arbitraries.of(true, false).sample()),
                                varIntType(Arbitraries.of(true, false).sample()),
                                booleanType(),
                                floatType(),
                                stringType());
    }

    @Provide
    Arbitrary<List<TypeCreatorAndValue<?, CondensedType<?, ?>>>> primitiveTypeList() {
        return primitiveType().list().ofMinSize(0).ofMaxSize(10);
    }

    @Provide
    ListArbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>> primitiveTypes() {
        return primitiveType().list();
    }

    public record Freqs(
            int primFreq,
            int arrayFreq,
            int structFreq,
            int maxListLength,
            int maxStructMembers,
            int maxDepth) {
        public Freqs reduceNestedFreqs(int factor) {
            return new Freqs(
                    primFreq * factor,
                    arrayFreq,
                    structFreq,
                    maxListLength,
                    maxStructMembers - 1,
                    maxDepth - 1);
        }

        public Freqs setStructFreq(int structFreq) {
            return new Freqs(
                    primFreq, arrayFreq, structFreq, maxListLength, maxStructMembers, maxDepth);
        }

        public static final Freqs DEFAULT = new Freqs(2, 1, 1, 10, 10, 4);

        public Freqs setMaxDepth(int i) {
            return new Freqs(primFreq, arrayFreq, structFreq, maxListLength, maxStructMembers, i);
        }

        public Freqs setMaxStructSize(int i) {
            return new Freqs(primFreq, arrayFreq, structFreq, maxListLength, i, maxDepth);
        }
    }

    @NotNull
    private Arbitrary<? extends TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>>
            getMemberArbitrary(Freqs freqs) {
        if (freqs.maxDepth <= 0) {
            return primitiveType();
        }
        return Arbitraries.frequency(
                        Tuple.of(freqs.primFreq, 1),
                        Tuple.of(freqs.arrayFreq, 2),
                        Tuple.of(freqs.structFreq, 3))
                .map(
                        i ->
                                switch (i) {
                                    case 1 -> primitiveType().sample();
                                    case 2 -> arrayType(freqs).sample();
                                    case 3 -> structType(freqs).sample();
                                    default -> throw new IllegalArgumentException("Invalid type");
                                });
    }

    @Provide
    @SuppressWarnings({"rawtypes", "unchecked"})
    <V> Arbitrary<TypeCreatorAndValue<List<V>, ArrayType<V, V>>> arrayType(Freqs freqs) {
        var member = getMemberArbitrary(freqs.reduceNestedFreqs(2));
        var length = Arbitraries.integers().between(0, freqs.maxListLength);
        var embedding = Arbitraries.of(EmbeddingType.class);
        var typeAndLength = Arbitraries.entries(member, length);
        return (Arbitrary<TypeCreatorAndValue<List<V>, ArrayType<V, V>>>)
                (Arbitrary)
                        typeAndLength.map(
                                e ->
                                        new TypeCreatorAndValue(
                                                out -> {
                                                    var memberType =
                                                            e.getKey().creator().create(out);
                                                    return out.writeAndStoreType(
                                                            id ->
                                                                    new ArrayType<>(
                                                                            id,
                                                                            memberType,
                                                                            embedding.sample()));
                                                },
                                                e.getKey().value().list().ofSize(e.getValue())));
    }

    @Provide
    <V> Arbitrary<TypeCreatorAndValue<List<V>, ArrayType<V, V>>> arrayType() {
        return arrayType(Freqs.DEFAULT);
    }

    @Provide
    @SuppressWarnings({"unchecked"})
    <T> Arbitrary<TypeCreatorAndValue<T, CondensedType<T, T>>> anyType() {
        return (Arbitrary<TypeCreatorAndValue<T, CondensedType<T, T>>>)
                getMemberArbitrary(Freqs.DEFAULT);
    }

    @Provide
    @SuppressWarnings({"unchecked"})
    private Arbitrary<List<TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>>>
            getMemberArbitraryList(Freqs freqs) {
        return getMemberArbitrary(freqs)
                .list()
                .ofMaxSize(freqs.maxStructMembers)
                .map(l -> (List<TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>>) l);
    }

    @Provide
    @SuppressWarnings({"unchecked"})
    private Arbitrary<List<TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>>>
            getMemberArbitraryList(Freqs freqs, int size) {
        return getMemberArbitrary(freqs)
                .list()
                .ofSize(size)
                .map(l -> (List<TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>>) l);
    }

    @Provide
    public Arbitrary<List<TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>>> members() {
        return getMemberArbitraryList(Freqs.DEFAULT);
    }

    @Provide
    @SuppressWarnings({"unchecked"})
    private ListArbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>> memberLists() {
        return (ListArbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>>)
                getMemberArbitrary(Freqs.DEFAULT).list();
    }

    @Provide
    public Arbitrary<
                    TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>>
            structType() {
        return structType(Freqs.DEFAULT);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @NotNull
    private static TypeCreatorAndValue<
                    Map<String, Object>, StructType<Map<String, Object>, Map<String, Object>>>
            createStructCreator(
                    List<TypeCreatorAndValue<?, ? extends CondensedType<?, ?>>> members,
                    List<String> names,
                    Arbitrary<EmbeddingType> embeddings,
                    Arbitrary<Integer> reductionIds) {
        if (members.isEmpty()) {
            return new TypeCreatorAndValue<>(
                    out ->
                            out.writeAndStoreType(
                                    id -> new StructType<>(id, List.of(), l -> Map.of())),
                    Arbitraries.just(Map.of()));
        }
        // problem: each member has an arbitrary, so create a combined
        // arbitrary over all members
        Arbitrary<Map<String, Object>> memberArbitrary =
                members.get(0)
                        .value()
                        .map(
                                v -> {
                                    var map = new HashMap<String, Object>();
                                    map.put(names.get(0), v);
                                    return map;
                                });
        for (int i = 1; i < members.size(); i++) {
            var j = i;
            memberArbitrary =
                    memberArbitrary.flatMap(
                            m ->
                                    members.get(j)
                                            .value()
                                            .map(
                                                    v -> {
                                                        m.put(names.get(j), v);
                                                        return m;
                                                    }));
        }
        return new TypeCreatorAndValue<
                Map<String, Object>, StructType<Map<String, Object>, Map<String, Object>>>(
                out -> {
                    var fields =
                            IntStream.range(0, members.size())
                                    .mapToObj(
                                            i -> {
                                                var t = members.get(i);
                                                return new Field<>(
                                                        names.get(i),
                                                        "",
                                                        t.creator().create(out),
                                                        (Map<String, Object> m) ->
                                                                m.get(names.get(i)),
                                                        embeddings.sample(),
                                                        reductionIds.sample());
                                            })
                                    .toList();
                    return out.writeAndStoreType(
                            id ->
                                    new StructType<>(
                                            id, (List) fields, r -> r, reductionIds.sample()));
                },
                memberArbitrary);
    }

    @Provide
    public Arbitrary<
                    TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>>
            structType(Freqs freqs) {
        var sizes = Arbitraries.integers().between(0, freqs.maxStructMembers);
        var membersAndNamesComb =
                sizes.map(
                        size ->
                                Combinators.combine(
                                        getMemberArbitraryList(freqs.reduceNestedFreqs(2), size),
                                        Arbitraries.strings()
                                                .alpha()
                                                .ofMaxLength(5)
                                                .list()
                                                .uniqueElements()
                                                .ofSize(size)));
        var embeddings = Arbitraries.of(EmbeddingType.class);
        var reductionIds = Arbitraries.integers().greaterOrEqual(0);
        return membersAndNamesComb.flatMap(
                membersAndNames ->
                        membersAndNames.as(
                                (members, names) ->
                                        createStructCreator(
                                                members, names, embeddings, reductionIds)));
    }

    private static void check(
            TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>
                    typeCreator,
            int writtenValues) {
        AtomicReference<StructType<Map<String, Object>, Map<String, Object>>> typeRef =
                new AtomicReference<>();
        var values = typeCreator.value().list().ofSize(writtenValues).sample();
        byte[] outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var type = typeCreator.creator().create(out);
                            typeRef.set(type);
                            for (var value : values) {
                                out.writeMessage(type, value);
                            }
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            for (var value : values) {
                var msg = in.readNextInstance();
                assertNotNull(msg);
                var origMap = ((ReadStruct) msg.value()).copy();
                assertEquals(value, msg.value());
                assertEquals(typeRef.get(), msg.type());
            }
            assertNull(in.readNextInstance());
        }
    }

    @Provide
    public Arbitrary<
                    TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>>
            structTypeWithOutNesting() {
        return structType(Freqs.DEFAULT.setMaxDepth(0));
    }

    @Property(seed = "3496424884544232945")
    public void testBasicStruct(
            @ForAll("structTypeWithOutNesting")
                    TypeCreatorAndValue<
                                    Map<String, Object>,
                                    StructType<Map<String, Object>, Map<String, Object>>>
                            typeCreator) {
        check(typeCreator, 3);
    }

    @Provide
    public Arbitrary<
                    TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>>
            structTypeWithoutInnerStructs() {
        return structType(Freqs.DEFAULT.setStructFreq(0));
    }

    @Property
    public void testBasicStructWithInnerArrays(
            @ForAll("structTypeWithoutInnerStructs")
                    TypeCreatorAndValue<
                                    Map<String, Object>,
                                    StructType<Map<String, Object>, Map<String, Object>>>
                            typeCreator) {
        check(typeCreator, 3);
    }

    @Provide
    public Arbitrary<
                    TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>>
            structTypeWithInnerStructs() {
        return structType(Freqs.DEFAULT.setMaxDepth(1));
    }

    @Property
    public void testBasicStructWithInnerStructs(
            @ForAll("structTypeWithInnerStructs")
                    TypeCreatorAndValue<
                                    Map<String, Object>,
                                    StructType<Map<String, Object>, Map<String, Object>>>
                            typeCreator) {
        check(typeCreator, 3);
    }

    @Provide
    public Arbitrary<
                    TypeCreatorAndValue<
                            Map<String, Object>,
                            StructType<Map<String, Object>, Map<String, Object>>>>
            structTypeWithAll() {
        return structType(Freqs.DEFAULT);
    }

    @Property(tries = 100)
    public void testBasicStructWithAll(
            @ForAll("structTypeWithAll")
                    TypeCreatorAndValue<
                                    Map<String, Object>,
                                    StructType<Map<String, Object>, Map<String, Object>>>
                            typeCreator) {
        check(typeCreator, 1);
    }

    @Property
    public void testMixedListOfPrimitiveTypes(
            @ForAll("primitiveTypes") @Size(min = 1, max = 10)
                    List<TypeCreatorAndValue<?, CondensedType<?, ?>>> primitiveTypes,
            @ForAll @IntRange(max = 100) int messagesWritten) {
        check(primitiveTypes, messagesWritten);
    }

    @Provide
    @SuppressWarnings({"unchecked"})
    private ListArbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>>
            depthOneTypesMaxStructSizeTwo() {
        return (ListArbitrary<TypeCreatorAndValue<?, CondensedType<?, ?>>>)
                getMemberArbitrary(Freqs.DEFAULT.setMaxDepth(1).setMaxStructSize(2)).list();
    }

    @SuppressWarnings({"unchecked"})
    private void check(
            List<TypeCreatorAndValue<?, CondensedType<?, ?>>> types, int messagesWritten) {

        record WhatToDo(Optional<Integer> writeType, Optional<Integer> writeValueOfType) {}
        List<WhatToDo> whatToDo = new ArrayList<>();
        int writtenTypes = 0;
        var typeOrMessage =
                Arbitraries.frequency(Tuple.of(types.size(), 1), Tuple.of(messagesWritten, 2));
        for (int i = 0; i < messagesWritten; i++) {
            if ((writtenTypes == 0 || typeOrMessage.sample() == 1) && writtenTypes < types.size()) {
                whatToDo.add(new WhatToDo(Optional.of(writtenTypes), Optional.empty()));
                writtenTypes++;
            } else {
                whatToDo.add(
                        new WhatToDo(
                                Optional.empty(),
                                Optional.of(
                                        Arbitraries.integers()
                                                .between(0, writtenTypes - 1)
                                                .sample())));
            }
        }

        List<CondensedType<?, ?>> currentTypes = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        byte[] outBytes =
                CondensedOutputStream.use(
                        out -> {
                            for (var action : whatToDo) {
                                if (action.writeType.isPresent()) {
                                    var type =
                                            types.get(action.writeType.get()).creator().create(out);
                                    currentTypes.add(type);
                                } else {
                                    assert action.writeValueOfType.isPresent();
                                    var type =
                                            (CondensedType<Object, Object>)
                                                    currentTypes.get(action.writeValueOfType.get());
                                    var value =
                                            types.get(action.writeValueOfType.get())
                                                    .value()
                                                    .sample();
                                    values.add(value);
                                    out.writeMessage(type, value);
                                }
                            }
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            List<Object> collectedValues = new ArrayList<>();
            for (var value : values) {
                var msg = in.readNextInstance();
                assertNotNull(msg);
                collectedValues.add(value);
                if (!value.equals(msg.value())) {

                    System.out.println("-- Types:");
                    for (var type : currentTypes) {
                        System.out.println("---- " + type.toPrettyString());
                    }
                    System.out.println("-- Values:");
                    for (var v : collectedValues) {
                        System.out.println("---- " + v.getClass() + " " + v);
                    }
                    for (int i = collectedValues.size(); i < values.size(); i++) {
                        var v = values.get(i);
                        System.out.println("---- new " + v.getClass() + " " + v);
                    }
                    System.out.println(value);
                    System.out.println(msg.value());
                }
                assertEquals(value, msg.value());
            }
            assertNull(in.readNextInstance());
        }
    }

    /**
     * StrucType{ FQ: StringType{id=16, name='string', description='A string of characters'}, S0c:
     * IntType(id=17, name=int8, description=A signed integer with 8 bytes, width=8, signed=true,
     * overflowMode=ERROR) }
     *
     * <p>{Cd= , 8 =2147483646}
     */
    @Property
    public void testStringIntStruct(@ForAll EmbeddingType embedding, @ForAll int reductionId) {
        var outBytes =
                CondensedOutputStream.use(
                        out -> {
                            var stringType = out.writeAndStoreType(StringType::new);
                            var intType =
                                    out.writeAndStoreType(
                                            id -> new IntType(id, 8, true, OverflowMode.ERROR));
                            var type =
                                    out.writeAndStoreType(
                                            id ->
                                                    new StructType<>(
                                                            id,
                                                            List.of(
                                                                    new Field<>(
                                                                            "FQ",
                                                                            "",
                                                                            stringType,
                                                                            (Map<String, Object>
                                                                                            m) ->
                                                                                    m.get("FQ"),
                                                                            embedding,
                                                                            reductionId),
                                                                    new Field<>(
                                                                            "S0c",
                                                                            "",
                                                                            intType,
                                                                            m -> m.get("S0c"),
                                                                            embedding,
                                                                            reductionId)),
                                                            m ->
                                                                    Map.of(
                                                                            "FQ",
                                                                            m.get("FQ"),
                                                                            "S0c",
                                                                            m.get("S0c")),
                                                            reductionId));
                            for (int i = 0; i < 10; i++) {
                                out.writeMessage(type, Map.of("FQ", "", "S0c", 2147483646L));
                            }
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes)) {
            for (int i = 0; i < 10; i++) {
                var msg = in.readNextInstance();
                assertNotNull(msg);
                var type = msg.type();
                assertInstanceOf(StructType.class, type);
                assertTrue(
                        ((StructType<?, ?>) type)
                                .getFields().stream()
                                        .allMatch(f -> f.reductionId() == reductionId));
                assertEquals(reductionId, ((StructType<?, ?>) type).getReductionId());
                assertEquals(Map.of("FQ", "", "S0c", 2147483646L), msg.value());
            }
        }
    }

    /**
     * Test case prints ---- StrucType{ : IntType(id=16, name=int4, description=A signed integer
     * with 4 bytes, width=4, signed=true, overflowMode=ERROR), PKxvQ: IntType(id=17, name=int4,
     * description=A signed integer with 4 bytes, width=4, signed=true, overflowMode=ERROR) } --
     * Values: ---- class java.util.HashMap {=-2, PKxvQ=-2147483648} ---- new class
     * java.util.HashMap {=5522, PKxvQ=-2} ---- new class java.util.HashMap {=1420540163, PKxvQ=-2}
     * ---- new class java.util.HashMap {=3972734, PKxvQ=50033} ---- new class java.util.HashMap
     * {=-2, PKxvQ=-2147483648} ---- new class java.util.HashMap {=646, PKxvQ=-523} ---- new class
     * java.util.HashMap {=224, PKxvQ=17384} ---- new class java.util.HashMap {=-1, PKxvQ=-2} ----
     * new class java.util.HashMap {=-17, PKxvQ=-604902}
     */
    @Property
    public void testIntVarIntStruct(@ForAll EmbeddingType embedding) {
        var values =
                List.of(
                        Map.of("", -2L, "PKxvQ", -2147483648L),
                        Map.of("", 5522L, "PKxvQ", -2L),
                        Map.of("", 1420540163L, "PKxvQ", -2L),
                        Map.of("", 3972734L, "PKxvQ", 50033L),
                        Map.of("", -2L, "PKxvQ", -2147483648L),
                        Map.of("", 646L, "PKxvQ", -523L),
                        Map.of("", 224L, "PKxvQ", 17384L),
                        Map.of("", -1L, "PKxvQ", -2L),
                        Map.of("", -17L, "PKxvQ", -604902L));
        var bytes =
                CondensedOutputStream.use(
                        out -> {
                            var intType =
                                    out.writeAndStoreType(
                                            id -> new IntType(id, 4, true, OverflowMode.ERROR));
                            var intType2 =
                                    out.writeAndStoreType(
                                            id -> new IntType(id, 4, true, OverflowMode.ERROR));
                            var type =
                                    out.writeAndStoreType(
                                            id ->
                                                    new StructType<
                                                            Map<String, Long>, Map<String, Long>>(
                                                            id,
                                                            List.of(
                                                                    new Field<>(
                                                                            "",
                                                                            "",
                                                                            intType,
                                                                            m -> m.get(""),
                                                                            embedding,
                                                                            0),
                                                                    new Field<>(
                                                                            "PKxvQ",
                                                                            "",
                                                                            intType2,
                                                                            m -> m.get("PKxvQ"),
                                                                            embedding,
                                                                            0)),
                                                            m ->
                                                                    Map.of(
                                                                            "",
                                                                            (long) m.get(""),
                                                                            "PKxvQ",
                                                                            (long)
                                                                                    m.get(
                                                                                            "PKxvQ"))));
                            for (var value : values) {
                                out.writeMessage(type, value);
                            }
                        },
                        true);
        try (var in = new CondensedInputStream(bytes)) {
            for (var value : values) {
                var msg = in.readNextInstance();
                assertNotNull(msg);
                assertEquals(value, msg.value());
            }
        }
    }

    @Property
    public void testBooleanRoundtrip(@ForAll boolean value) {
        var type = TypeCollection.getDefaultTypeInstance(BooleanType.SPECIFIED_TYPE);
        byte[] outBytes = CondensedOutputStream.use(out -> type.writeTo(out, value), false);
        try (var in = new CondensedInputStream(outBytes)) {
            var val = type.readFrom(in);
            assertEquals(value, val);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testStructFieldReduction() {

        record TestStruct(String a, int b) {}

        // reduction 1 just reduces the string to its length and inflates it by repeating "X"
        Reductions reductions =
                new Reductions() {

                    @Override
                    @SuppressWarnings("unchecked")
                    public <R, F> R reduce(int id, F value) {
                        if (id == 0) {
                            return (R) value;
                        }
                        if (id == 1) {
                            return (R) (Integer) ((String) value).length();
                        }
                        throw new IllegalArgumentException("Invalid id");
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public <R, F> F inflate(int id, R reduced) {
                        if (id == 0) {
                            return (F) reduced;
                        }
                        if (id == 1) {
                            return (F) "X".repeat((int) (long) reduced);
                        }
                        throw new IllegalArgumentException("Invalid id");
                    }
                };

        var outBytes =
                CondensedOutputStream.use(
                        out -> {
                            out.setReductions(reductions);
                            var intType =
                                    TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE);
                            var type =
                                    out.writeAndStoreType(
                                            id ->
                                                    new StructType<>(
                                                            id,
                                                            List.of(
                                                                    new Field<>(
                                                                            "a",
                                                                            "",
                                                                            intType,
                                                                            (TestStruct s) -> s.a,
                                                                            EmbeddingType.INLINE,
                                                                            1),
                                                                    new Field<>(
                                                                            "b",
                                                                            "",
                                                                            intType,
                                                                            s -> s.b,
                                                                            EmbeddingType.INLINE,
                                                                            0)),
                                                            m ->
                                                                    new TestStruct(
                                                                            (String) m.get("a"),
                                                                            (int)
                                                                                    (long)
                                                                                            m.get(
                                                                                                    "b"))));
                            out.writeMessage(type, new TestStruct("abc", 3));
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes).setReductions(reductions)) {
            var value = in.readNextInstance();
            assertNotNull(value);
            assertEquals(Map.of("a", "XXX", "b", (long) 3), value.value());
        }
    }

    @Test
    public void testWholeStructReduction() {

        record TestStruct(String a, int b) {}

        record TestStructReduced(int a, int b) {}

        // reduction 1 just reduces the string to its length and inflates it by repeating "X"
        Reductions reductions =
                new Reductions() {

                    @Override
                    @SuppressWarnings("unchecked")
                    public <R, F> R reduce(int id, F value) {
                        if (id == 0) {
                            return (R) value;
                        }
                        if (id == 1) {
                            var val = (TestStruct) value;
                            return (R) new TestStructReduced(val.a.length(), val.b);
                        }
                        throw new IllegalArgumentException("Invalid id");
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public <R, F> F inflate(int id, R reduced) {
                        if (id == 0) {
                            return (F) reduced;
                        }
                        if (id == 1) {
                            var val = (ReadStruct) reduced;
                            return (F)
                                    new TestStruct(
                                            "X".repeat((int) (long) val.get("a")),
                                            (int) (long) val.get("b"));
                        }
                        throw new IllegalArgumentException("Invalid id");
                    }
                };

        var outBytes =
                CondensedOutputStream.use(
                        out -> {
                            out.setReductions(reductions);
                            var intType =
                                    TypeCollection.getDefaultTypeInstance(IntType.SPECIFIED_TYPE);
                            var type =
                                    out.writeAndStoreType(
                                            id ->
                                                    new StructType<>(
                                                            id,
                                                            List.of(
                                                                    new Field<>(
                                                                            "a",
                                                                            "",
                                                                            intType,
                                                                            (TestStructReduced s) ->
                                                                                    s.a,
                                                                            EmbeddingType.INLINE),
                                                                    new Field<>(
                                                                            "b",
                                                                            "",
                                                                            intType,
                                                                            s -> (long) s.b,
                                                                            EmbeddingType.INLINE)),
                                                            m ->
                                                                    new TestStruct(
                                                                            "X"
                                                                                    .repeat(
                                                                                            (int)
                                                                                                    (long)
                                                                                                            m
                                                                                                                    .get(
                                                                                                                            "a")),
                                                                            (Integer) m.get("b")),
                                                            1));
                            out.writeMessageReduced(type, new TestStruct("abc", 3));
                        },
                        true);
        try (var in = new CondensedInputStream(outBytes).setReductions(reductions)) {
            var value = in.readNextInstance();
            assertNotNull(value);
            assertEquals(new TestStruct("XXX", 3), value.value());
        }
    }
}
