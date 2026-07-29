# Profiling & I/O Event Reductions Design

Date: 2026-07-29

## Overview

Three independent changes that reduce the size of profiling-config and default-config recordings in
the `default` and `reduced` presets. All changes are backward-compatible at the CJFR format level
(self-describing combined types). Inflate always reconstitutes original event types — combined types
are a storage artifact only.

---

## Change 1: Strip `state` from `ExecutionSample` and `NativeMethodSample`

**Flag:** reuse `removeTypeInformationFromStackFrames`  
**Presets:** `default`, `reduced`  
**Lossless:** yes (field is always `STATE_RUNNABLE` — confirmed in source and across all benchmark
recordings; JFR only samples RUNNABLE threads by design)

**Implementation:** add two new entries to `ReducedJFRTypes.REDUCED_JFR_TYPES`:

```
"jdk.ExecutionSample"    → RemovedPrimitiveField("state", Configuration::removeTypeInformationFromStackFrames)
"jdk.NativeMethodSample" → RemovedPrimitiveField("state", Configuration::removeTypeInformationFromStackFrames)
```

No new combiner, no new config flag, no reconstitutor changes needed.

---

## Change 2: Combine `ExecutionSample` and `NativeMethodSample` per time window

**New config flag:** `combineProfilingSamples` (boolean)  
**Presets:** `reduced` only (false in `lossless` and `default`)  
**New config param:** `profilingBucketSeconds` (long, default 10) — independent of `cpuBucketSeconds`

### Combined event schema: `jdk.combined.ExecutionSample`

Covers both `jdk.ExecutionSample` and `jdk.NativeMethodSample` (both have identical fields; the
original event type name is stored so inflate can reconstruct the correct type).

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of the window (first sample's timestamp) |
| `sampledThread` | Thread | Grouping key |
| `stackTrace` | StackTrace | Grouping key |
| `state` | String | Grouping key (always `STATE_RUNNABLE`; present so inflate works even without Change 1) |
| `originalEventType` | String | `"jdk.ExecutionSample"` or `"jdk.NativeMethodSample"` |
| `startTimeDiffs` | VarInt[] | Delta-encoded timestamps relative to window start, one per sample |
| `count` | Int | `startTimeDiffs.length` — redundant but useful for quick queries without array decode |

### Grouping key

`(sampledThread identity, stackTrace content-hash, state, originalEventType)`

Same content-hash approach as `ReducedStackTrace` — snapshot frames at accumulation time to avoid
stale reads from JFR's reusable buffers.

### Flush boundary

At each `profilingBucketSeconds` wall-clock boundary (same mechanism as `cpuBucketSeconds` in the
CPU load combiner): flush all open groups, emit one `jdk.combined.ExecutionSample` per group, reset
accumulators. Also flush on chunk boundary.

Groups with a single sample in the window emit a combined event with `count=1` (consistent schema).

### Reconstitutor

`ExecutionSampleReconstitutor` expands one combined event → N individual `jdk.ExecutionSample` (or
`jdk.NativeMethodSample`) events, one per entry in `startTimeDiffs[]`. Reconstituted fields:
- `startTime` = window `startTime` + `startTimeDiffs[i]`
- `sampledThread`, `stackTrace`, `state` = from combined event (pass-through)

Inflate output is indistinguishable from original (modulo Change 1 stripping `state` if that flag
is also active).

---

## Change 3: Combine I/O events per time window

**New config flag:** `combineIOEvents` (boolean)  
**Presets:** `reduced` only (false in `lossless` and `default`)  
**Reuses:** `profilingBucketSeconds` window

Covers: `jdk.SocketRead`, `jdk.SocketWrite`, `jdk.FileRead`, `jdk.FileWrite`, `jdk.FileForce`

### Combined event schemas

Each original event type gets its own combined type to avoid mismatched fields across incompatible
schemas (e.g. `endOfStream` exists on SocketRead but not SocketWrite; `FileForce` has no bytes).

**`jdk.combined.SocketRead`:**

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of window |
| `eventThread` | Thread | Grouping key |
| `host` | String | Grouping key |
| `address` | String | Grouping key |
| `port` | Int | Grouping key |
| `startTimeDiffs` | VarInt[] | Delta-encoded from window start |
| `durations` | VarInt[] | Per-call duration in ns |
| `bytesRead` | VarInt[] | Per-call bytes read |
| `endOfStream` | Boolean[] | Per-call endOfStream flag |

**`jdk.combined.SocketWrite`:** same as SocketRead but `bytesWritten` instead of `bytesRead`, no `endOfStream`.

**`jdk.combined.FileRead`:**

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of window |
| `eventThread` | Thread | Grouping key |
| `path` | String | Grouping key |
| `startTimeDiffs` | VarInt[] | Delta-encoded from window start |
| `durations` | VarInt[] | Per-call duration in ns |
| `bytesRead` | VarInt[] | Per-call bytes read |
| `endOfFile` | Boolean[] | Per-call endOfFile flag |

**`jdk.combined.FileWrite`:** same as FileRead but `bytesWritten` instead of `bytesRead`, no `endOfFile`.

**`jdk.combined.FileForce`:**

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of window |
| `eventThread` | Thread | Grouping key |
| `path` | String | Grouping key |
| `startTimeDiffs` | VarInt[] | Delta-encoded from window start |
| `durations` | VarInt[] | Per-call duration in ns |
| `metaData` | Boolean[] | Per-call metaData flag |

### Grouping key

- SocketRead: `(eventThread identity, host, address, port)`
- SocketWrite: `(eventThread identity, host, address, port)`
- FileRead/FileWrite/FileForce: `(eventThread identity, path)`

Each original event type maps to its own combined type, so reads and writes to the same
file/socket are always separate groups.

### Flush boundary

Same `profilingBucketSeconds` window as Change 2. Also flush on chunk boundary.

### Reconstitutor

One reconstitutor per combined type (`SocketReadReconstitutor`, `SocketWriteReconstitutor`,
`FileReadReconstitutor`, `FileWriteReconstitutor`, `FileForceReconstitutor`). Each expands a
combined event → N individual events of the corresponding original type, one per
`startTimeDiffs[i]`. All array fields are index-aligned.

---

## Configuration changes

```java
// New fields added to Configuration record:
boolean combineProfilingSamples,   // false in lossless+default, true in reduced
boolean combineIOEvents,           // false in lossless+default, true in reduced
long profilingBucketSeconds,       // default 10

// REDUCED_DEFAULT gains:
.withCombineProfilingSamples(true)
.withCombineIOEvents(true)

// profilingBucketSeconds=0 treated as 10 (same pattern as cpuBucketSeconds)
```

---

## JFREventCombiner registration

In `JFREventCombiner.registerCombiner(EventType, ...)`:

```java
if (configuration.combineProfilingSamples()) {
    if (name.equals("jdk.ExecutionSample") || name.equals("jdk.NativeMethodSample")) {
        put(eventType, new ExecutionSampleCombiner(configuration, basicJFRWriter));
    }
}
if (configuration.combineIOEvents()) {
    if (name.equals("jdk.SocketRead"))  put(eventType, new SocketReadCombiner(configuration, basicJFRWriter));
    if (name.equals("jdk.SocketWrite")) put(eventType, new SocketWriteCombiner(configuration, basicJFRWriter));
    if (name.equals("jdk.FileRead"))    put(eventType, new FileReadCombiner(configuration, basicJFRWriter));
    if (name.equals("jdk.FileWrite"))   put(eventType, new FileWriteCombiner(configuration, basicJFRWriter));
    if (name.equals("jdk.FileForce"))   put(eventType, new FileForceCombiner(configuration, basicJFRWriter));
}
```

---

## Testing

- `JFREventCombinerTest`: unit test for `ExecutionSampleCombiner` — same-group events collapse, different groups stay separate, window boundary triggers flush
- `BasicJFRRoundTripTest` or new `ProfilingReductionRoundTripTest`: condense with `reduced` preset → inflate → compare per-type event multisets (timestamps, threads, stackTraces preserved)
- Verify `state` strip: inflated events have no `state` field when `removeTypeInformationFromStackFrames=true`
- Benchmark: run `cjfr condense` on `renaissance-als_default_G1.jfr` (5389 ExecutionSamples, 4762 NativeSamples) and report size delta

---

## Known constraints

- `profile.jfr` fixture has zero I/O events — I/O combiners can only be tested with a recording that exercises `SocketRead/Write`/`FileRead/Write`. Either use a new fixture or accept that I/O combiner tests are integration-only.
- `profilingBucketSeconds` is serialized in the CJFR footer (same as `cpuBucketSeconds`) — old readers that don't know the field treat it as the default (10), which is safe.
- The `endOfStream`/`endOfFile` boolean arrays are low-cardinality but kept for full fidelity (Change A decision).
