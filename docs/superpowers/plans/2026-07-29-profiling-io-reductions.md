# Profiling & I/O Event Reductions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three independent data reductions for profiling and default JFR recordings: strip always-constant `state` field from `ExecutionSample`/`NativeMethodSample`, combine profiling samples per time window, and combine I/O events per time window.

**Architecture:** Change 1 uses the existing `ReducedJFRTypes` field-removal mechanism. Changes 2 & 3 each introduce a new `AbstractCombiner` + `AbstractReconstitutor` pair following the pattern of `ZStatisticsCombiner`. The time-window flush boundary is implemented by including `bucketIndex = epochSecond / profilingBucketSeconds` in the combiner token — a new token triggers automatic flush of the previous window's state via the `Cache.onRemove` callback in `EventCombiner`. All three changes are gated on new `Configuration` flags.

**Tech Stack:** Java 21, JDK JFR API (`jdk.jfr.consumer`), existing `AbstractCombiner`/`AbstractReconstitutor` framework in `JFREventCombiner.java`, JUnit 5, `RecordingStream` for live-event tests.

**Reference files:**
- `src/main/java/me/bechberger/jfr/Configuration.java` — presets and flag declarations
- `src/main/java/me/bechberger/jfr/ReducedJFRTypes.java` — field-removal registry
- `src/main/java/me/bechberger/jfr/CombinedEventType.java` — combined type enum
- `src/main/java/me/bechberger/jfr/JFREventCombiner.java` — combiners, reconstitutors, registration
- `src/test/java/me/bechberger/jfr/JFREventCombinerTest.java` — combiner unit test pattern
- `src/test/java/me/bechberger/jfr/BasicJFRRoundTripTest.java` — round-trip test pattern

---

### Task 1: Add `profilingBucketSeconds` to `Configuration` and new preset flags

**Files:**
- Modify: `src/main/java/me/bechberger/jfr/Configuration.java`

- [ ] **Step 1: Add the three new record components**

  In `Configuration.java`, add three components to the record declaration after `cpuBucketSeconds`:

  ```java
  boolean combineProfilingSamples,
  boolean combineIOEvents,
  long profilingBucketSeconds)
  ```

  And extend the compact constructor validation block:

  ```java
  if (profilingBucketSeconds == 0) {
      profilingBucketSeconds = 10L;
  } else if (profilingBucketSeconds < 0) {
      throw new IllegalArgumentException("profilingBucketSeconds must be positive");
  }
  ```

- [ ] **Step 2: Add `with*` methods**

  After `withCpuBucketSeconds`:

  ```java
  public Configuration withCombineProfilingSamples(boolean combineProfilingSamples) {
      return withFieldValue("combineProfilingSamples", combineProfilingSamples);
  }

  public Configuration withCombineIOEvents(boolean combineIOEvents) {
      return withFieldValue("combineIOEvents", combineIOEvents);
  }

  public Configuration withProfilingBucketSeconds(long profilingBucketSeconds) {
      return withFieldValue("profilingBucketSeconds", profilingBucketSeconds);
  }
  ```

- [ ] **Step 3: Update `REDUCED_DEFAULT` preset**

  Chain onto the existing `REDUCED_DEFAULT` builder:

  ```java
  .withCombineProfilingSamples(true)
  .withCombineIOEvents(true)
  ```

  Leave `DEFAULT` and `LOSSLESS` unchanged (new fields default to `false`/`0`→`10`).

- [ ] **Step 4: Update the old compact constructor (backward-compat)**

  The existing 13-arg constructor passes `false, false, false, false, false, false, false, false, false, 10L` for the fields added after `ignoreTooShortGCPauses`. Extend it to also pass the three new fields:

  ```java
  // existing last line of the forwarding call:
  false, // dropGCWorkerThreadFromGCPhaseParallel
  10L,   // cpuBucketSeconds
  false, // combineProfilingSamples   ← new
  false, // combineIOEvents            ← new
  10L);  // profilingBucketSeconds     ← new
  ```

- [ ] **Step 5: Update `eventCombinersEnabled()`**

  ```java
  public boolean eventCombinersEnabled() {
      return combinePLABPromotionEvents
              || combineObjectAllocationSampleEvents
              || combineEventsWithoutDataLoss
              || combineExceptionEvents
              || combineG1HeapRegionTypeChangeEvents
              || combineBlockingEvents
              || combineThreadParkLossless
              || combineProfilingSamples
              || combineIOEvents;
  }
  ```

- [ ] **Step 6: Run existing config tests**

  ```bash
  ./mvnw test -pl . -Dtest=ConfigurationTest,ConfigurationDocTest -q
  ```

  Expected: all pass (new fields have defaults; old tests unaffected).

- [ ] **Step 7: Commit**

  ```bash
  git add src/main/java/me/bechberger/jfr/Configuration.java
  git commit -m "feat(config): add combineProfilingSamples, combineIOEvents, profilingBucketSeconds"
  ```

---

### Task 2: Strip `state` from `ExecutionSample` and `NativeMethodSample`

**Files:**
- Modify: `src/main/java/me/bechberger/jfr/ReducedJFRTypes.java`
- Test: `src/test/java/me/bechberger/jfr/JFRReductionTest.java`

- [ ] **Step 1: Write failing test**

  Add to `JFRReductionTest.java`:

  ```java
  @Name("TestExecutionSampleLike")
  @Label("Execution Sample Like")
  static class TestExecutionSampleLike extends Event {
      String state = "STATE_RUNNABLE";
      int number;
      TestExecutionSampleLike(int number) { this.number = number; }
  }

  @Test
  public void testStateFieldStrippedFromExecutionSampleWithRemoveTypeInfo() throws Exception {
      var outputStream = new ByteArrayOutputStream();
      var config = Configuration.REASONABLE_DEFAULT; // removeTypeInformationFromStackFrames=false in default
      // Use REDUCED_DEFAULT which has removeTypeInformationFromStackFrames=true
      var reducedConfig = Configuration.REDUCED_DEFAULT;
      try (var out = new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
          var writer = new BasicJFRWriter(out, reducedConfig);
          try (var rs = new RecordingStream()) {
              rs.onEvent("TestExecutionSampleLike", event -> {
                  writer.processEvent(event);
                  rs.close();
              });
              rs.startAsync();
              new TestExecutionSampleLike(42).commit();
              rs.awaitTermination();
          }
      }
      try (var in = new CondensedInputStream(outputStream.toByteArray())) {
          var reader = new BasicJFRReader(in);
          var event = reader.readNextEvent();
          assertNotNull(event);
          assertFalse(event.getType().getFields().stream()
              .anyMatch(f -> f.getName().equals("state")),
              "state field should be stripped under removeTypeInformationFromStackFrames");
      }
  }
  ```

- [ ] **Step 2: Run test to verify it fails**

  ```bash
  ./mvnw test -pl . -Dtest=JFRReductionTest#testStateFieldStrippedFromExecutionSampleWithRemoveTypeInfo -q
  ```

  Expected: FAIL — the `state` field is present (not yet stripped).

- [ ] **Step 3: Add entries to `ReducedJFRTypes`**

  In `ReducedJFRTypes.java`, add two entries to `REDUCED_JFR_TYPES` (the `Map.ofEntries` call). Add after the `jdk.types.StackFrame` entry:

  ```java
  Map.entry(
      "jdk.ExecutionSample",
      entry(
          "jdk.ExecutionSample",
          new RemovedPrimitiveField(
              "state",
              Configuration::removeTypeInformationFromStackFrames))),
  Map.entry(
      "jdk.NativeMethodSample",
      entry(
          "jdk.NativeMethodSample",
          new RemovedPrimitiveField(
              "state",
              Configuration::removeTypeInformationFromStackFrames))),
  ```

- [ ] **Step 4: Run test to verify it passes**

  ```bash
  ./mvnw test -pl . -Dtest=JFRReductionTest#testStateFieldStrippedFromExecutionSampleWithRemoveTypeInfo -q
  ```

  Expected: PASS.

- [ ] **Step 5: Run full suite**

  ```bash
  ./mvnw test -pl . -q
  ```

  Expected: all pass.

- [ ] **Step 6: Commit**

  ```bash
  git add src/main/java/me/bechberger/jfr/ReducedJFRTypes.java \
          src/test/java/me/bechberger/jfr/JFRReductionTest.java
  git commit -m "feat(reduction): strip state field from ExecutionSample/NativeMethodSample"
  ```

---

### Task 3: Add `EXECUTION_SAMPLE` and `NATIVE_METHOD_SAMPLE` to `CombinedEventType`

**Files:**
- Modify: `src/main/java/me/bechberger/jfr/CombinedEventType.java`

- [ ] **Step 1: Add enum entries**

  Add before the closing semicolon of the enum:

  ```java
  EXECUTION_SAMPLE("jdk.combined.ExecutionSample", "jdk.ExecutionSample"),
  NATIVE_METHOD_SAMPLE("jdk.combined.NativeMethodSample", "jdk.NativeMethodSample"),
  SOCKET_READ("jdk.combined.SocketRead", "jdk.SocketRead"),
  SOCKET_WRITE("jdk.combined.SocketWrite", "jdk.SocketWrite"),
  FILE_READ("jdk.combined.FileRead", "jdk.FileRead"),
  FILE_WRITE("jdk.combined.FileWrite", "jdk.FileWrite"),
  FILE_FORCE("jdk.combined.FileForce", "jdk.FileForce");
  ```

- [ ] **Step 2: Compile check**

  ```bash
  ./mvnw compile -pl . -q
  ```

  Expected: compiles cleanly.

- [ ] **Step 3: Commit**

  ```bash
  git add src/main/java/me/bechberger/jfr/CombinedEventType.java
  git commit -m "feat(combiner): add profiling and I/O combined event type enum entries"
  ```

---

### Task 4: Implement `ExecutionSampleCombiner` and `ExecutionSampleReconstitutor`

**Files:**
- Modify: `src/main/java/me/bechberger/jfr/JFREventCombiner.java`

The combiner groups events by `(sampledThread, stackTrace content-hash, state, originalEventType)` within a `profilingBucketSeconds` window. The token includes `bucketIndex` — a different `bucketIndex` is a different token, which triggers `Cache.onRemove` → flush of the old window automatically.

The combined type stores: `startTime` (window start), `sampledThread`, `stackTrace`, `state`, `originalEventType` (String), and `startTimeDiffs` (VarInt array of deltas from window start in nanoseconds).

- [ ] **Step 1: Add the token and state records**

  Add near the top of the inner class section (after `GCIdState`):

  ```java
  record ExecutionSampleToken(
          jdk.jfr.consumer.RecordedThread sampledThread,
          ReducedStackTrace stackTrace,
          String state,
          String originalEventType,
          long bucketIndex) {}

  static final class ExecutionSampleState implements JFRObjectState {
      final Instant startTime;
      final jdk.jfr.consumer.RecordedThread sampledThread;
      final ReducedStackTrace stackTrace;
      final String state;
      final String originalEventType;
      final List<Long> startTimeDiffsNanos = new ArrayList<>();

      ExecutionSampleState(
              Instant startTime,
              jdk.jfr.consumer.RecordedThread sampledThread,
              ReducedStackTrace stackTrace,
              String state,
              String originalEventType) {
          this.startTime = startTime;
          this.sampledThread = sampledThread;
          this.stackTrace = stackTrace;
          this.state = state;
          this.originalEventType = originalEventType;
          startTimeDiffsNanos.add(0L); // first event is always diff=0
      }

      @Override
      public Instant startTime() { return startTime; }

      @Override
      public JFREventCombiner.DefinedMap<RecordedEvent> map() {
          throw new UnsupportedOperationException("ExecutionSampleState uses direct fields");
      }
  }
  ```

  Note: `DefinedMap.map()` is only called by `AbstractCombiner.combine()` — we override `combine()` directly, so throwing here is safe.

- [ ] **Step 2: Add `ExecutionSampleCombiner` class**

  Add after `ExecutionSampleState`:

  ```java
  static class ExecutionSampleCombiner
          extends AbstractCombiner<ExecutionSampleToken, ExecutionSampleState> {

      private final long profilingBucketSeconds;

      public ExecutionSampleCombiner(Configuration configuration, BasicJFRWriter basicJFRWriter) {
          super(
                  "jdk.combined.ExecutionSample",
                  configuration,
                  basicJFRWriter,
                  "startTimeDiffs",
                  createValueDefinition(basicJFRWriter));
          this.profilingBucketSeconds = configuration.profilingBucketSeconds();
      }

      @SuppressWarnings({"rawtypes", "unchecked"})
      private static MapEntry.ArrayValue<RecordedEvent, Long> createValueDefinition(
              BasicJFRWriter basicJFRWriter) {
          return new MapEntry.ArrayValue<>(
                  new MapPartValue<>(
                          "startTimeDiffs",
                          (out, eventType) ->
                                  (CondensedType)
                                          out.writeAndStoreType(
                                                  id ->
                                                          new me.bechberger.condensed.types
                                                                  .VarIntType(id, true)),
                          e -> 0L)); // placeholder getter; we override combine() directly
      }

      @Override
      public StructType<ExecutionSampleState, ?> createCombinedStateType(
              CondensedOutputStream out, EventType eventType) {
          var startTimeField =
                  new StructType.Field<ExecutionSampleState, Instant, Instant>(
                          "startTime",
                          basicJFRWriter.getDescription(eventType.getField("startTime")),
                          (CondensedType<Instant, Instant>)
                                  basicJFRWriter.getTypeCached(eventType.getField("startTime")),
                          ExecutionSampleState::startTime,
                          me.bechberger.condensed.Universe.EmbeddingType.INLINE,
                          JFRReduction.TIMESTAMP_REDUCTION.ordinal());
          var sampledThreadField =
                  new StructType.Field<ExecutionSampleState, Object, Object>(
                          "sampledThread",
                          basicJFRWriter.getDescription(eventType.getField("sampledThread")),
                          (CondensedType<Object, Object>) basicJFRWriter.getTypeCached(eventType.getField("sampledThread")),
                          s -> s.sampledThread);
          var stackTraceField =
                  new StructType.Field<ExecutionSampleState, Object, Object>(
                          "stackTrace",
                          basicJFRWriter.getDescription(eventType.getField("stackTrace")),
                          (CondensedType<Object, Object>) basicJFRWriter.getTypeCached(eventType.getField("stackTrace")),
                          s -> s.stackTrace);
          var stateType = out.writeAndStoreType(
                  id -> new me.bechberger.condensed.types.StringType(id));
          var stateField =
                  new StructType.Field<ExecutionSampleState, String, String>(
                          "state", "", (CondensedType<String, String>) stateType,
                          s -> s.state);
          var originalEventTypeField =
                  new StructType.Field<ExecutionSampleState, String, String>(
                          "originalEventType", "", (CondensedType<String, String>) stateType,
                          s -> s.originalEventType);
          var diffListType = out.writeAndStoreType(
                  id -> new me.bechberger.condensed.types.ArrayType<>(
                          id,
                          out.writeAndStoreType(
                                  vid -> new me.bechberger.condensed.types.VarIntType(vid, true))));
          var startTimeDiffsField =
                  new StructType.Field<ExecutionSampleState, List<Long>, List<Long>>(
                          "startTimeDiffs", "",
                          (CondensedType<List<Long>, List<Long>>) (CondensedType<?, ?>) diffListType,
                          s -> s.startTimeDiffsNanos);
          return out.writeAndStoreType(
                  id ->
                          new StructType<>(
                                  id,
                                  "jdk.combined.ExecutionSample",
                                  List.of(
                                          startTimeField,
                                          sampledThreadField,
                                          stackTraceField,
                                          stateField,
                                          originalEventTypeField,
                                          startTimeDiffsField)));
      }

      @Override
      public ExecutionSampleToken createToken(RecordedEvent event) {
          var stackTrace = event.getStackTrace();
          var reduced =
                  stackTrace != null
                          ? ReducedStackTrace.create(
                                  stackTrace,
                                  (int) configuration.maxStackTraceDepth())
                          : new ReducedStackTrace(
                                  new jdk.jfr.consumer.RecordedStackTrace[0]);
          long epochSec = event.getStartTime().getEpochSecond();
          long bucket = epochSec / profilingBucketSeconds;
          return new ExecutionSampleToken(
                  event.getThread("sampledThread"),
                  reduced,
                  event.getString("state"),
                  event.getEventType().getName(),
                  bucket);
      }

      @Override
      public ExecutionSampleState createInitialState(
              ExecutionSampleToken token, RecordedEvent event) {
          return new ExecutionSampleState(
                  event.getStartTime(),
                  token.sampledThread(),
                  token.stackTrace(),
                  token.state(),
                  token.originalEventType());
      }

      @Override
      public void combine(
              ExecutionSampleToken token, ExecutionSampleState state, RecordedEvent event) {
          long diffNanos =
                  event.getStartTime().toEpochMilli() * 1_000_000L
                          - state.startTime.toEpochMilli() * 1_000_000L
                          + (event.getStartTime().getNano() - state.startTime.getNano());
          state.startTimeDiffsNanos.add(diffNanos);
      }

      @Override
      public Instant getStartTimestamp(ExecutionSampleState state) {
          return state.startTime;
      }
  }
  ```

  > **Note on `ReducedStackTrace` constructor:** the existing `ReducedStackTrace` has no public zero-arg constructor. For events with no stack trace, pass `null` for the `stackTrace` field in the state and handle it in the reconstitutor. Adjust the token to use `stackTrace = null` when `event.getStackTrace() == null` and update `createToken` accordingly:

  ```java
  // In createToken, replace the reduced= assignment:
  var raw = event.getStackTrace();
  var reduced = raw == null ? null
          : ReducedStackTrace.create(raw, (int) configuration.maxStackTraceDepth());
  ```

  And update the token field handling in `createInitialState`:

  ```java
  return new ExecutionSampleState(
          event.getStartTime(),
          event.getThread("sampledThread"),
          reduced,  // may be null
          token.state(),
          token.originalEventType());
  ```

- [ ] **Step 3: Add `ExecutionSampleReconstitutor` class**

  Add after `ExecutionSampleCombiner`:

  ```java
  static class ExecutionSampleReconstitutor
          extends AbstractReconstitutor<ExecutionSampleCombiner> {

      public ExecutionSampleReconstitutor(String originalEventTypeName) {
          super(originalEventTypeName);
      }

      @Override
      public <E> List<E> reconstitute(
              StructType<?, ?> resultEventType,
              ReadStruct combined,
              EventBuilder<E, ?> builder) {
          Instant windowStart = combined.get(Instant.class, "startTime");
          @SuppressWarnings("unchecked")
          List<Long> diffs = (List<Long>) combined.get("startTimeDiffs");
          List<E> result = new ArrayList<>(diffs.size());
          for (Long diff : diffs) {
              Instant ts = windowStart.plusNanos(diff);
              result.add(
                      builder.put("startTime", ts)
                              .put("sampledThread", combined.get("sampledThread"))
                              .put("stackTrace", combined.get("stackTrace"))
                              .put("state", combined.get("state"))
                              .addStandardFieldsIfNeeded()
                              .build());
          }
          return result;
      }
  }
  ```

- [ ] **Step 4: Register reconstitutors in `recons` static block**

  In the `static { var m = ... }` block at the bottom of `JFREventCombiner`, add:

  ```java
  m.put(CombinedEventType.EXECUTION_SAMPLE,
          new ExecutionSampleReconstitutor("jdk.ExecutionSample"));
  m.put(CombinedEventType.NATIVE_METHOD_SAMPLE,
          new ExecutionSampleReconstitutor("jdk.NativeMethodSample"));
  ```

- [ ] **Step 5: Register combiners in `processNewEventType`**

  In `JFREventCombiner.processNewEventType`, add after the `combineBlockingEvents` block:

  ```java
  if (configuration.combineProfilingSamples()) {
      if (eventType.getName().equals("jdk.ExecutionSample")
              || eventType.getName().equals("jdk.NativeMethodSample")) {
          put(eventType, new ExecutionSampleCombiner(configuration, basicJFRWriter));
      }
  }
  ```

- [ ] **Step 6: Compile check**

  ```bash
  ./mvnw compile -pl . -q
  ```

  Fix any compilation errors before proceeding.

- [ ] **Step 7: Write failing round-trip test**

  Add to `JFREventCombinerTest.java`:

  ```java
  @Name("jdk.ExecutionSample")
  @Label("Method Profiling Sample")
  @StackTrace(true)
  static class FakeExecutionSample extends Event {
      @Label("Thread")
      Thread sampledThread;
      @Label("Thread State")
      String state;

      FakeExecutionSample(String state) {
          this.sampledThread = Thread.currentThread();
          this.state = state;
      }
  }

  @Test
  public void testExecutionSampleCombinerRoundTrip() throws Exception {
      var config = Configuration.REDUCED_DEFAULT;
      var outputStream = new ByteArrayOutputStream();
      List<Instant> originalTimestamps = new ArrayList<>();

      try (var out = new CondensedOutputStream(outputStream, StartMessage.DEFAULT)) {
          var writer = new BasicJFRWriter(out, config);
          try (var rs = new RecordingStream()) {
              var count = new java.util.concurrent.atomic.AtomicInteger(0);
              rs.onEvent("jdk.ExecutionSample", event -> {
                  originalTimestamps.add(event.getStartTime());
                  writer.processEvent(event);
                  if (count.incrementAndGet() >= 3) rs.close();
              });
              rs.startAsync();
              for (int i = 0; i < 3; i++) {
                  new FakeExecutionSample("STATE_RUNNABLE").commit();
                  Thread.sleep(5);
              }
              rs.awaitTermination();
          }
      }

      // Inflate and verify we get back 3 individual ExecutionSample events
      var inflated = new ByteArrayOutputStream();
      try (var in = new CondensedInputStream(outputStream.toByteArray())) {
          var jfrReader = new WritingJFRReader(new BasicJFRReader(in), inflated);
          while (jfrReader.readNextJFREvent() != null) {}
          jfrReader.close();
      }
      // Count reconstituted events using jfr print
      var tmp = java.nio.file.Files.createTempFile("test", ".jfr");
      java.nio.file.Files.write(tmp, inflated.toByteArray());
      var result = new ProcessBuilder("jfr", "summary", tmp.toString())
              .redirectErrorStream(true).start();
      var output = new String(result.getInputStream().readAllBytes());
      java.nio.file.Files.delete(tmp);
      assertTrue(output.contains("jdk.ExecutionSample"),
              "Expected jdk.ExecutionSample in inflated output but got:\n" + output);
  }
  ```

- [ ] **Step 8: Run test**

  ```bash
  ./mvnw test -pl . -Dtest=JFREventCombinerTest#testExecutionSampleCombinerRoundTrip -q
  ```

  Expected: PASS (or diagnose and fix failures).

- [ ] **Step 9: Run full suite**

  ```bash
  ./mvnw test -pl . -q
  ```

  Expected: all pass.

- [ ] **Step 10: Commit**

  ```bash
  git add src/main/java/me/bechberger/jfr/JFREventCombiner.java \
          src/test/java/me/bechberger/jfr/JFREventCombinerTest.java
  git commit -m "feat(combiner): add ExecutionSample/NativeMethodSample time-window combiner"
  ```

---

### Task 5: Implement I/O combiners (`SocketRead`, `SocketWrite`, `FileRead`, `FileWrite`, `FileForce`)

**Files:**
- Modify: `src/main/java/me/bechberger/jfr/JFREventCombiner.java`

All five I/O combiners follow the same pattern. The grouping key is `(eventThread, host+address+port | path, bucketIndex)`. Each combined event stores `startTimeDiffs[]`, `durations[]`, and type-specific arrays (`bytesRead[]`/`bytesWritten[]`, `endOfStream[]`/`endOfFile[]`, `metaData[]`).

The cleanest implementation is a single generic `IOEventCombiner` parameterized by the event type name plus a list of extra array fields, and a matching `IOEventReconstitutor`. The five types differ only in: key extraction (socket: host+address+port, file: path), extra array fields (bytes, boolean flag), and the combined/original type names.

- [ ] **Step 1: Add token, state, and generic combiner**

  Add after `ExecutionSampleReconstitutor` in `JFREventCombiner.java`:

  ```java
  record IOEventToken(
          jdk.jfr.consumer.RecordedThread eventThread,
          String key, // "host:address:port" for socket, "path" for file
          long bucketIndex) {}

  static final class IOEventState implements JFRObjectState {
      final Instant startTime;
      final jdk.jfr.consumer.RecordedThread eventThread;
      final String key;
      final List<Long> startTimeDiffsNanos = new ArrayList<>();
      final List<Long> durationsNanos = new ArrayList<>();
      final @Nullable List<Long> bytes;   // null for FileForce
      final @Nullable List<Boolean> flag; // endOfStream, endOfFile, metaData — null if absent

      IOEventState(
              Instant startTime,
              jdk.jfr.consumer.RecordedThread eventThread,
              String key,
              boolean hasBytes,
              boolean hasFlag) {
          this.startTime = startTime;
          this.eventThread = eventThread;
          this.key = key;
          this.bytes = hasBytes ? new ArrayList<>() : null;
          this.flag = hasFlag ? new ArrayList<>() : null;
          startTimeDiffsNanos.add(0L);
          durationsNanos.add(0L);
          if (hasBytes) bytes.add(0L);
          if (hasFlag) flag.add(false);
      }

      @Override public Instant startTime() { return startTime; }
      @Override public JFREventCombiner.DefinedMap<RecordedEvent> map() {
          throw new UnsupportedOperationException();
      }
  }

  /**
   * Generic I/O event combiner. {@code keyFields} is the ordered list of fields that form the
   * grouping key (["host","address","port"] for socket, ["path"] for file). {@code bytesField}
   * is the name of the bytes field ("bytesRead", "bytesWritten", null for FileForce).
   * {@code flagField} is the boolean field name ("endOfStream", "endOfFile", "metaData", null
   * if absent).
   */
  static class IOEventCombiner
          implements Combiner<IOEventToken, IOEventState> {

      private final String combinedTypeName;
      private final String originalEventTypeName;
      private final List<String> keyFields;
      private final @Nullable String bytesField;
      private final @Nullable String flagField;
      private final long profilingBucketSeconds;
      private final Configuration configuration;
      private final BasicJFRWriter basicJFRWriter;

      IOEventCombiner(
              CombinedEventType combinedEventType,
              List<String> keyFields,
              @Nullable String bytesField,
              @Nullable String flagField,
              Configuration configuration,
              BasicJFRWriter basicJFRWriter) {
          this.combinedTypeName = combinedEventType.getCombinedTypeName();
          this.originalEventTypeName = combinedEventType.getOriginalTypeName();
          this.keyFields = keyFields;
          this.bytesField = bytesField;
          this.flagField = flagField;
          this.profilingBucketSeconds = configuration.profilingBucketSeconds();
          this.configuration = configuration;
          this.basicJFRWriter = basicJFRWriter;
      }

      @Override
      public StructType<IOEventState, ?> createCombinedStateType(
              CondensedOutputStream out, EventType eventType) {
          // Build field list dynamically
          // (implementation follows the pattern of ExecutionSampleCombiner.createCombinedStateType)
          // Fields: startTime, eventThread, key fields (one String per key field), startTimeDiffs[],
          //         durations[], bytes[] (if present), flag[] (if present)
          List<StructType.Field<IOEventState, ?, ?>> fields = new ArrayList<>();

          fields.add(new StructType.Field<>(
                  "startTime",
                  basicJFRWriter.getDescription(eventType.getField("startTime")),
                  (CondensedType<Instant, Instant>)
                          basicJFRWriter.getTypeCached(eventType.getField("startTime")),
                  IOEventState::startTime,
                  me.bechberger.condensed.Universe.EmbeddingType.INLINE,
                  JFRReduction.TIMESTAMP_REDUCTION.ordinal()));

          fields.add(basicJFRWriter.<IOEventState>eventFieldToField2(
                  eventType.getField("eventThread"), s -> s.eventThread, false));

          var stringType = (CondensedType<String, String>)
                  basicJFRWriter.getTypeCached(eventType.getField(keyFields.get(0)));
          for (String kf : keyFields) {
              final String kfFinal = kf;
              fields.add(new StructType.Field<>(kfFinal, "", stringType,
                      s -> s.key)); // all key fields share the combined key string
          }
          // Overwrite: for socket we need to store host, address, port separately.
          // For simplicity, store the raw values from the state's key string split on ':'.
          // Actually, store them as a single combined key string is lossy for socket (port is int).
          // => Instead, store each key field separately in IOEventState as an Object[].
          // This is a design note: see Step 2 below for the corrected approach.

          // [This field list definition is completed in Step 2 after the state is corrected]
          return out.writeAndStoreType(id -> new StructType<>(id, combinedTypeName, fields));
      }

      @Override
      public IOEventToken createToken(RecordedEvent event) {
          var thread = event.getThread("eventThread");
          String key = keyFields.stream()
                  .map(f -> String.valueOf(event.getValue(f)))
                  .collect(java.util.stream.Collectors.joining(":"));
          long bucket = event.getStartTime().getEpochSecond() / profilingBucketSeconds;
          return new IOEventToken(thread, key, bucket);
      }

      @Override
      public IOEventState createInitialState(IOEventToken token, RecordedEvent event) {
          var state = new IOEventState(
                  event.getStartTime(), token.eventThread(), token.key(),
                  bytesField != null, flagField != null);
          // overwrite placeholder first-element values with real data
          if (bytesField != null) { state.bytes.set(0, event.getLong(bytesField)); }
          if (flagField != null) { state.flag.set(0, event.getBoolean(flagField)); }
          state.durationsNanos.set(0, event.getDuration().toNanos());
          return state;
      }

      @Override
      public void combine(IOEventToken token, IOEventState state, RecordedEvent event) {
          long diff = java.time.Duration.between(state.startTime, event.getStartTime()).toNanos();
          state.startTimeDiffsNanos.add(diff);
          state.durationsNanos.add(event.getDuration().toNanos());
          if (bytesField != null) state.bytes.add(event.getLong(bytesField));
          if (flagField != null) state.flag.add(event.getBoolean(flagField));
      }
  }
  ```

  > **Design correction note:** The key fields for socket (host, address, port) must be stored separately in the combined event so inflate can reconstitute each individual field. Update `IOEventState` to hold `Map<String, Object> keyValues` instead of a plain `String key`, and update `createToken` to use a composite for equality only. See Step 2.

- [ ] **Step 2: Revise `IOEventState` and `IOEventToken` for per-field key storage**

  Replace the `IOEventState` and `IOEventToken` from Step 1 with:

  ```java
  record IOEventToken(
          jdk.jfr.consumer.RecordedThread eventThread,
          String compositeKey, // used for token equality/hashing only
          long bucketIndex) {}

  static final class IOEventState implements JFRObjectState {
      final Instant startTime;
      final jdk.jfr.consumer.RecordedThread eventThread;
      final java.util.LinkedHashMap<String, Object> keyValues; // field name → value, ordered
      final List<Long> startTimeDiffsNanos = new ArrayList<>();
      final List<Long> durationsNanos = new ArrayList<>();
      final @Nullable List<Long> bytes;
      final @Nullable List<Boolean> flag;

      IOEventState(
              Instant startTime,
              jdk.jfr.consumer.RecordedThread eventThread,
              java.util.LinkedHashMap<String, Object> keyValues,
              boolean hasBytes,
              boolean hasFlag) {
          this.startTime = startTime;
          this.eventThread = eventThread;
          this.keyValues = keyValues;
          this.bytes = hasBytes ? new ArrayList<>() : null;
          this.flag = hasFlag ? new ArrayList<>() : null;
      }

      @Override public Instant startTime() { return startTime; }
      @Override public JFREventCombiner.DefinedMap<RecordedEvent> map() {
          throw new UnsupportedOperationException();
      }
  }
  ```

  Update `IOEventCombiner.createToken` to build `compositeKey` from `String.valueOf` join, and `createInitialState` to build a `LinkedHashMap<String, Object>` from `keyFields` → `event.getValue(field)`.

  Update `createCombinedStateType` to emit one typed field per key field using the event's actual field type.

- [ ] **Step 3: Complete `createCombinedStateType` in `IOEventCombiner`**

  Replace the placeholder `createCombinedStateType` body with:

  ```java
  @Override
  public StructType<IOEventState, ?> createCombinedStateType(
          CondensedOutputStream out, EventType eventType) {
      List<StructType.Field<IOEventState, ?, ?>> fields = new ArrayList<>();

      // startTime
      fields.add(new StructType.Field<>(
              "startTime",
              basicJFRWriter.getDescription(eventType.getField("startTime")),
              (CondensedType<Instant, Instant>)
                      basicJFRWriter.getTypeCached(eventType.getField("startTime")),
              IOEventState::startTime,
              me.bechberger.condensed.Universe.EmbeddingType.INLINE,
              JFRReduction.TIMESTAMP_REDUCTION.ordinal()));

      // eventThread
      fields.add(new StructType.Field<IOEventState, Object, Object>(
              "eventThread",
              basicJFRWriter.getDescription(eventType.getField("eventThread")),
              (CondensedType<Object, Object>) basicJFRWriter.getTypeCached(eventType.getField("eventThread")),
              s -> s.eventThread));

      // key fields (host/address/port or path)
      for (String kf : keyFields) {
          final String kfFinal = kf;
          @SuppressWarnings("unchecked")
          var fieldType = (CondensedType<Object, Object>)
                  basicJFRWriter.getTypeCached(eventType.getField(kfFinal));
          fields.add(new StructType.Field<>(
                  kfFinal, "",
                  fieldType,
                  s -> s.keyValues.get(kfFinal)));
      }

      // startTimeDiffs[]
      var varIntType = out.writeAndStoreType(
              id -> new me.bechberger.condensed.types.VarIntType(id, true));
      var longArrayType = out.writeAndStoreType(
              id -> new me.bechberger.condensed.types.ArrayType<>(id, varIntType));
      fields.add(new StructType.Field<>(
              "startTimeDiffs", "",
              (CondensedType<List<Long>, List<Long>>) (CondensedType<?, ?>) longArrayType,
              s -> s.startTimeDiffsNanos));

      // durations[]
      fields.add(new StructType.Field<>(
              "durations", "",
              (CondensedType<List<Long>, List<Long>>) (CondensedType<?, ?>) longArrayType,
              s -> s.durationsNanos));

      // bytes[] (optional)
      if (bytesField != null) {
          fields.add(new StructType.Field<>(
                  bytesField, "",
                  (CondensedType<List<Long>, List<Long>>) (CondensedType<?, ?>) longArrayType,
                  s -> s.bytes));
      }

      // flag[] (optional)
      if (flagField != null) {
          var boolType = out.writeAndStoreType(
                  id -> new me.bechberger.condensed.types.BooleanType(id));
          var boolArrayType = out.writeAndStoreType(
                  id -> new me.bechberger.condensed.types.ArrayType<>(id, boolType));
          fields.add(new StructType.Field<>(
                  flagField, "",
                  (CondensedType<List<Boolean>, List<Boolean>>) (CondensedType<?, ?>) boolArrayType,
                  s -> s.flag));
      }

      return out.writeAndStoreType(id -> new StructType<>(id, combinedTypeName, fields));
  }
  ```

  > **Note:** If `BooleanType` does not exist in the type system, use `IntType` (width=1) as a stand-in and store `flag` as `List<Integer>` (0/1). Check `me.bechberger.condensed.types` for the correct boolean type name. The existing combiner code uses `boolean` fields via `eventFieldToField2` — look at how those are typed for guidance.

- [ ] **Step 4: Add `IOEventReconstitutor`**

  ```java
  static class IOEventReconstitutor
          extends AbstractReconstitutor<IOEventCombiner> {

      private final List<String> keyFields;
      private final @Nullable String bytesField;
      private final @Nullable String flagField;

      IOEventReconstitutor(
              String originalEventTypeName,
              List<String> keyFields,
              @Nullable String bytesField,
              @Nullable String flagField) {
          super(originalEventTypeName);
          this.keyFields = keyFields;
          this.bytesField = bytesField;
          this.flagField = flagField;
      }

      @Override
      public <E> List<E> reconstitute(
              StructType<?, ?> resultEventType,
              ReadStruct combined,
              EventBuilder<E, ?> builder) {
          Instant windowStart = combined.get(Instant.class, "startTime");
          @SuppressWarnings("unchecked")
          List<Long> diffs = (List<Long>) combined.get("startTimeDiffs");
          @SuppressWarnings("unchecked")
          List<Long> durations = (List<Long>) combined.get("durations");
          @SuppressWarnings("unchecked")
          List<Long> bytes = bytesField != null ? (List<Long>) combined.get(bytesField) : null;
          @SuppressWarnings("unchecked")
          List<Boolean> flags = flagField != null ? (List<Boolean>) combined.get(flagField) : null;

          List<E> result = new ArrayList<>(diffs.size());
          for (int i = 0; i < diffs.size(); i++) {
              var b = builder
                      .put("startTime", windowStart.plusNanos(diffs.get(i)))
                      .put("duration", durations.get(i))
                      .put("eventThread", combined.get("eventThread"));
              for (String kf : keyFields) {
                  b.put(kf, combined.get(kf));
              }
              if (bytesField != null) b.put(bytesField, bytes.get(i));
              if (flagField != null) b.put(flagField, flags.get(i));
              b.addStandardFieldsIfNeeded();
              result.add(b.build());
          }
          return result;
      }
  }
  ```

- [ ] **Step 5: Register reconstitutors in the `recons` static block**

  ```java
  m.put(CombinedEventType.SOCKET_READ, new IOEventReconstitutor(
          "jdk.SocketRead", List.of("host", "address", "port"), "bytesRead", "endOfStream"));
  m.put(CombinedEventType.SOCKET_WRITE, new IOEventReconstitutor(
          "jdk.SocketWrite", List.of("host", "address", "port"), "bytesWritten", null));
  m.put(CombinedEventType.FILE_READ, new IOEventReconstitutor(
          "jdk.FileRead", List.of("path"), "bytesRead", "endOfFile"));
  m.put(CombinedEventType.FILE_WRITE, new IOEventReconstitutor(
          "jdk.FileWrite", List.of("path"), "bytesWritten", null));
  m.put(CombinedEventType.FILE_FORCE, new IOEventReconstitutor(
          "jdk.FileForce", List.of("path"), null, "metaData"));
  ```

- [ ] **Step 6: Register combiners in `processNewEventType`**

  Add after the `combineProfilingSamples` block:

  ```java
  if (configuration.combineIOEvents()) {
      if (eventType.getName().equals("jdk.SocketRead"))
          put(eventType, new IOEventCombiner(CombinedEventType.SOCKET_READ,
                  List.of("host", "address", "port"), "bytesRead", "endOfStream",
                  configuration, basicJFRWriter));
      if (eventType.getName().equals("jdk.SocketWrite"))
          put(eventType, new IOEventCombiner(CombinedEventType.SOCKET_WRITE,
                  List.of("host", "address", "port"), "bytesWritten", null,
                  configuration, basicJFRWriter));
      if (eventType.getName().equals("jdk.FileRead"))
          put(eventType, new IOEventCombiner(CombinedEventType.FILE_READ,
                  List.of("path"), "bytesRead", "endOfFile",
                  configuration, basicJFRWriter));
      if (eventType.getName().equals("jdk.FileWrite"))
          put(eventType, new IOEventCombiner(CombinedEventType.FILE_WRITE,
                  List.of("path"), "bytesWritten", null,
                  configuration, basicJFRWriter));
      if (eventType.getName().equals("jdk.FileForce"))
          put(eventType, new IOEventCombiner(CombinedEventType.FILE_FORCE,
                  List.of("path"), null, "metaData",
                  configuration, basicJFRWriter));
  }
  ```

- [ ] **Step 7: Compile and run full suite**

  ```bash
  ./mvnw compile -pl . -q
  ./mvnw test -pl . -q
  ```

  Expected: all pass.

- [ ] **Step 8: Commit**

  ```bash
  git add src/main/java/me/bechberger/jfr/JFREventCombiner.java
  git commit -m "feat(combiner): add I/O event time-window combiners (Socket/File Read/Write/Force)"
  ```

---

### Task 6: Round-trip test on real JFR files and benchmark

**Files:**
- Test: `src/test/java/me/bechberger/jfr/ProfilingReductionRoundTripTest.java` (new)

- [ ] **Step 1: Write round-trip test using `flight_recording_21TheJVMRunningMissionControl.jfr`**

  Create `src/test/java/me/bechberger/jfr/ProfilingReductionRoundTripTest.java`:

  ```java
  package me.bechberger.jfr;

  import static org.junit.jupiter.api.Assertions.*;

  import java.io.*;
  import java.nio.file.*;
  import java.util.*;
  import org.junit.jupiter.api.*;

  /**
   * Round-trip test: condense a real profiling JFR with reduced config, inflate, verify
   * event counts are preserved per type.
   */
  public class ProfilingReductionRoundTripTest {

      private static final Path SOURCE = Path.of(
              System.getProperty("user.home"),
              "flight_recording_21TheJVMRunningMissionControl.jfr");

      @Test
      public void testExecutionSampleRoundTrip() throws Exception {
          Assumptions.assumeTrue(Files.exists(SOURCE), "Source JFR not found, skipping");

          long originalCount = countEvents(SOURCE, "jdk.ExecutionSample");
          long originalNativeCount = countEvents(SOURCE, "jdk.NativeMethodSample");
          Assumptions.assumeTrue(originalCount > 0 || originalNativeCount > 0,
                  "No ExecutionSample/NativeMethodSample in source, skipping");

          var condensed = condense(SOURCE, Configuration.REDUCED_DEFAULT);
          var inflated = inflate(condensed);

          assertEquals(originalCount, countEvents(inflated, "jdk.ExecutionSample"),
                  "ExecutionSample count must survive round-trip");
          assertEquals(originalNativeCount, countEvents(inflated, "jdk.NativeMethodSample"),
                  "NativeMethodSample count must survive round-trip");

          // Size benefit: condensed should be smaller than original
          long origSize = Files.size(SOURCE);
          long condSize = condensed.length;
          System.out.printf("ExecutionSample round-trip: original=%d bytes, condensed=%d bytes, " +
                  "ratio=%.1f%%%n", origSize, condSize, 100.0 * condSize / origSize);
      }

      @Test
      public void testSocketReadRoundTrip() throws Exception {
          Assumptions.assumeTrue(Files.exists(SOURCE), "Source JFR not found, skipping");
          long originalCount = countEvents(SOURCE, "jdk.SocketRead");
          Assumptions.assumeTrue(originalCount > 0, "No SocketRead events, skipping");

          var condensed = condense(SOURCE, Configuration.REDUCED_DEFAULT);
          var inflated = inflate(condensed);
          assertEquals(originalCount, countEvents(inflated, "jdk.SocketRead"),
                  "SocketRead count must survive round-trip");
      }

      private static byte[] condense(Path source, Configuration config) throws Exception {
          var out = new ByteArrayOutputStream();
          try (var cos = new me.bechberger.condensed.CondensedOutputStream(
                  out, me.bechberger.condensed.Message.StartMessage.DEFAULT)) {
              var writer = new BasicJFRWriter(cos, config);
              writer.processFile(source.toFile());
          }
          return out.toByteArray();
      }

      private static Path inflate(byte[] condensed) throws Exception {
          var tmp = Files.createTempFile("inflated", ".jfr");
          try (var cos = new me.bechberger.condensed.CondensedInputStream(condensed)) {
              var reader = new WritingJFRReader(new BasicJFRReader(cos),
                      new FileOutputStream(tmp.toFile()));
              while (reader.readNextJFREvent() != null) {}
              reader.close();
          }
          return tmp;
      }

      private static long countEvents(Path jfr, String eventType) throws Exception {
          var result = new ProcessBuilder("jfr", "summary", jfr.toString())
                  .redirectErrorStream(true).start();
          var output = new String(result.getInputStream().readAllBytes());
          for (String line : output.split("\n")) {
              if (line.trim().startsWith(eventType + " ") || line.trim().startsWith(eventType + "\t")) {
                  var parts = line.trim().split("\\s+");
                  if (parts.length >= 2) return Long.parseLong(parts[1]);
              }
          }
          return 0;
      }
  }
  ```

- [ ] **Step 2: Run round-trip test**

  ```bash
  ./mvnw test -pl . -Dtest=ProfilingReductionRoundTripTest -q
  ```

  Expected: PASS with size ratio printed to stdout.

- [ ] **Step 3: Benchmark manually on the densest files**

  ```bash
  # flight.jfr: 71k ExecutionSamples, 9k unique groups
  time java -jar target/condensed-data-*.jar condense \
      --condenser-config reduced \
      /Users/i560383_1/Downloads/flight.jfr /tmp/flight.cjfr
  ls -lh /Users/i560383_1/Downloads/flight.jfr /tmp/flight.cjfr

  # aprof.jfr: 26k ExecutionSamples, 1.6k unique groups
  time java -jar target/condensed-data-*.jar condense \
      --condenser-config reduced \
      /Users/i560383_1/Downloads/aprof.jfr /tmp/aprof.cjfr
  ls -lh /Users/i560383_1/Downloads/aprof.jfr /tmp/aprof.cjfr
  ```

  Report the size ratios. If the combined format is larger than expected (overhead > savings for small event counts), note it — the combiner is already gated to `reduced` only so this is acceptable.

- [ ] **Step 4: Run full test suite one final time**

  ```bash
  ./mvnw test -pl . -q
  ```

  Expected: all pass.

- [ ] **Step 5: Commit**

  ```bash
  git add src/test/java/me/bechberger/jfr/ProfilingReductionRoundTripTest.java
  git commit -m "test: add profiling/IO round-trip and size-benefit tests"
  ```

---

## Self-Review

**Spec coverage check:**
- ✅ Change 1 (`state` strip): Task 2
- ✅ Change 2 (ExecutionSample/NativeMethodSample combiner): Tasks 3, 4
- ✅ Change 3 (I/O combiners): Tasks 3, 5
- ✅ `profilingBucketSeconds` config param: Task 1
- ✅ `CombinedEventType` enum entries: Task 3
- ✅ `processNewEventType` registration: Tasks 4, 5
- ✅ `recons` static map registration: Tasks 4, 5
- ✅ Round-trip tests: Tasks 4 (unit), 6 (integration)
- ✅ Benchmark: Task 6

**Potential issues flagged:**
- `BooleanType` — confirmed to exist at `me.bechberger.condensed.types.BooleanType`.
- `eventFieldToField` — only available for `T extends RecordedObject` types (JFR API getters). For combiner state types, construct `StructType.Field` directly with `getTypeCached` and a custom getter lambda, as shown in the plan and in `ZStatisticsCombiner`.
- `StructType.Field` constructor arity — the 6-arg constructor (with embedding type and reduction) is only needed for `startTime`. All other fields use the 4-arg constructor `(name, description, type, getter)`.
- `ReducedStackTrace` null handling — `event.getStackTrace()` returns null when stack traces are disabled. Always null-check before calling `ReducedStackTrace.create`.
