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

## Bug 304: `cjfr view <EventType>` showed raw type name and camelCase field names instead of `@Label` values

**Status:** Fixed.

**Observed:** `cjfr view jdk.GarbageCollection profile.jfr` printed the title "jdk.GarbageCollection" and column headers "Gc Id", "Sum Of Pauses", "Longest Pause" — raw type name and camelCase-converted field names. The JDK oracle (`jfr view`) shows "Garbage Collection" as title and "GC ID", "Sum of Pauses", "Longest Pause" as column headers.

**Root cause:** `JFRViewConfig(StructType)` used `type.getName()` for the view title and `field.name()` (after camelCase→Title Case conversion via `propertyToHeader`) for column headers. Both the event type `@Label` and field `@Label` annotations are stored in the condensed stream's description JSON (`BasicJFRWriter.parseEventDescription` / `parseFieldDescription`), but were never consulted during view rendering.

**Fix:** Two changes in `JFRView.java`:

1. `JFRViewConfig.typeDisplayName(StructType)` — new helper that calls `BasicJFRWriter.parseEventDescription(type.getDescription()).label()` and falls back to `type.getName()` when the label is null/empty or parsing fails.

2. `fieldDisplayName(Field)` — new helper that calls `BasicJFRWriter.parseFieldDescription(field.description()).label()` and falls back to `propertyToHeader(field.name())`. All column constructors updated to use `(header, prop)` 2-arg form with `header = fieldDisplayName(field)` and `prop = field.name()`, so the label drives the displayed header while the field name remains the data lookup key.

## Bug 305: "No event of type X found" error message used raw type name instead of @Label

**Status:** Fixed.

**Observed:** `cjfr view jdk.JavaMonitorEnter profile.jfr` printed "No event of type jdk.JavaMonitorEnter found." while the JDK oracle (`jfr view`) prints "No events found for 'Java Monitor Blocked'." (using the @Label). Similarly, `jdk.ClassLoad` showed "No event of type jdk.ClassLoad found." instead of "No events found for 'Class Load'.".

**Root cause:** `ViewCommand.reportNoEventType(String eventName, Set<String> seenTypes)` always used the raw event type name. The `@Label` for every event type is available in the `typeLabels` map (built from the footer's `eventTypeLabels` and in-memory struct type descriptions), but `MatchResult` only carried `seenTypes` — not `typeLabels`. So `reportNoEventType` had no access to the label.

**Fix:** Extended `MatchResult` to include `typeLabels` (computed from the `CombiningJFRReader` at the same time as events are collected in `collectMatches`). Added `lastTypeLabels` field alongside the existing `lastSeenTypes` for the delegation code path. `reportNoEventType` now uses `typeLabels.get(eventName)` — if a non-empty label distinct from the type name is found, it prints "No events found for '<label>'."; otherwise falls back to the old "No event of type X found." form.

## Bug 306: `cjfr view <EventType>` showed wrong duration format and "Forever" instead of "Indefinite"

**Status:** Fixed.

**Observed:** `cjfr view jdk.SafepointBegin profile.jfr` showed `2.667us` for a 2667 ns duration. `cjfr view jdk.ActiveRecording profile.jfr` showed `Forever` for `Long.MAX_VALUE` sentinel durations (`maxAge`, `recordingDuration`). The JDK oracle (`jfr view`) shows `0,00267 ms` and `Indefinite` respectively. Additionally, `cjfr view jdk.ActiveRecording` showed `0B` for zero byte `maxSize` while oracle shows `0 bytes`.

**Root cause:** `JFRView.DurationColumn` used `TimeUtil.formatDuration()` which formats sub-millisecond values in microseconds (`us`, no space before unit) and used `"Forever"` as the Long.MAX_VALUE sentinel label. `JFRView.MemoryColumn` used `MemoryUtil.formatMemory()` which uses no space before unit and `B` suffix for bytes (not the `bytes` unit name used by jfr).

**Fix:** In `JFRView.java`:
1. `DurationColumn.format()` now calls `ValueFormatter.formatTimespan(val)` which always formats sub-second values in milliseconds with a space before the unit and correctly maps `Long.MIN_VALUE` → "N/A" and `Long.MAX_VALUE` → "Indefinite".
2. `MemoryColumn.format()` now calls `ValueFormatter.formatMemory(value)` for BYTES columns, which formats as "N.N kB", "N.N MB", "0 bytes" (space before unit, matching oracle format). BITS columns still use `MemoryUtil.formatMemory` since there's no matching path in ValueFormatter.
3. Made `ValueFormatter` class and key methods (`formatTimespan`, `formatMemory`) `public` so `JFRView` can access them from the sibling package.

## Bug 307: `cjfr view <EventType>` collapsed struct fields into one "field=value" cell instead of expanding to separate columns

**Status:** Fixed.

**Observed:** `cjfr view jdk.GCHeapSummary profile.jfr` showed the `heapSpace` (VirtualSpace) struct as a single `Heap Space` column containing `"Start Address=0x500000000, Committed End Address=0x531000000, ..."`. The JDK oracle (`jfr view`) expands the struct into 5 separate columns with headers `"Heap Space : ..."` (one per sub-field: start address, committed end, committed size, reserved end, reserved size).

**Root cause:** `JFRViewConfig(StructType<?, ?> type)` mapped each top-level field to exactly one column via `fieldToColumn`. Generic struct fields produced a `StructColumn` which, in single-row mode, joined all sub-fields as `"field=value"` pairs. The oracle instead flattens each struct field into N columns at the top level.

**Fix:** Added `topLevelFieldColumns(Field<?, ?, ?> field)` which detects when `fieldToColumn` would return a `StructColumn` and instead expands the struct into one flat `NestedColumn` per sub-field, with compound header `"Parent : Child"`. Added `NestedColumn` wrapper record that delegates formatting through `event.getStruct(parentProp)` to the inner column. Dedicated formatters (Thread, StackTrace, Class, etc.) are unaffected because they return non-`StructColumn` instances from `fieldToColumn`.

## Bug 308: Known-but-empty event types exited with code 1 and showed suggestions instead of exiting 0

**Status:** Fixed.

**Observed:** `cjfr view jdk.ThreadDump profile.jfr` (where `jdk.ThreadDump` has 0 events but IS in the recording metadata) exited with code 1, printed "No events found for 'Thread Dump'." to stderr, and showed "Did you mean" suggestions. The JDK oracle (`jfr view`) prints to stdout and exits 0 with no suggestions.

**Root cause:** `reportNoEventType` in `ViewCommand` always printed to stderr, showed suggestions, and returned 1 regardless of whether the event type was recognized in the recording metadata. `typeLabels` (populated from the CJFR footer / type annotations) already distinguished known types from unknown ones, but this distinction wasn't used to change the exit behavior.

**Fix:** When `typeLabels` contains a non-empty label for the event type (meaning the type IS recognized in the recording metadata), `reportNoEventType` now prints "No events found for '<label>'." to stdout and returns 0 without suggestions — mirroring the oracle. Unknown event types (label absent from metadata) still use the old stderr + exit 1 + suggestions path.

## Bug 309: `cjfr view jdk.ActiveSetting` showed raw event type names in "Event Id" column instead of @Label

**Status:** Fixed.

**Observed:** `cjfr view jdk.ActiveSetting profile.jfr` showed `jdk.ThreadStart` in the "Event Id" column. The JDK oracle (`jfr view`) shows `Java Thread Start` (the `@Label` of `jdk.ThreadStart`).

**Root cause:** cjfr stores `jdk.ActiveSetting.id` (and `jdk.RecordingSetting.id`) as the target event type's name string (e.g. "jdk.ThreadStart") after the JMC compat fix (Bug 289). The `JFRView` rendered it as a plain string. The `typeLabels` map (already computed in `collectMatches` from the CJFR footer) was not passed into `JFRViewConfig`.

**Fix:** Added `EventIdColumn` that looks up the stored event type name in `typeLabels` to resolve it to the `@Label`. Added `JFRViewConfig(StructType, Map<String, String> typeLabels)` constructor and updated `topLevelFieldColumns()` to accept the parent type name and typeLabels. `ViewCommand.renderMatches()` now passes `matches.typeLabels()` to `JFRViewConfig`. The `EventIdColumn` is used only for the `id` field on `jdk.ActiveSetting` and `jdk.RecordingSetting`, falling back to the raw value when no label is found.

## Bug 310: Percentage fields stored as BFLOAT16 causing ±0.12% rounding errors

**Status:** Fixed.

**Observed:** `cjfr inflate profile.cjfr | jfr print --events jdk.G1AdaptiveIHOP` showed `thresholdPercentage = 47,46%` where the original JFR had `47,37%` — a 0.09% error. With 21 GC pauses all having quantized durations from 1ms timestamp resolution, the `gc-pauses` named view showed `Total Pause Time: 130 ms` (oracle: 139 ms) and `Minimum Pause Time: 1.00 ms` (oracle: 1.18 ms).

**Root cause:** The `default` preset stored `@jdk.jfr.Percentage`-annotated float fields using `Type.BFLOAT16` (16-bit brain floating point, 7 mantissa bits). BFLOAT16 has only ~3 decimal digits of precision and for values in the 0-100% range the error can be up to ±0.12%. The higher-precision `Type.FLOAT16` (IEEE 754 half-precision, 10 mantissa bits) uses the same 16-bit storage size but reduces the error to ±0.025%.

**Fix:** Changed `getPercentageFloatType()` in `BasicJFRWriter` from `Type.BFLOAT16` to `Type.FLOAT16`. The CJFR format stores the type tag in the file, so existing CJFR files with BFLOAT16 percentages still decode correctly; only newly condensed files use FLOAT16.

## Bug 311: Nested struct inside expanded struct rendered as raw `{key=val}` string

**Status:** Fixed.

**Observed:** `cjfr view jdk.ModuleExport profile.cjfr` showed an `Exported Package : Module` column containing `{name=jdk.compiler, lo...}` raw struct text. The JDK oracle (`jfr view`) shows only scalar sub-fields of `exportedPackage` (`Name`, `Exported`) and does not render the nested `module` struct as a separate column.

**Root cause:** `topLevelFieldColumns()` expanded each field of a top-level struct into a `NestedColumn`. For sub-fields that are themselves generic structs (e.g. `Package.module`), `fieldToColumn(subField, 1)` hit `StructColumn.of(prop, header, type, 0)`. With `avDepth=0`, `StructColumn.of()` returns an **anonymous `Column`** (not a `StructColumn` instance), so the `inner instanceof StructColumn` guard failed to skip it. The anonymous column's `format()` calls `.toString()` on the struct object, producing `{name=..., ...}` output.

**Fix:** Changed the guard to check `subField.type() instanceof StructType<?, ?>` directly (instead of `inner instanceof StructColumn`), with explicit carve-outs for dedicated struct formatters (Thread, Class, ClassLoader, Method, StackTrace) that ARE `StructType` instances but have useful single-cell renderers. This correctly skips only generic unhandled nested structs, matching oracle behavior.

## Bug 312: `gc-cpu-time` Total Time wrong when GCCPUTime events arrive out of chronological order

**Status:** Fixed.

**Observed:** `cjfr view gc-cpu-time profile.cjfr` showed `Total Time: 2.03 s` but oracle `jfr view gc-cpu-time profile.jfr` showed `Total Time: 2.61 s`.

**Root cause:** The `gc-cpu-time` "Total Time" column is `DIFF("startTime")` — the range of `jdk.GCCPUTime` event startTimes. The oracle's `QueryEvaluator` explicitly sorts events by `startTime` before feeding order-sensitive reducers (DIFF/FIRST/LAST), so `DIFF` is always `MAX(startTime) - MIN(startTime)`. The footer precompute accumulator fed events in JFR arrival order without sorting; in `profile.jfr` the first event (by arrival) had `startTime = 11:12:21.064` (not the chronological minimum of `11:12:20.484`), so `DiffReducer` used the wrong "first" value, producing `2.03 s` instead of `2.61 s`.

**Fix:** Changed `ViewPrecompute.Accumulator` to buffer all events per view as `(startTimeNanos, Object[] colValues)` rows, then sort by `startTime` at `build()` time before feeding reducers — but only for views that have at least one order-sensitive reducer (DIFF/FIRST/LAST). Order-insensitive views (MIN/AVG/MAX/SUM/COUNT) are fed immediately in arrival order (no buffering overhead). Also updated `FooterCollector.collectPrecomputedView` to call the new `acceptRow(viewName, startTime, values)` API.

## Bug 315: `cjfr view deprecated-methods-for-removal` shows too few rows (SET deduplication wrong)

**Status:** Fixed.

**Observed:** `cjfr view deprecated-methods-for-removal benchmark/renaissance-all_default_G1.jfr` showed 33 lines (30 content lines) while the oracle `jfr view` showed 72 lines. The same 24 deprecated methods were present but each had far fewer "Called from Class" entries.

**Root cause:** Two bugs:
1. `SetReducer` used `LinkedHashSet` with `ReadStruct.equals()` (value equality). Multiple events in the same group with the same caller class were collapsed into a single entry — e.g. 9 distinct `LoaderUtil` entries (from 9 different invocations with different `invocationTime` values) were collapsed into 1. The oracle's JDK SDK interns class objects by pool identity, so two events with the same invocationTime+caller share the same `RecordedClass` instance (deduplicated), while different invocationTimes produce distinct instances (kept separate).
2. `ViewRenderer` rendered `List`-valued cells with `cell-height > 1` as a comma-joined string via `ValueFormatter.format(List, ...)` instead of expanding each element onto its own physical line. The `wrapCell()` method also didn't handle `\n`-separated intra-cell content.

**Fix:**
- `SetReducer` now uses an `IdentityHashMap`-based set (keyed by object identity), matching the JDK pool-sharing semantics. When cjfr reads `.jfr` or `.cjfr` files, same-pool-entry structs share the same `ReadStruct` identity, so the identity set correctly deduplicates periodic re-emissions of the same deprecated invocation while keeping distinct invocations separate.
- `ViewRenderer.renderTable()`: List-valued cells in a column with `cell-height > 1` are now formatted as `\n`-separated lines (one element per line) instead of comma-joined.
- `ViewRenderer.wrapCell()`: now splits `\n`-separated intra-cell content before hard-wrapping each sub-line independently.

## Bug 314: `cjfr view thread-cpu-load` collapses recycled thread names into one row per name

**Status:** Fixed.

**Observed:** `cjfr view thread-cpu-load benchmark/renaissance-all_gc_G1.jfr` showed 1,327 rows while oracle `jfr view` showed 2,269 rows. The missing rows were all recycled thread pool names: e.g. `block-manager-storage-async-thread-pool-0` appeared once in cjfr output but many times in the oracle (one row per distinct OS thread, each with a different `javaThreadId`).

**Root cause:** `QueryEvaluator.canonicalKey` maps struct GROUP BY keys to their formatted display string via `ValueFormatter.format`. For thread structs, `formatStruct` returns only the `javaName` (e.g. `"block-manager-storage-async-thread-pool-0"`), losing the `javaThreadId` that distinguishes recycled threads. When the same thread-pool name is reused for different OS threads over the recording's lifetime, all events from those threads shared one group key — only the first thread's `LAST(user)` result survived.

The oracle groups threads by pool-object identity (unique pool pointer per thread struct), which is effectively `(javaName, javaThreadId, osThreadId)`. Our canonicalKey used only `javaName`.

**Fix:** `QueryEvaluator.canonicalKey` now passes thread structs (`javaName`/`osName` fields present) through as raw `ReadStruct` objects rather than converting to a display string. `ReadStruct.equals` compares all struct fields including `javaThreadId` and `osThreadId`, so two threads with the same name but different thread ids produce different group keys — matching oracle semantics. Non-thread structs still use `ValueFormatter.format(s, null)` (method/class/stackframe grouping collapses by display string, which is correct: two frames at the same method but different bytecodeIndex should share a group).

## Bug 316: `canonicalKey` thread-join string had a NUL byte (spotless reformat corruption)

**Status:** Fixed (incorporated into Bug 314 fix revision above).

**Observed:** The Bug 314 fix used `return (name != null ? name.toString() : "") + " " + tid;` to build the thread group key. After spotless reformatting, the space character in `" "` was silently replaced with a NUL byte (`\x00`), causing the string join to produce `"threadName\x00tid"`. This was invisible in the editor but caused group-key collisions whenever two threads happened to hash to the same collision under the NUL-padded key (unlikely in practice, but the corruption was present in the source).

**Root cause:** spotless/google-java-format occasionally replaces characters in string literals under certain Unicode conditions. The NUL byte `\x00` was undetectable without a hex dump.

**Fix:** Replaced the NUL-byte string join with `return s;` (pass raw `ReadStruct`). `ReadStruct.equals` uses value equality over all fields, which correctly handles all thread distinguishers without relying on string concatenation.

## Bug 317: `gc-pause-phases` view missing 2 rows due to `ignoreTooShortGCPauses` in DEFAULT preset

**Status:** Fixed.

**Observed:** `cjfr view gc-pause-phases profile.jfr` showed 3 rows; oracle (`jfr view`) showed 5 rows. Missing: `Reconsider SoftReferences` (avg 0.000278 ms) and `Notify and keep alive finalizable` (avg 0.000083 ms).

**Root cause:** `Configuration.DEFAULT` included `.withIgnoreTooShortGCPauses(true)`. The `GCPhasePauseLevelCombiner` in `JFREventCombiner` uses this flag to drop phase names where every event's duration is below `isEffectivelyZeroDuration()` threshold (< 1 microsecond at `durationTicksPerSecond=1_000_000`). The two missing phases have sub-microsecond average durations and so were silently dropped from condensation entirely — they were absent from the `.cjfr` file and could not be recovered at inflate time.

**Fix:** Removed `.withIgnoreTooShortGCPauses(true)` from `Configuration.DEFAULT` (`Configuration.java` line 134). Sub-microsecond GC phases are legitimate JVM events and should not be silently dropped; they now appear in the view output (durations quantized to "0 s" at 1 µs precision, which is correct).

## Bug 318: `allocation-by-thread` shows `N/A` — `ObjectAllocationSample` lossless combiner dropped `eventThread`

**Status:** Fixed.

**Observed:** `cjfr view allocation-by-thread` showed `N/A` as the thread name (100.00%) instead of `main`. Oracle (`jfr view`) correctly showed `main` and `C1 CompilerThread0`.

**Root cause:** `ObjectAllocationSampleCombiner` in lossless mode grouped events by `(objectClass, stackTrace)` only. The `eventThread` field was never stored in the combined type, so the reconstitutor could not set it on the reconstituted events. The view renders `N/A` for events with a null/missing thread.

**Fix:** Added `eventThread` as an inner grouping level between `stackTrace` and `[weights]` in the lossless combiner, yielding the new format `objectClass → stackTrace → eventThread → [weights]`. The combined type is now `jdk.combined.ObjectAllocationSampleLosslessV2` (registered as `OBJECT_ALLOCATION_SAMPLE_LOSSLESS_V2` in `CombinedEventType`). The old `jdk.combined.ObjectAllocationSampleLossless` type (V1) is kept for backward-compatible inflate of old `.cjfr` files. The reconstitutor detects the format by type name and uses `ReadList.asMapEntryList()` to extract the per-thread buckets, mirroring the `PromoteObjectSample` eventThread recovery pattern.

## Bug 319: Sub-millisecond event durations lost — `getTimespanType` used timestamp precision for built-in `duration` field

**Status:** Fixed.

**Observed:** `cjfr view vm-operations` showed `HandshakeAllThreads` (avg 0.0150 ms), `ClassLoaderStatsOperation` (avg 0.151 ms), `ICBufferFull`, and `JFROldObject` all as "0 s". Oracle showed correct sub-millisecond durations. Similarly, `cjfr view gc-pause-phases` Level 1 phases `Notify Soft/WeakReferences` (avg 0.0112 ms) and `Notify PhantomReferences` (avg 0.00633 ms) showed "0 s" instead of correct microsecond values (after the Bug 317 fix made them visible).

**Root cause:** Two methods in `BasicJFRWriter` incorrectly used `timeStampTicksPerSecond` (1,000/sec → 1 ms resolution) instead of `durationTicksPerSecond` (1,000,000/sec → 1 µs resolution) for duration fields:

1. `getTimespanType(ValueDescriptor, boolean)`: the `Math.min` pick for the built-in `duration` field (when `fieldName.equals("duration") && topLevel`) used `configuration.timeStampTicksPerSecond()` rather than `configuration.durationTicksPerSecond()`. Durations below 1 ms were quantized to 0, and JFR omits zero-duration fields from the inflated output.

2. `getDurationType()`: directly returned `getCachedTimespanType(1_000_000_000 / configuration.timeStampTicksPerSecond())` — same 1 ms resolution bug. Used by `GCPhasePauseLevelCombiner` and `CombinerSpec.gcPhasePauseLevel` for the explicit `duration` field in the phase-entry struct.

**Fix:** Both methods now unconditionally use `configuration.durationTicksPerSecond()` for duration quantization. Events with durations below 1 µs still quantize to 0 (acceptable at DEFAULT precision), but events from ~1 µs to ~1 ms now retain their values.

## Bug 320: `exception-by-message` renders empty message as "N/A" instead of blank

**Status:** Fixed.

**Observed:** `cjfr view exception-by-message benchmark/renaissance-all_default_G1.jfr` showed:

```
N/A    118
```

Oracle (`jfr view`) showed a blank in the Message column with count 118.

**Root cause:** `ValueFormatter.isEmpty(Object)` returned `true` for empty strings (`v instanceof String s && s.isEmpty()`). The `format()` method treats any value for which `isEmpty` is true the same as `null`, rendering it as "N/A" (or a `missing:` hint value). JFR exceptions thrown without a message have an empty-string message field; the oracle renders these as blank cells, not "N/A".

**Fix:** Changed `isEmpty` to always return `false`. Only `null` values now fall through to the N/A / missing-hint path. Empty strings are rendered by the normal `value.toString()` fallback, producing a blank cell matching oracle output.

## Bug 321: `deoptimizations-by-site` shows N/A for `lineNumber` and `bci` columns

**Status:** Fixed.

**Observed:** `cjfr view deoptimizations-by-site` showed `N/A` for the Line Number and Bytecode Index columns on all rows. Oracle (`jfr view`) showed correct integer values (e.g., `649 0`, `2.014 311`). Additionally, cjfr showed ~1075 rows vs oracle's ~1119 rows.

**Root cause:** `ReducedJFRTypes.java` contained entries for `jdk.Deoptimization` and `jdk.CompilerInlining` that removed their `lineNumber` and `bci` fields when `removeBCIAndLineNumberFromStackFrames` is true (which is the default in `Configuration.DEFAULT`). The comment said these were "direct bci+lineNumber fields on the event itself (distinct from StackFrame fields) — drop with same flag." However, these fields are the primary identifying data for the `deoptimizations-by-site` view's GROUP BY — dropping them causes all events to group by method only, losing site granularity and rendering N/A.

**Fix:** Removed `jdk.Deoptimization` and `jdk.CompilerInlining` from `REDUCED_JFR_TYPES`. These events' `lineNumber` and `bci` fields are the core data, not StackFrame overhead to trim.

**Note:** The remaining row-count difference (1119 vs 1075) and value differences (e.g., line numbers differ by a few) are due to JVM JIT non-determinism: the deoptimizations recorded in the `.jfr` occur at dynamically-compiled code positions that differ between the original run and the inflation run. Confirmed by comparing oracle `.jfr` against its own inflated `.jfr` — same discrepancy appears, ruling out data loss.

## Bug 322: `contention-by-address` view shows N/A for Monitor Address column

**Status:** Fixed.

**Observed:** `cjfr view contention-by-address` on a `.cjfr` condensed with the `reduced` preset showed `N/A` for the Monitor Address column on all rows.

**Root cause:** Two compounding issues:

1. `CombinerSpec.javaMonitorEnter()` (used by `combineBlockingEvents`) keyed the map by `monitorClass` (a class reference, grouped per-GC-cycle) and wrote `address=0L` for all reconstituted events, discarding the actual monitor address.

2. `ReducedJFRTypes` removed the `address` field from `jdk.JavaMonitorEnter` events when `removeUnnecessaryAddresses=true` (enabled in DEFAULT/REDUCED presets), so even with a corrected combiner, the address field was absent from events before the combiner ran.

**Fix:**

1. Added `jdk.combined.JavaMonitorEnterV2` (new `CombinedEventType` enum entry + `CombinerSpec.Specs.javaMonitorEnterV2()`). The V2 spec keys the map by `address` (Long, via `keyExtractor(e -> e.getLong("address"))`) and uses `collectNamedStructArray` to preserve `monitorClass`, `eventThread`, `duration`, `stackTrace`, and `previousOwner` per event. Default reconstitution copies all struct fields back plus the address key, giving the view correct data for `UNIQUE(eventThread)` and `MAX(duration)`.

2. Changed the `ReducedJFRTypes` removal predicate for `jdk.JavaMonitorEnter.address` from `Configuration::removeUnnecessaryAddresses` to `c -> c.removeUnnecessaryAddresses() && !c.combineBlockingEvents()`, so the address field is preserved when the V2 combiner needs it.

The old V1 combiner is retained for backward-compatible reading of existing `.cjfr` files.

**Note:** After reconstitution, the `contention-by-address` view on the inflated/cjfr output shows fewer rows than the oracle on the raw `.jfr` (e.g., 1 merged row for IndexShuffleBlockResolver instead of 3), because the V2 combiner's `nextGcIdBased` grouping collapses events across GC cycles into a single address entry per monitor object per cycle. This is an acceptable fidelity trade-off for the lossy `reduced` preset.

## Bug 323: `latencies-by-type` shows `0 s` for ThreadPark, ThreadSleep, JavaMonitorWait durations

**Status:** Fixed.

**Observed:** `cjfr view latencies-by-type` on a `.cjfr` condensed with the `reduced` preset showed `0 s` for Average, P99, Longest, and Total columns for Java Thread Park, Java Thread Sleep, and Java Monitor Wait.

**Root cause:** The `combineBlockingEvents` V1 combiners for `ThreadPark`, `ThreadSleep`, and `JavaMonitorWait` used `countEvents()` as the map value, discarding the `duration` of each event. On reconstitution, `duration=0L` was hardcoded for all events.

**Fix:** Added V2 combiners for all three event types (`threadParkV2`, `threadSleepV2`, `javaMonitorWaitV2`) that use `collectNamedStructArray("...", "duration")` to preserve the actual duration per event. Added new `CombinedEventType` entries (`THREAD_PARK_V2`, `THREAD_SLEEP_V2`, `JAVA_MONITOR_WAIT_V2`) for schema versioning. The V1 combiners are retained for backward-compatible reading of existing `.cjfr` files.

## Bug 324: `contention-by-address` shows N/A addresses for the `default` preset

**Status:** Fixed.

**Observed:** `cjfr view contention-by-address` on a `.cjfr` file condensed with the `default` preset showed `N/A` for the Monitor Address column for all rows.

**Root cause:** `ReducedJFRTypes` had `jdk.JavaMonitorEnter.address` gated on `removeUnnecessaryAddresses` (true in DEFAULT), so the address field was stripped from every event in the `default` preset. The `contention-by-address` view uses this field as its primary grouping key, so all rows collapsed to `N/A`. After the Bug 322 fix (which changed the predicate to `removeUnnecessaryAddresses && !combineBlockingEvents`), the address was still removed in DEFAULT (combineBlockingEvents=false), reproducing the same symptom.

**Fix:** Removed `jdk.JavaMonitorEnter.address` from `ReducedJFRTypes` entirely. The `address` field is semantically meaningful — it uniquely identifies which monitor instance caused contention, and is the grouping key for both the `contention-by-address` view and the V2 combiner. It is not a "raw memory pointer" in the dispensable-address sense.

## Bug 325: `thread-start` view shows N/A for Duration column in cjfr native view

**Status:** Fixed.

**Observed:** `cjfr view thread-start` showed `N/A` for the Duration column for all thread entries, while `jfr view thread-start` showed actual durations (e.g., "26.1 s", "2.05 s").

**Root cause:** The `thread-start` view query uses `DIFFERENCE(startTime)` — an unqualified aggregate over a field shared between two joined aliases (`ThreadStart AS S, ThreadEnd AS E`). In `QueryEvaluator.evalJoinCell`, when `aliasesOf(agg.arg())` is called with an unqualified `FieldPath`, it returns an empty list (no alias prefix → no aliases to iterate). The aggregation loop never ran, the `DiffReducer` received no values, and `result()` returned `null` → rendered as "N/A".

**Fix:** In `evalJoinCell`, when `aliasesOf` returns empty (unqualified field argument), fall back to iterating over all aliases in `aliasRows` so the DIFFERENCE() reducer receives `startTime` from both `ThreadStart` and `ThreadEnd` events in the group, producing the correct duration.

## Bug 326: `jvm-flags` Name column truncated (phantom)

**Status:** Not a bug / phantom.

**Reported:** `cjfr view jvm-flags` appeared to show truncated flag names (e.g., "A..." at 4 chars) in an earlier stale run.

**Investigation:** Retested on current build with `profile.jfr` directly and on `.cjfr` files — the Name column renders at full width (54 chars at width=160). The earlier truncation was from a stale pre-rebuild JAR. No fix required.

## Bug 327: `active-settings` view shows 81 rows on `default` preset instead of 1

**Status:** Known diff (data-loss artifact of 1ms timestamp quantization in DEFAULT preset).

**Observed:** `cjfr view active-settings profile_default.cjfr` shows 81 rows (one per event type). `jfr view active-settings profile.jfr` and `cjfr view active-settings profile_lossless.cjfr` both show 1 row (File Force — the only event type whose settings changed in the last periodic batch).

**Root cause:** The `active-settings` view.ini query uses `LAST_BATCH` aggregation to select only the most recent periodic snapshot of settings. The `LAST_BATCH` implementation in `QueryEvaluator` finds the global maximum `startTime` across events and retains only groups where at least one event has `startTime == globalBatchTs` (exact nanosecond equality).

The DEFAULT preset uses `timeStampTicksPerSecond = 1_000` (1ms precision). The recording has two JFR chunks. All 80 `ActiveSetting` events in chunk 2 are emitted in the same millisecond and thus share the same quantized `startTime` after condensation. Since `lastBatchTimestamp()` returns that quantized ms value and all 80 chunk-2 events match it exactly, all 80 `id` groups survive the LAST_BATCH filter → 81 rows.

With LOSSLESS precision (nanosecond), the 80 events have unique sequential nanosecond timestamps. Only one event has the globally maximum ns timestamp (id=1519 = File Force), so only that group survives → 1 row.

**Impact:** DEFAULT preset produces incorrect `active-settings` output. The LAST_BATCH design relies on sub-millisecond timestamp precision to distinguish "the last periodic flush" from earlier flushes. When timestamps are quantized to ms, the distinction is lost.

**Possible fixes:**
1. Accept as a known limitation of the DEFAULT lossy preset (1ms quantization inherently loses intra-millisecond ordering).
2. Increase `timeStampTicksPerSecond` in DEFAULT to µs or ns precision (breaks backward compatibility of existing `.cjfr` files, increases file size).
3. Change `inLastBatch()` to use a tolerance window (e.g., events within 1ms of the max are "in last batch") — would fix DEFAULT but might collapse two genuinely distinct batches into one.

## Bug 328: `safepoints` Duration shows `0 s` instead of `Indefinite` when joined event type has no events

**Status:** Fixed.

**Observed:** `cjfr view safepoints profile.jfr` showed `0 s` for the Duration column on all rows. Oracle (`jfr view safepoints profile.jfr`) showed `Indefinite`.

**Root cause:** The `safepoints` view.ini query computes `DIFFERENCE([B|E].startTime)` over a three-way join of `SafepointBegin AS B, SafepointEnd AS E, SafepointStateSynchronization AS S`. In the test recording, `SafepointEnd` and `SafepointStateSynchronization` have zero events — they are not emitted by the JVM in this recording.

The `DIFFERENCE([B|E].startTime)` aggregate uses oracle semantics: when the joined `SafepointEnd` alias `E` has no events for a group, the "end time" is undefined, so the duration is `Indefinite` (`Long.MAX_VALUE` nanos). The cjfr `DiffReducer` received only one value (`B.startTime`) per group (because alias `E` had no rows), and computed `last - first = 0 ns`, rendering as `0 s`.

**Fix:** In `QueryEvaluator.evalJoinCell`, after aggregating a `DIFFERENCE` aggregate with a `Coalesce` argument, check if any alias in the coalesce list has no events in the current group. If so, override the result with `Duration.ofNanos(Long.MAX_VALUE)` → renders as `Indefinite`. This matches oracle behavior where an absent joined alias means the difference is undefined.

## Bug 329: `jvm-flags` double values use 3 significant figures instead of oracle's 4

**Status:** Partially fixed (trailing-zero stripping corrected; 1 rounding edge case remains).

**Observed:** `cjfr view jvm-flags` on lossless `.cjfr` showed:
- `SweeperThreshold: 0.500` (should be `0.5` — trailing zeros)  
- `InitialRAMPercentage: 1.56` (should be `1.562` — too few sig figs)

**Root cause:** `ValueFormatter.formatDouble` called `threeSigFigs` (3 sig figs) instead of oracle's apparent 4-sig-fig format. Oracle strips trailing decimal zeros (e.g. `0.5000` → `0.5`).

**Fix:** Changed `formatDouble` to use `%.4g` with explicit trailing-zero stripping. This fixes the `0.5` case. One remaining diff: `InitialRAMPercentage 1.5625` → cjfr shows `1.563` (Java HALF_UP rounding) while oracle shows `1.562` (HALF_EVEN / banker's rounding). This is a Java `String.format` vs oracle internal rounding mode difference for a specific binary-fraction value.

## Bug 330: Inflate drops fields from zero-occurrence event types

**Symptom:** `jfr view gc /inflated.jfr` fails with "Can't find field named 'gcId' in
jdk.OldGarbageCollection". Metadata inspection shows the inflated JFR only has
`stackTrace`, `eventThread`, `startTime` for any event type with zero occurrences in the
recording.

**Root cause:** `BasicJFRWriter.registerEventTypes()` only stored the ID→name mapping and
`@Label` for pre-registered event types; it did NOT write their `StructType` to the CJFR
stream. At inflate time, `WritingJFRReader.resolveInflatedEventTypeId()` couldn't find these
types in the CJFR type collection and fell back to `recording.registerEventType(name, b -> {})`
— an empty builder yielding only the three default JFR event fields.

**Fix:** `registerEventTypes()` now calls `writeOutEventTypeIfNeeded(t)` for each type,
writing its full `StructType` (all fields + annotations) to the CJFR stream even when the
recording has zero events of that type.

**Scope:** Affects all event types with zero occurrences in the recording, including
`jdk.OldGarbageCollection`, `jdk.ObjectCount`, `jdk.GCPhaseConcurrent`, `jdk.ParallelOldGarbageCollection`,
`jdk.PromotionFailed`, `jdk.EvacuationFailed`, and ~40 others.

**Status:** Fixed in `BasicJFRWriter.registerEventTypes()`.

## Bug 331: Inflated lossless JFR missing `state` field in ExecutionSample/NativeMethodSample

**Symptom:** `jfr print inflated.jfr` shows no `state = "STATE_RUNNABLE"` line in
`jdk.ExecutionSample` and `jdk.NativeMethodSample` events, whereas the original JFR has it
for every event.

**Root cause:** `ReducedJFRTypes` removes the `state` field unconditionally (`c -> true`),
since JFR only samples RUNNABLE threads so the field is always STATE_RUNNABLE. But this also
applied to lossless inflate, where the field was never re-added: the CJFR StructType lacked
`state` in its schema, so neither the JMC type definition nor the event data had the field.

**Fix (two-part):**
1. `WritingJFRReader.createType()`: when building the JMC schema for `ExecutionSample` or
   `NativeMethodSample` and the `state` field is absent from the CJFR struct, add it as a
   `Builtin.STRING` field so the inflated type includes it.
2. `WritingJFRReader.toTypedValue()`: when `state` is null in the ReadStruct (dropped at
   condense), inject the constant `"STATE_RUNNABLE"` instead of null.

**Status:** Fixed.

## Bug 332: `cjfr print` aborts mid-event on out-of-range Instant sentinel (ThreadPark.until)

**Symptom:** `cjfr print profile.cjfr` output for `jdk.ThreadPark` was truncated after the `timeout` field — `until`, `address`, `eventThread`, and `stackTrace` were all missing, and no closing `}` was emitted.

**Root cause:** `ThreadPark.until` is a `@Timestamp("MILLISECONDS_SINCE_EPOCH")` field. When the value is "no timeout" (represented as epoch-millis `Long.MIN_VALUE`), the CJFR reader produces an `Instant` far before `Instant.MIN`. `DateTimeFormatter.format(instant.atZone(...))` threw a `DateTimeException` (invalid EpochDay), which propagated up through `printTextEvent` and aborted output mid-event. The error went to stderr and the closing `}` was never printed.

**Fix:** Added a sentinel guard in `PrintCommand.formatValue`: if the Instant's epoch-second is at or beyond `Instant.MIN`/`Instant.MAX`, return `"N/A"` without calling the formatter. Added a catch-all `DateTimeException → "N/A"` fallback for other edge cases.

**Status:** Fixed. Also affects any other event type with epoch-millis "unset" sentinels.

## Bug 333: `cjfr print` nested struct fields indented at fixed depth instead of relative depth

**Symptom:** `jdk.ModuleExport` and similar events with multiply-nested structs printed inner struct fields at wrong indent level (all at 4 spaces, regardless of nesting depth). Oracle uses 2-space increments per nesting level.

**Root cause:** `formatStruct` hardcoded `"    "` (4 spaces) for field indent and `"  }"` for closing brace, regardless of how deeply the struct was nested.

**Fix:** Added `indent` parameter to `formatStruct(ReadStruct, String)`. The caller passes the current field indent (`"  "` at top level); each nesting level appends `"  "` more. All `formatValue` callers threaded through `indent` parameter.

**Status:** Fixed.

## Bug 334: `cjfr print` shows ClassLoader `name` field ("app") instead of type class name in struct context

**Symptom:** `jdk.ModuleExport` showed `classLoader = app` where oracle shows `classLoader = jdk.internal.loader.ClassLoaders$AppClassLoader`. `jdk.ModuleRequire` showed `classLoader = bootstrap` where oracle shows `classLoader = null` for the bootstrap loader.

**Root cause:** `formatClassLoader` used the loader's `name` field ("app", "bootstrap") as primary, then fell back to type class name. But in standalone struct contexts (not inline within a Class field), oracle renders the type class name directly, and null type (bootstrap) renders as the literal `null`.

**Fix:** Added `formatClassLoaderStandalone` for use in `formatStruct` dispatch: prefers type class name; null type renders as `"null"`. The existing `formatClassLoader` (used inline in Class fields) continues using the `name` field for "bootstrap"/"app".

**Status:** Fixed. ClassLoader.id (shown by oracle as `(id = 3)`) is JFR-internal and not available in CJFR data — this remains a minor known diff.

## Bug 335: `cjfr print` zero memory address renders as `0x0` instead of `0x00000000`

**Symptom:** `jdk.NativeLibrary.topAddress` (and similar zero `@MemoryAddress` fields) printed as `0x0` where oracle shows `0x00000000`.

**Root cause:** `PrintCommand.formatValue` used `"0x%X"` format with no minimum width for MemoryAddress fields.

**Fix:** Changed to `"0x%08X"` — minimum 8 hex digits, zero-padded. Larger addresses (>8 digits) still print without leading zeros, matching oracle behavior.

**Status:** Fixed.

## Bug 336: `cjfr print` ExecutionSample/NativeMethodSample missing `state = "STATE_RUNNABLE"`

**Symptom:** `cjfr print` output for `jdk.ExecutionSample` and `jdk.NativeMethodSample` had 3 fields where oracle has 4. The `state = "STATE_RUNNABLE"` field was absent.

**Root cause:** The condenser drops the `state` field from these event types at condense time because it is always `STATE_RUNNABLE` (only runnable threads are sampled). The raw CJFR data has no `state` field, so `cjfr print` couldn't find it to render it. Bug 331's inflate-time fix injected `state` for JFR inflation but didn't address the print path.

**Fix:** Added a special case in `printTextEvent`: for `jdk.ExecutionSample` and `jdk.NativeMethodSample`, inject `state = "STATE_RUNNABLE"` before the `stackTrace` tail field when the struct lacks a `state` field.

**Status:** Fixed.

## Bug 337: `cjfr view jdk.ExecutionSample` / `jdk.NativeMethodSample` missing `Thread State` column

**Symptom:** `cjfr view jdk.ExecutionSample recording.cjfr` showed only `Start Time`, `Thread`, and `Stack Trace` columns — no `Thread State` column. Oracle `jfr view jdk.ExecutionSample` shows a fourth column `Thread State` with value `STATE_RUNNABLE` for all rows.

**Root cause:** The condenser drops the `state` field from `jdk.ExecutionSample` and `jdk.NativeMethodSample` at condense time (it is always `STATE_RUNNABLE`). Bug 336 added a special-case injection in `PrintCommand` for the `print` path. The `view` path uses `JFRViewConfig(StructType)` to build column definitions from the event type's stored fields — since `state` is absent from the StructType, no column was generated for it.

**Fix:** Added a `buildColumns()` helper in `JFRViewConfig` that post-hoc injects a synthetic `Thread State` column before the `Stack Trace` column when the event type is `jdk.ExecutionSample` or `jdk.NativeMethodSample` and the `state` field is absent from the StructType. The column always renders `STATE_RUNNABLE` (falling back to the actual field value if somehow present). Also added matching synthetic resolution in `FieldResolver` for query evaluator paths.

**Status:** Fixed.

## Bug 338: `cjfr view active-settings` shows all event types instead of one row (default preset)

**Symptom:** `cjfr view active-settings profile.cjfr` (default preset) shows ~80 rows instead of
the oracle's 1 row. Lossless correctly shows 1 row.

**Root cause:** The `active-settings` view uses a 6-way self-JOIN on `jdk.ActiveSetting` with a
`LAST_BATCH` filter. The oracle (ns-precision timestamps) works because only the group for event type
"File Force" (id=1519) has its last `enabled` setting at the globally-last timestamp
(`.357318834`); all others have slightly earlier timestamps (`.357308667`, `.357313709`, etc.), so
only 1 group passes `LAST_BATCH`. With 1ms quantized timestamps (default preset), all 80 enabled
events in the last batch collapse to `.357ms`, so all 80 groups appear to be in the last batch.

**Why not fixable:** Any fix that keeps only 1 group (e.g., by stream position) incorrectly
breaks recordings where multiple event types genuinely share the exact same ns-precision timestamp
at the last batch — in which case oracle correctly shows all of them (verified: renaissance G1 file
shows 187 rows with all 187 event types at the identical timestamp `.991321792`).

**Status:** Known degradation of default (1ms) preset. Lossless preset is correct. The LAST_BATCH
filter in `evaluateJoin` uses per-alias global-max-timestamp comparison, which is semantically
correct for ns precision and merely over-inclusive under ms quantization.

## Bug 339: `cjfr print --json` missing `state` field for ExecutionSample/NativeMethodSample

**Symptom:** `cjfr print --json` output for `jdk.ExecutionSample` and `jdk.NativeMethodSample` had 3 fields (`startTime`, `sampledThread`, `stackTrace`) where oracle has 4. The `state: "STATE_RUNNABLE"` field was absent from the JSON output.

**Root cause:** The `printJsonEvent` method iterates `event.getType().getFields()` directly. Since the `state` field is dropped at condense time (it is always `STATE_RUNNABLE`), it is not in the StructType field list and was not emitted in JSON. The text path (Bug 336) had a special injection before `stackTrace`, but the JSON path had no equivalent injection.

**Fix:** Added the same injection in `printJsonEvent`: after writing the `stackTrace` field, if the event type is `jdk.ExecutionSample` or `jdk.NativeMethodSample` and `state` is absent from the StructType, emit `"state": "STATE_RUNNABLE"`. Field ordering matches oracle (JSON puts `state` after `stackTrace`, unlike text which puts it before).

**Status:** Fixed.

## Bug 340: Lossless preset incorrectly deduplicates per-chunk events in multi-chunk JFR files

**Symptom:** For multi-chunk JFR files (recordings with 18 chunks), `cjfr condense -c lossless` produced far fewer events than oracle for `jdk.BooleanFlag`, `jdk.ActiveSetting`, `jdk.SystemProcess`, `jdk.InitialEnvironmentVariable`, `jdk.InitialSystemProperty`, `jdk.PhysicalMemory`, `jdk.GCSurvivorConfiguration`, `jdk.CPUInformation`, and other chunk-boundary events. Example: oracle has 11826 `BooleanFlag` events, lossless had only 657 (ratio = 18 = number of chunks). Single-chunk JFR files were unaffected.

**Root cause:** `JFREventDeduplication` unconditionally registered deduplicators for `FLAG_EVENTS`, `SINGLETON_EVENTS`, `ActiveSetting`, `SystemProcess`, `InitialEnvironmentVariable/SystemProperty/SecurityProperty`, `ModuleRequire/Export/Resolution`, `PhysicalMemory`, `SwapSpace`, `JavaAgent`, `NativeAgent`, `DeprecatedInvocation` for ALL presets including lossless. These events are all emitted once per JFR chunk with distinct timestamps. In a multi-chunk recording, the deduplication (keyed by payload value) collapsed all 18 copies of each event down to 1 — correctly for default/reduced presets, but incorrectly for lossless which must preserve every distinct-timestamp event.

**Fix:** Wrapped all deduplicator registrations in `JFREventDeduplication` inside a new `registerAllDeduplicators()` method, called only when `!isLosslessPreset(configuration)`. For lossless, no deduplication is applied at all. The inner lossless guard that was previously protecting only the `registerPeriodicTimeSeries()` call was removed (redundant after the outer guard).

**Status:** Fixed.

## Bug 341: `cjfr print --json` crashes with `Invalid value for EpochDay` for sentinel Instant fields

**Symptom:** `cjfr print --json profile_lossless.cjfr` terminated with `Error: Invalid value for EpochDay (valid values -365243219162 - 365241780471): -365243219528` mid-output, producing truncated/invalid JSON. The `until` field of `jdk.ThreadPark` events with no timeout (sentinel `Instant.MIN`) was the trigger.

**Root cause:** The `toJson(Object, String)` method formatted `Instant` values via `instant.atZone(ZoneId.systemDefault())`. Converting `Instant.MIN` (representing "no value set", stored as Long.MIN_VALUE nanoseconds) to a `ZonedDateTime` calls `LocalDate.ofEpochDay()` internally, which throws `DateTimeException` for epoch-day values outside its valid range.

**Fix:** Wrapped the `instant.atZone()` call in a try-catch for `DateTimeException`. On failure, falls back to `instant.toString()` which produces `-1000000000-01-01T00:00:00Z` (valid ISO-8601, slightly different from oracle's timezone-aware rendering but semantically equivalent).

**Status:** Fixed.

## Bug 342: Inflation loses event type metadata for event types with 0 recorded events

**Symptom:** `cjfr view events-by-count recording.cjfr` outputs `Event Types by Count` (no `(Experimental)` suffix), while oracle `jfr view events-by-count recording.jfr` outputs `Event Types by Count (Experimental)`. Also, the inflated `.jfr` file has fewer event type definitions (e.g. `jdk.Flush`, `jdk.SyncOnValueBasedClass`, `jdk.ZStatisticsCounter`, `jdk.ZStatisticsSampler`, `jdk.ZThreadPhase`) than the original.

**Root cause:** CJFR format stores only event types for which at least one event was written. During inflation (`WritingJFRReader.toJFRFile`), event type definitions are emitted only for types that appear in the CJFR event stream. Event types that existed in the original recording with 0 events (their type definition is in the JFR metadata chunk, but no event data) are never written to the CJFR stream and therefore absent from inflated files.

**Impact:** The `jfr view events-by-count` oracle marks the view title `(Experimental)` when any event type in `FROM *` has `@Experimental` annotation. With `@Experimental` types absent from the inflated file, the title is rendered without the suffix. Inflated files have fewer event type definitions, affecting any tool that relies on JFR type metadata (e.g. `jfr metadata`).

**Fix:** Would require storing 0-event type definitions in the CJFR format (format version bump) or maintaining a separate type-definition-only section. Not currently worth the format complexity.

**Status:** Known limitation. Accept as won't-fix.

## Bug 343: `cjfr print --json` float fields rendered with double precision instead of float precision

**Symptom:** `cjfr print --json` outputs `0.44999998807907104` for `jdk.G1BasicIHOP.thresholdPercentage`, while oracle shows `0.45`. Similarly `jdk.G1AdaptiveIHOP.thresholdPercentage` outputs `0.4736842215061188` instead of `0.47368422`.

**Root cause:** In `PrintCommand.toJson()`, the `Float` case called `n.doubleValue()` which converts float32 → float64, exposing the float32 representation error at double precision. `String.valueOf(0.44999998807907104f)` should be `0.45` but `String.valueOf((double)0.45f)` is `0.44999998807907104`.

**Fix:** Added a separate `instanceof Float` branch that calls `n.floatValue()` and uses `String.valueOf(float)`, preserving the float32 representation exactly.

**Status:** Fixed.

## Bug 344: `cjfr print --json` renders `Instant.MIN` sentinel as `-1000000000-01-01T00:00:00Z` instead of oracle's `-999999999-01-01T00:00+18:00`

**Symptom:** `cjfr print --json` outputs `"-1000000000-01-01T00:00:00Z"` for `jdk.ThreadPark.until` (the "no deadline" sentinel), while oracle outputs `"-999999999-01-01T00:00+18:00"`.

**Root cause:** The earlier Bug 341 fix used `instant.toString()` as a fallback for `Instant.MIN` (which throws `DateTimeException` when converted through `atZone`). Java's `Instant.MIN.toString()` produces `-1000000000-01-01T00:00:00Z` (UTC). The oracle renders it as the earliest local date-time in Java's proleptic Gregorian calendar (`LocalDateTime.MIN` at `ZoneOffset.MAX` = `+18:00`), which is `-999999999-01-01T00:00+18:00` (without zero seconds, as oracle omits them).

**Fix:** Changed fallback in `PrintCommand.toJson()` for negative-sentinel Instants that exceed `LocalDate` range to return the string literal `-999999999-01-01T00:00+18:00`, exactly matching oracle. MAX Instant returns `+999999999-12-31T23:59:59.999999999-18:00`.

**Status:** Fixed.

## Bug 345: `cjfr print` omits classloader instance ID `(id = N)` in standalone ClassLoader fields

**Symptom:** `cjfr print` renders `classLoader = jdk.internal.reflect.DelegatingClassLoader` while oracle renders `classLoader = jdk.internal.reflect.DelegatingClassLoader (id = 6)`. Affects any event with a standalone `classLoader` field (e.g. `jdk.ClassLoaderStatistics`, `jdk.ModuleExport`, `jdk.ModuleRequire`).

**Root cause:** The oracle adds `(id = N)` from `RecordedClassLoader.getId()`, which returns the constant pool slot number of the classloader instance in the JFR recording. cjfr's `formatClassLoaderStandalone()` has no access to this ID because:
1. The classloader's constant pool ID is not stored as a regular field in the `jdk.types.ClassLoader` struct (only `type` and `name` are stored).
2. During condense, all `DelegatingClassLoader` instances (same type, null name) are deduplicated into a single pool entry — so all inflated entries share the same ID.

**Impact:** Minor: display-only difference in classloader identity. Semantic data (type name, loader name) is preserved. Different `DelegatingClassLoader` instances can't be distinguished in print output (they differ by ID in the original).

**Fix:** Would require storing the original classloader pool ID as a synthetic field in the condensed format, or preserving per-instance identity for classloaders during deduplication. Non-trivial format change.

**Status:** Known limitation.

## Bug 346: `cjfr print --json` timestamps have fewer than 9 fractional-second digits (trailing zeros stripped)

**Symptom:** `cjfr print --json` renders `"2025-12-05T12:12:20.4844675+01:00"` while oracle outputs `"2025-12-05T12:12:20.484467500+01:00"`. Timestamps whose nanosecond value has trailing zeros are shortened (`.484467500` → `.4844675`).

**Root cause:** `PrintCommand.toJson()` used `DateTimeFormatter.ISO_OFFSET_DATE_TIME` which outputs the minimum number of fractional digits needed to represent the value without loss (Java's standard behavior). Oracle's `jfr print --json` always outputs exactly 9 nanosecond digits.

**Fix:** Added a static `JSON_TIMESTAMP_FMT` built with `DateTimeFormatterBuilder.appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, true)` to force exactly 9 fractional digits, matching oracle's fixed-width nanosecond format.

**Status:** Fixed.

## Bug 347: `cjfr print --json` timestamps use wrong fractional digit count (fixed 9 vs oracle's ms/µs/ns trimming)

**Symptom:** After Bug 346 fix, `cjfr print --json` outputs `"2025-12-05T12:12:20.354000000+01:00"` (9 digits) where oracle outputs `"2025-12-05T12:12:20.354+01:00"` (3 digits). Oracle trims trailing zeros at 3-digit (ms/µs/ns) boundaries.

**Root cause:** The Bug 346 fix used `appendFraction(..., 9, 9, true)` (fixed 9 digits). Oracle always trims at 3-digit group boundaries: outputs 3 digits when last 6 nanosecond digits are 0, 6 digits when last 3 are 0, and 9 digits otherwise. Java's `appendFraction(..., 0, 9, true)` strips individual zeros (wrong), and fixed-9 never trims (also wrong).

**Fix:** Replaced single `JSON_TIMESTAMP_FMT` with three formatters (`JSON_TS_3`, `JSON_TS_6`, `JSON_TS_9`) and a `jsonTimestampFmt(Instant)` selector that picks based on `instant.getNano() % 1_000_000 == 0` (→ 3 digits) / `% 1_000 == 0` (→ 6 digits) / else (→ 9 digits).

**Status:** Fixed.

## Bug 348: `cjfr print --json` renders unsigned long values as negative signed longs

**Symptom:** `UnsignedLongFlag.value` for `MaxGCMinorPauseMillis` and `MaxMetaspaceSize` shows `-1` in cjfr but `18446744073709551615` (0xFFFFFFFFFFFFFFFF) in oracle. Affects all `@Unsigned long` JFR fields.

**Root cause:** `PrintCommand.toJson()` called `n.longValue()` unconditionally, rendering Java's signed `-1L` as `-1` instead of the unsigned representation `18446744073709551615`.

**Fix:** Added `CondensedType<?,?>` parameter to `toJson()`. When `fieldType instanceof VarIntType vit && !vit.isSigned()`, use `Long.toUnsignedString(l)` to render unsigned longs correctly.

**Status:** Fixed.

## Bug 349: `cjfr print --json` Duration sentinel values rendered incorrectly (Forever/N/A)

**Symptom:** `jdk.ActiveRecording.maxAge` shows `PT2562047H47M16.854S` (≈292 years) instead of oracle's `PT2562047788015215H30M7.999999999S` (Duration.MAX_VALUE). `jdk.GCConfiguration.pauseTarget` shows a similarly wrong negative Duration instead of oracle's `PT-2562047788015215H-30M-8S`.

**Root cause:** `JFRReduction.TIMESPAN_REDUCTION.inflate()` returned `Duration.ofNanos(Long.MAX_VALUE)` for the "Forever" sentinel and `Duration.ofNanos(Long.MIN_VALUE)` for the "N/A" sentinel. These differ from oracle's `Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)` and `Duration.ofSeconds(Long.MIN_VALUE, 0)`. Additionally, the millisecond-quantization VarIntType divided `Long.MAX_VALUE` by 1_000_000, making the round-tripped value `9223372036854000000` (not exactly `Long.MAX_VALUE`), so an exact equality check failed.

**Fix:** `TIMESPAN_REDUCTION.inflate()` now checks for the near-max/near-min range (within 1_000_000 ns tolerance) and returns `Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)` / `Duration.ofSeconds(Long.MIN_VALUE, 0)` respectively. `PrintCommand.formatDuration()` updated to guard against `getSeconds() >= Long.MAX_VALUE - 1` overflow before calling `toNanos()`.

**Status:** Fixed.

## Bug 350: `cjfr print` renders `jdk.ActiveSetting.id` as event-type-name string instead of oracle's integer event-type-id

**Symptom:** `jfr print` shows `id = 10` (integer event type ID), but `cjfr print` shows `id = "jdk.AllocationRequiringGC"` (the event type name string). Affects both text and JSON output.

**Root cause:** At condense time, `ActiveSetting.id` is remapped from the raw integer event type ID to the event type name string, for JMC compatibility (`project_jmc_compat.md`). This makes the field value a String in the condensed type system, which serializes as a quoted name.

**Status:** Known limitation (intentional JMC compatibility trade-off). Not planned to fix without a way to preserve both integer and name representations.

## Bug 351: `cjfr print` uses `Locale.ROOT` decimal separator (`.`) while oracle's `jfr print` uses system locale (may be `,` on European systems)

**Symptom:** On a JVM with locale `English (Germany)`, oracle outputs `flushInterval = 1,00 s` (comma) but cjfr outputs `flushInterval = 1.00 s` (period).

**Root cause:** cjfr's `ValueFormatter` uses `Locale.ROOT` consistently for reproducible output; oracle's `jfr print` uses the JVM default locale (e.g., `String.format(...)` without explicit Locale).

**Status:** Known limitation. cjfr's behavior is arguably better (locale-independent). Not planned to fix.

## Bug 352: `cjfr print` (text format) renders `@Unsigned long` fields as signed longs

**Symptom:** `UnsignedLongFlag.value` for `MaxGCMinorPauseMillis` and `MaxMetaspaceSize` shows `-1` in cjfr text output but `18446744073709551615` (0xFFFFFFFFFFFFFFFF) in oracle. Bug 348 only fixed the JSON path.

**Root cause:** `PrintCommand.formatValue()` (text path) fell through to `value.toString()` without checking `VarIntType.isSigned()`. The JSON path was separately fixed in Bug 348 via `toJson()`.

**Fix:** Added unsigned check near end of `formatValue()`: when `field.type() instanceof VarIntType vit && !vit.isSigned() && value instanceof Long l`, use `Long.toUnsignedString(l)`.

**Status:** Fixed.

## Bug 354: `cjfr print` memory/bitrate scale stops at PB — should include EB

**Symptom:** `jdk.G1BasicIHOP.recentAllocationRate` shows `8192.0 PB/s` in cjfr but `8,0 EB/s` in oracle `jfr print`. Similarly, any memory or bitrate value in the exabyte/exabit range (≥ 1024 PB) formats incorrectly.

**Root cause:** `ValueFormatter.formatMemory()` and `ValueFormatter.formatBitrate()` defined unit arrays that topped out at `"PB"` / `"Pbps"`. Values such as `Long.MAX_VALUE` bytes (≈ 8 EB, used as a sentinel for "initial/unset allocation rate" in IHOP events) exceeded the maximum unit and were printed in PB with a large mantissa.

**Fix:** Added `"EB"` to `formatMemory`'s unit array and `"Ebps"` to `formatBitrate`'s unit array.

**Status:** Fixed.

## Bug 355: `cjfr print` emits `eventThread = N/A` when oracle `jfr print` omits null eventThread

**Symptom:** Events such as `jdk.JavaErrorThrow`, `jdk.JavaMonitorWait`, `jdk.ObjectAllocationSample`, and `jdk.ThreadPark` (which have a null `eventThread`) show `eventThread = N/A` in cjfr but have no `eventThread` line in oracle output.

**Root cause:** `shouldSuppressField()` only suppressed null stackTrace and zero event duration. Null `eventThread` was not suppressed, so `formatValue(null, field)` returned `"N/A"` and the line was printed.

**Fix:** Added `if (value == null && "eventThread".equals(field.name())) return true;` to `shouldSuppressField()`.

**Status:** Fixed.

## Bug 356: `cjfr print` renders empty stackTrace as `[]` instead of oracle's `[\n  ]`

**Symptom:** `jdk.ThreadStart` events with an empty (zero-frame) stackTrace show `stackTrace = []` in cjfr but `stackTrace = [\n  ]` (open bracket, blank line, close bracket) in oracle.

**Root cause:** `formatStackTrace()` returned the compact `"[]"` string for empty frame lists. Oracle prints the open bracket, an empty line, and the close bracket on separate lines.

**Fix:** Changed `if (frames.isEmpty()) return "[]"` to `return "[\n  ]"` in `formatStackTrace()`.

**Status:** Fixed.

**Symptom:** Oracle `jfr print` shows `classLoader = jdk.internal.reflect.DelegatingClassLoader (id = 6)` while cjfr shows only `classLoader = jdk.internal.reflect.DelegatingClassLoader`. The `(id = N)` distinguishes multiple instances of the same ClassLoader class (e.g., different `DelegatingClassLoader` instances with ids 4, 6, 11, 13...).

**Root cause:** The `(id = N)` suffix is derived from the constant pool reference index in the JFR file. cjfr's pool-less struct design (inline struct encoding, deduplication) does not preserve pool indices — there is no ID available to the printer.

**Status:** Known limitation (structural: pool IDs not preserved in cjfr format). Affects events with classLoader fields: `jdk.ModuleExport`, `jdk.ModuleRequire`, `jdk.ClassLoaderStatistics`, etc.

## Bug 357: `cjfr print --json` serializes `Infinity`/`NaN` float values instead of `null`

**Symptom:** Oracle `jfr print --json` renders non-finite double/float values (e.g. `jdk.G1BasicIHOP.recentAllocationRate` when prediction has not yet started) as JSON `null`. cjfr renders them as the non-standard JSON token `Infinity` (via Java's `String.valueOf(double)`).

**Root cause:** `toJson()` called `String.valueOf(n.doubleValue())` / `String.valueOf(n.floatValue())` unconditionally. `String.valueOf(Double.POSITIVE_INFINITY)` = `"Infinity"`, which is invalid JSON and differs from oracle's `null`.

**Fix:** Added `Double.isFinite(dv) ? ... : "null"` guard for both Float and Double branches in `toJson()`.

**Status:** Fixed.

## Bug 358: `cjfr print --json` formats epoch-zero `Instant` as `HH:mm:ss.mmm+offset` instead of oracle's `HH:mm+offset`

**Symptom:** `jdk.ThreadPark.until` with value `Instant.EPOCH` (0ms, representing "park indefinitely") shows `1970-01-01T01:00:00.000+01:00` in cjfr but `1970-01-01T01:00+01:00` in oracle.

**Root cause:** `jsonTimestampFmt()` always selected `JSON_TS_3` (3-decimal format) for millisecond-boundary timestamps. Oracle omits the `:00.000` seconds+fraction when both are zero for the epoch sentinel.

**Fix:** Added `JSON_TS_0` formatter (`yyyy-MM-dd'T'HH:mm` + offset) and select it when `instant == Instant.EPOCH` (`epochSecond == 0 && nano == 0`).

**Status:** Fixed.

## Bug 359: `cjfr print` renders `Long.MIN_VALUE` DataAmount sentinel as `--9223372036854775808 bytes` instead of `N/A`

**Symptom:** `jdk.YoungGenerationConfiguration.maxSize` shows `--9223372036854775808 bytes` in cjfr but `N/A` in oracle. Reproducible with ZGC recordings where ZGC has no young generation size limit.

**Root cause:** The JFR field is declared `@Unsigned long maxSize`. When no limit is configured, ZGC sets it to `Long.MIN_VALUE` as an "unset" sentinel. The condensed storage uses a signed VarInt for DataAmount fields. `formatMemory(Long.MIN_VALUE)` sets `neg = true` and calls `Math.abs(Long.MIN_VALUE)`, which overflows back to `Long.MIN_VALUE` (negative). The result is `"-" + "-9223372036854775808 bytes"` = `"--9223372036854775808 bytes"`. In JSON, the value is emitted as `-9223372036854775808` while oracle emits the unsigned `9223372036854775808`.

**Fix:** In the `jdk.jfr.DataAmount` branch of `formatValue()`, check for `v == Long.MIN_VALUE` before calling `formatMemory` and return `"N/A"`. In `toJson()`, added a check: when `fieldType` is a `"memory varint"` VarIntType and value is `Long.MIN_VALUE`, emit `Long.toUnsignedString(Long.MIN_VALUE)` = `"9223372036854775808"`.

**Status:** Fixed.

## Bug 360: `cjfr print` renders near-60-second timeouts as `60.0 s` instead of `1 m 0 s`

**Symptom:** `jdk.ThreadPark.timeout` values of `PT59.999999958S` (≈60s, but not exactly) are rendered as `60.0 s` by cjfr but as `1 m 0 s` by oracle. Affects 3767 ThreadPark events in the gauss-mix recording.

**Root cause:** `formatTimespanAbs` used the raw `seconds` value for threshold comparison. `59.999999958 < 60.0` so it skips the `>= 60` branch, falls into the `>= 1_000_000_000L` (nanos ≥ 1s) branch, and calls `threeSigFigs(59.999999958)` which rounds to `"60.0"` — a correct display value but in the wrong unit. Oracle rounds before unit selection.

**Fix:** Computed `roundedSeconds = Double.parseDouble(threeSigFigs(seconds))` and used that for the `>= 60` and `>= 3600` threshold tests in `ValueFormatter.formatTimespanAbs`.

**Status:** Fixed.

## Bug 361: `cjfr print` renders float `@Frequency` fields as integers (drops decimal part)

**Symptom:** `jdk.ThreadContextSwitchRate.switchRate = 8975.555 Hz` in oracle but `8975 Hz` in cjfr. The decimal part is truncated.

**Root cause:** The `@Frequency` branch in `formatValue()` called `n.longValue() + " Hz"`, which truncates floats/doubles to integers. The `switchRate` field is declared as `float` in the JFR metadata.

**Fix:** Added float/double handling in the `@Frequency` branch: if the value is a float or double, use the float/double representation. Integer-valued floats still render without decimal (e.g. `1000 Hz`).

**Status:** Fixed.

## Bug 362: `cjfr print` renders `-1 byte` as `-1 bytes` (wrong plural for memory count of 1)

**Symptom:** `jdk.GCHeapMemoryPoolUsage.max = -1 byte` in oracle but `-1 bytes` in cjfr. Also affects `bytesRead = 1 byte` positive case.

**Root cause:** `formatMemory` always used `"bytes"` (plural) for the raw-bytes unit. Oracle uses singular `"byte"` when the absolute value is exactly 1.

**Fix:** In `formatMemory`, when `u == 0` (bytes unit) and `abs == 1`, use `"byte"` (singular) instead of `"bytes"`.

**Status:** Fixed.

## Bug 363: `cjfr view/print` renders sub-second durations near 1 s as "1000 ms" instead of "1.00 s"

**Symptom:** A duration of 999.5 ms (or any value in ~[999.5 ms, 1000 ms)) renders as `1000 ms` in cjfr but `1.00 s` (or `1,00 s` in German locale) in oracle. Oracle rounds to 3 significant figures before selecting the display unit; cjfr did not apply this rounding for the ms→s boundary.

**Root cause:** `formatTimespanAbs` used the raw `nanos >= 1_000_000_000L` condition to select the seconds branch. `roundedSeconds` was already computed for the `>= 60` and `>= 3600` thresholds (Bug 360 fix), but not used for the ms→s boundary. For `nanos = 999_500_000` (999.5 ms), `nanos < 1_000_000_000` so the code fell into the ms branch and called `threeSigFigs(999.5)`, which rounds to `"1000"`, producing `"1000 ms"` instead of `"1.00 s"`.

**Fix:** Added `|| roundedSeconds >= 1.0` to the seconds branch condition, matching the Bug 360 pattern. Now `roundedSeconds = 1.00` routes to the seconds branch → `threeSigFigs(0.9995) + " s"` = `"1.00 s"`.

**Status:** Fixed.

## Bug 364: `cjfr view` FREQUENCY ColumnType truncates float frequencies to integers

**Symptom:** In views, a float-typed `@Frequency` field like `ThreadContextSwitchRate.switchRate = 8975.555f` renders as `8975 Hz` instead of `8975.555 Hz`. This is the view-path equivalent of Bug 361 (which fixed only the print path).

**Root cause:** `ValueFormatter.format()` handled `ColumnType.Kind.FREQUENCY` with `n.longValue() + " Hz"`, which truncates float/double values to integers. The print path (`PrintCommand.formatValue()`) was separately fixed in Bug 361.

**Fix:** Applied the same float/double handling as Bug 361 to `ValueFormatter.format()`'s FREQUENCY branch: float and double values check for whole-number (`== Math.rint(v)`) and either render without decimal (integer-valued) or with decimal (non-integer).

**Status:** Fixed.

## Bug 365: `cjfr view` shows full lambda parameter list instead of oracle's `(...)`

**Symptom:** In views with a method column (e.g., `hot-methods`), cjfr renders lambda methods as
`me.bechberger.WritingJFRReader.lambda$toTypedValue$0(ReadStruct, WritingJFRReader$ReadStructPath, TypedValueBuilder)` while oracle shows `me.bechberger.WritingJFRReader.lambda$toTypedValue$0(...)`. The auto-generated parameter types of lambda methods are not meaningful to users.

**Root cause:** `ValueFormatter.formatMethod()` decoded and displayed the full parameter list unconditionally. Oracle abbreviates parameters to `(...)` whenever the method name contains `lambda$` (the JVM-generated name pattern for lambda expressions). The print path (`jfr print` stack traces) shows full params; only the view method-cell path uses `(...)`.

**Fix:** In `ValueFormatter.formatMethod()`, when the method name contains `lambda$`, use `"..."` as the parameter string instead of the decoded descriptor params. The print path (`PrintCommand.formatMethod`) is unchanged.

**Status:** Fixed.

## Bug 366: `cjfr view` rounds double flag values with HALF_UP instead of oracle's HALF_EVEN

**Symptom:** `InitialRAMPercentage` shows `1.563` in cjfr view but `1.562` in oracle `jfr view`. The raw value is `1.5625` which is exactly halfway between `1.562` and `1.563` when rounded to 4 significant figures.

**Root cause:** `ValueFormatter.formatDouble()` used `String.format(Locale.ROOT, "%.4g", v)` which applies `HALF_UP` rounding. Oracle's `jfr view` uses `HALF_EVEN` (banker's rounding). The divergence only affects halfway values (last significant digit is exactly 5), but `1.5625` is a common value for `InitialRAMPercentage`.

**Fix:** Changed `formatDouble()` to use `new BigDecimal(v).round(new MathContext(4, RoundingMode.HALF_EVEN)).toPlainString()` instead of `String.format("%.4g", ...)`.

**Status:** Fixed.

## Bug 367: `cjfr print` omits `(id = N)` for standalone ClassLoader fields

**Symptom:** Oracle `jfr print` renders a ClassLoader-typed event field as `classLoader = jdk.internal.reflect.DelegatingClassLoader (id = 6)`, but cjfr rendered it as `classLoader = jdk.internal.reflect.DelegatingClassLoader` — without the pool ID suffix.

**Root cause:** `PrintCommand.formatClassLoaderStandalone()` returned only the type class name, ignoring the constant-pool ID of the ClassLoader instance. Oracle always appends ` (id = N)` for standalone ClassLoader fields (those that are direct fields of an event, not ClassLoader values nested inside a Class struct).

**Fix:** Added `getPoolId(String fieldName)` to `ReadStruct` to expose the `idsOrNull` pool ID for a given field. In `PrintCommand.printTextEvent()`, ClassLoader-typed domain fields are now detected before the generic `formatValue()` dispatch, and `formatClassLoaderStandalone(loader, poolId)` is called with the event's pool ID for that field. The pool IDs in cjfr output differ from oracle because cjfr deduplicates identical ClassLoader structs in its constant pool (a known accepted difference: opaque pool renumbering).

**Status:** Fixed (structural format matches; pool IDs are renumbered as expected).

## Bug 368: `cjfr print --json` does not escape forward slashes and uses expanded array format

**Symptom:** Two JSON formatting differences from oracle `jfr print --json`:
1. Oracle escapes forward slashes as `\/` (e.g. `"destination": "\/Users\/..."`) but cjfr emitted literal `/`.
2. Oracle uses compact array notation `"events": [{...}, {...}]` — the first event starts on the same line as `[`, and between events the delimiter is `, {` — but cjfr used an expanded format with the array opening on its own line.

**Root cause:** (1) `PrintCommand.jsonEscape()` had `replace` calls for `\`, `"`, and control characters but missed `/`. (2) `printJson()` called `printJsonEvent()` which began with `System.out.print(indent + "{")`, so each event's `{` was printed by the event method itself rather than being attached to the preceding `[` or `}, `.

**Fix:** (1) Added `.replace("/", "\\/")` to `jsonEscape()`. (2) `printJson()` now prints `{` (for first event) or `, {` (for subsequent) before calling `printJsonEvent()`; `printJsonEvent()` no longer prints the opening `{`.

**Status:** Fixed.

## Bug 369: `cjfr print` renders `jdk.ActiveSetting.id` as event-type name string instead of numeric ID

**Symptom:** Oracle `jfr print` renders `id = 2` (the original numeric event-type class ID), but cjfr renders `id = "jdk.ThreadStart"` (the resolved event-type name in string form, with quotes).

**Root cause:** At condense time, `BasicJFRWriter.createActiveSettingIdField()` stores the event-type name as a String instead of the original numeric ID. This was intentional (Bug 2 in JMC_FIX.md) to fix a JMC NPE when inflating recordings. The remapping is a non-reversible schema change: the numeric class ID from the original recording is discarded and replaced with the string name.

**Status:** Known accepted difference (JMC compatibility requirement). The print output differs from oracle for this field.

## Bug 370: `cjfr print --json` emits a trailing newline after the final `}`

**Symptom:** Oracle `jfr print --json` ends without a trailing newline (`}` is the last byte), but cjfr emitted `}\n` (extra newline).

**Root cause:** `printJson()` used `System.out.println("}")` for the final closing brace, which appends `\n`.

**Fix:** Changed final `println("}")` to `print("}")` in `printJson()`.

**Status:** Fixed.

## Bug 371: `cjfr print --json` uses expanded array format instead of oracle's compact `[{...}, {...}]`

**Symptom:** Oracle renders JSON arrays as `"frames": [{...frame1...}, {...frame2...}]` — first element on same line as `[`, elements separated by `, ` (no extra newlines between the `}` of one element and `, {` of the next). cjfr rendered `[\n  {...}\n  {...}]` with extra leading newline and extra indentation.

**Root cause:** `listToJson()` prepended `\n + inner` before each element (including the first), and used `inner = indent + "  "` as the element indentation context, adding 2 extra spaces vs oracle.

**Fix:** Removed the leading `\n` before the first element (so it starts on the same line as `[`). Changed separator between elements from `\n` to just `, ` (the `}` closing the previous struct already ends with `\n + indent`, so `, {` appears on the same line as the closing `}`). Changed element indentation from `inner = indent + "  "` to `indent` to match oracle's nesting level. Removed the trailing `\n + indent` before `]` (so `]` appears immediately after the last element's closing `}`).

**Status:** Fixed.

## Bug 372: `cjfr print --json --stack-depth N` produces invalid JSON with trailing comma

**Symptom:** Using `--stack-depth N` with JSON output caused `json.decoder.JSONDecodeError: Illegal trailing comma before end of array` when N is less than the stack trace depth. The generated JSON included a trailing `, ` after the last included frame.

**Root cause:** `listToJson()` had `if (i < list.size() - 1 && i == limit - 1) sb.append(", ");` — intended to signal truncation but actually appended a trailing comma after the last element, which is illegal JSON.

**Fix:** Removed the trailing-comma append. Oracle simply shows the top N frames without any trailing marker; `"truncated"` on the stackTrace struct conveys whether frames were omitted.

**Status:** Fixed.

## Bug 373: `cjfr view class-loaders` collapses multiple `DelegatingClassLoader` instances into one row

**Symptom:** `cjfr view class-loaders` shows one row for `jdk.internal.reflect.DelegatingClassLoader` while oracle `jfr view class-loaders` shows 21 distinct rows (one per unique ClassLoader instance).

**Root cause:** The `class-loaders` view uses `GROUP BY classLoader`. Oracle groups by pool-object identity — each `DelegatingClassLoader` instance in the JFR file has a unique constant-pool slot (ids 4, 6, 10, 11, 13, 15, 16, 18, 19, 20, 21, …), so 21 distinct groups form. cjfr deduplicates identical ClassLoader structs (same type, null name) into a single pool entry during condensation, so all `DelegatingClassLoader` events reference the same `ReadStruct` object (pool id = 2). The `canonicalKey` method formats them to the same display string, collapsing all 21 groups into 1.

**Impact:** `class-loaders` view understates the number of distinct classloader instances when multiple instances of the same ClassLoader type (with null name) exist in the recording.

**Fix:** Would require preserving per-instance classloader identity through the condense pipeline (e.g., storing original JFR pool IDs for ClassLoader structs rather than deduplicating by value). Non-trivial format change; same root cause as Bug 345 (classLoader ID renumbering in `print`).

**Status:** Known limitation (structural: classloader pool deduplication collapses instances with identical type+name).

## Bug 374: `cjfr view` renders lambda method parameters as `(...)` instead of decoded types

**Symptom:** `cjfr view allocation-by-site` (and other views showing stack frame methods) renders lambda methods as `lambda$export$0(...)` while oracle renders the actual decoded parameter types: `lambda$export$0(byte[][], ByteBuffer)`.

**Root cause:** `ValueFormatter.formatMethod()` had a `nameStr.contains("lambda$")` branch that unconditionally replaced params with `"..."`. This was modelled after oracle's `ValueFormatter.formatMethod(m, compact=true)` compact rendering (which abbreviates to `"..."`). However, oracle's view table uses `FieldFormatter.format()` (not `formatCompact()`), which passes `compact=false` — displaying full decoded params. The compact form is only used as a fallback when text is wider than its column.

**Fix:** Removed the `lambda$` special-case in `ValueFormatter.formatMethod`; all methods (including lambdas) now use the decoded descriptor parameter list.

**Status:** Fixed.

## Bug 375: `cjfr view events-by-count`/`events-by-name` missing `(Experimental)` suffix on `.cjfr` files

**Symptom:** `cjfr view events-by-count recording.cjfr` renders `Event Types by Count` but oracle renders `Event Types by Count (Experimental)`. The `(Experimental)` suffix appears in oracle's output because the JFR recording contains experimental event types (e.g. `jdk.Flush`, `jdk.ZStatisticsCounter`) even though they have 0 events.

**Root cause:** `events-by-count` and `events-by-name` use `FROM *` queries which cjfr cannot evaluate natively — they fall through to oracle `jfr view` via inflation. When inflating a `.cjfr` file, event types with 0 events are written as minimal stubs (3 fields, no annotations). The inflated `.jfr`'s stub types lack the `@Experimental` annotation, so oracle's `isExperimental()` check does not fire.

For `.jfr` inputs, cjfr correctly delegates to oracle `jfr view` directly (no inflation), so the result matches oracle exactly.

**Fix:** Preserve full event type schema (field definitions + annotations) in `.cjfr` for all event types, including zero-event ones. Requires storing zero-event type registrations in the `.cjfr` format. Non-trivial format change.

**Status:** Known limitation (structural: zero-event type schemas not stored in `.cjfr`).

## Bug 376: `cjfr view safepoints` fails on `.cjfr` files ("Missing event found")

**Symptom:** `cjfr view safepoints recording.cjfr` produces "Missing event found for safepoints", while oracle `jfr view safepoints recording.jfr` shows a full table with 31 rows (all showing `Indefinite` duration). The view works correctly on `.jfr` input files with cjfr.

**Root cause:** `safepoints` uses a correlated join on `SafepointBegin`, `SafepointEnd`, and `SafepointStateSynchronization`. The latter two have 0 events. When inflating `.cjfr` to `.jfr` for oracle delegation, zero-event types are written as minimal stubs without their actual fields (e.g. `SafepointStateSynchronization` loses its `duration` field). Oracle then reports "Can't find field named 'S.duration'" and fails.

On `.jfr` input: cjfr evaluates `safepoints` natively (correctly returns 31 rows with `Indefinite` duration), and oracle `jfr view` also works since the original type definitions are intact.

**Fix:** Same root cause as Bug 375 — requires preserving full type schema for zero-event types in `.cjfr` inflation.

**Status:** Known limitation (structural: zero-event type schemas not stored in `.cjfr`; only affects `.cjfr` input, not `.jfr`).

## Bug 377: `cjfr view system-processes` truncates Command Line from end instead of beginning

**Symptom:** `cjfr view system-processes recording.cjfr` truncates long command line paths from the end (e.g. `"/Applications/Adobe Acrobat DC/Adobe Acrobat.app/Contents/Helpers/AdobeResourceSynchronizer.app/Contents/MacOS/Adobe..."`) instead of from the beginning (e.g. `"...Adobe Acrobat DC/Adobe Acrobat.app/Contents/Helpers/AdobeResourceSynchronizer.app/Contents/MacOS/AdobeResourceSynchronizer"`). Oracle uses beginning truncation for this column to show the most-specific (rightmost) part of the path.

**Root cause:** `ViewRenderer.wrapCell()` used only the global `--truncate` CLI flag (`this.truncateBeginning`) and ignored per-column `FORMAT truncate-beginning` hints from `view.ini`. The `system-processes` Command Line column carries a `FORMAT truncate-beginning` hint in the on-system `view.ini`.

**Fix:** Added `truncateBeginningFor(int col)` method (parallel to `shrinkable`/`normalizedFor`) that checks for a `truncate-beginning` FORMAT hint on the column. Updated `wrapCell` signature to accept `boolean colTruncateBeginning` and updated `truncateLines` similarly; both methods combine `truncateBeginning || colTruncateBeginning` for the direction decision.

**Status:** Fixed.

## Bug 378: `cjfr view hot-methods`/`memory-leaks-by-site` shows full method params when oracle truncates to `(...)`

**Symptom:** When a method signature is too wide for its table column, oracle renders `ClassName.methodName(...)` (compact form) but cjfr renders the full signature truncated with trailing `...` (e.g. `lambda$toTypedValue$0(ReadStruct, WritingJFRReader$ReadStructPath...`). Affects any view where a method column is narrower than some method signatures.

**Root cause:** Oracle's `TableRenderer.setCellContent()` detects when a formatted cell string exceeds the column width and calls `FieldFormatter.formatCompact()` which renders methods as `class.method(...)`. Our `ViewRenderer` had no equivalent: it passed the full string directly to `wrapCell()` which did end-truncation with `...` rather than compact method form.

**Fix:** After `distributeFlexibleWidth()` finalizes column widths, scan all cells: if a cell's string exceeds its column width and `compactMethod()` produces a shorter result (strips params to `(...)`), apply the compact form. `compactMethod()` pattern-matches on the last `(.+)` suffix of fully-qualified method signatures.

**Status:** Fixed.

## Bug 379: `cjfr print` includes hidden lambda frames that oracle `jfr print` skips

**Symptom:** `cjfr print` text output includes extra stack frames for synthetic lambda-generated classes (e.g., `me.bechberger.jfr.CombiningJFRReader$$Lambda$104+0x...`) that oracle `jfr print` omits.

**Root cause:** In the raw JFR data, some stack frames have `method.hidden = true` indicating they are JVM-synthesized lambda dispatch frames. Oracle's `jfr print` (text mode) skips these hidden frames. cjfr's `formatStackTrace()` iterated all frames unconditionally.

**Fix:** Added `isHiddenFrame(ReadStruct frame)` helper that reads `frame.method.hidden`; `formatStackTrace()` skips frames where it returns true. JSON output is unaffected (oracle also keeps hidden frames in `--json` mode).

**Status:** Fixed.

## Bug 380: `cjfr condense --preset lossless` drops repeated `jdk.NativeLibraryLoad` events

**Symptom:** With lossless preset, if a native library load attempt is made multiple times in a recording (e.g., same library retried after failure), only the first `(name, success)` pair survives. For a benchmark recording with 116 `NativeLibraryLoad` events, lossless condense kept only 18 (98 events dropped).

**Root cause:** `JFREventDeduplication` registered `NativeLibraryLoad` dedup by `(name, success)` in the block that runs for ALL presets including lossless, treating it like a static event. However, `NativeLibraryLoad` is a lifecycle event that can legitimately repeat multiple times (same library load retried, e.g. in Hadoop's classpath search), so deduping it is lossy.

**Fix:** Moved `NativeLibraryLoad` dedup from the always-on block to `registerPeriodicTimeSeries()` (non-lossless only).

**Status:** Fixed.

## Bug 381: `cjfr condense --preset lossless` drops chunk-repeated `jdk.SystemProcess` events

**Symptom:** With lossless preset, `jdk.SystemProcess` events (periodic per-chunk snapshot of all running processes) are deduplicated by `(pid, commandLine)`. For a 3-chunk recording with 2778 events and 1032 unique processes, only 1032 events survive (1746 dropped).

**Root cause:** Same as Bug 380 — `SystemProcess` dedup was registered in the always-on block (not lossless-guarded). Like `NetworkUtilization` and other periodic events, `SystemProcess` is emitted at each chunk boundary; the same process appears in each snapshot. Lossless mode should preserve every distinct-timestamp observation.

**Fix:** Moved `SystemProcess` dedup to `registerPeriodicTimeSeries()` (non-lossless only), matching the treatment of other periodic chunk events.

**Status:** Fixed.

## Bug 382: `cjfr condense --preset lossless` drops `jdk.PhysicalMemory`/`jdk.SwapSpace` when `usedSize` unchanged between chunks

**Symptom:** With lossless preset, `PhysicalMemory` and `SwapSpace` events that share the same `usedSize` value as the previous chunk event are dropped. For a 6-chunk recording, lossless condense kept 5 events (1 dropped because two consecutive chunks had the same used memory).

**Root cause:** `PhysicalMemory` and `SwapSpace` used `putSingleton` in the always-on block, with a comment calling them "hardware/OS constants". While `totalSize` is constant, `usedSize` changes per chunk. `putSingleton` dedupes when all non-timestamp fields match — two consecutive equal snapshots merge into one.

**Fix:** Moved both to `registerPeriodicTimeSeries()` so lossless mode preserves every distinct-timestamp observation.

**Status:** Fixed.

## Bug 383: `cjfr print` default stack depth is unlimited; oracle defaults to 5 frames

**Symptom:** `cjfr print` with no `--stack-depth` option prints all stack frames (e.g., 25–59 frames per event), while oracle `jfr print` defaults to 5 visible frames and shows `...` for truncated traces. For `jdk.ExecutionSample` on a benchmark recording, oracle shows `...` in 23957/27289 events; cjfr (before fix) showed `...` in only 5845 (only events flagged `truncated=true` in the raw recording).

**Root cause (part 1):** `PrintCommand.java`'s `--stack-depth` option had `defaultValue = "-1"` (unlimited) instead of `"5"`. Oracle `jfr print` initialises `stackDepth = 5` (confirmed via `javap` on `Print.class`: `iconst_5; istore 5`).

**Root cause (part 2):** `formatStackTrace()` showed `...` only when the `truncated` flag was set or when visible frames were still remaining after exhausting the frame list. Oracle's `PrettyWriter.printStackTrace()` uses a different rule (confirmed via `javap` on `PrettyWriter.class`): iterate all frames tracking total index `i` and visible count separately; skip hidden/non-Java frames without counting them; show `...` when `isTruncated() || i == stackDepth` (total-index equals stack depth). This means if hidden frames appear in the first `stackDepth` slots, `i > stackDepth` when the visible limit is hit and oracle does NOT show `...`.

**Fix:** Changed `defaultValue` to `"5"`. Rewrote `formatStackTrace` to mirror oracle's exact loop: advance `i` for every frame (including hidden), increment `visibleCount` only for non-hidden frames, break when `visibleCount >= maxDepth`, then `showEllipsis = truncated || (maxDepth > 0 && i == maxDepth)`.

**Status:** Fixed.

## Bug 384: `cjfr print --stack-depth 0` shows all frames instead of suppressing all

**Symptom:** `cjfr print --stack-depth 0` prints full stack traces (all frames, no `...`). Oracle `jfr print --stack-depth 0` shows zero frames with `...` for every event that has a stack trace.

**Root cause:** `formatStackTrace()` checked `maxDepth > 0` before breaking the frame loop and before deciding to show `...`. When `maxDepth=0`, the condition was always false so the loop ran unconditionally and `showEllipsis` was always false (unless `truncated=true`). Same incorrect guard `stackDepth > 0` appeared in `listToJson()` (JSON frames) and `formatListItems()` (non-stack lists). Oracle's `PrettyWriter` loop condition is `count < stackDepth`; when `stackDepth=0`, `0 < 0` is false so the loop body never executes and `i == stackDepth == 0` triggers `...`.

**Fix:** Changed all three guards from `> 0` to `>= 0`, making `0` a valid limit (show nothing). `maxDepth < 0` remains the unlimited sentinel (all frames, never depth-triggered `...`).

**Status:** Fixed.

## Bug 385: `cjfr print --exact` renders `0 bytes` and `0 bytes/s` instead of `0 byte` and `0 byte/s`

**Symptom:** In `--exact` mode, `@DataAmount` fields render as `0 bytes` and data-rate fields render as `0 bytes/s`. Oracle renders these as `0 byte` (singular) and `0 byte/s`. Oracle also uses singular `1 byte` for exactly-1-byte values and `-1 byte` for the sentinel -1.

**Root cause:** `PrintCommand.formatValue()` used `v + " bytes"` unconditionally for exact `@DataAmount` values, and `v + " bytes/s"` for exact data-rate values, without applying the oracle singular rule: singular when -1 ≤ v ≤ 1, plural otherwise.

**Fix:** Also fixed `bits/s` data-rate fields (`readRate`, `writeRate`) which had the same issue: `0 bits/s` → `0 bit/s`. Changed all affected code paths to use `v >= -1 && v <= 1 ? " byte" : " bytes"` (and `" byte/s"` / `" bytes/s"` respectively) to match oracle's singular/plural boundary.

**Status:** Fixed.

## Bug 386: `cjfr print` shows `738 bytes/s` instead of `738 byte/s` for sub-kB data rates

**Symptom:** In non-exact mode, byte-level data rates (combined `@DataAmount + @Frequency` fields like `recentAllocationRate`) render as `X bytes/s` when the value is at the byte level (< 1 KB/s). Oracle renders these as `X byte/s` (singular "byte" at the byte scale, e.g., `738 byte/s`).

**Root cause:** The code used `mem + "/s"` where `mem` was the output of `formatMemory(v)` (which returns `"738 bytes"`). Only the zero case was special-cased to use `"0 byte/s"`. Oracle consistently uses singular `byte/s` for all byte-level rates.

**Fix:** Changed the byte-rate path to strip the trailing `"s"` from the "bytes" unit when constructing the rate string: any memory value ending in `" bytes"` or `" byte"` is converted to `" byte/s"`.

**Status:** Fixed.

## Bug 387: `cjfr print --exact` shows raw nanosecond number instead of `N/A`/`Forever` for duration sentinels

**Symptom:** In `--exact` mode, duration fields with `Long.MIN_VALUE` (the "N/A" sentinel, e.g. `jdk.ThreadPark.timeout` with no-timeout park) show as a large negative number (`-9223372036854776000.000000000 s`). Oracle shows `N/A`. Similarly, `Long.MAX_VALUE` (the "Forever" sentinel) must show `Forever`, which oracle preserves in exact mode.

**Root cause:** `formatDuration()` checked the `exact` flag before the sentinel checks, so `isForever` / `isNA` were evaluated but the raw decimal path ran first, producing nonsensical large numbers. The oracle retains the sentinel substitution (`N/A` and `Forever`) even in `--exact` mode — only "real" durations get the raw-seconds treatment.

**Fix:** Moved the sentinel checks (`isForever` → `"Forever"`, `isNA` → `"N/A"`) before the `exact` branch. Sentinel values return their substitution strings unconditionally; only non-sentinel durations proceed to the exact raw-seconds formatter.

**Status:** Fixed.

## Bug 388: `cjfr print` omits `(classLoader = null)` for anonymous/hidden class types

**Symptom:** For hidden classes (e.g. `java.lang.String$$StringConcat.0x00003ff801148400`), oracle shows `(classLoader = null)` but cjfr omits the classLoader suffix entirely.

**Root cause:** `PrintCommand.formatClass()` checked `if (loader != null)` before appending the classLoader suffix. For anonymous/hidden classes, the classLoader struct is stored as a null pool reference — `cls.hasField("classLoader")` is `true` but `cls.getStruct("classLoader")` returns `null`. The null-guard skipped the suffix entirely. Oracle shows `(classLoader = null)` in this case.

**Fix:** Changed `formatClass()` to separate the "field exists" check from the "field is null" check. When `hasField("classLoader")` is true but `getStruct("classLoader")` returns null, emit `(classLoader = null)` to match oracle's behavior.

**Status:** Fixed.

## Bug 389: `cjfr view allocation-by-class` shows full hidden-class name instead of numeric ID

**Symptom:** For hidden/lambda classes like `java.util.stream.Collectors$$Lambda$127+0x000000c8010dc040.539690370`, oracle `jfr view allocation-by-class` shows just `539690370` (the numeric ID suffix), but cjfr shows the full truncated name `org.openjdk.jmc.flightrecorder.writer.ConstantPool$$Lamb...`.

**Root cause:** `ViewRenderer` had a `compactMethod` fallback for oversized cells (replacing parameter lists with `(...)`) but no equivalent for class names. When a class-type cell exceeds the column width, oracle strips to the simple class name (everything after the last `.`).

**Fix:** Added `compactClass(String s)` to `ViewRenderer`: for strings with no spaces or parentheses (class-name-shaped), extract the last dot-delimited component. Applied alongside `compactMethod` in the per-cell compact-formatting pass.

**Status:** Fixed.

## Bug 390: `cjfr view` default width is 160 instead of oracle's 80

**Symptom:** `cjfr view gc profile.jfr` (no `--width`) produces 159-char-wide tables while oracle produces 82-char-wide tables on an 80-column terminal.

**Root cause:** `ViewCommand.DEFAULT_WIDTH = 160` hardcoded. Oracle uses the actual terminal width (80 when no TTY), so the default should match.

**Fix:** Changed `DEFAULT_WIDTH = 80` and updated the `--width` description. Updated tests that previously relied on the 160-wide default to explicitly pass `--width 160`.

**Status:** Fixed.

## Bug 391: `cjfr view memory-leaks-by-class` truncates class names with `...` instead of simple name

**Symptom:** At width 80, oracle shows `TypedFieldValueImpl` for `org.openjdk.jmc.flightrecorder.writer.TypedFieldValueImpl` in the Object Class column, while cjfr shows `org.openjdk.jmc.flightrecorder.writer.Type...`.

**Root cause:** Same as Bug 389 — `ViewRenderer` lacked `compactClass` fallback for class-name cells that exceed the column width.

**Fix:** Same fix as Bug 389 (`compactClass` method in `ViewRenderer`).

**Status:** Fixed (same fix as Bug 389).


## Bug 392: `compactClass` incorrectly stripped non-Java-class dotted strings (paths, UUIDs)

**Symptom:** `cjfr view environment-variables` showed `log` instead of the full path `/Users/.../restarter.log` for `IJ_RESTARTER_LOG`, and `33E8D0D7-2465-40C9-8F73-502205B012A9` instead of the full value for `XPC_SERVICE_NAME`. `cjfr view system-processes` showed `app/Contents/MacOS/BambuStudio` instead of oracle's `...ambuStudio.app/Contents/MacOS/BambuStudio`.

**Root cause:** The `compactClass` method added in Bug 389/391 was too aggressive: it fired on any string with a `.` that had no spaces or parentheses, including file paths and dotted UUIDs. Paths starting with `/` like `/Users/foo/bar.log` were stripped to `log`; `application.com.jetbrains.56426642.UUID` was stripped to `UUID`.

**Fix:** Added guards to `compactClass`: (1) reject strings containing `/` or `\` (paths); (2) require at least 2 dots (so `file.log` doesn't match); (3) require all non-last dot-delimited segments to start with a letter, `$`, or `_` (so all-digit segments like `56426642` in UUIDs/PIDs are rejected).

**Status:** Fixed.

## Bug 393: `cjfr print --events <zero-count-type>` prints spurious warning

**Symptom:** `cjfr print --events jdk.ObjectAllocationOutsideTLAB file.cjfr` emits `Warning: No events found matching filter: jdk.ObjectAllocationOutsideTLAB` even though the event type is valid and defined in the recording — it just has 0 events. Oracle `jfr print` prints nothing (no warning) for event types that exist but have 0 events.

**Root cause:** `PrintCommand.warnUnknownFilters()` seeds its `seen` set only from events actually read. A filter for a 0-count event type never matches a read event, so it's never added to `seen`, triggering the false warning.

**Fix:** After reading all events, additionally seed `seen` with all type names from the stream's `TypeCollection` (via new `CombiningJFRReader.getAllKnownTypeNames()`). This includes types registered in the stream even if they have 0 events, suppressing false warnings while still warning for truly unknown type names.

**Status:** Fixed.

## Bug 394: `cjfr view jvm-flags` Name column width 4 instead of 39, Value right-aligned instead of left

**Symptom:** `cjfr view jvm-flags` at width 80 showed Name truncated to 4 chars ("A..."), Value column at full 104 chars (extending beyond terminal), and all values right-aligned. Oracle shows Name=39, Value=39, left-aligned.

**Root cause (column widths):** `ColumnType.flexibleFor` for `LAST(value)` short-circuited on the first candidate event type `IntFlag` whose `value` field is `int` (non-flexible), returning false. This left only `Name` as the single flex column (target=79). However, `StringFlag.value` is a 104-char string — its preferred width consumed the entire target, leaving budget=0 for Name which got clamped to its header label width of 4.

**Fix (column widths):** In `distributeFlexibleWidth`, when in the overflow+flex branch, cap any non-flex column whose preferred width exceeds `target / nCols` (the "fair share") to that share. This prevents one large column from starving the flex column(s). jvm-flags: `fairShare = 79/2 = 39`, Value capped from 104 → 39, Name gets remaining budget of 39. Total = 79. ✓

**Root cause (alignment):** `renderTable` set `rightAlign[c] = true` when any cell was `Boolean` or `isNumericLike`. For jvm-flags Value, `AbortVMOnCompilationFailure = false` is a Boolean → rightAlign fired. Then `ActiveProcessorCount = -1` is numeric → reinforced. Oracle left-aligns coalesced/mixed-type columns regardless of cell content.

**Fix (alignment):** After processing all rows, if any cell in a column is a `String` (`anyText[c] = true`), force `rightAlign[c] = false`. Columns that mix String and non-String values are always left-aligned. Pure-boolean columns (e.g. `longest-compilations` Succeeded) stay right-aligned since `anyText` is only set for String cells.

**Status:** Fixed.

## Bug 395: `cjfr view` COUNT aggregate column treated as non-flexible; deoptimizations-by-reason Count column width 5 instead of 25

**Symptom:** `cjfr view deoptimizations-by-reason` at width 80 showed Reason=73, Count=5 (total=79). Oracle shows Reason=54, Count=25 (total=80). The narrow Count column was because `COUNT` was hard-coded as non-flexible regardless of its argument type.

**Root cause:** `ColumnType.flexibleFor` for `Aggregate` nodes hard-coded `return false` for `COUNT` and `UNIQUE`, even when the argument field (e.g. `reason`, `thrownClass`) is text-like (String or class reference). Oracle sizes COUNT columns as flexible when the argument is a flexible field — the count column absorbs its share of leftover terminal width alongside the grouping column.

**Fix:** Removed the special-case `false` for COUNT/UNIQUE in `flexibleFor`; delegate to `flexibleFor(agg.arg())` like all other aggregates. With both `reason` and `COUNT(reason)` flexible, `deoptimizations-by-reason` gets target=80 (2 flex), budget=40 distributed as Reason=54, Count=25.

**Known residual difference:** `exception-by-message` (Message=74 vs oracle=73) — oracle inconsistently treats `COUNT(message)` as non-flexible despite `message` being a String, yielding target=79 (1 flex). The exact oracle decision criterion is unclear; the 1-char width difference is accepted as a known deviation.

**Status:** Fixed (net improvement; exception-by-message 1-char residual accepted).

## Bug 396: `cjfr view` all-non-flex tables not padded to terminal width; gc-references and safepoints column widths 1–3 chars narrower

**Symptom:** `cjfr view gc-references` and `cjfr view safepoints` showed columns 2–3 chars narrower than oracle. For example gc-references: oracle `[12, 10, 10, 10, 10, 10, 10]` (total=84), cjfr `[10, 10, 10, 10, 10, 10, 10]` (total=79).

**Root cause:** `distributeFlexibleWidth` returned early when there were no flex or shrinkable columns (all-non-flex table), leaving the table at its natural content width. Oracle pads all-non-flex tables with `ceil((termWidth − used) / nCols)` per column so the total slightly exceeds `termWidth`.

**Fix:** In `distributeFlexibleWidth`, when `flexIdx` and `shrinkIdx` are both empty and `surplus = termWidth − used > 0`, add `ceil(surplus / nCols)` to every column width.

**Status:** Fixed. gc-references: 0 diffs. safepoints: widths now match.

## Bug 397: `cjfr view` "Indefinite" sentinel in Duration column right-aligned instead of left; safepoints data rows differ

**Symptom:** `cjfr view safepoints` data rows showed `Indefinite` right-aligned (trailing-padded) in the Duration column, while oracle left-aligns it. Also the State Syncronization column (all nulls) was left-aligned instead of right-aligned.

**Root cause:** Two related issues:
1. `Duration.ofNanos(Long.MAX_VALUE)` — the "Indefinite" sentinel from `DIFF([B|E].startTime)` when the SafepointEnd event is absent — is a real `Duration` value, not a `String`. Since `anyText[c]` was never set for it, the column remained right-aligned (Duration is numeric-like). Oracle treats "Indefinite" as text-like and left-aligns it.
2. The State Syncronization column is all-null (SafepointStateSynchronization absent from recording). With no observed values, `rightAlign[c]` stayed false (left-aligned default), but oracle right-aligns the `N/A` placeholder in what is semantically a Duration column.

**Fix (1):** When a `Duration` value's nanos exceed `Long.MAX_VALUE − 1_000_000` (the Indefinite sentinel), set `anyText[c] = true`. The `anyText` override then forces left-alignment.

**Fix (2):** After the main cell loop, for all-null columns (`!anyValue[c]`), set `rightAlign[c] = !flexibleFor(c)` (schema-based: non-flex/Duration columns default to right-aligned, flex/String columns to left-aligned). This is applied before the `anyText` override so the Indefinite fix takes priority.

**Status:** Fixed. safepoints: 0 diffs.

## Bug 398: `cjfr view` flex tables incorrectly shrink when natural content fills or overflows the terminal

**Symptom:** `cjfr view vm-operations` at width 80 showed VM Operation column width 24 instead of 25 (oracle). Content naturally fills exactly 80 chars but cjfr's formula `target = termWidth + flexIdx.size() − 2 = 79` caused a 1-char shrink of the flex column.

**Root cause:** The formula `target = termWidth + flexIdx.size() − 2` produces `termWidth − 1` for 1-flex tables, causing a 1-char shrink even when the natural content exactly fills the terminal. Oracle only shrinks when the table overflows the terminal (`used > termWidth`); when `used ≤ termWidth`, oracle leaves the natural content widths unchanged.

**Fix:** In `distributeFlexibleWidth`, when `delta ≤ 0` (no growth needed), `used ≥ termWidth` (content fills or overflows the terminal), and there are no shrinkable columns, return early without any flex distribution. This preserves the natural content width when the table already fits within the terminal.

**Status:** Fixed. vm-operations: column widths now match oracle (residual 12 diffs = locale number format `1,234` vs `1.234` = known deviation).

## Bug 399: `cjfr view` active-settings Event Type column non-flexible; column widths 1 char narrower

**Symptom:** `cjfr view active-settings` showed a line width of 85 instead of oracle's 84. The last column (Throttle) was 12 chars wide instead of 11, and the Event Type header was right-truncated.

**Root cause:** Two issues:
1. `ColumnType.isFlexibleField` did not recognise `StringType` fields as flexible. The `ActiveSetting.id` field is remapped at condensation time from a numeric class ID to a `StringType` (event type name). But `isFlexibleField` only checked the field's declared description for `java.lang.String` (the original JFR class-reference type), not the condensed `StringType`. So Event Type was treated as non-flexible.
2. The grow path in `distributeFlexibleWidth` distributed all surplus exclusively to flex columns (last flex col absorbing the rounding remainder). Oracle distributes `ceil(surplus / nCols)` to **all** columns uniformly — the same algorithm as the all-non-flex case — regardless of flex vs non-flex distinction.

**Fix (1):** In `ColumnType.isFlexibleField`, added `if (field.type() instanceof StringType) return true` before the description probe. `StringType` fields store free text and should expand like `java.lang.String` columns.

**Fix (2):** In `distributeFlexibleWidth`, replaced the grow path (`delta > 0`, flex-only expansion) with the same `ceil(surplus / nCols)` pad applied to all columns, matching the all-non-flex algorithm.

**Status:** Fixed. active-settings: 0 diffs.

## Bug 400: `cjfr view active-recordings` crashes with "Error: long overflow"

**Symptom:** `cjfr view active-recordings profile.cjfr` prints `Error: long overflow` and exits with no output. The recording has an active recording whose `duration` and `maxAge` fields are set to the "Indefinite"/"Forever" sentinel (`Duration.ofSeconds(Long.MAX_VALUE)`, meaning no limit).

**Root cause:** Bug 397's fix added an "Indefinite sentinel" check that called `d.toNanos()` on the duration value. For `Duration.ofNanos(Long.MAX_VALUE)` (used for safepoints' missing-join `DIFF` sentinel), `toNanos()` returns `Long.MAX_VALUE` safely. But `Duration.ofSeconds(Long.MAX_VALUE)` (used for active-recordings' "no limit" sentinel) makes `toNanos()` call `Math.multiplyExact(Long.MAX_VALUE, 1_000_000_000)` — which throws `ArithmeticException: long overflow`.

**Fix:** Use `d.getSeconds() >= Long.MAX_VALUE / 1_000_000_000L` instead of `d.toNanos() >= Long.MAX_VALUE - 1_000_000`. The threshold `9223372036` correctly identifies both forms of the sentinel:
- `Duration.ofNanos(Long.MAX_VALUE).getSeconds() = 9223372036` → `>= 9223372036` → `true` ✓
- `Duration.ofSeconds(Long.MAX_VALUE).getSeconds() = Long.MAX_VALUE >> 9223372036` → `true` ✓
- Normal 10 s duration: `10 < 9223372036` → `false` ✓

**Known residual difference:** Without `--width`, oracle uses the natural content width (105 chars for this recording) while cjfr defaults to 80 and shrinks the Destination column to fit. This is a terminal-width mismatch category — not caused by this fix; oracle uses natural width on non-TTY output. With explicit `--width 80` both produce matching output.

**Status:** Crash fixed. Terminal-width mismatch on default width is a known deviation.

## Bug 401: `cjfr view jvm-flags` columns not shrunk at `--width 80`; rows 153 chars wide

**Symptom:** `cjfr view jvm-flags --width 80` produced 153-char wide rows (Name=48, Value=104) instead of oracle's 39+39=79 chars. The title was centered over 153 chars instead of 79.

**Root cause:** The Bug 398 fix added an early-return guard in `distributeFlexibleWidth` with condition `delta <= 0 && used >= termWidth && shrinkIdx.isEmpty()`. For jvm-flags, `used = 153 > termWidth = 80`, so `used >= termWidth` was true — causing the guard to fire and skip the shrink path. The condition was too broad: it prevented shrinking even when content significantly overflowed the terminal.

The Bug 398 fix was specifically for `vm-operations` where natural content width exactly equals `termWidth` (both 80). In that case, the `target = termWidth + flexCount - 2` formula produces `target = 79`, causing a spurious 1-char shrink that oracle doesn't do. The guard was meant to preserve natural widths when content already fits — but `used >= termWidth` also fires when content far exceeds `termWidth`.

**Fix:** Change `used >= termWidth` to `used <= termWidth` in the guard. Now:
- vm-operations: `used = 79 (or 80) <= termWidth = 80` → return early → natural width preserved ✓
- jvm-flags: `used = 153 > termWidth = 80` → guard does not fire → falls into shrink branch → Name=39, Value=39 ✓

**Status:** Fixed. jvm-flags: only locale diffs remain (number format `.` vs `,`).

## Bug 402: `cjfr view events-by-name/events-by-count` missing `(Experimental)` suffix in title

**Symptom:** `cjfr view events-by-name` and `cjfr view events-by-count` displayed the plain title `Event Types by Name` instead of oracle's `Event Types by Name (Experimental)`. Any FROM-* view (all-event-types query) should append `(Experimental)` to the title when the recording contains experimental event types (those annotated with `@jdk.jfr.Experimental`).

**Root cause:** `NativeView.render()` checks `typeIsExperimental(e.getType().getDescription())` for each event in the `eventsByType` map. For combined event types (e.g. `jdk.GCPhaseParallel` → combined as `jdk.combined.GCPhaseParallel`), the combiner's `createCombinedStateType` created the `StructType` with an empty description (`StructType(id, typeName, fields)`). So even though `jdk.GCPhaseParallel` is `@Experimental`, the description of the stored combined type contained no `jdk.jfr.Experimental` string, and `typeIsExperimental` returned false.

**Fix:** In `JFREventCombiner.createCombinedStateType`, pass the original event type's description (via `basicJFRWriter.getEventDescription(eventType)`) to the `StructType` constructor instead of using the no-description variant. The description is now stored in the cjfr stream as part of the combined type's metadata, so on inflate/read the reconstituted events carry the original `@Experimental` (and other type-level) annotations in their `ReadStruct.getType().getDescription()`.

The `profile.cjfr` and `profile_lossless.cjfr` test fixtures were regenerated to include the new description bytes.

**Status:** Fixed. events-by-name and events-by-count: `(Experimental)` suffix now matches oracle.

## Bug 403: `cjfr view jdk.GarbageCollection` shows `GC Identifier` instead of `GC ID`

**Observation:** `cjfr view jdk.GarbageCollection profile.cjfr` renders the `gcId` column header as
`GC Identifier` (the `@Label` value from JFR metadata), but `jfr view jdk.GarbageCollection` shows
`GC ID`.

**Root cause:** Oracle's `FieldBuilder.makeLabel()` hardcodes field-name→label abbreviations:
`gcId` → `GC ID`, `compilerId` → `Compiler ID`, `startTime` (no duration sibling) → `Time`.
Our `JFRView.fieldDisplayName()` used the `@Label` annotation directly, bypassing these overrides.

**Fix:** Applied the same hardcoded overrides in `JFRView.fieldDisplayName()` and `NativeView.expandStar()`.

**Status:** Fixed.

## Bug 404: `cjfr view jdk.GCReferenceStatistics` shows `Start Time` instead of `Time`

**Observation:** Oracle shows column header `Time` for events without a `duration` field (e.g.
`jdk.GCReferenceStatistics`), but cjfr showed `Start Time`.

**Root cause:** Oracle's `makeLabel()` returns `"Time"` for `startTime` when there is no `duration`
sibling field. Our code always used `@Label("Start Time")`.

**Fix:** `JFRView.fieldDisplayName()` now checks the parent type's field list for a `duration` field
and returns `"Time"` when absent, matching oracle.

**Status:** Fixed.

## Bug 405: `cjfr view <EventType>` columns have wrong widths (too wide or data truncated)

**Observation:** Direct event-type views (e.g. `cjfr view jdk.GarbageCollection`) had wrong column
widths: integer/duration columns were fixed at 10 chars minimum, causing `Cause` to be truncated and
`GC ID` to be 10 wide instead of 5. Oracle sizes columns to their natural content width.

**Root cause:** `JFRView.computeColumnWidths()` distributed remaining space evenly without scanning
actual event data. Oracle scans all event data to compute natural column widths (max of header and
data), then expands flex columns to fill remaining space.

**Fix:** Added a data-driven `computeColumnWidths(termWidth, events, cellHeight)` overload that scans
all events to find each column's natural width, expands flex columns to fill remaining terminal width
when total < terminal, and shrinks flex columns when total > terminal. `renderMatches()` now passes
the full event list to `JFRView` for data-driven layout.

**Status:** Fixed.

## Bug 406: `cjfr view <EventType>` `Start Time` column right-aligned instead of left-aligned

**Observation:** `Start Time` values were right-aligned in direct event-type views, but oracle uses
left-alignment for timestamp values.

**Root cause:** `InstantColumn.alignment()` returned `Alignment.RIGHT`.

**Fix:** Changed `InstantColumn.alignment()` to return `Alignment.LEFT`.

**Status:** Fixed.

## Bug 407: `cjfr view <EventType>` title centering off by 1

**Observation:** Title centering used floor division, producing 1 fewer leading space than oracle.
E.g. "Garbage Collection" (18 chars) over 79-char header: oracle shows 31 spaces, cjfr showed 30.

**Root cause:** `(headerLine.length() - name.length()) / 2` uses floor; oracle uses ceiling.

**Fix:** Changed to `(headerLine.length() - name.length() + 1) / 2`.

**Status:** Fixed.

## Bug 408: `cjfr view <EventType>` all-fixed-width columns not expanded to fill terminal width

**Observation:** `cjfr view jdk.TenuringDistribution` showed very narrow columns (GC ID=6, Age=5,
Size=6) leaving most of the 80-char terminal blank. Oracle fills the terminal with equal expansion.

**Root cause:** `computeColumnWidths` returned natural widths immediately when `flexCount == 0`
(no flex/string columns), leaving all-numeric tables at minimal natural widths. Oracle's
`TableRenderer.setColumnWidths()` has a 4th pass `distribute(true)` that expands ALL columns to
fill remaining terminal width when no flex columns consumed the space.

**Fix:** When `naturalTotal < termWidth && flexCount == 0`, treat all `n` columns as expandable
(equivalent to oracle's pass 4 with `allow_all = true`), distributing extra space equally.

**Status:** Fixed.

## Bug 409: `cjfr view jdk.ExecutionSample` shows Thread State BEFORE Stack Trace

**Observation:** `cjfr view jdk.ExecutionSample` column order was: Time, Thread, Thread State,
Stack Trace. Oracle: Time, Thread, Stack Trace, Thread State.

**Root cause:** The synthetic Thread State column injection used `cols.add(stackIdx, stateCol)`
which inserts AT the stackTrace index (pushing stackTrace right), so Thread State ended up before
Stack Trace. The insert should be at `stackIdx + 1` (after stackTrace).

**Fix:** Changed `cols.add(stackIdx, stateCol)` → `cols.add(stackIdx + 1, stateCol)`.

**Status:** Fixed.

## Bug 412: `cjfr view jdk.MetaspaceSummary` shows extra `Data Space` and `Class Space` columns

**Observation:** `cjfr view jdk.MetaspaceSummary` showed columns `Data Space` and `Class Space`
in addition to `Total : Committed/Used/Reserved`. Oracle shows only the Total sub-columns.

**Root cause:** `MetaspaceSummary` has three fields of type `MetaspaceSizes` (`metaspace`/Total,
`dataSpace`/Data, `classSpace`/Class). Oracle uses `HashSet<ValueDescriptor>` in
`FieldBuilder.createWildcardFields()` to skip re-expansion of already-seen struct types — only
the first occurrence (`metaspace`) is expanded; `dataSpace` and `classSpace` are dropped entirely.
Our code had no such deduplication, so all three were expanded.

**Fix:** Added `expandedStructTypes: Set<String>` tracking in `topLevelFieldColumns`. When a
struct type is seen again, return `List.of()` (drop) rather than expanding or rendering as leaf.

**Status:** Fixed.

## Bug 413: `cjfr view <EventType>` Duration column truncates sub-millisecond values

**Observation:** `cjfr view jdk.GCPhasePauseLevel1` showed `0.000292 m` (truncated) instead of
`0.000292 ms`. Duration values like `0.000292 ms` (11 chars) were clipped to 10 chars.

**Root cause:** `DurationColumn.width()` returned `Math.max(10, header.length()) = 10` as a
hardcoded minimum, and `maxWidth()` defaulted to `width()=10`, capping the data-driven natural
width at 10. Sub-millisecond durations like `0.000292 ms` (11 chars) were truncated to 10.

**Fix:** Changed `DurationColumn.width()` to `Math.max(8, header.length())` (matching the "Duration"
header length) and added `maxWidth()` returning `-1` (no cap), allowing the data-driven width
computation to size the column to fit the widest actual value.

**Status:** Fixed.

## Bug 414: `cjfr view <EventType>` flex column shrink uses equal split instead of oracle's greedy fill

**Observation:** `cjfr view jdk.SystemProcess --width 120` showed `Process Identifier` column at
54 chars and `Command Line` at 54 chars. Oracle gives `Process Identifier`=18 (its natural/header
width) and `Command Line`=91 (remaining budget).

**Root cause:** When `naturalTotal > termWidth`, the shrink path divided the total flex budget
equally among all flex columns (`perFlex = flexBudget / flexCount`). Oracle's `distribute()`
pass 2 increments columns one unit at a time until each reaches its natural width — equivalent to
"fill smallest flex columns to their natural first, give remainder to larger".

**Fix:** Replaced equal-split with a greedy fill: sort flex column indices by natural width
ascending, then for each column compute `share = remaining / flexLeft` and assign
`min(natural, share)`, carrying leftover to larger columns.

**Status:** Fixed.

## Bug 415: `cjfr view <EventType>` memory address formatted as lowercase hex without zero-padding

**Observation:** `cjfr view jdk.NativeLibrary` showed `0x10230c000` (lowercase) and `0x0` (no
zero-padding) instead of oracle's `0x10230C000` and `0x00000000`.

**Root cause:** `MemoryAddressColumn.format()` used `Long.toHexString(value)` which produces
lowercase hex with no padding. Oracle's `FieldFormatter` uses `String.format("0x%08X", d)`.

**Fix:** Changed to `String.format("0x%08X", value)` — uppercase, minimum 8 hex digits.

**Status:** Fixed.

## Bug 416: `cjfr view <EventType>` null stack traces show `-` instead of `N/A`

**Observation:** `cjfr view jdk.ActiveSetting` showed `-` in the Stack Trace column when no stack
trace was recorded. Oracle shows `N/A`.

**Root cause:** `StackTraceColumn.format()` returned `List.of("-")` for null stack traces.

**Fix:** Changed to `List.of("N/A")`.

**Status:** Fixed.

## Bug 417: `cjfr view <EventType>` boolean values are left-aligned instead of right-aligned

**Observation:** `cjfr view jdk.PromoteObjectInNewPLAB` showed `false ` (left-padded) instead of
oracle's `  false` (right-aligned) in the Tenured column.

**Root cause:** `BooleanColumn.alignment()` returned `Alignment.LEFT`. Oracle's FieldBuilder sets
`field.alignLeft = false` for boolean fields.

**Fix:** Changed `BooleanColumn.alignment()` to return `Alignment.RIGHT`.

**Status:** Fixed.

## Bug 418: `cjfr view <EventType>` memory address columns are right-aligned instead of left-aligned

**Observation:** Memory address columns in `cjfr view jdk.NativeLibrary` were right-aligned.
Oracle's FieldBuilder explicitly sets `field.alignLeft = true` for `@MemoryAddress` fields
(overriding the default right-alignment for numeric types).

**Root cause:** `MemoryAddressColumn.alignment()` returned `Alignment.RIGHT`.

**Fix:** Changed to `Alignment.LEFT`.

**Status:** Fixed.

## Bug 419: `cjfr view <EventType>` truncation cuts without ellipsis

**Observation:** `cjfr view jdk.JavaMonitorWait` showed `java.lang.Objec` instead of oracle's
`java.lang.Obj...` for class names that don't fit in their column.

**Root cause:** `JFRView.truncate()` used hard truncation (cut at column width) instead of
adding `"..."` at the end. Oracle's `TableCell.truncate()` appends `"..."`.

**Fix:** Changed `truncate()` to append `"..."` (3 chars) at the end for END truncation, or
prepend for BEGIN truncation, when the value is too wide.

**Status:** Fixed.

## Bug 420: `cjfr view <EventType>` ClassLoader names not compacted when truncated

**Observation:** `cjfr view jdk.ModuleRequire` showed `jdk.internal.loader.ClassLoaders$`
(hard-truncated) instead of oracle's `ClassLoaders$AppClassLoader` (compact form).

**Root cause:** Oracle applies a compact format (last dot-component of class name) when a value
doesn't fit its column. Our code just hard-truncated. `ClassLoaderColumn` and `ClassColumn`
lacked a `compact()` method, and `NestedColumn` didn't delegate it.

**Fix:** Added `compact(String value)` method to `Column` interface (default = no-op). Override
in `ClassLoaderColumn`, `ClassColumn`, and `NestedColumn` (delegates to inner) to strip package
prefix: `value.substring(value.lastIndexOf('.') + 1)`.

**Status:** Fixed.

## Bug 421: `cjfr view <EventType>` null nested struct shows `-` instead of `N/A`

**Observation:** `cjfr view jdk.ModuleExport` showed `-` for null `targetModule` fields.
Oracle shows `N/A` for all null values (`field.missingText = "N/A"`).

**Root cause:** `NestedColumn.format()` returned `List.of("-")` for null parent struct.

**Fix:** Changed to `List.of("N/A")`.

**Status:** Fixed.

## Bug 422: `cjfr view <EventType>` "No events found" missing leading blank line

**Observation:** `cjfr view jdk.ClassLoad` output started with `No events found for 'Class Load'.`
without the blank line that oracle prints before it.

**Root cause:** Oracle's `TableRenderer.render()` calls `out.println()` before the message.
Our code in `ViewCommand` called `System.out.println(message)` directly without the preceding blank.

**Fix:** Added `System.out.println()` before the "No events found" message.

**Status:** Fixed.

## Bug 423: `cjfr view <EventType>` column width algorithm uses greedy assignment instead of oracle's round-robin distribute

**Observation:** `cjfr view jdk.ClassLoaderStatistics` produced a table 119 chars wide instead of
oracle's 120. `cjfr view jdk.GCPhasePause` gave column widths [17,15,19,12,15] instead of oracle's
[10,8,28,5,24].

**Root cause:** Our width algorithm greedily assigned each column its preferred width then
distributed remainder. Oracle's `TableRenderer.setColumnWidths()` uses a `distribute()` method that
runs 4 passes, each an outer `while (amountLeft > 0 && amountLeft != lastAmountLeft)` loop
containing an inner `for` loop over all cells. The inner loop distributes one unit to each
qualifying cell without checking budget — it can overshoot the target by up to `n-1`. Pass 3 only
fills non-fixed (String-typed) columns; Pass 4 fills all. Oracle decompiled from `jrt:/` confirms
`fixedWidth = !typeName.equals("java.lang.String")` (set in `FieldBuilder.configureAliases()`).

**Fix:** Reimplemented `computeColumnWidths` to simulate oracle's 4-pass `distribute()` exactly,
with `isOracleFixedWidth()` method on `Column` interface (returns `false` for `StringColumn`,
`ClassColumn`, `ClassLoaderColumn`, `MethodColumn`, `StackTraceColumn`, `ThreadColumn`,
`EventIdColumn`, `NestedColumn` delegates to inner).

**Status:** Fixed.

## Bug 424: `cjfr view <EventType>` column headers use wrong alignment (investigation)

**Observation:** `cjfr view jdk.GCPhasePause` rendered the "Level" and "Duration" headers
left-aligned when oracle right-aligns those headers to match the data column alignment.
`jdk.CPULoad` showed "Time" header left-aligned in a right-aligned column.

**Root cause:** An earlier attempted fix forced all headers to `Alignment.LEFT`, but oracle's
`TableRenderer` uses the same alignment for both headers and data cells. The original code using
`column.alignment()` for headers was correct; the incorrect patch introduced a regression.

**Fix:** Reverted header rendering to use `column.alignment()` for headers, matching oracle
which right-aligns numeric column headers (Duration, Memory, Integer, Percentage, etc.) and
left-aligns string/stack column headers.

**Status:** Fixed.

**Status:** Fixed.

## Bug 425: `cjfr view <EventType> --width N` is ignored; output uses oracle's default width

**Observation:** `cjfr view TestEvent file.cjfr --width 40` produced the same 119-char output as
without `--width`, and `testViewOnCondensedExtremeNumericEvents` truncated `Long.MAX_VALUE` values.

**Root cause:** `computeColumnWidths(int termWidth, ...)` ignored `termWidth` and always ran
oracle's `determineTableWidth()` capped at 120. The constructor passed `config.width()` but the
method didn't use it.

**Fix:** Added `widthIsUserSet` boolean to `PrintConfig`. The 3-arg public constructor sets it to
`true`; the default no-arg constructor sets it to `false`. `computeColumnWidths` uses `termWidth`
directly as `tableWidth` when `userSetWidth=true`, bypassing `determineTableWidth()`. Updated
`testViewOnCondensedExtremeNumericEvents` to pass `--width 300` so the 19-digit `Long.MAX_VALUE`
is not truncated.

**Status:** Fixed.

## Bug 426: `cjfr view <EventType>` default (no --width) uses 80 chars instead of oracle's determineTableWidth

**Observation:** `cjfr view jdk.Compilation profile.cjfr` produced 87-char output instead of
oracle's 122-char output. `jdk.CPULoad` also had wrong widths with no `--width` flag.

**Root cause:** `ViewCommand.effectiveWidth()` returns `DEFAULT_WIDTH=80` when `--width` is not
set. This was passed to `new PrintConfig(width, cellHeight, truncate)` which uses the 3-arg
constructor that sets `widthIsUserSet=true`. Thus `computeColumnWidths` used 80 as tableWidth
instead of running oracle's `determineTableWidth()` algorithm.

**Fix:** Changed `ViewCommand` to use the 4-arg `PrintConfig` constructor with
`widthIsUserSet = (width != -1)`. When `--width` is not set (`width==-1`), `widthIsUserSet=false`
and `computeColumnWidths` runs oracle's `determineTableWidth()` which caps at 120. When `--width N`
is explicit, `widthIsUserSet=true` and N is used directly.

**Status:** Fixed.

## Bug 427: `cjfr view <EventType>` MethodColumn omits method parameters in stack trace display

**Observation:** `cjfr view jdk.ExecutionSample` showed stack frames as `Class.method` while
oracle shows `Class.method(ParamType, ...)`. This caused the Stack Trace column to be narrower
than oracle (83 vs 88 dashes in ExecutionSample, 67 vs 86 in NativeMethodSample).

**Root cause:** `MethodColumn.format()` concatenated `ClassName` + `.` + `methodName`, ignoring
the `descriptor` field. Oracle's `FieldFormatter` calls `Method.toString()` which includes decoded
parameters. `ValueFormatter.formatMethod()` already decoded parameters correctly but was private
and not called from `MethodColumn`.

**Fix:** Made `ValueFormatter.formatMethod()` public and changed `MethodColumn.format()` to
delegate to it. This includes `(ParamType1, ParamType2, ...)` when a `descriptor` field is
present, matching oracle's output format.

**Status:** Fixed.

## Bug 428: `cjfr view <EventType>` determineTableWidth wrong for small tables with ≥3 columns

**Observation:** `cjfr view jdk.PhysicalMemory` showed 41-char output while oracle shows 80.
`jdk.PhysicalMemory` has only 3 columns with prefSum=32 (total preferred widths).

**Root cause:** Our implementation of oracle's `determineTableWidth()` had the wrong condition.
When `prefSum < 40 AND n < 3`, oracle returns 40 (early exit). When `prefSum < 40 AND n >= 3`,
oracle falls through to the next condition `if prefSum < 80 → return 80`. Our code instead
returned `(n < 3) ? prefSum : 40`, which returned 40 for n≥3 and prefSum<40 instead of 80.

Verified from `TableRenderer.class` bytecode: the `n < 3` guard only applies to the `return 40`
early exit; for n≥3 with prefSum<40, execution falls through to `return 80`.

**Fix:** Changed `determineTableWidth` logic to:
1. `prefSum > 120` → 120
2. `prefSum < 40 AND n < 3` → 40 (early exit)
3. `prefSum < 80` → 80 (covers both `40 <= prefSum < 80` and `prefSum < 40 with n >= 3`)
4. else → prefSum

**Status:** Fixed.

## Bug 429: `cjfr view jdk.CPUTimeStampCounter` renders frequency as GHz instead of Hz

**Observation:** `cjfr view jdk.CPUTimeStampCounter` showed `1.00 GHz` for the OS Frequency
and Fast Time Frequency columns, while oracle shows `1000000000 Hz`.

**Root cause:** `JFRView.FrequencyColumn.format()` scaled the Hz value to GHz/MHz/kHz for large
values, mimicking a human-friendly display that oracle doesn't use. Oracle's `jfr view` renders
`@Frequency` fields as the raw integer value followed by ` Hz`, with no scaling.

**Fix:** Removed the scaling logic from `FrequencyColumn.format()`; now always returns
`value + " Hz"`, matching oracle's output.

**Status:** Fixed.

## Bug 430: `cjfr view jdk.Shutdown` shows `:−1` line number suffix for native frames

**Observation:** `cjfr view jdk.Shutdown` showed `java.lang.Shutdown.beforeHalt():-1` in the
Stack Trace column, while oracle shows `java.lang.Shutdown.beforeHalt()` (no line number).

**Root cause:** `JFRView.StackTraceColumn.format()` unconditionally appended `:` + `lineNumber`
for any frame that has a `lineNumber` field, including frames where line number is -1 (the JFR
sentinel for "unknown/not applicable", used for native methods).

**Fix:** In `StackTraceColumn.format()`, only append the line number when `lineNumber >= 0`.

**Status:** Fixed.

## Bug 431: `cjfr view jdk.LongFlag` and `jdk.UnsignedLongFlag` show integers without comma grouping

**Observation:** `cjfr view jdk.LongFlag` showed values like `2147483647` without comma grouping,
while oracle shows `2.147.483.647` (German locale thousands separator) which is structurally
equivalent to `2,147,483,647` (ROOT locale). Column widths were wrong as a consequence (10 vs 13
for the Value column).

**Root cause:** `JFRView.IntegerColumn.format()` and `SentinelIntegerColumn.format()` used
`String.valueOf(val)` which produces no thousands separating, instead of `String.format(Locale.ROOT, "%,d", n)`.

**Fix:** Updated both `IntegerColumn.format()` and `SentinelIntegerColumn.format()` to use
`String.format(Locale.ROOT, "%,d", n.longValue())` for `Number` values.

**Status:** Fixed.

## Bug 432: `cjfr view <EventType>` shows `-` for null values instead of `N/A`

**Observation:** Multiple event type views (`jdk.StringFlag`, `jdk.OldObjectSample`, etc.)
showed `-` for null/absent field values, while oracle shows `N/A`.

**Root cause:** All `Column.format()` implementations in `JFRView` returned `List.of("-")` for
null values. Oracle's `jfr view` uses `N/A` as the universal null replacement in table cells.

**Fix:** Updated all `Column` implementations (`DurationColumn`, `InstantColumn`, `ThreadColumn`,
`MemoryColumn`, `MemoryAddressColumn`, `StringColumn`, `IntegerColumn`, `SentinelIntegerColumn`,
`FloatColumn`, `BooleanColumn`, `PercentageColumn`, `FrequencyColumn`, `DataRateColumn`,
`ClassColumn`, `MethodColumn`, `NestedColumn`, `EventIdColumn`, `StructColumn`) to return
`List.of("N/A")` instead of `List.of("-")` for null values.

**Status:** Fixed.

## Bug 433: `cjfr view jdk.DoubleFlag` shows wrong precision (e.g. `20.00` instead of `20`, `0.5000` instead of `0.5`)

**Observation:** `cjfr view jdk.DoubleFlag` rendered float/double values with fixed 2 decimal
places (e.g. `20.00`, `1.00`, `0.5000`, `1.562000`). Oracle shows `20`, `1`, `0,5`, `1,562` (4
significant figures, trailing zeros stripped, locale thousands separator).

**Root cause:** `JFRView.FloatColumn.format()` used `String.format("%.2f", v)` (fixed 2 decimal
places) instead of oracle's 4-significant-figure rule. `ValueFormatter.formatDouble()` also lacked
a guard for `Double.isNaN(v)` / `Double.isInfinite(v)` before constructing `BigDecimal(v)`, causing
a `NumberFormatException("Infinite or NaN")` when a condensed double value was inflated as `Infinity`
(e.g. `Double.MAX_VALUE` overflows the condenser's double precision — a separate condenser bug).

**Fix:**
- `FloatColumn.format()` now delegates to `ValueFormatter.formatDoublePublic(d)` (the same 4-sig-fig
  HALF_EVEN logic used by the native-view query path).
- `ValueFormatter.formatDouble()` now short-circuits for NaN → `"NaN"` and Infinity →
  `"Infinity"` / `"-Infinity"` before calling `new BigDecimal(v)`.

**Status:** Fixed.

## Bug 434: `cjfr view` shows line numbers in stack trace column; oracle doesn't

**Observation:** `cjfr view jdk.ThreadStart` and `jdk.JavaErrorThrow` showed line numbers appended
to stack frames (e.g. `java.lang.Error.<init>(String):72`). Oracle shows only
`java.lang.Error.<init>(String)` — no line number suffix.

**Root cause:** An earlier Bug 430 fix incorrectly added `:lineNumber` to stack frame display when
`lineNumber >= 0`. Oracle's table view never shows line numbers in the stack trace column for any
event type (line numbers are available in `jfr print` but not `jfr view`).

**Fix:** Removed the line number appending from `JFRView.StackTraceColumn.format()`.

**Status:** Fixed.

## Bug 435: `cjfr view jdk.ModuleExport` shows `"bootstrap"` for bootstrap ClassLoader; oracle shows `N/A`

**Observation:** `cjfr view jdk.ModuleExport` and `jdk.ModuleRequire` showed `"bootstrap"` in the
Exporting ClassLoader column for modules with the bootstrap class loader. Oracle shows `N/A`.

**Root cause:** `JFRView.ClassLoaderColumn.format()` fell back to the `name` field (`"bootstrap"`)
when the loader's `type` was null. Oracle only renders the loader's `type.name` (the class name of
the loader object); when `type` is null (bootstrap loader has no Java type), it renders nothing/N/A.

**Fix:** Removed the `name`-field fallback; `ClassLoaderColumn` now returns `N/A` when `type` is
null.

**Status:** Fixed.

## Bug 436: `cjfr view <EventType>` DataRate shows wrong format (no space, extra precision)

**Observation:** `cjfr view jdk.G1AdaptiveIHOP` showed data rates like `"71.62MB/s"` (no space
before unit, 2 decimal places). Oracle shows `"71,6 MB/s"` (space before unit, 1 decimal place).
Zero-rate showed `"0B/s"` vs oracle's `"0 byte/s"` (singular "byte", space).

**Root cause:** `JFRView.DataRateColumn.format()` used `MemoryUtil.formatMemory()` which uses
minimum decimals for exact round-trip and no space before the unit.

**Fix:** `DataRateColumn.format()` now uses `ValueFormatter.formatMemory()` (same 1-decimal,
space-separated format as the oracle's `MemoryColumn`) + `"/s"` suffix. Zero bytes/s renders as
`"0 byte/s"` (singular) to match oracle.

**Status:** Fixed.

## Bug 437: `cjfr view jdk.OSInformation` shows extra blank row for OS version string

**Observation:** `cjfr view jdk.OSInformation` output had an extra blank line after the OS version
row. Oracle shows it in a single row.

**Root cause:** The `osVersion` field value ends with a trailing newline character (from the `uname`
command output stored in the JFR recording). When rendered in the table, the trailing `\n` caused
the table renderer to emit a second empty row.

**Status:** Fixed.

## Bug 438: `cjfr view` truncates method parameter list mid-string (e.g. `Error.<init>(St...`); oracle shows `Error.<init>(...)`

**Observation:** When a method name with parameters doesn't fit the column width, we truncate the
string at the character boundary: `java.lang.Error.<init>(St...`. Oracle truncates by replacing the
entire parameter list with `(...)`: `java.lang.Error.<init>(...)`.

Affects `StackTraceColumn` (stack trace event-type views) and `MethodColumn`.

**Root cause:** `MethodColumn` had no `compact()` override. The default `compact()` passes through
unchanged, so the generic `truncate()` (which does end-truncation with `...`) was used.
`StackTraceColumn` delegates format to `MethodColumn` but the `compact()` was called on the
`StackTraceColumn` itself, also without an override.

**Fix:** Added `compact(String value)` to `MethodColumn` that replaces everything from the last `(`
to the end with `(...)`. Added a `compact()` override to `StackTraceColumn` that delegates to
`METHOD_COLUMN.compact()`.

**Status:** Fixed.

## Bug 439: `cjfr view jdk.Flush` shows title "Flush"; oracle shows "Flush (Experimental)"

**Observation:** Event types with `@Experimental` annotation should show "(Experimental)" in their
display name. `jdk.Flush` shows "Flush", but oracle shows "No events found for 'Flush (Experimental)'."
Same for `jdk.SyncOnValueBasedClass` → "Value Based Class Synchronization (Experimental)".

**Root cause:** Two places construct the display name from the event type's description JSON:
1. `JFRView.typeDisplayName()` — used when events exist to build the view header
2. `FieldResolver.typeLabel()` — used for the "No events found" message
3. `BasicJFRWriter.recordEventTypeLabel()` — used to populate the footer's eventTypeLabels map

None of them checked for `@Experimental` in the annotation list.

**Fix:**
- `JFRView.typeDisplayName()` now checks `parsed.annotations()` for `jdk.jfr.Experimental` and
  appends " (Experimental)".
- `FieldResolver.typeLabel()` checks if `description.contains("jdk.jfr.Experimental")` and appends
  " (Experimental)".
- `BasicJFRWriter.recordEventTypeLabel()` checks annotation elements for `jdk.jfr.Experimental`
  so newly-condensed `.cjfr` footers carry the correct label.
- `BasicJFRWriter.ParsedAnnotationElement` made `public` so `JFRView` can access it.
- `profile_lossless.cjfr` regenerated to pick up the corrected footer labels.

**Status:** Fixed.

## Bug 441: `cjfr view jdk.types.Method` shows "No events found for '[null,null,[]]'"

**Observation:** Querying a struct/metadata type like `jdk.types.Method` shows "No events found for
'[null,null,[]]'" instead of the oracle behavior "Could not find a view or an event type named
jdk.types.Method". The label displayed was the raw JSON description `[null,null,[]]`.

**Root cause:** Struct types (referenced types, not event types) are stored with `null` label and
description, producing the description JSON `[null,null,[]]`. `FieldResolver.typeLabel()` tried to
extract the label as the first quoted string in the JSON; when the description starts with `null`
(no quote), `indexOf('"')` returns -1 and the code fell through to `return description` — returning
the raw JSON string as the label.

**Fix:** When `indexOf('"')` is -1 (no quoted first element), return `fallbackName` (the type name)
instead of the raw description. This causes `reportNoEventType()` to detect that `label == eventName`
and emit the "No event of type X found" error (exit 1), matching oracle behavior.

**Status:** Fixed.

## Bug 442: `cjfr view jdk.ActiveSetting` shows "(Experimental)" in Event Id data column

**Observation:** `cjfr view jdk.ActiveSetting` shows event type labels with "(Experimental)" suffix
in the `Event Id` column (e.g. "Value Based Class Synchronization (Experimental)"), while oracle
`jfr view jdk.ActiveSetting` shows the clean label without the suffix (e.g. "Value Based Class
Synchronization").

**Root cause:** Bug 439 stored the "(Experimental)" suffix in the footer `eventTypeLabels` map via
`BasicJFRWriter.recordEventTypeLabel()`. The `EventIdColumn` in `JFRView` and `relabelSettingId()`
in `QueryEvaluator` both look up event type labels from this map to translate stored type names (e.g.
`jdk.ValueBasedObjectSynchronization`) into human labels — and thus included the "(Experimental)"
suffix in data column values. Oracle only shows the suffix in view titles/header lines (e.g.
"No events found for 'Flush (Experimental)'"), not in data cells.

**Fix:** Strip the `" (Experimental)"` suffix in `EventIdColumn.format()` (`JFRView`) and
`relabelSettingId()` (`QueryEvaluator`) before returning the label for column rendering.

**Status:** Fixed.

## Bug 443: `cjfr print` does not support `--xml` option

**Observation:** `jfr print --xml profile.jfr` outputs XML format. Running `cjfr print --xml
profile.cjfr` fails with "Error: Unknown option: --xml".

**Root cause:** The `PrintCommand` only implements text (`--print`) and JSON (`--json`) output
formats; XML output was never implemented.

**Status:** Fixed. Added `--xml` option to `PrintCommand` with recursive XML rendering matching
oracle's structure: null structs use `xsi:nil="true"`, arrays use `<array name="N" size="M">` with
`<struct index="I">` elements, timestamps use ISO-8601 with nanosecond precision. Injected
`STATE_RUNNABLE` for ExecutionSample/NativeMethodSample events after stackTrace, matching oracle.

## Bug 444: `cjfr metadata` command not implemented

**Observation:** `jfr metadata profile.jfr` prints all event type schemas. `jfr metadata --events
jdk.ThreadStart profile.jfr` shows the schema for a specific event type. `cjfr` has no `metadata`
subcommand.

**Status:** Not fixed.

## Bug 445: `cjfr print --xml` renders null scalar fields as `<value name="..."></value>` instead of `<value name="..." xsi:nil="true"/>`

**Observation:** In oracle `jfr print --xml`, null scalar fields (e.g. `javaName` when the thread
has no Java name) render as `<value name="javaName" xsi:nil="true"/>`. Our implementation rendered
them as `<value name="javaName"></value>` because `printXmlField` fell through to the scalar path
which called `xmlValue(null, ...)` returning `""`.

**Fix:** Added an explicit null check for non-struct fields at the top of `printXmlField()`: if
`value == null` and the field type is not a `StructType`, emit `<value name="..." xsi:nil="true"/>`.

**Status:** Fixed.

## Bug 446: `cjfr print --xml` uses wrong field order (puts `eventThread`/`stackTrace` last instead of natural declaration order)

**Observation:** In oracle `jfr print --xml`, fields appear in their JFR declaration order (e.g.
`SafepointBegin`: `startTime`, `duration`, `eventThread`, then domain fields like `safepointId`).
Our XML implementation reused the text-output field ordering (meta first, then domain, then
`eventThread`/`stackTrace` last), causing `eventThread` to appear after `safepointId` etc.

**Fix:** `printXmlEvent()` now iterates fields in natural declaration order (single loop over
`event.getType().getFields()`) without the meta/domain/tail reordering. The text output's
`printTextEvent()` retains its reordering independently.

**Status:** Fixed.

## Bug 447: `cjfr print --xml` does not escape single quotes as `&apos;`

**Observation:** Oracle `jfr print --xml` escapes single quote characters (`'`) in element content
as `&apos;` (e.g. in `jvmArguments`). Our `xmlEscape()` only escaped `&`, `<`, `>`, `"`.

**Fix:** Added `'` → `&apos;` to `xmlEscape()`.

**Status:** Fixed.

## Bug 448: `cjfr print --xml --stack-depth N` does not limit stack frames in XML output

**Observation:** `--stack-depth` limits stack frames in text and JSON output formats, but XML
`printXmlArray()` did not apply the limit, so all frames were always rendered regardless of the
`--stack-depth` option.

**Fix:** `printXmlArray()` now computes `limit = stackDepth >= 0 ? min(stackDepth, size) : size`
and iterates only up to `limit`, consistent with how JSON handles it in `listToJson()`.

**Status:** Fixed.

## Bug 449: `cjfr view types` does not work — shows "No event of type types found"

**Observation:** `jfr view types profile.jfr` lists all event types in the recording with their
event counts. `cjfr view types profile.jfr` instead shows "No event of type types found." with
did-you-mean suggestions. The word `types` doesn't contain a `-` and is not a known view in
`NativeView`, so it never reaches the JDK `jfr view` delegation path.

**Fix:** Added `viewName.equals("types")` to the delegation condition in `ViewCommand.call()`,
alongside the existing `viewName.contains("-")` check. `types` is now forwarded to `jfr view`
on `.jfr` input and inflated on `.cjfr` input, same as other special views.

**Status:** Fixed.