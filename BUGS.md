# Bug Report

Bugs found while using `cjfr` as a normal user.

**Resolved bugs removed from this file:** 218, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 245, 246, 247, 248.

## Optimization: ZStatisticsCounter/Sampler `value` stored as a signed residual (lossless size win)

**Status:** Implemented. Pure size optimization — no behaviour change; still exactly lossless.

**Observation:** the `value` field of `jdk.ZStatisticsCounter` / `jdk.ZStatisticsSampler` is
(near-)cumulative *within a statistic id*: for counters `value_n ≈ value_{n-1} + increment_n`
(samplers have no `increment`, so `value_n ≈ value_{n-1}`). Storing the raw magnitude wastes a
multi-byte varint on every event even though the delta is almost always zero — on the ZGC benchmark
recordings ~99.7% of the per-id deltas are 0.

**Change:** `ZStatisticsCombiner.createValueDefinition` now stores `value` as a **signed** residual
`value - prevValue - increment` (default `VarIntType`, one byte when 0), and
`ZStatisticsReconstitutor` rebuilds `value = prevValue + increment + residual` by replaying entries
in serialized order. The writer holds per-id running state in a `Map` captured by the residual
getter. On the read side the per-id state is scoped to the **inflate session** (a fresh map per
`createReadStructReconstitutor` call) — crucially NOT an instance field, because reconstitutors live
in a JVM-lifetime static registry and an instance field would leak the last cumulative value of one
inflate into the next (observed as every decoded value being offset by a constant). Correctness
relies on the combiner's write getters running in the *same per-id sequence* the reconstitutor
replays — verified by per-(id,value) multiset equality (order-independent, since event reordering is
an accepted non-bug).

**Measured (lossless preset):** the `value` field drops 43385 → 15470 bytes (−28 KB, ≈ −2.6% of the
whole `.cjfr`) on a large ZGC recording, and the saving **survives max compression** (the compressor
cannot recover the cumulative structure the residual encoding removes).

**Backward-compat:** no CJFR version bump — the combined-type schema is self-describing in the
stream; old `.cjfr` files still read via the reconstitutor's numeric branches. `profile.cjfr`
fixture regenerated (schema bytes change even with 0 ZStatistics events, per the combiner-schema
versioning note). Regression guard: `ZStatisticsValueResidualTest` — a lossless round-trip on
`benchmark/renaissance-dotty_gc_ZGC.jfr` asserting the per-id value multiset is preserved for both
event types, plus a second test that runs the round-trip twice in one JVM to guard against the
reconstitutor-state leak.

## Bug 219: `double` JFR fields silently truncated to 32-bit `float` precision during condensation

**Status:** By design.

`double` values are stored as `float` (32-bit) in the condensed format for default/reasonable-default configs, and as `float16` for reduced-default. Only the full/lossless config preserves `double` precision. This is documented behavior for space savings.

**Impact:** Low — expected precision trade-off in lossy configurations.

## Bug 243: lossy informational mismatch in `renaissance-dotty_default_G1.jfr` (config `reasonable-default`, compression `ZSTD`)

**Status:** Informational — expected for lossy configuration.

Lossy configurations (`reasonable-default`, `reduced-default`) combine and reduce events by design. Event-type deltas observed:
- `jdk.TenuringDistribution`: `1065 -> 89`
- `jdk.GCPhasePauseLevel1`: `323 -> 182`
- `jdk.GCPhasePauseLevel2`: `159 -> 123`
- `jdk.GCPhaseConcurrentLevel1`: `44 -> 27`

## Bug 244: `inflate` was very slow on large detail recordings

**Status:** Fixed.

**Root Cause:** `WritingJFRReader.toTypedValue()` was redundantly converting the same sub-struct `ReadStruct` instances (threads, stack traces, etc.) to JMC `TypedValue` objects for every event. The condensed format's universe cache returns identical `ReadStruct` instances, but each conversion triggered expensive deep `equals()/hashCode()` in the JMC constant pool.

**Fix:** Added an `IdentityHashMap<ReadStruct, TypedValue>` cache in `WritingJFRReader` to reuse converted sub-structs, and periodic JMC chunk rotation (every 100K events) to bound per-chunk memory. Also added progress reporting every 10s.

**Result:** `renaissance-all_gc_details_G1.cjfr` inflate: timeout at 180s → 35s. Small file: 41.6s → 2.1s.

## Bug 248: `condense` is slow on large detail recordings

**Status:** Not a bug — `condense` completes in ~48s for the 242 MB file. Previous timeout reports were due to insufficient timeout windows.

## Bug 249: `GCPhaseParallelCombiner` silently drops most `jdk.GCPhaseParallel` events

**Status:** Fixed.

**Root Cause:** `GCPhaseParallelCombiner.createValueDefinition()` used `SingleValue` for the per-name worker data. Within a single GC ID, multiple GC worker threads produce events with the same phase name (e.g., "GC Worker Start"). `SingleValue` only keeps the last event per name, silently dropping all other workers' data.

For `renaissance-dotty_default_G1.jfr` with the `default` config: 69426 → 2034 `jdk.GCPhaseParallel` events (97% loss). For `renaissance-fj-kmeans_default_G1.jfr`: 95.2% total event loss reduced to 0.78% after fix.

**Fix:** Changed `SingleValue` to `ArrayValue` in `GCPhaseParallelCombiner` so all worker entries per phase name are preserved. Updated `GCPhaseParallelReconstitutor` to iterate over the array, with backward compatibility for old `.cjfr` files that used the single-value format.

## Bug 250: `view` renders nested struct fields as an ambiguous, inconsistently-formatted comma list

**Status:** Fixed.

Running `cjfr view profile.jfr jdk.GCHeapSummary` renders the nested `heapSpace`
struct (`jdk.VirtualSpace`) on a single row as:

```
Heap Space
21474836480, 22296920064, 784.0MB, 34359738368, 12.0GB
```

Two facets:

1. **No field labels.** `JFRView.StructColumn.format` joins only the sub-field
   *values* with `", "` when the struct fits on one row (the common case),
   dropping the field names. The reader cannot tell which number is which. The
   JDK `jfr print` tool renders the same struct with named sub-fields
   (`start = 0x500000000`, `committedSize = 784,0 MB`, ...).

2. **Inconsistent per-field formatting.** `@MemoryAddress` fields
   (`start`, `committedEnd`, `reservedEnd`) are printed as raw decimal byte
   counts because `@MemoryAddress` is not handled in `JFRView.fieldToColumn`
   (it falls through to `IntegerColumn`), while `@DataAmount` fields
   (`committedSize`, `reservedSize`) are formatted with units. The output mixes
   `21474836480` and `784.0MB` in the same cell.

**Impact:** Medium — nested-struct fields (GC heap summaries, virtual spaces)
are unreadable and misleading in `view`.

**Fix:** In `JFRView.StructColumn.format`, the single-row path now prefixes each
sub-field with its header (`label=value`) instead of joining bare values. Added
`JFRView.MemoryAddressColumn` (renders `0x…` hex) and dispatch for the
`@MemoryAddress` annotation in `fieldToColumn`, so address fields no longer print
as raw decimals next to unit-formatted `@DataAmount` fields.

## Bug 252: fields with both `@Unsigned` and `@Timestamp`/`@Timespan` lose the tick→epoch conversion during condensation

**Status:** Fixed.

`cjfr view profile.jfr jdk.OldObjectSample` showed `Allocation Time = 01:00:00`
(the epoch in +01:00), and a roundtrip
(`condense` → `inflate` → `jfr print`) produced
`allocationTime = 1970-01-01T00:00:00.218Z` instead of the real
`2025-12-05T11:12:20.360Z`.

**Root Cause:** `OldObjectSample.allocationTime` is annotated
`@Unsigned @Timestamp("TICKS")`. `BasicJFRWriter.gettObjectFunction` decided how
to store a field via JMC's `ValueDescriptor.getContentType()`, which returns
`null` when a field carries **more than one** content-type annotation (both
`@Unsigned` and `@Timestamp` are `@ContentType` meta-annotations). With a null
content type the field fell through to a plain unsigned varint, so the value was
stored as raw ticks with no epoch base — never routed through
`getInstant(...)`/`TIMESTAMP_REDUCTION`. `startTime` was unaffected because it
carries only `@Timestamp`. This applied to every `@Unsigned @Timestamp` and
`@Unsigned @Timespan` field across all event types.

**Fix:** Resolve the content type via the project's own `Annotations` helper
instead of JMC's ambiguous `getContentType()`, and deprioritize `@Unsigned` so
the specific annotation (`@Timestamp`/`@Timespan`/`@DataAmount`) wins when several
content types are present.

## Bug 251: `view` prints the `arrayElements` "not an array" sentinel as raw `-2147483648`

**Status:** Fixed.

`cjfr view profile.jfr jdk.OldObjectSample` shows `Array Elements = -2147483648`
for objects that are not arrays. `-2147483648` is `Integer.MIN_VALUE`, which the
JFR metadata documents as the sentinel for "not an array" (*"... or minimum value
for the type int if it is not an array"*). The JDK `jfr print` tool shows `N/A`.

**Impact:** Low — misleading value in one column of `OldObjectSample`.

**Fix:** Added `JFRView.SentinelIntegerColumn`, which renders `Integer.MIN_VALUE`
as `N/A`. `fieldToColumn` dispatches `int` fields whose `@Description` contains
"minimum value for the type int" to it; real array counts still render normally.

## Bug 253: `summary`/`view` on a lossy-inflated `.jfr` crashes with a `NullPointerException`

**Status:** Fixed.

`cjfr summary rd_inflated.jfr` (where `rd_inflated.jfr` was produced by
`condense --condenser-config reduced-default` → `inflate`) crashed with:

```
NullPointerException: Cannot invoke "jdk.jfr.ValueDescriptor.getTypeName()" because "field" is null
    at BasicJFRWriter.getDescription(BasicJFRWriter.java:372)
    at BasicJFRWriter.eventFieldToField(BasicJFRWriter.java:429)
    at CombinerSpec.buildNamedStruct(CombinerSpec.java:507)
```

**Root Cause:** `summary` and `view` read a `.jfr` through `CombiningJFRReader`,
which re-condenses on the fly via `BasicJFRWriter` + `EventCombiner`. The combiner
for `jdk.ThreadPark` collects a named struct over the fields
`duration, eventThread, stackTrace, timeout, until, address`. A lossy inflate
(reduced-default / reasonable-default) reconstitutes `jdk.ThreadPark` **without**
the `@MemoryAddress` `address` field. When the file is read again,
`CombinerSpec.buildNamedStruct` called `eventType.getField("address")`, which
returns `null` for the now-missing field, and passed that null `ValueDescriptor`
straight into `eventFieldToField` → `getDescription`, which dereferences
`field.getTypeName()`.

**Impact:** Medium — `summary` and `view` (any `CombiningJFRReader` consumer) crash
outright on a JFR file that was round-tripped through a lossy condenser config,
which is a normal workflow (condense to save space, inflate to inspect, then
summarize).

**Fix:** `CombinerSpec.buildNamedStruct` now skips field names that are absent from
the actual event type instead of passing a null `ValueDescriptor` downstream. A
genuinely-missing field simply drops from the re-combined struct. This guards both
the value-struct and key-struct (`keyStructFields`) paths, since both route through
`buildNamedStruct`. Regression test:
`CombiningJFRReaderTest.testReadingLossyInflatedJFRDoesNotThrow`.

## Bug 254: `view` renders data-rate fields (`@DataAmount @Frequency`) as a plain byte size, dropping the "/s"

**Status:** Fixed.

`cjfr view profile.jfr jdk.G1BasicIHOP` rendered
`Recent Allocation Rate = 450.52MB`, but the field is a rate
(`bytes/second`). The JDK `jfr print` tool renders `450,5 MB/s`.

**Root Cause:** `jdk.G1BasicIHOP.recentAllocationRate` (and other rate fields) is
annotated `@DataAmount("BYTES") @Frequency`. In `JFRView.fieldToColumn` the field's
condensed type name resolves to a `@DataAmount` type, so it matched the
`MemoryColumn` branch and was formatted as a plain size, ignoring the `@Frequency`
annotation entirely. The pre-existing `FrequencyColumn` was no better — it would
render `Hz`/`MHz`, which is wrong for a byte rate.

**Impact:** Low/Medium — allocation-rate and I/O-rate columns are mislabelled as
sizes; the number is right but the unit is misleading.

**Fix:** Added `JFRView.DataRateColumn`, which formats the value as a memory size
with a `/s` suffix (`450.52MB/s`), honouring `@DataAmount("BITS")` vs `BYTES`.
`fieldToColumn` now dispatches any field carrying both `@Frequency` and
`@DataAmount` to it, before the type-name switch. Tests:
`JFRViewTest.testDataRateColumnRendersBytesPerSecond` /
`testDataRateColumnRendersBitsPerSecond`.

## Bug 255: `view` renders array object classes as raw JVM descriptors (`[B`, `[Ljava/lang/Object;`)

**Status:** Fixed.

`cjfr view profile.jfr jdk.ObjectAllocationSample` showed object classes such as
`[B` and `[Ljava.lang.Object;`. The JDK `jfr print` tool renders these as
`byte[]` and `java.lang.Object[]`.

**Root Cause:** Array class names are stored as JVM type descriptors
(`[B`, `[Ljava/lang/Object;`). `JFRView.ClassColumn.format` only replaced `/` with
`.`, so it produced the misleading `[Ljava.lang.Object;` instead of decoding the
descriptor.

**Impact:** Low — array types in `ObjectAllocationSample`/`OldObjectSample` and any
other `@Class` field are hard to read.

**Fix:** Added `JFRView.ClassColumn.decodeClassName`, which expands leading `[`
dimensions into `[]` suffixes, maps primitive codes (`B`→`byte`, `I`→`int`, …), and
strips the `L…;` wrapper for object element types. Non-array names keep the existing
`/`→`.` behaviour. Tests: `JFRViewTest.testDecodeClassNameArrayDescriptors` /
`testDecodeClassNameNonArray`.

## Bug 256: `jdk.MetaspaceChunkFreeListSummary` events silently dropped by the lossless `default` config

**Status:** Fixed.

`cjfr condense profile.jfr out.cjfr` → `cjfr inflate out.cjfr out.jfr` under the
`default` config lost **all** `jdk.MetaspaceChunkFreeListSummary` events:
`jfr summary` shows `84` in the original and `0` after the roundtrip. This is a
data-loss bug in a config that advertises `combineEventsWithoutDataLoss`, so it is
more severe than the `view`-formatting bugs above.

**Root Cause:** `BasicJFRWriter.isUnnecessaryEvent` classified any
`jdk.MetaspaceChunkFreeListSummary` whose chunk counts (`specializedChunks`,
`smallChunks`, `mediumChunks`, `humongousChunks` + their total-size fields) were all
zero as "unnecessary", and `ignoreEvent` dropped it before it ever reached the
dedicated `MetaspaceChunkFreeListSummaryCombiner`. `ignoreUnnecessaryEvents` is `true`
in `DEFAULT`. G1 emits these events with all-zero chunk counts — every one of the 84
(and all 28560 in the larger `renaissance-all_gc_G1.jfr`) — so the whole event type
vanished. Unlike the `jdk.G1HeapRegionTypeChange` `from == to` case (which encodes no
information), an all-zero metaspace summary is a distinct, expected data point
carrying `when`/`metadataType`/`gcId`/`startTime`, and the combiner already preserves
all of those fields (zeros included).

**Impact:** High — a normal lossless workflow (condense to save space, inflate to
inspect) silently discards an entire GC event type.

**Fix:** Removed the `jdk.MetaspaceChunkFreeListSummary` clause from
`isUnnecessaryEvent`. These events now flow to `MetaspaceChunkFreeListSummaryCombiner`,
which groups them per `gcId` by `(when, metadataType)` and roundtrips losslessly. A
`condense` → `inflate` roundtrip now preserves all 84 events with an identical
`(gcId, when, metadataType)` multiset. Regression test:
`CondenseCommandBugTest.testMetaspaceChunkFreeListSummaryPreservedInDefaultConfig`.

## Bug 257: `view` renders class-loader fields as `-` or the bare instance name instead of the loader's class

**Status:** Fixed.

`cjfr view profile.jfr jdk.ClassLoaderStatistics` rendered the `Class Loader` /
`Parent Class Loader` columns as `-` for VM-internal loaders (and `app`/`platform`
for the named ones), losing the loader's class. The JDK `jfr print` tool shows the
loader's *type*, e.g. `jdk.internal.reflect.DelegatingClassLoader` and
`jdk.internal.loader.ClassLoaders$AppClassLoader`.

**Root Cause:** `JFRView.ClassLoaderColumn.format` read only the `jdk.types.ClassLoader`
`name` field (the loader's *instance* name, which is `null` for most VM-internal
loaders and a short alias like `app`/`platform` otherwise). It ignored the `type`
sub-struct (a `Class` holding the loader's class name), which is what `jfr print`
displays. Additionally the column width was fixed at `max(10, header.length())` (12),
so even a correctly-resolved name was truncated regardless of `--width`.

**Impact:** Medium — class-loader columns in `jdk.ClassLoaderStatistics` (and any
`@ClassLoader` field) are unreadable/misleading; the loader identity is effectively
lost in the view.

**Fix:** `ClassLoaderColumn.format` now renders the decoded `type` class name
(via `ClassColumn.decodeClassName`, matching `jfr print`), falling back to the
instance `name` only when the `type` sub-struct is absent, and to `-` when the whole
loader is null. The column is now flexible-width (`width() == -1`) so long loader class
names get room like other class columns. Tests:
`JFRViewTest.testClassLoaderColumnRendersTypeName`,
`testClassLoaderColumnPrefersTypeOverName`,
`testClassLoaderColumnFallsBackToNameWhenTypeAbsent`,
`testClassLoaderColumnReturnsDashForNullLoader`.

## Bug 258: inflated `.jfr` loses all `@ContentType` unit/format rendering (`jfr print` shows raw bytes, decimal addresses, fractions)

**Status:** Fixed.

A `condense` → `inflate` roundtrip produced a `.jfr` where the standard JDK
`jfr print` tool rendered every `@DataAmount`, `@MemoryAddress`, `@Percentage`,
`@Frequency`, `@Timespan`, and `@Timestamp` field as a **raw number** instead of a
formatted value:

- `jdk.GCHeapConfiguration.minSize`: `8388608` instead of `8,0 MB`
- `jdk.GCHeapConfiguration.objectAlignment`: `8` instead of `8 bytes`
- `jdk.GCHeapSummary.heapSpace.start`: `21474836480` instead of `0x500000000`
- `jdk.CPULoad.jvmUser`: `0.10726073` instead of `10,73%`
- timestamps: full ISO `2025-12-05T11:12:20.357860834Z` instead of the short
  `11:12:20.357` form

**Root Cause:** `WritingJFRReader.getOrCreateAnnotationType` registered the
content-type annotation types (`jdk.jfr.DataAmount`, `jdk.jfr.MemoryAddress`, …)
as plain annotation types **without** the `@ContentType` meta-annotation that JFR
requires to trigger unit/format rendering. The field-level annotations were emitted
(so `jfr metadata` still printed `@DataAmount` on the field), but because the
*annotation type declaration* lacked `@ContentType`, `jfr print` treated them as
inert markers. `jfr metadata` showed `0` `@ContentType` declarations in the inflated
file versus `8` in the original, and `ValueDescriptor.getContentType()` returned
`null` for affected fields.

**Impact:** High — the whole purpose of `inflate` is to produce a JFR that standard
tools read faithfully. Every size, address, percentage, rate, duration, and
timestamp column in an inflated recording was mis-rendered by `jfr print` (and any
JMC-based consumer), across essentially all event types.

**Fix:** `WritingJFRReader.getOrCreateAnnotationType` now attaches a `@ContentType`
meta-annotation to any annotation type that is itself a content-type annotation
(detected via the newly-exposed `BasicJFRWriter.Annotations.isContentTypeAnnotation`,
which reflects on `jdk.jfr.ContentType`). Because the predefined JDK annotation types
are intentionally not initialized in the recording, `jdk.jfr.ContentType` is
registered as a custom annotation type and referenced; it is resolved before the
`customAnnotationTypes.computeIfAbsent` call to avoid reentrant `HashMap` mutation.
After the fix the inflated file declares `@ContentType` on `DataAmount`,
`MemoryAddress`, `Percentage`, `Frequency`, `Timespan`, `Timestamp`, and `Unsigned`,
and `jfr print` renders `8,0 MB`, `0x500000000`, `10,73%`, etc. identically to the
original. Regression test:
`WritingJFRReaderTest.testInflatedContentTypeAnnotationsPreserved`.

## Bug 259: `view --json` leaks the `arrayElements` "not an array" sentinel as raw `-2147483648`

**Status:** Fixed.

`cjfr view profile.jfr jdk.OldObjectSample --json` emitted
`"arrayElements": -2147483648` for objects that are not arrays. `-2147483648` is
`Integer.MIN_VALUE`, the JFR-documented sentinel for "not an array". A JSON consumer
would read it as a real array with roughly negative-two-billion elements. The table
view already renders this as `N/A` (Bug 251) and `jfr print` shows `N/A`, so the
`--json` path was inconsistent with both.

**Root Cause:** Bug 251's fix lives in `JFRView.SentinelIntegerColumn`, which only the
*table* renderer uses. The `--json` path in `ViewCommand.eventToMap`/`convertValue`
serialized field values raw, with no awareness of the sentinel, so `Integer.MIN_VALUE`
passed straight through as a bare number.

**Impact:** Low/Medium — machine-readable JSON output misrepresents the "not an array"
case as a nonsensical negative array count for `jdk.OldObjectSample` (and any `int`
field carrying the same "minimum value for the type int" sentinel description).

**Fix:** `ViewCommand.eventToMap` now inspects each field's `@Description`; when it
contains "minimum value for the type int" and the value equals `Integer.MIN_VALUE`, it
emits JSON `null` (the idiomatic "N/A") instead of the raw sentinel. This reuses the
exact field-description signal `JFRView.fieldToColumn` uses to pick
`SentinelIntegerColumn`, so only that specific field is affected — genuine array counts
(including any that legitimately equal other values) are untouched. Regression test:
`ViewCommandTest.testJsonEmitsNullForNotAnArraySentinel`.

## Bug 260: `@Timespan` "unset" sentinel (`Long.MIN_VALUE` / `N/A`) corrupted into a bogus large negative duration by condense→inflate

**Status:** Fixed.

`cjfr condense profile.jfr out.cjfr` → `cjfr inflate out.cjfr out.jfr` turned
`jdk.GCConfiguration.pauseTarget` from `N/A` (raw `Long.MIN_VALUE`, JFR's "unset"
sentinel) into `-106752 d 0 h` (a garbage negative duration). `jfr print` on the
original shows `pauseTarget = N/A`; on the roundtrip it showed the bogus value.

**Root Cause:** `pauseTarget` is `@Timespan("MILLISECONDS") long` with the raw value
`Long.MIN_VALUE` meaning "not set". `BasicJFRWriter.getTimespanType`'s getter called
`event.getDuration(name)`, producing a huge negative `Duration`.
`JFRReduction.TIMESPAN_REDUCTION.reduce` then ran `TimeUtil.clamp(value).toNanos()`,
which clamped the sentinel to `-365 days` (`MAX_DURATION_SECONDS`). On top of that, the
condensed varint's per-field multiplier quantized the stored value, so the reconstructed
duration was neither the sentinel nor `-365 d` but a further-mangled `-106752 d`. The
`Long.MIN_VALUE` "unset" semantic was destroyed.

**Impact:** Medium — any `@Timespan` long carrying the JFR unset sentinel (e.g.
`GCConfiguration.pauseTarget`) is silently corrupted from `N/A` into a nonsensical
negative duration by a normal condense→inflate roundtrip, misleading anyone reading the
inflated recording.

**Fix:** Preserve the sentinel end to end. (1) `BasicJFRWriter.getTimespanType`'s getter
reads the raw long via `event.getLong(name)` and, when it is `Long.MIN_VALUE`, emits
`Duration.ofNanos(Long.MIN_VALUE)` as a marker instead of the clamped duration. (2)
`JFRReduction.TIMESPAN_REDUCTION.reduce` detects that marker duration (by its exact
seconds/nanos decomposition, avoiding `toNanos()` overflow) and stores `Long.MIN_VALUE`
without clamping. (3) `WritingJFRReader.convertTimespanToUnit` recognises the quantized
sentinel on inflate — any timespan more negative than twice the `-365 day` clamp bound is
impossible for real (already-clamped) data, so it must be the sentinel — and restores
`Long.MIN_VALUE` in the field's unit. After the fix the roundtrip preserves
`pauseTarget = Long.MIN_VALUE` bit-exactly (`jfr print` shows `N/A`), while non-sentinel
timespans (e.g. `G1MMU.timeSlice`) are unchanged. Regression test:
`WritingJFRReaderTest.testInflatedTimespanUnsetSentinelPreserved`.

## Bug 261: `@Timestamp` "unset" sentinel (`Long.MIN_VALUE` / `N/A`) corrupted into a bogus epoch value by condense→inflate

**Status:** Fixed.

The timestamp analog of Bug 260. `jdk.ThreadPark.until` is a
`@Timestamp("MILLISECONDS_SINCE_EPOCH") long` that is `Long.MIN_VALUE` (rendered `N/A`)
whenever a thread parks with no deadline (`park()` rather than `parkUntil()`). A normal
`cjfr condense profile.jfr out.cjfr` → `cjfr inflate out.cjfr out.jfr` roundtrip turned
all 6 of the original's `until = N/A` values into a bogus epoch value
(`-3059627606664`). `jfr print` on the original showed `until = N/A`; on the roundtrip it
showed the garbage.

**Root Cause:** JMC's `RecordedObject.getInstant("until")` maps the raw `Long.MIN_VALUE`
sentinel to `Instant.MIN`. `JFRReduction.TIMESTAMP_REDUCTION.reduce` then ran
`Util.toNanoSeconds(Instant.MIN)` — `epochSecond * 1e9 + nano` overflows for
`Instant.MIN` (`epochSecond = -31557014167219200`) — and delta-encoded the wrapped result
against the shared `Universe.lastStartTimeNanos` baseline. So the sentinel was both
mangled into a bogus value and (worse) risked poisoning the delta baseline for
neighbouring timestamps.

**Impact:** Medium — any `@Timestamp` long carrying the JFR unset sentinel (e.g.
`ThreadPark.until`) is silently corrupted from `N/A` into a nonsensical epoch instant by a
normal condense→inflate roundtrip. Unlike the timespan case (Bug 260), the shared
delta-encoding baseline meant a mangled sentinel could also skew the reconstructed times
of surrounding events.

**Fix:** Carry the sentinel through end to end. (1)
`JFRReduction.TIMESTAMP_REDUCTION.reduce` detects `Instant.MIN` and returns
`Long.MIN_VALUE` directly, **without** updating `lastStartTimeNanos`, so the delta
baseline is not poisoned. (2) `inflate` restores `Instant.MIN` bit-exactly on reading back
`Long.MIN_VALUE` (the timestamp varint uses multiplier 1, so it survives the roundtrip
exactly — no threshold heuristic needed, unlike Bug 260), again leaving the baseline
untouched. (3) `WritingJFRReader.convertTimestampToUnit` recognises `Instant.MIN` and
writes back `Long.MIN_VALUE` in the field's unit. After the fix all 6 `ThreadPark.until`
sentinels roundtrip exactly (`jfr print` shows `N/A`), and the surrounding ThreadPark
`startTime` values are unchanged (baseline not poisoned). Regression test:
`WritingJFRReaderTest.testInflatedTimestampUnsetSentinelPreserved`.

## Bug 262: `default`/`lossless` preset silently drops `jdk.PromoteObjectInNewPLAB.plabSize` (→ `-1 byte`)

**Status:** Fixed.

The `default` preset (aliased as `lossless`) enables `combinePLABPromotionEvents = true`,
which groups `jdk.PromoteObjectInNewPLAB` / `jdk.PromoteObjectOutsidePLAB` events by GC id
to save space. A plain `cjfr condense profile.jfr out.cjfr` → `cjfr inflate out.cjfr
out.jfr` roundtrip turned **every** `PromoteObjectInNewPLAB.plabSize` value into `-1 byte`.
In the original the field holds real, varied PLAB buffer sizes (288 bytes – 2.7 MB across
~44 distinct values); after inflate all of them read `-1 byte`. `jfr print` on the original
showed the real sizes; on the roundtrip it showed `-1 byte` everywhere.

**Root Cause:** `JFREventCombiner.PromoteObjectCombiner` grouped events by `objectClass →
tenuredAndAge → [objectSize array]` and **deliberately discarded** `plabSize`;
`PromoteObjectReconstitutor` then hardcoded `plabSize = -1L`. `plabSize` varies per event
within a single GC id, so it is genuine per-event data that cannot be collapsed away without
loss. This made a preset explicitly labelled "lossless" (`Configuration.java` comments the
PLAB combiner as lossless; `LOSSLESS` is a literal alias of `DEFAULT`) silently lose data.

**Impact:** Medium — the flagship lossless preset dropped real per-event data. Anyone using
`cjfr` at its advertised-lossless default to archive allocation profiles lost all PLAB
buffer-size information, which is exactly the data a GC-tuning user inspecting PLAB
promotion events cares about.

**Fix:** True-lossless — preserve `plabSize` by folding it into the combiner's grouping key
so it roundtrips exactly, keeping PLAB combining (and its compression) enabled in the
default preset. `PromoteObjectCombiner.createValueDefinition` now wires the previously
ignored `hasPlabSize` flag through: when the event type has a `plabSize` field
(`PromoteObjectInNewPLAB` does; `PromoteObjectOutsidePLAB` does not) it inserts an extra
`MapValue` nesting level keyed on `plabSize` between `tenuredAndAge` and the objectSize
array/sum. The combined `StructType` schema is auto-derived from the `MapValue` hierarchy,
so no separate schema declaration changed. `PromoteObjectReconstitutor` drops the hardcoded
`-1L` and adds a conditional third `asMapEntryList()` loop keyed on `plabSize` (present only
when `resultEventType.hasField("plabSize")`), mirroring the `hasTLABSize` branch in
`BasicObjectAllocationReconstitutor`. After the fix the full `profile.jfr` plabSize
distribution roundtrips exactly (all ~44 distinct values, correct counts, no `-1 byte`), and
`PromoteObjectOutsidePLAB` (no plabSize field) still roundtrips fine. Regression test:
`JFREventCombinerTest.testPromoteObjectInNewTLABCombiner` (asserts the reconstituted plabSize
multiset equals the original's for the lossless `sumObjectSizes=false` case, and that the
`-1` sentinel never appears).

**Note:** the CJFR combined-event format changed, so pre-existing `.cjfr` fixtures condensed
with the old schema must be regenerated (`cjfr condense --force`); the tracked
`profile.cjfr` test fixture was regenerated as part of this fix.

## Bug 263: `@Timespan` "Forever" sentinel (`Long.MAX_VALUE`) corrupted into a bogus `365 d 0 h` by condense→inflate

**Status:** Fixed.

The positive-value analog of Bug 260. `jdk.ActiveRecording.maxAge` and
`jdk.ActiveRecording.recordingDuration` are `@Timespan("MILLISECONDS") long` fields that hold
the JFR "Forever" sentinel `Long.MAX_VALUE` (9223372036854775807 ms) for an unbounded
recording. `jfr print` on the original renders both as `Forever`; after a plain
`cjfr condense profile.jfr out.cjfr` → `cjfr inflate out.cjfr out.jfr` roundtrip (default =
lossless preset) both became `365 d 0 h`.

**Root Cause:** JMC's `getDuration("maxAge")` returns `Duration.ofMillis(Long.MAX_VALUE)`
(seconds=`Long.MAX_VALUE`, nano=999999999). `JFRReduction.TIMESPAN_REDUCTION.reduce` then ran
`TimeUtil.clamp(value)`, which clamps any duration with `seconds > MAX_DURATION_SECONDS`
(365 days) down to exactly 365 days — destroying the "Forever" meaning. The getter in
`BasicJFRWriter.getTimespanType` special-cased only the negative `Long.MIN_VALUE` ("N/A")
sentinel (Bug 260), not the positive `Long.MAX_VALUE` ("Forever") one.

**Impact:** Medium — the flagship lossless preset silently turns an infinite/unbounded
recording duration into a finite 365-day value. Any `@Timespan` long carrying the Forever
sentinel is affected, not just ActiveRecording.

**Fix:** Symmetric to Bug 260, on the positive side, in three coordinated places. (1)
`BasicJFRWriter.getTimespanType` getter detects raw `Long.MAX_VALUE` and carries it as
`Duration.ofNanos(Long.MAX_VALUE)` (avoiding the overflow-prone `ofMillis`). (2)
`JFRReduction.TIMESPAN_REDUCTION.reduce` detects that carrier by its exact seconds/nano
decomposition (seconds=9223372036, nano=854775807) and returns `Long.MAX_VALUE`, skipping
`clamp`; `inflate` already does `Duration.ofNanos(reduced)` so it restores the carrier
bit-exactly. (3) `WritingJFRReader.convertTimespanToUnit` (both overloads) recognises the
Forever carrier via a new threshold helper `isForeverTimespanSentinel`
(`getSeconds() > 2 * MAX_DURATION_SECONDS`) — threshold rather than exact-match because the
per-field varint multiplier quantizes the value in the lossy `reasonable-default`/
`reduced-default` presets (multiplier 1000) even though DEFAULT uses multiplier 1 — and writes
the field back as `Long.MAX_VALUE`. After the fix `maxAge`/`recordingDuration` roundtrip as
`Forever` under all presets (default, reasonable-default, reduced-default), and a finite
`flushInterval = 1,00 s` in the same event is unchanged. Regression test:
`WritingJFRReaderTest.testInflatedTimespanForeverSentinelPreserved`.

## Bug 264: condense→inflate loses the recording's timezone (gmtOffset), so `jfr print` renders all times in UTC

**Status:** Fixed.

`jfr print` on an inflated `.jfr` renders every timestamp shifted from the original's local
zone to UTC. E.g. `jdk.JVMInformation.jvmStartTime` shows `12:03:28.167` on the original but
`10:03:28.167` on the inflated file (a 2-hour CEST offset). This is NOT a print-time
environment default: forcing `TZ=Europe/Berlin` for both still shows `12:03` (orig) vs
`10:03` (inflated), and `TZ=UTC` on the original still shows `12:03`. So the recording
carries its own gmtOffset that the original honours and the inflated file has lost.

**Root Cause:** the underlying epoch data is bit-exact — `RecordedEvent.getInstant`/`getLong`
return identical values for original and inflated (verified: `jvmStartTime` raw ms =
1784714608167 in both; `Instant` = `2026-07-22T10:03:28.167Z` in both). Only the *display
zone* differs. cjfr writes the inflated `.jfr` via the JMC `flightrecorder.writer` library
(`RecordingImpl.writeFileHeader`), whose chunk header contains no gmtOffset field, so
JMC-written files default to UTC display. cjfr neither captures the original recording's
gmtOffset during condense nor re-injects it during inflate.

**Impact:** Low/cosmetic — no data is corrupted; `cjfr view` (cjfr's own renderer) is
unaffected. Only the JDK `jfr print` local-time *rendering* of an inflated file differs from
the original, which can confuse a user diffing `jfr print` output before/after a roundtrip.

**Fix:** three layers (implemented, commit `011e928`). (1) Capture the source chunk's
gmtOffset at condense (`BasicJFRWriter.readChunkGmtOffsetMillis` parses the raw `.jfr`
metadata region). (2) Store it in the CJFR `Universe` struct — backward-compatible field,
`Constants.VERSION` bumped 1→2; old files default to the `GMT_OFFSET_UNSET` sentinel. (3)
Re-inject at inflate by vendoring `MetadataImpl`/`RecordingImpl` so
`MetadataImpl.writeRegion` emits the real gmtOffset attribute; switched the build from
maven-assembly to maven-shade so the vendored writer classes shadow the jar. Old `.cjfr`
files with no captured offset fall back to UTC. Regression test
`WritingJFRReaderTest.testInflatedRecordingTimezonePreserved`.

## Bug 265: condense→inflate loses the daylight-saving (`dst`) offset, so summer-time recordings render 1h early

**Status:** Fixed.

Follow-up to Bug 264, found by roundtripping a *summer* recording
(`benchmark/renaissance-dotty_gc_ZGC.jfr`, recorded 2024-05-24 in CEST). After inflate,
every timestamp rendered exactly **one hour early** (e.g. `jdk.ZThreadPhase.startTime`
`17:24:31` on the original vs `16:24:31` on the inflated file). The underlying epoch instant
was bit-exact in both (`2024-05-24T15:24:31.343Z`); only the display zone was wrong, and the
shift was independent of the `TZ` env var (so it was an embedded-offset problem, not an
environment default).

**Root Cause:** the JFR metadata `<region>` element carries the zone as **two** attributes —
`gmtOffset` (standard-time offset) and `dst` (daylight-saving adjustment) — and `jfr print`
renders wall-clock time as their **sum**. For CEST both are `3600000` ms (+1h each = +2h). The
Bug 264 fix captured and re-emitted only `gmtOffset`, dropping `dst`, so summer recordings
lost the DST hour. Winter recordings (e.g. `profile.jfr`, no `dst` attribute) were unaffected,
which is why Bug 264's test passed.

**Impact:** Low/cosmetic (same class as Bug 264) — no data corruption, `cjfr view` unaffected;
only `jfr print` local-time rendering of an inflated summer-time recording was off by the DST
hour.

**Fix:** in `BasicJFRWriter.findRegionGmtOffset`/`combineOffsetAndDst`, read both `gmtOffset`
and `dst` from the region and return their **sum** as the effective offset. The existing
storage (`Universe.gmtOffsetMillis`) and re-emission (`MetadataImpl.writeRegion` emits a single
`gmtOffset` with `dst` absent/0) then render the correct wall-clock time without any Universe
schema change or CJFR-format bump. Regression test
`WritingJFRReaderTest.testInflatedRecordingDstOffsetPreserved` (gated on the summer benchmark
recording existing; asserts the effective offset survives the roundtrip AND exceeds the +1h
standard offset, proving the DST hour was captured).


## Bug 266: `lossless` preset deduplicates periodic time-series events, silently dropping distinct-timestamp observations

**Status:** Fixed.

Found by roundtripping recordings under `-c lossless` and diffing `jfr summary` counts against
the source. The `lossless` preset — whose entire contract is "keep everything" — was dropping
periodic time-series events. On `benchmark/renaissance-dotty_gc_SerialGC.jfr`:
`jdk.DirectBufferStatistics` collapsed 14 → 1 and `jdk.GCHeapMemoryPoolUsage` 6 → 5 after a
`condense -c lossless` + `inflate` roundtrip. Each of those dropped events had a **distinct
timestamp**, so the observation series was lost even though the preset promised losslessness.

**Root Cause:** `LOSSLESS = DEFAULT.withName("lossless")` inherits `ignoreUnnecessaryEvents=true`,
which activates `JFREventDeduplication`. That deduplicator did not distinguish two very different
kinds of "duplicate": (1) genuinely-static events whose payload never changes (flags, JVM/OS/CPU
information, configurations, env vars) — deduping these is truly lossless; versus (2) periodic
time-series events (`DirectBufferStatistics`, `GCHeapMemoryPoolUsage`, `NativeMemoryUsage`,
`*Statistics`, `CPULoad`, `ThreadCPULoad`, `ThreadAllocationStatistics`, `CodeCacheStatistics`,
etc.) that repeat with often-identical payloads but at distinct timestamps — deduping these
drops real observations. Both categories were deduped unconditionally for every preset.

**Impact:** Medium (correctness for the `lossless` preset specifically). The `default` /
`reasonable-default` / `reduced-default` presets are lossy by design, so dedup there is intended
and unchanged. Only `lossless` violated its own contract.

**Fix:** in `JFREventDeduplication`, split the registrations into (a) always-on static-event
dedup and (b) a `registerPeriodicTimeSeries()` group gated on `!isLosslessPreset(configuration)`
(a name check for `"lossless"`, so no Configuration field / CJFR-format change). Under
`lossless`, static events still dedup (lossless) while every periodic observation survives.
Verified: SerialGC roundtrip now keeps `DirectBufferStatistics` 14 → 14 and
`GCHeapMemoryPoolUsage` 6 → 6 under `lossless`, while `default` still collapses them (14 → 1,
6 → 5); on a multi-chunk recording, `BooleanFlag` still dedups identically under both presets
(11826 → 657). Regression test
`JFREventDeduplicationTest.testLosslessPresetPreservesPeriodicTimeSeries`.


## Bug 267: `GCPhasePauseLevelCombiner` drops same-named parallel sub-phases (sibling of Bug 249)

**Status:** Fixed.

Found by diffing `jfr summary` counts on `benchmark/renaissance-all_gc_details_G1.jfr` after a
`condense -c lossless` + `inflate` roundtrip: `jdk.GCPhasePauseLevel4` collapsed 108 → 36 and
`jdk.GCPhasePauseLevel2` 2851 → 2827. Inspecting the source, a single `gcId` emits **multiple
GCPhasePauseLevelN events with the same phase `name`** — e.g. G1 records the "Balance queues"
sub-phase 3 times per GC id (one per parallel GC worker). This combiner is registered under
`combineEventsWithoutDataLoss` (true in `DEFAULT`/`LOSSLESS`), so the loss violates the
"WithoutDataLoss" contract for **all** presets, not just `lossless`.

**Root Cause:** identical to Bug 249, but in the sibling combiner. `GCPhasePauseLevelCombiner`
(used for GCPhasePauseLevel1-4, GCPhasePause, GCPhaseConcurrent[Level1]) grouped per-`gcId`
timings into a `Map<name, duration>` using `SingleValue`. `SingleValue` keeps only the last
value per key, so same-named phases within a GC id overwrote each other — all but one were lost.
Bug 249 fixed only `GCPhaseParallelCombiner`; this one was left with the flaw.

**Impact:** Medium (silent data loss in a lossless-by-design combiner). Undercounts parallel GC
sub-phases; the drop scales with GC worker count (÷3 for a 3-worker G1 in the sample).

**Fix:** mirror Bug 249 — switch the per-name value from `SingleValue<duration>` to
`ArrayValue<duration>` in `GCPhasePauseLevelCombiner.createValueDefinition`, and rewrite
`GCPhasePauseLevelReconstitutor.reconstitute` to `flatMap` every duration in the list back into
its own event (with backward compat for old `.cjfr` files that stored a single duration). The
`ignoreTooShortGCPauses` filter was updated to keep a (name) entry when *any* of its durations is
non-trivial. Verified on the G1 detail recording: all GCPhase* counts now match the source
exactly (Level4 108 → 108, Level2 2851 → 2851, etc.) under both `default` and `lossless`.
Regression test `JFREventCombinerTest.testGCPhasePauseLevelCountIsPreserved`.


## Bug 268: `lossless`/`default` silently drops `G1HeapRegionTypeChange.start` (and 3 sibling `start` fields)

**Status:** Fixed.

Found by value-level diffing a `condense`+`inflate` roundtrip of
`benchmark/renaissance-all_gc_details_G1.jfr`: the inflated `jdk.G1HeapRegionTypeChange` events
had **no `start` field at all** (`jfr metadata` on the inflated file omitted it, `jfr print`
rendered no `start = 0x…`), whereas the source events carry `start` (a `@MemoryAddress` long, the
region's base heap address). The combined struct on disk (`cjfr view --no-reconstitution`) *did*
store `start`, so this was not a combiner/storage loss — it was the reduced *type* definition
omitting the field.

**Root Cause:** `ReducedJFRTypes.REDUCED_JFR_TYPES` removed `start` for four types
(`jdk.G1HeapRegionTypeChange`, `jdk.G1HeapRegionInformation`,
`jdk.ShenandoahHeapRegionStateChange`, `jdk.ShenandoahHeapRegionInformation`) gated on
`Configuration::ignoreUnnecessaryEvents`. But `ignoreUnnecessaryEvents` is `true` in **both**
`default` and `lossless` (LOSSLESS = DEFAULT.withName), so the "keep everything" preset dropped the
field — same lossless-contract violation family as Bug 266. `start` is a raw memory address
(`start = heapBase + index * regionSize`), so it belongs with every other address-field reduction,
all of which correctly gate on `Configuration::removeUnnecessaryAddresses` (false in
default/lossless; true only in reasonable-/reduced-default).

**Impact:** Low/medium — a raw address field, derivable from `index`, but its removal under a
preset literally named `lossless` is a contract violation and surprising to a user diffing
`jfr print`. Since `createAndRegisterEventStructType` builds the standalone reconstitution target
type from the reduced field set, the field never reappeared on inflate.

**Fix:** switch all four `start` removals from `ignoreUnnecessaryEvents` to the shared
`addressField(...)` helper (i.e. `removeUnnecessaryAddresses`). Now `default`/`lossless` keep
`start` (verified: the `(index, start)` multiset of the 748206 surviving events —
`from != to`, the intended `isUnnecessaryEvent` drop — matches the source bit-for-bit), while
`reduced-default` still drops it (verified: absent from inflated metadata). `profile.jfr` contains
zero of these events, so the tracked `profile.cjfr` fixture needed no regeneration.
Regression test `WritingJFRReaderTest.testInflatedG1HeapRegionStartPreserved`.


## Bug 270: `lossless` preset deduplicates `jdk.NativeLibrary` and `jdk.GCConfiguration`, dropping end-of-recording emissions

**Status:** Fixed.

Found by diffing `jfr summary` counts on `profile.jfr` after a `condense -c lossless` + `inflate`
roundtrip: `jdk.NativeLibrary` collapsed 763 → 382 (exactly half), and `jdk.GCConfiguration`
collapsed 2 → 1.

**Root Cause:** Both event types are periodic: the JFR runtime emits them once at the start of
the recording and once at the end (two distinct timestamps, 3 seconds apart in `profile.jfr`).
`JFREventDeduplication` registered:
- `jdk.NativeLibrary` with a `("name", "baseAddress", "topAddress")` key unconditionally (outside
  the `isLosslessPreset` guard), treating it as a static event rather than a periodic one.
- `jdk.GCConfiguration` via `putSingleton(...)` in `SINGLETON_EVENTS` — also unconditional,
  deduping when all non-timestamp fields are equal.

Because each library's `name`/`baseAddress`/`topAddress` and `GCConfiguration`'s payload are
constant across all emissions, the deduplicator kept only the first observation and dropped the
rest. This violated the `lossless` contract.

**Impact:** Low/medium — a roundtrip under `lossless` drops the end-of-recording `NativeLibrary`
snapshot (381 events) and one of two `GCConfiguration` periodic checkpoints. Users diffing
`jfr summary` counts after a lossless roundtrip would see unexplained halving of these types.

**Fix:** moved both event types into `registerPeriodicTimeSeries()` (guarded by
`!isLosslessPreset(configuration)`):
- `jdk.NativeLibrary` moved from the unconditional constructor block to `registerPeriodicTimeSeries()`.
- `jdk.GCConfiguration` removed from `SINGLETON_EVENTS` and added as `putSingleton(...)` inside
  `registerPeriodicTimeSeries()`.

Also fixed a latent inflate-side issue: `WritingJFRReader.toTypedValue` padded missing
`lineNumber`/`bytecodeIndex` StackFrame fields (removed during condense) with `0` instead of the
JDK runtime's canonical `-1` sentinel. Changed to `asValue((int) -1)` for those two INT fields
when they are absent. Regression test: `BasicJFRRoundTripTest.testReducedStackFrames` now asserts
`getBytecodeIndex() == -1` and `getLineNumber() == -1`.



**Status:** By design.

While value-diffing a `-c lossless` roundtrip of `benchmark/renaissance-dotty_default_G1.jfr`,
`jdk.ModuleExport.exportedPackage.module.classLoader` showed two distinct source
`java.net.URLClassLoader` instances (constant-pool `id = 4` with 22 exports, `id = 5` with 67)
collapsing into a single inflated `URLClassLoader (id = 3)` with 89 exports; the JFR constant-pool
ids were also renumbered (e.g. `PlatformClassLoader (id = 3)` → `(id = 4)`).

**Why it is not data loss:** `jdk.types.ClassLoader` has exactly two fields, `type` (Class) and
`name` (String). Both instances have `type = java.net.URLClassLoader` and `name = null` — i.e.
they are **value-identical**; the only thing distinguishing them was JFR object identity, encoded
as an ephemeral constant-pool index that JFR itself does not guarantee stable. cjfr keys references
by value, so it correctly dedups the two identical structs. The actual payload — the
`exportedPackage.name` multiset (`"dotty/tools"`, etc.) — is preserved **exactly** across the
roundtrip (verified: 89 = 22 + 67 exports, package-name multiset identical). Constant-pool id
renumbering is a rebuild artifact, not user data.

**Oracle caveat recorded for future hunts:** when count-diffing `classLoader`/reference-struct
values, compare the *leaf payload* multiset (here package names), not the constant-pool `id`
suffixes — id renumbering is expected and benign.

## Bug 272: `cjfr view` renders duration sentinels as garbled negative values instead of `N/A`/`Forever`

**Status:** Fixed.

Found during manual testing of `cjfr view profile.cjfr jdk.GCConfiguration` and
`cjfr view profile.cjfr jdk.ActiveRecording` after the Bug 270 fix:

```
jdk.GCConfiguration  Pause Target = -2562047h 47      # expected: N/A
jdk.ActiveRecording  Max Age = 2562047h 4              # expected: Forever
                     Recording Duration = 2562047h 4   # expected: Forever
```

**Root Cause:** JFR uses `Long.MIN_VALUE` nanos as the "N/A" sentinel and `Long.MAX_VALUE` nanos
as the "Forever" sentinel for `@Timespan`-annotated `long` fields (e.g. `GCConfiguration.pauseTarget`,
`ActiveRecording.maxAge`, `ActiveRecording.duration`). These arrive in `JFRView.DurationColumn`
as either `Duration` objects or raw `Long` nanos values.

`DurationColumn.format()` passed them directly to `formatDuration()`, which calls
`Duration.negated()` on `Duration.ofNanos(Long.MIN_VALUE)` — this overflows and produces a very
large negative duration string.

**Fix:** Added threshold-based sentinel detection in `DurationColumn.format()` before calling
`formatDuration()`. Uses the same `2L * MAX_DURATION_SECONDS` threshold as
`WritingJFRReader.convertTimespanToUnit` to handle the slight quantization that varint encoding
may apply to the exact sentinel value:

```java
long seconds = val.getSeconds();
long threshold = 2L * me.bechberger.util.TimeUtil.MAX_DURATION_SECONDS;
if (seconds < -threshold) return List.of("N/A");
if (seconds > threshold)  return List.of("Forever");
```

Handles both `Duration` and `Long` (raw nanos) input types.

**Regression tests:** Added 4 tests in `JFRViewTest`:
- `testDurationColumnRendersNaForMinValueSentinel` — `Duration.ofNanos(Long.MIN_VALUE)` → `"N/A"`
- `testDurationColumnRendersForeverForMaxValueSentinel` — `Duration.ofNanos(Long.MAX_VALUE)` → `"Forever"`
- `testDurationColumnRendersNaForMinValueLongNanos` — `Long.MIN_VALUE` raw → `"N/A"`
- `testDurationColumnRendersForeverForMaxValueLongNanos` — `Long.MAX_VALUE` raw → `"Forever"`


## Bug 273: `jdk.DeprecatedInvocation` dedup key is `method` alone, collapsing distinct call sites

**Status:** Fixed.

Found while diffing `jfr summary` on `benchmark/renaissance-neo4j-analytics_default_G1.jfr`
after a default roundtrip: `jdk.DeprecatedInvocation` collapsed 4 → 1.

`jdk.DeprecatedInvocation` records each deprecated-method call site: one event per `(method,
invocationTime, stackTrace)` triple, emitted at chunk boundary. In a multi-chunk recording, the
same call site appears once per chunk — those cross-chunk re-emissions are the intended dedup
target.

**Root Cause:** `JFREventDeduplication` used `stableKey(e, "method")` as the dedup key — only
the method name. In the neo4j recording (1 chunk), 4 distinct call sites of
`System.getSecurityManager()` had identical `method` + `forRemoval` but different
`invocationTime` and `stackTrace`. All 4 were collapsed to 1.

In the als recording (2 chunks, 46 events), the 6 distinct method names produced 6 groups,
keeping only the earliest observation for each — a 46 → 6 collapse where 23 was correct.

**Evidence:** 
- `renaissance-neo4j-analytics_default_G1.jfr` (1 chunk): 4 events, same method
  `System.getSecurityManager()`, four distinct `invocationTime` values → correctly 4 distinct
  call sites, incorrectly deduped to 1.
- `renaissance-als_default_G1.jfr` (2 chunks): 23 unique call sites × 2 chunks = 46 events →
  should deduplicate to 23 (cross-chunk dups), was deduping to 6 (one per method name).

**Fix:** Changed dedup key to `stableKey(e, "method") + "|" + stableKey(e, "invocationTime")`.
The equality predicate simplified to `(a, b) -> true` since two events matching both method and
invocationTime are by definition the same invocation.

**After fix:**
- neo4j: 4 → 4 ✓ (all 4 distinct call sites preserved)
- als: 46 → 23 ✓ (cross-chunk dups removed, all call sites preserved)

**Regression test:** `JFREventDeduplicationTest.testDeprecatedInvocationPreservesDistinctCallSites`


## Bug 274: JMC parser warns "'virtual' field not found in reader for 'thread'" on Java 21+ recordings

**Status:** Known JMC reader version limitation — not a cjfr bug. Whitelisted in test.

Found when running `JMCCompatibilityTest#benchmarkRecordingsAreJMCCompatibleAfterLosslessRoundtrip`
against all 4 benchmark recordings (all Java 21+):

```
[WARNING] Could not find field with name 'virtual' in reader for 'thread'
```

**Root Cause Analysis:**

The inflated JFR is correct. `jfr metadata` confirms `java.lang.Thread` has all 6 fields
including `virtual`. The `virtual` (boolean) field was added to `java.lang.Thread` in Java 21
for virtual thread support.

JMC's `JfrThread` struct (in `StructTypes.java`) is hardcoded to 5 fields:
`osName`, `osThreadId`, `javaThreadId`, `javaName`, `group` — it predates Java 21 and has no
`virtual` field. When JMC's `ReflectiveReader` encounters `virtual` in the binary constant pool
data, it cannot map it to the struct and logs a WARNING.

This is a reader version incompatibility in JMC, not a cjfr inflate bug. The JFR binary is
valid; other parsers (JDK's `jfr` tool) read it without error.

**Action:** Whitelisted the warning in `JMCCompatibilityTest.KNOWN_JMC_READER_WARNINGS`. The
set of known warnings is documented in-code with the reason for each entry.


## Bug 275: ZStatisticsCounter and ZStatisticsSampler duration zeroed after lossless roundtrip

**Status:** Fixed.

Found via field-level `RecordingFile` comparison of `benchmark/renaissance-all_gc_ZGC.jfr`
against its lossless roundtrip using `CheckZGCDuration.java`.

`jdk.ZStatisticsCounter` has 2,686,932 events with non-zero duration (41–42 ns each) in source.
After lossless roundtrip, all 2,686,932 became 0. Same for `jdk.ZStatisticsSampler`: 47,450
non-zero durations → 0.

**Root Cause:**

`ZStatisticsCombiner.createValueDefinition()` builds a `statisticEntry` struct per per-id entry
that stores `startTime`, `eventThread` (if present), `increment` (if present), and `value` —
but **not** `duration`. During reconstitution, `addStandardFieldsIfNeeded()` falls back to
`Duration.ZERO` when `duration` is absent from the combined struct.

**Fix:** Added `duration` field to the `statisticEntry` struct (guarded by null-check for
forward compatibility). In `ZStatisticsReconstitutor.reconstitute()`, restore `duration` from
the struct data before calling `addStandardFieldsIfNeeded()` so it takes the stored value
rather than falling back to zero.

Note: this changes the CJFR format (ZStatisticsEntry struct gains a `duration` field).
`profile.cjfr` fixture regenerated.

**Regression coverage:** `JMCCompatibilityTest` and `JFREventCombinerTest` cover ZGC
configurations via the benchmark recordings.


## Bug 276: `testInflatedG1HeapRegionStartPreserved` fails if inflate takes > 10s (progress message leaks to stderr)

**Status:** Fixed.

`WritingJFRReader.toJFRFile()` prints `"  inflate progress: N events written..."` to `System.err`
every 10 seconds for large recordings. `CommandExecuter.checkNoError()` asserts `filteredError`
is empty, but `IGNORED_STDERR_WARNING_PREFIXES` did not include the progress prefix.

When `renaissance-all_gc_details_G1.jfr` (a large recording with 6M+ events) is inflated in CI,
inflation takes > 10 seconds and the progress line leaks to the test's stderr capture → test fails.

**Fix:** Added `"inflate progress: "` to `CommandExecuter.IGNORED_STDERR_WARNING_PREFIXES`.
The `stripIgnoredStderrWarnings()` filter applies `stripLeading()` before matching, so
the two-space indent in the actual message (`"  inflate progress: ..."`) is handled correctly.

**Note:** This is a test infrastructure fix, not a cjfr behavior fix. The progress message
itself is intentional and correct.


## Bug 277: GCPhasePauseLevel startTimes collapsed to gcId-level minimum in lossless roundtrip

**Status:** Fixed.

Found via `CheckStartTime.java` comparing `benchmark/renaissance-all_gc_details_G1.jfr`
against its lossless roundtrip.

Within one `gcId`, a `GCPhasePauseLevel1` event (e.g., gcId=7852 Phase 1..5) has distinct
`startTime` values in source. After lossless roundtrip, all events within the same `gcId` share
the same `startTime` (the minimum, i.e., the combined event's `startTime`).

**Example:** gcId=7852 has 5 phases with sequential startTimes differing by milliseconds. In the
roundtrip, all 5 phases show `start=2024-05-24T10:06:42.214591500Z`.

**Affected event types (all use `GCPhasePauseLevelCombiner`):**
- `jdk.GCPhasePause` (multiple pauses per GC in concurrent collectors)
- `jdk.GCPhasePauseLevel1..4`
- `jdk.GCPhaseConcurrent`, `jdk.GCPhaseConcurrentLevel1`

Also affected by the same per-entry startTime loss:
- `jdk.GCReferenceStatistics`: 4 reference types per gcId share the combined event's startTime
- `jdk.MetaspaceChunkFreeListSummary`: 4 entries per gcId share the combined event's startTime

**Root Cause:** `GCPhasePauseLevelCombiner` stores only `name -> [durations]`. Each entry in
the array only contains a `duration` (nanosecond long), not a `{startTime, duration}` pair.
During reconstitution, `addStandardFieldsIfNeeded()` sets the same startTime on all events
from a single combined event.

The combiner is gated on `combineEventsWithoutDataLoss()` (true in DEFAULT and LOSSLESS), so
this IS supposed to be a lossless operation — but it loses per-phase startTimes.

**Impact:** GC phase timeline is incorrect in inflated JFR: all phases within a GC appear to
start simultaneously rather than sequentially. JMC's "GC Details" view would show wrong
timeline positioning.

**Fix approach:** Change each duration entry to a `{startTime, duration}` struct. Update
`GCPhasePauseLevelReconstitutor` to restore `startTime` per entry. Format change required.
Same fix needed for `GCReferenceStatistics` (add startTime to value) and
`MetaspaceChunkFreeListSummary` (store actual startTime instead of hash key).

**Applied fix:** Changed `GCPhasePauseLevelCombiner.createValueDefinition` to store a
`GCPhaseEntry` struct per array entry containing both `startTime` and `duration` fields.
Updated `GCPhasePauseLevelReconstitutor.reconstitute()` to restore `startTime` from each
`GCPhaseEntry` struct before calling `addStandardFieldsIfNeeded()`. Updated
`BasicJFRRoundTripTest.testGCPhasePauseLevel1Combiner` to extract duration from the new struct.
`profile.cjfr` fixture regenerated.

Note: `GCReferenceStatistics` and `MetaspaceChunkFreeListSummary` have effectively-zero
durations in all recordings tested, so their startTime loss is lower priority and not fixed here.


## Bug 278: GCPhaseParallel startTimes collapsed to gcId-level minimum in lossless roundtrip

**Status:** Fixed.

Found via `GCParallelCheck.java` checking `benchmark/renaissance-all_gc_details_G1.jfr`.

Within gcId=7853, 45 `jdk.GCPhaseParallel` events have distinct startTimes spanning ~50µs
(parallel GC worker threads with overlapping execution). After lossless roundtrip, all 45
events shared the combined event's gcId startTime.

**Root Cause:** `GCPhaseParallelCombiner`'s `GCWorker` struct stored only `{eventThread,
gcWorkerId, duration}` — no `startTime`. During reconstitution, all events from one combined
event got the combined event's startTime via `addStandardFieldsIfNeeded()`.

**Fix:** Added `startTime` as the first field of the `GCWorker` struct in
`GCPhaseParallelCombiner.createValueDefinition()`. Updated `GCPhaseParallelReconstitutor` to
restore per-worker `startTime` (both List<ReadStruct> and single-ReadStruct paths).
`profile.cjfr` fixture regenerated.

**Impact:** GC parallel phase worker timelines were incorrectly collapsed in JMC's GC Details
view. All 45 parallel sub-phases appeared to start simultaneously.


## Bug 279: GCReferenceStatistics startTimes collapsed to gcId-level in lossless roundtrip

**Status:** Fixed.

Found via `CheckAllStartTimes.java` on `profile.jfr`: 63 of 84 `jdk.GCReferenceStatistics`
events had differing startTimes after lossless roundtrip. Each GC produces 4 reference stats
events (SoftReference, WeakReference, FinalReference, PhantomReference) with distinct
startTimes spread over ~300ns. After roundtrip all 4 shared the combined event's startTime.

**Root Cause:** `GCReferenceStatisticsCombiner` stored `type -> count (Long)` — no startTime
per entry. `GCReferenceStatisticsReconstitutor` fell through to `addStandardFieldsIfNeeded()`
for startTime, getting the combined event's startTime for all 4 events.

**Fix:** Changed value definition from `SingleValue<count>` to `SingleValue<GCRefEntry struct
{startTime, count}>`. Updated `GCReferenceStatisticsReconstitutor` to restore `startTime` from
the struct before `addStandardFieldsIfNeeded()`. `profile.cjfr` fixture regenerated.

**Impact:** Low (nanosecond-level differences). Affects lossless roundtrip correctness.


## Bug 280: MetaspaceChunkFreeListSummary startTimes collapsed to gcId-level in lossless roundtrip

**Status:** Fixed.

Found via `CheckAllStartTimes.java` on `profile.jfr`: 63 of 84 events had differing
startTimes after lossless roundtrip, with a maximum difference of 12ms. Each GC produces 4
MetaspaceChunkFreeListSummary events (before/after × metadata/class) at distinct times.

**Root Cause:** `MetaspaceChunkFreeListSummaryCombiner`'s `MetaspaceChunkData` struct did not
include `startTime`. During reconstitution, all 4 entries within a GC got the combined event's
startTime via `addStandardFieldsIfNeeded()`.

**Fix:** Added `startTime` as the first field of `MetaspaceChunkData` struct. Updated
`MetaspaceChunkFreeListSummaryReconstitutor` to restore `startTime` per entry before
`addStandardFieldsIfNeeded()`. `profile.cjfr` fixture regenerated.

**Impact:** Significant — up to 12ms startTime error in inflated JFR. Metaspace GC analysis
showing before/after snapshot timings would be incorrect.


## Bug 281: ClassLoaderWrapper equality by name-only collapses distinct anonymous class loaders

**Status:** Fixed.

Found via `ComprehensiveFieldCheck.java` on `benchmark/renaissance-all_gc_details_ZGC.jfr`: 493
of 1001 `jdk.ClassLoaderStatistics.classLoader` fields were wrong after lossless roundtrip.
`org.neo4j.codegen.CodeLoader` and `org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader`
were both replaced with `java.net.URLClassLoader` — their parent loader class.

**Root Cause:** `JFRHashConfig.ClassLoaderWrapper.equals()` compared class loaders only by
`getName()`. All anonymous class loaders (`name = null`) — including `CodeLoader`,
`ByteArrayClassLoader`, `URLClassLoader` — returned `Objects.equals(null, null) = true`.
The condensed format's universe deduplicated all null-named loaders to the first one seen
(`URLClassLoader` with id=6, the parent of the others in the loader hierarchy).

**Fix:** `ClassLoaderWrapper.equals()` now compares `(name, type.getId())`. Two class loaders
are identical only if they have the same name AND the same loader class type (by JVM class ID).
Added `typeId()` helper to handle `null` type gracefully (`-1L` for bootstrap/null).

**Impact:** Any recording with multiple anonymous (un-named) class loaders of different types
showed wrong loader types in `ClassLoaderStatistics`, `PromoteObjectInNewPLAB/OutsidePLAB`,
`ObjectCount`, and any other event type using class loader fields.

**Affected presets:** All presets (default, lossless, etc.) — the hash config is shared.


## Bug 282: `ThreadParkLossless` startTimes collapsed to gcId-level in lossless roundtrip

**Status:** Fixed.

Found via `ComprehensiveFieldCheck.java` on `benchmark/renaissance-all_gc_details_ZGC.jfr`:
93,060 `jdk.ThreadPark` events showed startTime mismatches after lossless roundtrip — only
1,031 of 93,060 (startTime, duration) key pairs survived intact; 92,029 were wrong.

**Root Cause:** `CombinerSpec.Specs.threadParkLossless()` uses `collectNamedStructArray` with
fields `duration, eventThread, stackTrace, timeout, until, address` — **`startTime` was missing**.
`EventBuilder` initialises `startTime` from the combined event, and `copyStructFields` only
overwrites fields that are present in the struct. Without `startTime` in the struct, all
`ThreadPark` events within a `nextGcId` window share the combined event's startTime (the
startTime of the first event in that window), corrupting every subsequent event's startTime.

**Fix:** Added `"startTime"` as the first field in the `ThreadParkOccurrence` struct in
`CombinerSpec.Specs.threadParkLossless()`. `profile.cjfr` fixture regenerated.

**Impact:** High — every `jdk.ThreadPark` event in a lossless roundtrip had wrong startTime
and wrong duration. The `timeout` and `until` fields were also mismatched because events were
matched by wrong index order. Thread park profiling timelines were entirely incorrect in JMC.

**Verification:** After fix, `CheckThreadPark.java` reports 0 key mismatches on the ZGC recording
(93,060 events, 0 Keys in src not matching rt).


## Bug 283: `@Timespan` values > 365 days clamped to 365 days in lossless roundtrip

**Status:** Fixed.

Found via `ComprehensiveFieldCheck.java` after the Bug 282 fix: 8 `jdk.ThreadPark.timeout` events
in `benchmark/renaissance-all_gc_details_ZGC.jfr` show `timeout` values like `9223372036854774682`
(≈ `Long.MAX_VALUE - 1125`) in the source, becoming `31536000000000000` (365 days in nanoseconds)
after lossless roundtrip.

**Root Cause:** `TimeUtil.clamp()` clips any `@Timespan` `Duration` with seconds > `MAX_DURATION_SECONDS`
(365 days) to exactly 365 days. The Bug 260/263 sentinels (`Long.MIN_VALUE`, `Long.MAX_VALUE`) were
handled specially in `BasicJFRWriter.getTimespanType`, but sub-`Long.MAX_VALUE` values that are still
> 365 days (e.g. ForkJoinPool's internal `Long.MAX_VALUE - 1125` park deadline) fell through to
`getDuration(fieldName)` → `clamp()` → lose precision.

**Fix:** Extended the Forever sentinel detection in `BasicJFRWriter.getTimespanType` from an exact
`raw == Long.MAX_VALUE` check to a threshold check on the resulting Duration: any Duration with
`getSeconds() > 2 * MAX_DURATION_SECONDS` is normalised to `Duration.ofNanos(Long.MAX_VALUE)` (the
Forever carrier). The existing `TIMESPAN_REDUCTION.reduce` exact-match and `isForeverTimespanSentinel`
threshold on inflate already handle the carrier correctly, so no changes needed there.

After the fix, the 8 large `timeout` values roundtrip as `Long.MAX_VALUE` (`9223372036854775807`)
instead of `31536000000000000`. The semantic "wait effectively forever" is preserved; the exact
sub-`Long.MAX_VALUE` nanosecond count (from JVM internals) is normalised to the standard sentinel.

**Affected fields:** Any `@Timespan long` field with a value between 365 days and `Long.MAX_VALUE`.
`ThreadPark.timeout` is the only known field in the benchmark recordings.


## Bug 284: `G1HeapRegionInformation` startTimes collapsed to combined-event level in lossless roundtrip

**Status:** Fixed.

Found via `ComprehensiveFieldCheck.java` on `benchmark/renaissance-dotty_gc_details_G1.jfr`:
50 `jdk.G1HeapRegionInformation` events show startTime diffs of ~1.25–1.875µs after lossless
roundtrip. Within a GC, 20-30 events span 1.875µs (62.5ns per event = one JVM timer tick).

**Root Cause:** `CombinerSpec.Specs.g1HeapRegionInformation()` uses `collectStructArray("G1RegionInfo",
"index")`, which calls `buildDynamicStruct` with `ALWAYS_SKIP = {"startTime", "gcId", "eventThread"}`.
`startTime` was always excluded from the dynamic struct. During reconstitution, all events in the
group share the combined event's startTime.

**Fix:** Removed `startTime` from `ALWAYS_SKIP`. The only live caller of `buildDynamicStruct`
via `collectStructArray` is `g1HeapRegionInformation` (the `dynamicStruct`/`gcBeforeAfterSummary`
path is dead code — never called). The `G1RegionInfo` struct now includes `startTime` per entry,
and `copyStructFields` restores it during reconstitution. `profile.cjfr` fixture regenerated.

**Impact:** Very low — at most 1.875µs error in `G1HeapRegionInformation` startTimes within a
GC. G1 heap region information is a snapshot at GC time; sub-microsecond startTime precision
is unlikely to be user-visible.

## Bug 285: Agent ignores `--condenser-config` — always condenses at `REASONABLE_DEFAULT`

**Status:** Fixed (commit `2cacdfe`).

Both `SingleRecordingThread` and `RotatingRecordingThread` called `new BasicJFRWriter(condensedOut)`
(the no-arg constructor, which hardcodes `Configuration.REASONABLE_DEFAULT`) instead of
`new BasicJFRWriter(condensedOut, configuration)`. The `configuration` field from `RecordingThread`
was set correctly from the CLI arg but never passed to the writer.

Additionally, `StartMessage.generatorConfiguration` stored `Agent.getAgentArgs()` (the raw
agent-args string, e.g. `"start,duration=3s,/tmp/test.cjfr"`) instead of `configuration.name()`
(e.g. `"default"`), so the metadata in the output file was also wrong.

**Root Cause:**
- `SingleRecordingThread`: `new BasicJFRWriter(condensedOut)` → uses `REASONABLE_DEFAULT`
- `RotatingRecordingThread`: same; plus `getConfiguration()` was `private` in `RecordingThread`,
  blocking access from the subclass.

**Fix:**
- Changed `RecordingThread.getConfiguration()` from `private` to `protected`
- Both threads now call `new BasicJFRWriter(out, configuration)` / `new BasicJFRWriter(out, getConfiguration())`
- Both threads now store `configuration.name()` / `getConfiguration().name()` in `StartMessage`

**Impact:** High — users specifying `--condenser-config=default` (or any config other than
`reasonable-default`) via the agent got `reasonable-default` output silently. Size differences
could be 2–5× for default vs reasonable-default (timestamp quantization).

**Regression tests:** `RecordingThreadHardeningTest.testSingleRecordingThreadPropagatesConfiguration`
and `testRotatingRecordingThreadPropagatesConfiguration` verify both StartMessage and the embedded
Configuration object round-trip correctly for `DEFAULT` and `REASONABLE_DEFAULT`.

## Bug 286: `ON_THE_FLY_CONFIG` leaves structural combiners active during on-the-fly JFR condensation

**Status:** Fixed.

**Observed:** `cjfr view my.jfr` and `cjfr inflate my.jfr` pass raw `.jfr` files through an
on-the-fly condense step inside `CombiningJFRReader.readerForJFRFile`. The config used
(`ON_THE_FLY_CONFIG`) disabled `ignoreUnnecessaryEvents` and `combineEventsWithoutDataLoss` but
left `combinePLABPromotionEvents`, `combineG1HeapRegionTypeChangeEvents`, and
`combineThreadParkLossless` all enabled (inherited from `DEFAULT`).

**Impact:**
1. PLAB, G1 region-type-change, and ThreadPark events were combined into struct arrays during
   on-the-fly condensation, then reconstituted back. The round-trip is lossless, but it is
   unnecessary work and means `view`/`inflate` of a raw `.jfr` file internally processes a
   different event structure than the JFR file actually contains.
2. `ON_THE_FLY_CONFIG.name()` was `"default"` (inherited unchanged), so the in-memory `.cjfr`'s
   `StartMessage.generatorConfiguration` claimed `"default"` for a config that is not `DEFAULT`.

**Root cause:** The comment in `ON_THE_FLY_CONFIG` only justified disabling
`combineEventsWithoutDataLoss` (the general-purpose combiner that can crash on unknown event
layouts). The three structural combiners are safe to disable independently — they only match
specific known event types and cannot crash — but were never explicitly turned off.

**Fix:** Added `.withCombinePLABPromotionEvents(false)`, `.withCombineG1HeapRegionTypeChangeEvents(false)`,
`.withCombineThreadParkLossless(false)`, and `.withName("on-the-fly")` to `ON_THE_FLY_CONFIG` in
`CombiningJFRReader.java`.

**Regression test:** `CombiningJFRReaderTest.testOnTheFlyCondensationDoesNotCombineEvents` asserts
no `jdk.combined.*` event types appear when reading a raw `.jfr` via `CombiningJFRReader.fromPaths`.

## Bug 288: `cjfr view` dumps the raw `StackTrace` struct when a view selects `stackTrace` directly

**Status:** Fixed.

**Observed:** views whose `SELECT` names a bare `stackTrace` column (not `stackTrace.topFrame`)
rendered the whole struct instead of the top frame. On
`benchmark/renaissance-all_gc_details_G1.jfr`, `cjfr view thread-start` showed the Stack Trace
column as `{frames=[{bytecodeIndex=2, lineNumber=2582, method={name=start, ...}}, ...]}` where
`jfr view` shows the top frame's method signature `java.lang.System$2.start(Thread,
ThreadContainer)`. Affected views: `thread-start`, `contention-by-site`, `monitor-inflation`,
`blocked-by-system-gc` (and `active-settings` uses `stackTrace` in a WHERE filter).

**Root cause:** `ValueFormatter.formatStruct` had explicit cases for `StackFrame`, `Thread`,
`Class`, `ClassLoader`, and `Method`, but **no case for `StackTrace`**. A `jdk.types.StackTrace`
struct (which carries a `frames[]` array) fell through to the `s.toString()` fallback, producing
the raw struct dump. `jfr view` renders a directly-selected stackTrace as its top frame's method.

**Fix:** added a `typeName.endsWith(".StackTrace")` case to `formatStruct` that formats
`frames[0].method` via the existing `formatMethod` helper (same output as the `StackFrame` case),
returning an empty string when there are no frames so a `missing: N/A` FORMAT hint fills the cell
(matching `jfr view`'s `N/A` for traceless events). Verified: `monitor-inflation` becomes
byte-identical to the oracle (mod locale); `thread-start`/`contention-by-site` Stack Trace columns
now match (their residual diffs are the unrelated constant-pool Class-identity grouping family,
Bug 271 / `project_view_struct_grouping`, not this formatter). Full `mvn test` green.

**Note:** `thread-start` and `contention-by-site` still differ from the oracle for reasons
independent of this fix — `thread-start` also has a `ThreadStart×ThreadEnd` join-cardinality
difference (native 720 rows vs oracle 1588) and renders the empty `DIFF(startTime)` duration as
`N/A` where the oracle shows `Indefinite`; `contention-by-site` merges same-name call sites the
oracle keeps distinct by CP Class identity. Those are tracked separately.

## Bug 289: Agent-condensed files fabricate a corrupt event type named after a number (e.g. "110"), breaking `jfr`/JMC

**Status:** Fixed.

**Observed:** `HA_condenser_JFR_65g_res.cjfr` (written by the live `condensed jfr agent`) failed to
inflate: `Refusing to write invalid JFR type name (id=176): "110"`. The same guard blocked
`cjfr view` and would have produced a `.jfr` that crashes `jfr view`/`jfr metadata` (the JDK reader
runs type names through `Checks.isClassName`, which rejects leading digits).

**Root cause:** `jdk.ActiveSetting.id` / `jdk.RecordingSetting.id` are stored at condense time as the
*name* of the event type the id points at, so inflate can remap them to the new class id
(`BasicJFRWriter.createActiveSettingIdField`). The getter looks the id up in
`eventTypeIdToName` and, on a miss, falls back to `String.valueOf(classId)` — a bare number like
`"110"`. The CLI `condense` path pre-fills that map via `registerEventTypes(r.readEventTypes())`,
but the **agent path never did** (`RecordingStream` in `SingleRecordingThread`/`RotatingRecordingThread`
calls `processEvent` directly with no upfront registration). `jdk.ActiveSetting` events are emitted
at recording start, before most event types have produced an event, so their referenced ids were
absent from the map → stored as bare numbers. At inflate, `resolveInflatedEventTypeId("110")` found
no matching type and registered a junk stub event type literally named `"110"`.

**Fix (condense-side, prevents new corruption):** `BasicJFRWriter`'s constructor now pre-seeds
`eventTypeIdToName` from `FlightRecorder.getFlightRecorder().getEventTypes()` (every registered
event type's stable process-global id → name), guarded by `FlightRecorder.isAvailable()` and a
catch-all so JFR-less contexts fall back to lazy population. This covers CLI, single-agent, and
per-rotation writers in one place.

**Fix (inflate-side, repairs already-written legacy files):** `WritingJFRReader.resolveInflatedEventTypeId`
no longer fabricates a stub type when the stored name is not a valid Java type name. If the name is
numeric it is treated as the raw class id (the JFR reader tolerates an `ActiveSetting.id` pointing
at an id with no resolvable type); only genuinely valid names still get the zero-occurrence stub.

**Verified:** the legacy `HA_condenser_JFR_65g_res.cjfr` now inflates and passes `jfr summary`,
`jfr metadata`, `jfr view gc`, and `jfr print --events jdk.ActiveSetting`. A fresh agent recording
(`-javaagent` on a busy JVM) inflates with **no numeric-named types** in metadata and ActiveSetting
ids resolving to real class ids. Full `mvn test` green.

## Bug 290: Agent recordings lose the recording JVM's timezone — `jfr print` renders event times in UTC instead of local

**Status:** Fixed.

**Observed:** A recording made by the live `condensed jfr agent` in `TZ=Europe/Berlin`
(offset +02:00) inflates to a `.jfr` whose `jfr print` shows event `startTime`s in **UTC**
(e.g. `09:48:52`) instead of the recording's local wall-clock time (`11:48:52`). A native
`-XX:StartFlightRecording` file recorded in the same zone renders `11:48:52`, and a CLI
`condense` of that native file round-trips correctly at `11:48:52`. Only the agent path was
wrong. (`jfr summary` always prints Start in UTC for all three — that is not the tell.)

**Root cause:** the condensed universe carries `gmtOffsetMillis` (ms east of UTC, incl. DST) so
inflate can restore the region offset that `jfr print` uses to render local time. The CLI
`CondenseCommand` sets it from the source chunk via
`BasicJFRWriter.readChunkGmtOffsetMillis(input)` → `setGmtOffsetMillis(...)`. The agent path
(`SingleRecordingThread`, `RotatingRecordingThread`) creates the `BasicJFRWriter` but **never
calls `setGmtOffsetMillis`**, so `universe.gmtOffsetMillis` stays `GMT_OFFSET_UNSET` and inflate
emits no region offset → the JDK reader defaults to UTC. Same failure *shape* as the
[[project_timezone_preservation]] family (Bug 264/265: region gmtOffset must survive), but a
distinct site — the CLI was fixed there; the agent was never wired up.

**Fix:** the agent has no source chunk to parse — it *is* the running JVM — so the authoritative
offset is the recording JVM's own default zone. Both agent writer-creation sites now call
`writer.setGmtOffsetMillis(TimeZone.getDefault().getOffset(System.currentTimeMillis()))`
immediately after `new BasicJFRWriter(...)`. `TimeZone.getOffset(now)` returns ms east of UTC
including the current DST adjustment, exactly the `gmtOffset+dst` sum `jfr print` renders. The
per-rotation writer in `RotatingRecordingThread` re-reads it on each rotation, so a rotation that
crosses a DST boundary records the offset in effect for that file.

**Verified:** a fresh `-javaagent` recording in `TZ=Europe/Berlin` now inflates so `jfr print`
renders `11:53:46` local (matching a native JFR oracle), where before the fix it rendered
`09:53:46` UTC. All 107 agent/recording tests (`*Recording*,*Agent*,*Rotating*`) green.

## Bug 291: `cjfr view network-utilization` renders read/write rates as byte sizes instead of a bit rate

**Status:** Fixed.

**Observed:** `cjfr view network-utilization` printed the `readRate`/`writeRate` columns as
memory sizes (`5.4 kB`, `1.4 MB`) where the JDK `jfr view` oracle renders them as a *bit rate*
(`42.9 kbps`, `1.4 Mbps`, `814.7 kbps`). Same magnitude, wrong unit family.

**Root cause:** `ColumnType.classify(...)` probed the field description for `jdk.jfr.DataAmount`
and mapped it to `Kind.MEMORY`. But the network-utilization rate fields carry **both**
`@DataAmount(BITS)` **and** `@Frequency` — that combination is "bits per second", not a byte
count. The plain `DataAmount → MEMORY` probe fired first and won, so the value was scaled and
suffixed as bytes (`bytes`/`kB`/`MB`) rather than bits-per-second.

**Fix:** added a `Kind.BITRATE` and a precedence rule in `classify`: a description containing
*both* `jdk.jfr.DataAmount` and `jdk.jfr.Frequency` classifies as `BITRATE` **before** the plain
`DataAmount → MEMORY` probe. `ValueFormatter.formatBitrate(long)` mirrors `formatMemory` (binary
÷1024 scaling, integer at base, one decimal above) with the `bps`/`kbps`/`Mbps`/… unit ladder.

**Verified:** data rows now match the oracle (`42.9 kbps`, `1.4 Mbps`, `814.7 kbps`) modulo the
documented Locale.ROOT decimal-separator known-diff. New unit test `ValueFormatterTest` (16
cases) pins `formatBitrate`, the BITRATE dispatch through `format(...)`, and MEMORY-vs-BITRATE
divergence at equal magnitude.

## Bug 292: `cjfr view` omits the `Avg.`/`Max.`/`Min.` prefix on derived aggregate column labels

**Status:** Fixed.

**Observed:** For views whose `view.ini` has no explicit `COLUMN` clause, `cjfr view` derived the
column header from the leaf field's metadata label but dropped the statistical-aggregate prefix.
network-utilization's header read `Read Rate` / `Write Rate` where the oracle reads
`Avg. Read Rate` / `Max. Read Rate` etc.

**Root cause:** `ColumnType.labelFor` recurses through an `Aggregate` to its argument and returns
the *bare* leaf label by design (it is also used where no prefix is wanted). The renderer used
that bare label directly, so `AVG`/`MAX`/`MIN` never contributed their prefix.

**Fix:** `ViewRenderer.resolveLabels` now prepends `aggregatePrefix(expr)` to the derived
metadata label — `AVG → "Avg. "`, `MAX → "Max. "`, `MIN → "Min. "`, everything else (COUNT, SUM,
LAST/FIRST/LAST_BATCH, percentiles) → no prefix, matching `jfr view`'s header derivation.

**Verified:** network-utilization header now matches the oracle exactly.

## Bug 293: `cjfr view memory-leaks-by-*` reports the wrong sample per group — first-in-batch instead of last

**Status:** Fixed.

**Observed:** On a ZGC recording (`renaissance-dotty_gc_ZGC.jfr`), `cjfr view
memory-leaks-by-class` and `memory-leaks-by-site` reported different Alloc. Time / Object Age /
Heap Usage values than the JDK `jfr view` oracle for the same group. e.g. for
`java.util.concurrent.ConcurrentHashMap$Node[]` cjfr showed `17:24:30 / 1 m 13 s / 2.0 MB`
where the oracle showed `17:24:37 / 1 m 6 s / 122.0 MB`.

**Root cause:** these views are `SELECT LAST_BATCH(...) ... GROUP BY ... ORDER BY allocationTime`.
All 25 `OldObjectSample` events share one `startTime` (a single end-of-recording batch), so the
LAST_BATCH batch filter keeps every event and each group collapses to one output row. The
`LAST_BATCH` reducer was `FirstLastReducer(true)` (FIRST) — it returned the *first* event visited
in the group. But `jfr view`'s representative within a batch is the *last* event in chronological
(stable-`startTime` → file) order: `ConcurrentHashMap$Node[]` has samples at 17:24:30 (2.0 MB)
and 17:24:37 (122.0 MB), and the oracle keeps the 17:24:37 one. The earlier "FIRST-like over the
already-batch-filtered subset" reasoning was wrong; it only passed on the G1 fixtures because
their per-`object.type` groups happened to be single-membered after constant-pool Class-identity
collapse.

**Fix:** `Aggregators.reducer` case `"LAST_BATCH"` now uses `FirstLastReducer(false)` (LAST).
`orderedForAggregate` already stable-sorts the group by `startTime`, so equal-`startTime` ties
retain file order and LAST = last-in-file = jfr's representative.

**Verified:** both `memory-leaks-by-class` and `memory-leaks-by-site` now match the oracle
(modulo the documented Locale.ROOT decimal known-diff). `object-statistics` stays IDENTICAL (its
LAST_BATCH columns are genuinely multi-batch, ordered by `totalSize DESC`), and `active-settings`
output is byte-identical before/after (its LAST_BATCH columns are single-per-group). Full
`ViewCommandTest` (38) + `ValueFormatterTest` (16) green.

## Bug 294: `cjfr view memory-leaks-by-site` collapses every allocation into one `N/A` row — `stackTrace.topApplicationFrame` unresolved

**Status:** Fixed.

**Observed:** On any recording with `jdk.OldObjectSample` events (e.g. `profile.jfr`, 26 samples),
`cjfr view memory-leaks-by-site` rendered a single group with `Application Method` = `N/A`,
whereas the JDK `jfr view` oracle rendered one row per allocation site with real method names
(`me.bechberger.jfr.cli.JFRCLI.createCommandLine()`, `org.tukaani.xz.ArrayCache.getByteArray(int,
boolean)`, `org.openjdk.jmc.flightrecorder.writer.LEB128ByteArrayWriter.writeBytes(long, byte[])`,
…). `memory-leaks-by-class` was unaffected.

**Root cause:** the view query is `SELECT LAST_BATCH(stackTrace.topApplicationFrame), … GROUP BY
stackTrace.topApplicationFrame`. `topApplicationFrame` is a synthetic StackTrace accessor (like
`topFrame`/`topNotInitFrame`) — it is not a stored field. `ColumnType` knew its type/label, but
`FieldResolver.resolve` had no branch for it, so it resolved to `null` for every event. The
GROUP BY therefore bucketed all 26 samples under one null key, rendered `N/A`.

**Fix:** added a `topApplicationFrame` branch to `FieldResolver.resolve` plus a
`firstApplicationFrame(frames)` helper: return the first frame whose declaring class
(`method.type.name`) is *not* a JDK/runtime class, else `null` (so all-JDK traces stay a single
`N/A` group, matching jfr — unlike `topNotInitFrame`, which falls back to `frames[0]`). "System"
is approximated by well-known runtime package prefixes (`java.`, `javax.`, `jdk.`, `sun.`,
`com.sun.`), normalizing JVM-internal `/` separators first; everything else — including
third-party libs like `org.openjdk.jmc.*` / `org.tukaani.*` — counts as application code. Derived
independently from observed oracle output, not from the GPLv2 `jdk.jfr.internal.query` source.

**Verified:** `cjfr view memory-leaks-by-site` on `profile.cjfr` is now byte-identical to the
oracle modulo the documented Locale.ROOT decimal known-diff, including the one legitimately-`N/A`
row (a trace that is entirely `java.*`/`jdk.internal.loader.*` class-loading plumbing) and the
third-party frames. `memory-leaks-by-class` stays identical (no regression). New
`FieldResolverTest` (14 cases) covers the system-vs-application classification, slash
normalization, and prefix look-alikes (`javaxyz`, `com.sundry`). Full suite green.

## Bug 295: `cjfr view` renders plain integers without digit grouping (`3242` vs jfr's `3,242`)

**Status:** Fixed.

**Observed:** In tabular views, `cjfr view` printed plain integer counts ungrouped (`3242`,
`10310`) where the JDK `jfr view` oracle groups them with the locale thousands separator
(`3.242` / `10.310` in a German oracle, `3,242` / `10,310` under Locale.ROOT).

**Root cause:** `ValueFormatter` rendered whole numbers with a bare `Long.toString(...)`, applying
no grouping at all — a structural mismatch with jfr, not merely a locale-separator difference.

**Fix:** route every whole-number path (plain `Number`, whole-valued `Double`, and the `FREQUENCY`
Hz suffix) through a `groupInteger` helper using `String.format(Locale.ROOT, "%,d", v)`. This
matches jfr's grouping *structure*; only the separator character differs from a non-ROOT oracle,
which is the documented Locale.ROOT known-diff the tests already normalize.

**Verified:** `ValueFormatterTest` pins the grouped output (`1,000`, `3,242`, `1,234,567`,
`-4,200`) and a whole-valued double (`12,345`). The memory/bitrate base-unit paths are unaffected
(their ÷1024 scaling caps the base value at 1023, so grouping never applies).

## Bug 296: `cjfr view cpu-tsc` groups the Hz frequency with a thousands separator

**Status:** Fixed. (Corrects the FREQUENCY handling that Bug 295 had routed through grouping.)

**Observed:** `cpu-tsc` rendered `Fast Time Frequency: 1,000,000,000 Hz`, but the JDK `jfr view`
oracle renders it with **no** separator: `Fast Time Frequency: 1000000000 Hz`. Unlike plain integer
counts (Bug 295), `jdk.jfr.Frequency` columns are not digit-grouped by jfr.

**Root cause:** Bug 295's fix over-generalized by routing the `FREQUENCY` `ColumnType.Kind` through
`groupInteger`. Frequency is a distinct content kind that jfr renders ungrouped.

**Fix:** `ValueFormatter` FREQUENCY branch now emits `n.longValue() + " Hz"` (no grouping); plain
counts still group. `ValueFormatterTest.formatFrequencyHasNoDigitGrouping` pins `2600 Hz`.

## Bug 297: `cjfr view active-settings` drops all settings columns but the primary — self-join LAST_BATCH cutoff was global

**Status:** Fixed.

**Observed:** `active-settings` showed only the `enabled` value per setting id (e.g.
`jdk.FileForce true`), with threshold / stackTrace / period / cutoff / throttle columns blank,
where jfr shows the full row.

**Root cause:** `active-settings` is a correlated self-join over six `jdk.ActiveSetting` aliases
(E/T/S/P/C/U), each filtered by a different `name`, correlated by `GROUP BY id`, with
`LAST_BATCH(...)` restricting each to the final periodic emission. `QueryEvaluator.evaluateJoin`
computed a **single global** last-batch timestamp across all six aliases. Because the aliases'
events carry nanosecond-granularity timestamps that differ slightly, only the primary alias's rows
fell inside the global cutoff; the other five aliases' values were filtered out and rendered blank.

**Fix:** compute a **per-alias** last-batch timestamp for the aggregate values, and gate group
survival on the **primary (first FROM) alias's** global-max timestamp so only the final-emitted id
survives (matching the oracle's one-result behaviour). `evalJoinCell` now takes a
`Map<String,Instant>` of per-alias batch timestamps instead of a single `Instant`.

**Note:** the Event Type column showing the raw type name (`jdk.FileForce`) rather than the
oracle's human label (`File Force`) was the separate Bug 299, now also fixed.

## Bug 298: `cjfr view compiler-configuration` renders a blank row label (`: N/A`) for a field absent from the recording

**Status:** Fixed.

**Observed:** `compiler-configuration` printed a row `: N/A` — an empty label — for
`LAST(dynamicCompilerThreadCount)` when that field is absent from the recording's
`jdk.CompilerConfiguration` event type (it does not exist on all JDKs). The blank label makes the
row unidentifiable. (Reproduces on both `.jfr` and `.cjfr`, so it is a renderer bug, not condense
lossiness. On JDK 25 the `jfr` oracle instead aborts the whole view with a "Can't find field"
error, so there is no directly comparable oracle row.)

**Root cause:** `ViewRenderer.resolveLabels` resolved each column's label from the field's metadata
`@Label`. When the field is absent, `ColumnType.labelFor` returns null; the expr is an `Aggregate`
(not a bare `FieldPath`), so it fell through to the final `else` → empty string.

**Fix:** when metadata resolution fails and the expr is an `Aggregate` wrapping a `FieldPath`, fall
back to the raw field path (`dynamicCompilerThreadCount`) — the same fallback already used for a
bare FieldPath — so the row stays identifiable. `ViewRendererTest` pins the fallback.

## Non-bug (investigated): `cjfr view active-settings` shows many rows on a *default*-preset `.cjfr` (1 on `.jfr`)

**Status:** Not a bug — expected default-preset lossiness. Documented so it is not re-chased.

**Observed:** `cjfr view active-settings` renders 1 row (matching the `jfr` oracle) when run on a
`.jfr` file, but ~80 rows when run on a `.cjfr` produced with the **default** preset.

**Investigation:** The `active-settings` self-join uses `LAST_BATCH`, which keeps only the events
sharing the single global-maximum `startTime`. In the source `.jfr`, all 171 `jdk.ActiveSetting`
`enabled` events have *distinct nanosecond* startTimes, so LAST_BATCH selects exactly one (id 1519
→ "File Force") → 1 row. The default preset condenses with `timeStampTicksPerSecond: 1000`
(millisecond quantization), collapsing those 171 nanosecond timestamps into 2 millisecond buckets;
the max bucket then holds 80 `enabled` events → 80 rows. Verified: re-condensing the same recording
with the **lossless** preset (which preserves nanosecond startTimes) restores 171 distinct
timestamps and renders exactly **1 row**, byte-identical to the oracle.

**Conclusion:** The renderer and query evaluator are correct on both `.jfr` and lossless `.cjfr`.
The row-count difference on a default-preset `.cjfr` is the intended timestamp-quantization
lossiness of that preset, not a defect. (Consistent with the "measure renderer fidelity on `.jfr`,
not `.cjfr`" principle — condense lossiness ≠ renderer bug.)

## Bug 299: `cjfr view active-settings` shows the raw event-type name (`jdk.FileForce`) instead of the `@Label` (`File Force`)

**Status:** Fixed.

**Observed:** the `active-settings` Event Type column rendered the fully-qualified type name
(`jdk.FileForce`, `jdk.BooleanFlag`, …) where the `jfr` oracle shows the human `@Label`
(`File Force`, `Boolean Flag`, …).

**Root cause:** cjfr stores `jdk.ActiveSetting.id` as the *target* event type's **name** string (so
inflate can reverse-map it to a class id — see Bug 289), and `active-settings` selects that `id` as
its first column. `jfr view` instead renders the target type's `@Label`. That label lives in the
type's metadata, which was **dropped during condense** for types with zero events: cjfr only writes
a struct type (carrying the `["Label",…]` description) for event types that actually emit events,
and `jdk.FileForce` has none. Threading the recording's type table to the view layer could not
recover a label that was never persisted.

**Fix (two parts):**
1. **Persist labels at condense.** `BasicJFRWriter` now records every event type's `@Label` (name →
   label) from the same sources that populate `eventTypeIdToName` — including zero-event types from
   `FlightRecorder.getEventTypes()` / `registerEventTypes`. The map is written to the `.cjfr` footer
   under a new flag bit (bit 16) in the existing v2 layout: additive, backward-compatible (older
   files have the bit unset → empty map), no version bump. Inflate is untouched — `id` still stores
   the name string.
2. **Relabel at view.** `ViewCommand` builds a name → `@Label` map (footer labels authoritative,
   stream type-collection descriptions as fallback) and threads it through `NativeView.render` into
   `QueryEvaluator`, which maps an `ActiveSetting`/`RecordingSetting` `id` value to the target type's
   label (falling back to the raw name when unknown).

**Verified:** `cjfr view active-settings` on a lossless `.cjfr` now renders `File Force` (single row,
all columns) byte-identical to the `jfr` oracle; inflate round-trip still maps `ActiveSetting.id`
correctly (337 events); footer round-trip and the CJFRFooter/Integrity/Summary/View tests pass.

## Bug 300: lossless preset silently drops `eventThread` from GC combiner events

**Status:** Fixed.

**Observed:** a field-level `jfr print` diff of a **lossless** round-trip of `profile.jfr`
(`condense --condenser-config=lossless` → `inflate` → `jfr print`) shows `eventThread` **absent**
in the inflated output for 5 combiner-collapsed event types (~7300 events): `jdk.PromoteObjectInNewPLAB`
(6769), `jdk.PromoteObjectOutsidePLAB` (417), `jdk.GCPhasePauseLevel1` (84), `jdk.GCPhasePauseLevel2`
(32), `jdk.GCPhasePause` (21). Per-type event counts are identical — a pure field loss, not a count
bug. Total `eventThread` lines: orig 19111, inflated only 11788. The lossless contract is violated.

**Root cause:** two `JFREventCombiner` combiners registered under lossless flags explicitly discard
the thread id. `PromoteObjectCombiner` (javadoc "Throws away the thread id.") groups by
`objectClass → tenuredAndAge → [plabSize →] objectSize` — eventThread is neither key nor stored, so
`addStandardFieldsIfNeeded()` sets it to null at reconstitution. `GCPhasePauseLevelCombiner` (javadoc
"throws away the thread id.") groups by `name → [{startTime,duration}]` — eventThread not stored.
`GCPhaseParallelCombiner` already does this right: it stores `eventThread` as a struct field and its
reconstitutor null-guards it back — the reference pattern.

**Thread cardinality (profile.jfr):** GCPhasePause* → always a single `VM Thread` (cheap to store
per phase entry). PLAB promotion → spread across 10+ GC worker threads → must become a grouping
dimension (trades against the combiner's compression ratio). User decision: fix both, truly lossless
— accept the PLAB size hit.

**Fix (mirror the `GCPhaseParallel` pattern in both combiners):**
1. `GCPhasePauseLevelCombiner` — add an `eventThread` field to the per-phase `GCPhaseEntry` struct
   via `eventFieldToField(eventType.getField("eventThread"), true)`; the reconstitutor's ReadStruct
   branch null-guards `phaseEntry.get("eventThread")` back into the builder. Legacy branches
   (single Long / List<Long>) left untouched for backward-compat.
2. `PromoteObjectCombiner` — insert an `eventThread`-keyed `MapValue` (`.withKeyByReference()`, thread
   is a reference type) just above the objectSize leaf, so distinct GC worker threads bucket
   separately (`objectClass → tenuredAndAge → [plabSize →] eventThread → sizes`). The reconstitutor
   detects the new map level (elements are key/value pairs) and iterates it, `put`ting eventThread
   before descending into the object sizes; old-format files fall through to the legacy path.

No version bump — combined-type schemas are self-describing in the stream, so old `.cjfr` files still
read via the legacy branches.

**Verified:** after the fix the per-type `eventThread` mismatch count is **0** (all 5 types show
orig==inflated); total `eventThread` lines 11788 → 19111 (matches orig). PLAB eventThread value
multiset identical. Lossless `.cjfr` size 239201 → 243503 bytes (+4302, +1.8%) — bounded, still well
under gzip (298678); the accepted cost of the truly-lossless choice. Regression guards added to
`JFREventCombinerTest` (`testGCPhasePauseLevelCombiner`, `testPromoteObjectInNewTLABCombiner`) assert
the eventThread multiset survives round-trip. Full suite green.

## Non-bug (investigated): lossless PLAB combiner collapses per-event `startTime` to one per gcId

**Status:** Not a bug — accepted combiner tradeoff. Documented so it is not re-chased.

**Observed:** a field-level `jfr print` diff of a lossless round-trip of `profile.jfr` shows
`startTime` differing on ~4535 `jdk.PromoteObjectInNewPLAB` / `jdk.PromoteObjectOutsidePLAB` events
(off by up to ~1 ms at display resolution). All other differing lines in the diff are pure event
**reordering** (identical multisets) after the Bug 300 eventThread grouping.

**Investigation:** `PromoteObjectCombiner` collapses all promotion events of a gcId into one grouped
structure keyed by `objectClass → tenuredAndAge → [plabSize →] eventThread → objectSize`. The
`AbstractCombiner` base stores a **single** `startTime` per group (the group's first-event
timestamp, `JFREventCombiner.java:455`), which every reconstituted event inherits. The original
events, however, have **near-unique nanosecond** startTimes: 6769 `InNewPLAB` events → **6593
distinct** nanosecond timestamps. Grouping by startTime to preserve it losslessly yields ~1.03
events per (gcId, startTime) bucket — the combiner would collapse essentially nothing, reverting
these types toward raw-JFR size (the combiner exists precisely to collapse 50k+ promotion
events/gcId).

**Conclusion:** preserving per-event PLAB `startTime` is fundamentally incompatible with the PLAB
combiner's purpose. Unlike `eventThread` (Bug 300, a small-cardinality grouping key that collapses
well), `startTime` is high-cardinality and would defeat the combiner entirely. The one-startTime-
per-gcId collapse is the accepted lossy tradeoff of enabling PLAB combining, not a defect. (If a
user needs exact PLAB timestamps, the fix would be a dedicated opt-in flag, not the default lossless
behaviour.)

## Non-bug (investigated): remaining lossless round-trip diffs are event reordering + synthetic pool-id renumbering

**Status:** Not a bug — semantically faithful. Documented so it is not re-chased.

**Observed:** after Bug 300, a full `jfr print` line-diff of a lossless round-trip of `profile.jfr`
shows 41323 differing line-pairs. Decomposing them (sort each side, `comm`) reveals **36127 lines
are pure event reordering** (identical multisets on both sides — the PLAB combiner now emits events
grouped by eventThread, a different order than the source). The genuine value differences reduce to:

- **`startTime` (4535)** — the PLAB combiner's one-startTime-per-gcId collapse (separate non-bug
  entry above).
- **`classLoader` / `parentClassLoader` (483 / 5)** on `jdk.ClassLoaderStatistics`,
  `jdk.ModuleExport`, `jdk.ModuleRequire`: the class-loader **name multiset is identical**; only the
  synthetic `(id = N)` inside the rendered class-loader struct differs (e.g. `AppClassLoader id=3` →
  `id=2`). JFR constant-pool ids are internal/non-semantic; cjfr assigns its own numbering during
  dedup. Same entity, different pool id.
- **`id` (173)** on `jdk.ActiveSetting`: cjfr stores `ActiveSetting.id` as the target event type's
  *name* and remaps to a class id at inflate (Bug 289). The numeric id is renumbered (orig up to
  1819, infl up to ~214), but the **(setting name, value) multiset is byte-identical** across all
  337 events — every setting round-trips with the same semantic content; only the internal class-id
  numbering changes.

**Conclusion:** no semantic data is lost. The residual diffs are (a) intended combiner reordering,
(b) the accepted PLAB startTime collapse, and (c) internal pool/class-id renumbering that JFR itself
treats as opaque. Verified by multiset equality of the semantic fields (name/value/class-name),
ignoring the opaque numeric ids. Consistent with the "measure fidelity by semantic content, not
by-id byte order" principle.

## Non-bug (investigated): lossless dedups per-chunk static-info / flag events to one copy

**Status:** Not a bug — intended dedup of statically-valued events, distinct from the Bug 266
periodic-time-series carve-out. Documented so it is not re-chased.

**Observed:** on a multi-chunk recording (`renaissance-all_gc_G1.jfr`, 3 chunks), a lossless
round-trip drops ~2/3 of the events for every static-info / flag type — e.g. `jdk.JVMInformation`
3→1, `jdk.CPUInformation` 3→1, `jdk.GCHeapConfiguration` 3→1, `jdk.BooleanFlag` 1971→657,
`jdk.LongFlag` 486→162, `jdk.IntFlag` 156→52. The `jfr` oracle shows 3 copies (one per chunk, at
distinct startTimes 16:06/16:16/16:27); cjfr keeps 1.

**Investigation:** `JFREventDeduplication` registers `SINGLETON_EVENTS` (JVMInformation,
CPUInformation, OSInformation, VirtualizationInformation, the four GC/heap configs, CodeCache/Compiler
configs, CPUTimeStampCounter) and `FLAG_EVENTS` (Int/Boolean/Long/… Flag) for **all** presets
including lossless (`JFREventDeduplication.java:45-50`). The `putSingleton` matcher compares every
field **except startTime/endTime** (line 229), so per-chunk re-emissions with identical payloads
collapse to one. These events are JFR bookkeeping — the JVM re-emits the same immutable
configuration/flag snapshot at every chunk boundary; the payload never changes, so only the
chunk-boundary timestamp is lost, carrying no per-timestamp information.

**Contrast with Bug 266:** periodic *time-series* events (NetworkUtilization, GCHeapMemoryPoolUsage,
ThreadCPULoad, …) DO represent distinct observations over time, so they are only deduped for
non-lossless presets (`registerPeriodicTimeSeries()` gated behind `!isLosslessPreset`). Static-info /
flag events are categorically different: they are constants, not samples.

**Conclusion:** keeping one copy of an immutable per-chunk constant is the intended lossless
behaviour (its payload is fully preserved); the dropped copies differ only in a chunk-boundary
timestamp with no semantic content. The strict "every distinct timestamp survives" contract applies
to time-series observations, not to re-emitted constants. (User decision 2026-07-27: keep dedup,
document.)

## Non-bug (investigated): lossless round-trip loses type-level `@Label` on 13 referenced struct types — not fixable via public JFR API

**Status:** Investigated, documented as known limitation. Not fixable without internal JDK API.

**Observed (Bug 301):** a `jfr metadata` comparison of a lossless condense→inflate round-trip
shows 13 referenced struct types missing their type-level `@Label` annotation in the inflated
output: `java.lang.Class` ("Java Class"), `java.lang.Thread` ("Thread"), `jdk.types.ClassLoader`
("Java Class Loader"), `jdk.types.Method` ("Java Method"), `jdk.types.Module` ("Module"),
`jdk.types.OldObject` ("Old Object"), `jdk.types.OldObjectArray` ("Old Object Array"),
`jdk.types.OldObjectField` ("Old Object Field"), `jdk.types.OldObjectGcRoot` ("GC Root"),
`jdk.types.Package` ("Package"), `jdk.types.Reference` ("Reference"),
`jdk.types.StackTrace` ("Stacktrace"), `jdk.types.ThreadGroup` ("Thread Group").

**Impact scope:** `jfr metadata` output and JMC's type-browser display. Does NOT affect
`jfr print`/`jfr view` output bodies (those use field-level labels, which are preserved).

**Root cause (confirmed):** `BasicJFRWriter.createStructType(ValueDescriptor field, id)` is
called with a `ValueDescriptor` representing a **field reference** in a parent type, not the
type itself. `field.getLabel()` returns the **field's** label (e.g. "Array Information" for the
`array` field of `jdk.types.OldObject`), not the referenced type's own `@Label` ("Old Object
Array"). The JDK public JFR API (`jdk.jfr.ValueDescriptor`) gives no access to the referenced
type's own annotations from a field reference; those annotations live in the internal
`jdk.jfr.internal.Type` object, accessible only via `--add-opens jdk.jfr/jdk.jfr.internal`.

**Attempted fix (reverted):** storing `field.getLabel()` as the struct-type description introduced
a regression — wrong labels appeared (e.g. `@Label("Event Thread")` on `java.lang.Thread`).

**Options not taken:**
- Hardcoded `Map<String, String>` for 13 known types — JDK-version-coupled, brittle.
- Reflection via `--add-opens jdk.jfr/jdk.jfr.internal` — prohibited by project policy.

**Code impact:** `BasicJFRWriter.getEventDescription` was refactored into a shared
`getTypeDescription(label, desc, anns)` helper (no semantic change for event types). Struct
types now store `getTypeDescription(null, null, List.of())` — JSON-array shape but null label,
so inflate skips the `addAnnotation` call (same net result as before). `WritingJFRReader`
`addEventTypeAnnotations` is now called for both event types and struct types, but with a
null-label JSON-array the struct path is a no-op. These changes are forward-compatible if a
future JDK revision exposes the type-level label via the public API.

## Bug 301: `numPlabsFilled` and `numDirectAllocated` in `jdk.G1EvacuationYoungStatistics` / `jdk.G1EvacuationOldStatistics` are never properly populated by the JVM

**Status:** Third-party JVM bug. No cjfr action possible.

**Observed:** In `profile.jfr` (21 GC runs), `numPlabsFilled = 4362671119` (0x10409140F) and
`numDirectAllocated = 5368747312` (0x140009530) are **identical across every GC event** even
though other fields in the same event (`allocated`, `directAllocated`, `regionsRefilled`) vary
normally per cycle.

In `benchmark/renaissance-all_gc_details_G1.jfr` (thousands of GC runs), `numPlabsFilled =
7641904886854956784` (0x6A0D80018C4FB6F0) — a 64-bit value whose magnitude (7.6 × 10¹⁸) is far
beyond any plausible PLAB fill count and is constant across all GC events. `numDirectAllocated =
5` (plausible but also constant).

**Root cause:** The JVM's `G1EvacStats::reset()` clears the underlying `_num_plab_filled` and
`_num_direct_allocated` counters between GC cycles, but the JFR event emission path does not
read from those counters — or reads them before they are flushed from per-thread `PLABData`
accumulators. The 64-bit garbage value in the renaissance recording is consistent with
uninitialized stack or heap memory being read as `ulong`.

**Impact on cjfr:** These fields contain no meaningful information. They would compress well
(constant per recording) but inflating them is still correct — cjfr faithfully preserves the
original garbage values. No data is lost and no additional loss is introduced.

**Recommendation:** These fields could be dropped entirely in reduced/archival-max presets
as they carry zero information. Not implemented yet — waiting to confirm whether any JFR
analysis tool ever uses them.


## Bug 302: `@Category` array annotation silently dropped during inflate, causing JMC Event Type Tree to be empty

**Status:** Fixed.

**Observed:** After a condense→inflate round-trip, JMC's Event Type Tree was empty ("Einen Event Type Tree gibt es im inflate auch nicht mehr"). The `jdk....` event types appeared with 0 events in JMC but had no category groupings. Additionally, `cjfr view active-settings profile.jfr` showed raw type names (`jdk.FileForce`) instead of `@Label` values (`File Force`).

**Root cause (confirmed):** Two related issues:

1. **Array-valued annotations dropped**: `@Category({"Flight Recorder", "Java Application"})` stores its value as a `String[]`. The annotation-writing loop in `WritingJFRReader` only handled single scalar-value annotations; multi-value list entries were silently skipped. JMC uses `@Category` exclusively to populate the Event Type Tree — without it, the tree is empty.

2. **Zero-event type labels missing from `.jfr` view path**: For types with no events (e.g. `jdk.FileForce`), no struct type is written to the condensed stream, so they don't appear in the stream type collection. The footer's `eventTypeLabels` map covers all types (populated in `BasicJFRWriter.close()` from `registerEventTypes`), but `ViewCommand.typeLabels()` only consulted the footer for on-disk `.cjfr` files via `CJFRFooterReader.tryRead(Path)`. When viewing a raw `.jfr` (on-the-fly condensation), the footer lived in an in-memory byte array that wasn't exposed.

**Fixes applied:**

1. `WritingJFRReader`: detect list-valued annotation entries (`values.size() == 1 && values.get(0) instanceof List`) and write them via `addAnnotation(Type, Consumer<TypedValueBuilder>)` with `putField("value", String[])`. Added `getOrCreateArrayAnnotationType` helper with a distinct cache key (`name + " array"`) so scalar and array registrations of the same annotation type don't collide.

2. `CJFRFooterReader.tryRead(byte[])`: new overload that reads footer from in-memory bytes using array-index arithmetic (mirrors the `RandomAccessFile` path for disk files).

3. `CombiningJFRReader`: on-the-fly `.jfr` condensed bytes are now stored in `ReaderAndReadEvents.condensedBytes`; `inMemoryFooters()` exposes the parsed footers. `ViewCommand.typeLabels()` now calls `jfrReader.inMemoryFooters()` to get the full `eventTypeLabels` map for `.jfr` inputs.

**Regression tests added:** `JMCCompatibilityTest.categoryAnnotationsSurviveRoundTrip` (existing), `JMCCompatibilityTest.eventTypeAnnotationsSurviveRoundTrip` (new — also verifies `@Label` and footer coverage for all types including zero-event types).

## Bug 303: `cjfr view recording` (and `modules`, `safepoints`, `tlabs`) silently fell through to "No event type found"

**Status:** Fixed.

**Observed:** `cjfr view recording profile.jfr` printed "No event of type recording found. Did you mean one of these: recording ..." — the view was in the suggestions list but the command failed to render it.

**Root cause:** The delegation check in `ViewCommand.run()` used `viewName.contains("-")` as a heuristic for "this is a named JDK view, not an event type name". Most JDK named views are kebab-case (`gc-pauses`, `hot-methods`, …), so the heuristic worked. But four JDK views have no hyphen: `recording`, `modules`, `safepoints`, `tlabs`. These all use `FROM *` (not natively evaluable), so `tryNativeView` returned empty — and then the delegation guard `viewName.contains("-")` was false, so the code fell through to `reportNoEventType` instead of delegating to `jfr view`.

**Fix:** Changed the delegation guard from `viewName.contains("-")` to `NativeView.isKnownView(viewName) || viewName.contains("-")`. The primary check uses the view.ini registry (accurate for all JDK 21+ views); the `-` heuristic remains as a fallback for pre-21 JDKs where the registry is empty but dash-named views are still meaningful.
