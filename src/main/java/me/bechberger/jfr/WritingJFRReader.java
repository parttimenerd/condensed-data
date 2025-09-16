package me.bechberger.jfr;

import static me.bechberger.condensed.Util.toNanoSeconds;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.bechberger.JFRReader;
import me.bechberger.condensed.ReadList;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.condensed.types.ArrayType;
import me.bechberger.condensed.types.CondensedType;
import me.bechberger.condensed.types.StringType;
import me.bechberger.condensed.types.StructType;
import me.bechberger.jfr.JFREventCombiner.JFREventTypedValuedReconstitutor;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmc.flightrecorder.writer.RecordingImpl;
import org.openjdk.jmc.flightrecorder.writer.api.*;
import org.openjdk.jmc.flightrecorder.writer.api.Types.Builtin;
import org.openjdk.jmc.flightrecorder.writer.api.Types.JDK;
import org.openjdk.jmc.flightrecorder.writer.api.Types.Predefined;

public class WritingJFRReader {

    private final JFRReader reader;
    private final OutputStream outputStream;
    private RecordingImpl recording;
    private final Map<CondensedType<?, ?>, Predefined> typeMap = new IdentityHashMap<>();
    private final Map<CondensedType<?, ?>, Type> realTypeMap = new IdentityHashMap<>();
    private final JFREventTypedValuedReconstitutor reconstitutor =
            new JFREventTypedValuedReconstitutor(this);
    private final Queue<TypedValue> eventsToEmit = new ArrayDeque<>();
    private final Map<String, Integer> combinedEventCount = new HashMap<>();

    public WritingJFRReader(JFRReader reader, OutputStream outputStream) {
        this.reader = reader;
        this.outputStream = outputStream;
    }

    public WritingJFRReader(BasicJFRReader reader) {
        this(reader, new ByteArrayOutputStream());
    }

    public JFREventTypedValuedReconstitutor getReconstitutor() {
        return reconstitutor;
    }

    void initRecording() {
        this.recording =
                (RecordingImpl)
                        Recordings.newRecording(
                                outputStream,
                                r -> {
                                    r.withStartTicks(1);
                                    r.withTimestamp(1);
                                });
    }

    public @Nullable TypedValue readNextJFREvent() {
        if (!eventsToEmit.isEmpty()) {
            var event = eventsToEmit.poll();
            recording.writeEvent(event);
            return event;
        }
        var event = reader.readNextEvent();
        if (event == null) {
            return null;
        }
        if (recording == null) {
            initRecording();
        }
        if (reconstitutor.isCombinedEvent(event)) {
            combinedEventCount.put(
                    event.getType().getName(),
                    combinedEventCount.getOrDefault(event.getType().getName(), 0) + 1);
            eventsToEmit.addAll(reconstitutor.reconstitute(this.reader.getInputStream(), event));
            return readNextJFREvent(); // comes in handy when the combined events
        }
        var evt = toTypedValue(event, true, new ReadStructPath());
        recording.writeEvent(evt);
        return evt;
    }

    public Map<String, Integer> getCombinedEventCount() {
        return Collections.unmodifiableMap(combinedEventCount);
    }

    public CondensedType<?, ?> getCondensedType(String name) {
        return Objects.requireNonNull(
                reader.getInputStream().getTypeCollection().getTypeOrNull(name));
    }

    /** Turn a read struct with a known event type into a typed value */
    public TypedValue fromReadStruct(ReadStruct struct) {
        return toTypedValue(struct, true, new ReadStructPath());
    }

    public List<TypedValue> readAllJFREvents() {
        List<TypedValue> events = new ArrayList<>();
        TypedValue evt;
        while ((evt = readNextJFREvent()) != null) {
            events.add(evt);
        }
        return events;
    }

    private Predefined getPredefType(CondensedType<?, ?> type, boolean isEvent) {
        if (typeMap.containsKey(type)) {
            return typeMap.get(type);
        }
        typeMap.put(type, type::getName);
        realTypeMap.put(type, createType(type, isEvent));
        return typeMap.get(type);
    }

    private Type getRealType(CondensedType<?, ?> type, boolean isEvent) {
        return Objects.requireNonNull(realTypeMap.get(type));
    }

    private @Nullable Predefined getPredefinedTypeOfBuiltin(String name) {
        return switch (name) {
            case "boolean" -> Builtin.BOOLEAN;
            case "byte" -> Builtin.BYTE;
            case "char" -> Builtin.CHAR;
            case "short" -> Builtin.SHORT;
            case "int" -> Builtin.INT;
            case "long" -> Builtin.LONG;
            case "float" -> Builtin.FLOAT;
            case "double" -> Builtin.DOUBLE;
            case "String", "java.lang.String" -> Builtin.STRING;
            default -> null;
        };
    }

    private @Nullable Predefined getPredefinedJDKAnnotation(String name) {
        var predef = getPredefinedJDKType(name);
        if (predef != null && predef.toString().startsWith("ANNOTATION")) {
            return predef;
        }
        return null;
    }

    private @Nullable Predefined getPredefinedJDKType(String name) {
        return Arrays.stream(JDK.values())
                .filter(jdk -> jdk.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private boolean putJFRTypeIntoConstantPool(CondensedType<?, ?> type) {
        return (type instanceof StructType<?, ?> structType
                        && !type.getName().contains("StackFrame"))
                || type instanceof StringType;
    }

    private Type createType(CondensedType<?, ?> type, boolean isEvent) {
        Consumer<TypeStructureBuilder> builderConsumer =
                builder -> {
                    if (type instanceof StructType<?, ?> structType) {
                        for (var field : structType.getFields()) {
                            var parsed = BasicJFRWriter.parseFieldDescription(field.description());
                            var isArray = parsed.isArray();
                            var innerType =
                                    isArray
                                            ? ((ArrayType<?, ?>) field.type()).getValueType()
                                            : field.type();
                            Consumer<TypedFieldBuilder> fieldBuilderConsumer =
                                    (TypedFieldBuilder b) -> {
                                        if (isArray) {
                                            b.asArray();
                                        }
                                        for (var ann : parsed.annotations()) {
                                            Predefined jdk = getPredefinedJDKAnnotation(ann.type());
                                            if (jdk != null) {
                                                if (!ann.values().isEmpty()) {
                                                    b.addAnnotation(
                                                            jdk, ann.values().get(0).toString());
                                                } else {
                                                    b.addAnnotation(jdk);
                                                }
                                            }
                                        }
                                    };
                            var builtin = getPredefinedTypeOfBuiltin(parsed.type());
                            if (builtin != null) {
                                builder.addField(field.name(), builtin, fieldBuilderConsumer);
                            } else {
                                var jdk = getPredefinedJDKType(field.name());
                                if (jdk != null) {
                                    builder.addField(field.name(), jdk, fieldBuilderConsumer);
                                } else {
                                    var predef = getPredefType(innerType, false);
                                    if (realTypeMap.containsKey(innerType)) {
                                        builder.addField(
                                                field.name(),
                                                getRealType(innerType, false),
                                                fieldBuilderConsumer);
                                    } else {
                                        builder.addField(
                                                field.name(), predef, fieldBuilderConsumer);
                                    }
                                }
                            }
                        }
                        return;
                    }
                    throw new IllegalArgumentException("Unsupported type: " + type);
                };
        return isEvent
                ? recording.registerType(type.getName(), "jdk.jfr.Event", builderConsumer)
                : recording
                        .getTypes()
                        .getOrAdd(
                                type.getName(), putJFRTypeIntoConstantPool(type), builderConsumer);
    }

    // stackTrace, eventThread, startTime

    private long toLong(Object longable) {
        if (longable instanceof Float) {
            return (long) (float) (Float) longable;
        }
        if (longable instanceof Double) {
            return (long) (double) (Double) longable;
        }
        return (long) longable;
    }

    private @Nullable TypedValue getTypedPrimitiveValue(TypedField field, Object value) {
        if (value == null) {
            return null;
        }
        var type = field.getType();
        if (value instanceof Instant) {
            // improve
            return field.getType().asValue(toNanoSeconds((Instant) value));
        }
        if (value instanceof Duration) {
            return field.getType().asValue(((Duration) value).toNanos());
        }
        return switch (type.getTypeName()) {
            case "boolean" -> type.asValue((boolean) value);
            case "byte" -> type.asValue((byte) toLong(value));
            case "char" -> type.asValue((char) toLong(value));
            case "short" -> type.asValue((short) toLong(value));
            case "int" -> type.asValue((int) toLong(value));
            case "long" -> type.asValue(toLong(value));
            case "float" -> type.asValue((float) value);
            case "double" -> type.asValue((double) (float) value);
            case "String", "java.lang.String" -> type.asValue((String) value);
            default -> {
                if (value instanceof String) {
                    yield type.asValue((String) value);
                }
                yield null;
            }
        };
    }

    private record ReadStructPath(@Nullable ReadStructPath parent, @Nullable ReadStruct value) {

        ReadStructPath() {
            this(null, null);
        }

        ReadStructPath add(ReadStruct value) {
            return new ReadStructPath(this, value);
        }

        boolean contains(ReadStruct value, int minCount) {
            var current = this;
            while (current != null) {
                if (current.value == value) {
                    if (minCount == 0) {
                        return true;
                    }
                    minCount--;
                }
                current = current.parent;
            }
            return false;
        }
    }

    private TypedValue toTypedValue(ReadStruct struct, boolean isEvent, ReadStructPath visited) {
        getPredefType(struct.getType(), isEvent);
        if (visited.contains(struct, 1)) {
            return getRealType(struct.getType(), isEvent).nullValue();
        }
        var curVisited = visited.add(struct);
        return getRealType(struct.getType(), isEvent)
                .asValue(
                        builder -> {
                            for (var field : builder.getType().getFields()) {
                                var value = struct.get(field.getName());
                                if (value == null) {
                                    builder.putField(field.getName(), field.getType().nullValue());
                                    continue;
                                }
                                if (field.isArray() && !(value instanceof ReadList<?>)) {
                                    throw new IllegalArgumentException(
                                            "Expected array, got: " + value);
                                }
                                if (!field.isArray() && value instanceof ReadList<?>) {
                                    throw new IllegalArgumentException(
                                            "Expected non-array, got: "
                                                    + ((ReadList<?>) value).size()
                                                    + " elements for field "
                                                    + field.getName());
                                }
                                if (value instanceof ReadList<?>) {
                                    List<TypedValue> values = new ArrayList<>();
                                    int index = 0;
                                    for (var val : (ReadList<?>) value) {
                                        var prim = getTypedPrimitiveValue(field, val);
                                        if (prim != null) {
                                            values.add(prim);
                                        } else {
                                            values.add(
                                                    toTypedValue(
                                                            (ReadStruct) val, false, curVisited));
                                        }
                                        index++;
                                    }
                                    builder.putField(
                                            field.getName(), values.toArray(new TypedValue[0]));
                                } else {
                                    if (struct.getType()
                                            .getField(field.getName())
                                            .getTypeName()
                                            .equals("timestamp")) {
                                        if (value instanceof Instant) {
                                            value = toNanoSeconds((Instant) value);
                                        } else if (value instanceof Duration) {
                                            value = ((Duration) value).toNanos();
                                        } else if (!(value instanceof Long)) {
                                            throw new IllegalArgumentException(
                                                    "Expected Instant, Duration or Long"
                                                            + " (nanoseconds) for "
                                                            + field.getName()
                                                            + ", got: "
                                                            + value);
                                        }
                                    }
                                    var prim = getTypedPrimitiveValue(field, value);
                                    if (prim != null) {
                                        builder.putField(field.getName(), prim);
                                    } else {
                                        builder.putField(
                                                field.getName(),
                                                toTypedValue(
                                                        (ReadStruct) value, false, curVisited));
                                    }
                                }
                            }
                        });
    }

    public void close() {
        try {
            recording.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void toJFRFile(JFRReader reader, Path output) {
        Path path = toJFRFile(reader);
        try {
            Files.move(path, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path toJFRFile(JFRReader reader) {
        try {
            Path tmp = Files.createTempFile("recording", ".jfr");
            WritingJFRReader writingJFRReader =
                    new WritingJFRReader(reader, Files.newOutputStream(tmp));
            while (true) {
                var event = writingJFRReader.readNextJFREvent();
                if (event == null) {
                    break;
                }
            }
            writingJFRReader.close();
            return tmp;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<RecordedEvent> toJFREventsList(BasicJFRReader reader) {
        return toJFREventsList(reader, Integer.MAX_VALUE);
    }

    public static List<RecordedEvent> toJFREventsList(BasicJFRReader reader, int limit) {
        try {
            Path tmp = toJFRFile(reader);
            List<RecordedEvent> events = new ArrayList<>();
            try (RecordingFile recordingFile = new RecordingFile(tmp)) {
                for (int i = 0; i < limit; i++) {
                    if (!recordingFile.hasMoreEvents()) {
                        break;
                    }
                    events.add(recordingFile.readEvent());
                }
            }
            Files.deleteIfExists(tmp);
            return events;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}