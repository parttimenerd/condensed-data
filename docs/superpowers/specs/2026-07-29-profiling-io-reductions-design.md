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
**Lossless:** yes — field is always `STATE_RUNNABLE` (JFR only samples RUNNABLE threads by design;
confirmed in source and across all benchmark recordings)

Add two entries to `ReducedJFRTypes.REDUCED_JFR_TYPES`:

```
"jdk.ExecutionSample"    → RemovedPrimitiveField("state", Configuration::removeTypeInformationFromStackFrames)
"jdk.NativeMethodSample" → RemovedPrimitiveField("state", Configuration::removeTypeInformationFromStackFrames)
```

---

## Change 2: Combine `ExecutionSample` and `NativeMethodSample` per time window

**New config flag:** `combineProfilingSamples` (boolean, false in `lossless`+`default`, true in `reduced`)  
**New config param:** `profilingBucketSeconds` (long, default 10)

### Combined event schema: `jdk.combined.ExecutionSample`

Covers both `jdk.ExecutionSample` and `jdk.NativeMethodSample` — original type name stored in
`originalEventType` so inflate reconstructs the correct type.

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of window (first sample's timestamp) |
| `sampledThread` | Thread | Grouping key |
| `stackTrace` | StackTrace | Grouping key |
| `state` | String | Grouping key (present even when Change 1 strips it from originals) |
| `originalEventType` | String | `"jdk.ExecutionSample"` or `"jdk.NativeMethodSample"` |
| `startTimeDiffs` | VarInt[] | Delta-encoded timestamps relative to window start |
| `count` | Int | `startTimeDiffs.length` — for queries that don't need to decode the array |

### Grouping key

`(sampledThread identity, stackTrace content-hash, state, originalEventType)`

Uses the `ReducedStackTrace` content-hash approach to avoid stale reads from JFR's reusable buffers.

### Flush boundary

Every `profilingBucketSeconds` wall-clock seconds and on chunk boundary. Groups with a single
sample emit a combined event with `count=1`.

### Reconstitutor

Expands one combined event → N individual `jdk.ExecutionSample` (or `jdk.NativeMethodSample`)
events, one per `startTimeDiffs[i]`:
- `startTime` = window `startTime` + `startTimeDiffs[i]`
- `sampledThread`, `stackTrace`, `state` = pass-through from combined event

---

## Change 3: Combine I/O events per time window

**New config flag:** `combineIOEvents` (boolean, false in `lossless`+`default`, true in `reduced`)  
**Reuses:** `profilingBucketSeconds` window

Covers: `jdk.SocketRead`, `jdk.SocketWrite`, `jdk.FileRead`, `jdk.FileWrite`, `jdk.FileForce`

Each original type gets its own combined type to keep schemas clean (incompatible fields across
types — `endOfStream` on SocketRead only, no bytes on FileForce, etc.).

### `jdk.combined.SocketRead`

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

**`jdk.combined.SocketWrite`:** same minus `endOfStream`, `bytesWritten` instead of `bytesRead`.

### `jdk.combined.FileRead`

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of window |
| `eventThread` | Thread | Grouping key |
| `path` | String | Grouping key |
| `startTimeDiffs` | VarInt[] | Delta-encoded from window start |
| `durations` | VarInt[] | Per-call duration in ns |
| `bytesRead` | VarInt[] | Per-call bytes read |
| `endOfFile` | Boolean[] | Per-call endOfFile flag |

**`jdk.combined.FileWrite`:** same minus `endOfFile`, `bytesWritten` instead of `bytesRead`.

### `jdk.combined.FileForce`

| Field | Type | Description |
|---|---|---|
| `startTime` | Timestamp | Start of window |
| `eventThread` | Thread | Grouping key |
| `path` | String | Grouping key |
| `startTimeDiffs` | VarInt[] | Delta-encoded from window start |
| `durations` | VarInt[] | Per-call duration in ns |
| `metaData` | Boolean[] | Per-call metaData flag |

### Grouping key

- Socket types: `(eventThread identity, host, address, port)`
- File types: `(eventThread identity, path)`

### Reconstitutor

One per combined type (`SocketReadReconstitutor`, etc.), each expanding to N individual events of
the original type. All array fields are index-aligned.

---

## Configuration changes

```java
// New fields in Configuration record:
boolean combineProfilingSamples,   // false in lossless+default, true in reduced
boolean combineIOEvents,           // false in lossless+default, true in reduced
long profilingBucketSeconds,       // default 10; 0 treated as 10 (same pattern as cpuBucketSeconds)

// REDUCED_DEFAULT gains:
.withCombineProfilingSamples(true)
.withCombineIOEvents(true)
```

---

## JFREventCombiner registration

```java
if (configuration.combineProfilingSamples()) {
    if (name.equals("jdk.ExecutionSample") || name.equals("jdk.NativeMethodSample"))
        put(eventType, new ExecutionSampleCombiner(configuration, basicJFRWriter));
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

- Unit test `ExecutionSampleCombiner`: same-group events collapse, different groups stay separate,
  window boundary triggers flush
- Round-trip test with `reduced` preset: condense → inflate → compare per-type event multisets
  (timestamps, threads, stackTraces preserved)
- `state` strip: inflated events have no `state` field when `removeTypeInformationFromStackFrames=true`
- Benchmark on `flight.jfr` (71k ExecutionSamples, 9k unique groups → ~87% event reduction) and
  `profile(2).jfr` (4.9k NativeMethodSamples, 9 unique groups → ~99.8% event reduction)
- I/O combiner tests require a fixture with SocketRead/FileRead events; `profile.jfr` has none —
  use `flight_recording_21TheJVMRunningMissionControl.jfr` (145 SocketRead, 9 groups) or generate
  a synthetic fixture
