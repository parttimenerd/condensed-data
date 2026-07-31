---
title: Analyzing Recordings
---

# Analyzing Recordings

`cjfr` queries `.cjfr` files directly; no inflation needed. All analysis
commands accept the same filtering flags, so you can zero in on the
30-second GC storm you care about before deciding whether to inflate the full recording.

## Commands at a glance

| Command | Purpose |
|---|---|
| `cjfr summary` | Aggregate stats: event counts, GC summary, allocation rate |
| `cjfr view <VIEW_OR_EVENT> <FILE...>` | Tabular view of a named view or one event type |
| `cjfr print <FILE...>` | Print raw events in `jfr print` text or JSON format |
| `cjfr inflate` | Convert to JFR for [JDK Mission Control](https://adoptium.net/jmc), [Firefox Profiler](https://parttimenerd.github.io/firefox-profiler/), [jfr-query](https://parttimenerd.github.io/jfr-query/), async-profiler, etc. |

All four accept the **same filter flags** described below.

---

## Time-range filtering

Narrow the window you care about with `--start`, `--end`, or `--duration`.
Timestamps are in local time unless you include an explicit offset.

| Flag | Accepted formats | Example |
|---|---|---|
| `--start` | `yyyy-MM-dd HH:mm:ss`, `yyyy-MM-ddTHH:mm:ss`, ISO-8601 with timezone | `--start="2024-05-24 12:07:00"` |
| `--end` | same as `--start` | `--end="2024-05-24 12:09:00"` |
| `--duration` | `1h30m`, `5m`, `30s`, `500ms`, `100us` | `--duration=2m` |

Combine `--start` + `--end` **or** `--start` + `--duration` (or `--end` + `--duration`). Don't pass all three.
Use `cjfr summary --short recording.cjfr` to find the recording's start time.

```shell
# Summary of a 2-minute window
cjfr summary --start="2024-05-24 12:07:00" --duration=2m recording.cjfr

# View heap summaries in that window
cjfr view --start="2024-05-24 12:07:00" --end="2024-05-24 12:09:00" \
  jdk.GCHeapSummary recording.cjfr

# Inflate just that window for Mission Control
cjfr inflate --start="2024-05-24 12:07:00" --duration=2m \
  recording.cjfr slice.jfr
```

---

## GC percentile filter

Focus on the worst GC pauses and the application activity that caused them.
`--gc-percentile=N` keeps only events that fall within the `--gc-percentile-context`
window (default 1 minute) around every GC whose pause duration is at or above
the Nth percentile. Pass `0` to disable (default).

- With `cjfr summary`, this changes the **event-count table**: you see counts of
  what happened around the worst pauses. The standard GC Summary section is still
  shown when the input is a single file.
- With `cjfr inflate`, this produces a `.jfr` containing only those windows;
  much smaller than a full inflation and fast to open in JMC.
- With `cjfr view`, it restricts the event listing to those windows.

```shell
# Keep only events near the slowest 10% of GC pauses (≥ 90th percentile)
cjfr summary --gc-percentile=90 recording.cjfr

# Widen the context window to 2 minutes around each qualifying GC
cjfr summary --gc-percentile=90 --gc-percentile-context=2m recording.cjfr

# Inflate only those high-pause windows to JFR
cjfr inflate --gc-percentile=95 recording.cjfr pauses.jfr
```

`--gc-percentile-context` defaults to `1m`: the time window before and after
each qualifying GC pause to include. A smaller value (e.g. `15s`) gives
tighter slices; a larger one (e.g. `5m`) captures longer allocation patterns that
build up before the pause.

---

## Event-type filtering

Pass `--events` to include only specific JFR event types. Accepts a
comma-separated list, and is repeatable.

For a full reference of available JFR event types and their fields, see
[JFR Events](https://sap.github.io/jfrevents/25.html).

```shell
# Summary counting only GC events
cjfr summary --events=jdk.GarbageCollection,jdk.GCHeapSummary recording.cjfr

# Inflate to a GC-only JFR (much smaller than full inflation)
cjfr inflate --events=jdk.GarbageCollection,jdk.GCHeapSummary,jdk.G1HeapSummary \
  recording.cjfr gc-only.jfr

# Repeatable form (same result):
cjfr inflate --events=jdk.GarbageCollection --events=jdk.GCHeapSummary \
  recording.cjfr gc-only.jfr
```

Useful event groups for GC analysis:

| Goal | Event types |
|---|---|
| GC pauses only | `jdk.GarbageCollection`, `jdk.GCPhasePause` |
| Heap sizing | `jdk.GCHeapSummary`, `jdk.G1HeapSummary`, `jdk.MetaspaceSummary` |
| Allocation pressure | `jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB`, `jdk.ObjectAllocationSample` |
| Full GC picture | `jdk.GarbageCollection`, `jdk.GCHeapSummary`, `jdk.TenuringDistribution`, `jdk.GCReferenceStatistics`, `jdk.GCCPUTime` |

**Collector-specific pause fields in `jdk.GarbageCollection`:** the pause field name
varies by GC algorithm. When using `cjfr view --json` or scripting against JSON output:

| Collector | Pause field(s) | Unit |
|---|---|---|
| G1GC | `longestPause`, `sumOfPauses` | nanoseconds |
| ZGC | `duration` | nanoseconds |
| Shenandoah | `duration` | nanoseconds |
| Serial / Parallel | `longestPause`, `sumOfPauses` | nanoseconds |

`cjfr summary --json` always reports `.gc.p95Micros` and `.gc.maxMicros` in **microseconds** regardless of collector, derived from the collector's pause field.

---

## Working with multiple files

All files are merged in time order; the normal way to work with a rotating recording set.

!!! warning "GC Summary is single-file only"
    `cjfr summary` only produces the dedicated **GC Summary** section when querying
    a **single file**. When multiple files are passed, the event count table is
    merged across all files, but the GC-specific summary section is omitted.

    Workarounds:

    - Run `summary --short` on the most recent rotation file per host; it is
      usually representative.
    - Run `summary --json` on each file individually and aggregate `.gc.p95Micros`
      / `.gc.maxMicros` (values in microseconds).
    - Or `inflate` the multi-file set to a single `.jfr` and re-run
      `summary --short` on that single output.

For `cjfr summary`, pass all files as positional arguments (glob expansion works too):

```shell
# Summary across a whole day of rotating recordings
cjfr summary rec_0.cjfr rec_1.cjfr rec_2.cjfr
cjfr summary rec_*.cjfr
```

For `cjfr inflate`, all input files come first as positional arguments, with the output file (`.jfr`) last. For `cjfr view`, the view or event name comes **first** (mirroring the JDK `jfr view`), followed by one or more input files:

```shell
# Inflate multiple files into a single JFR
cjfr inflate rec_0.cjfr rec_1.cjfr rec_2.cjfr merged.jfr

# Combine multi-file with time range: extract 5-minute window across the set
cjfr inflate --start="2024-05-24 03:00:00" --duration=5m \
  rec_0.cjfr rec_1.cjfr window.jfr

# View one event type across several files (name first, then the files)
cjfr view jdk.GarbageCollection rec_0.cjfr rec_1.cjfr rec_2.cjfr
```

---

## `summary` output modes

```shell
cjfr summary recording.cjfr             # default: header + event table + GC/alloc summary
cjfr summary --short recording.cjfr     # header + GC/alloc summary only (no event table)
cjfr summary --full recording.cjfr      # adds EventWriteTree and per-type byte statistics
cjfr summary --json recording.cjfr      # machine-readable JSON
cjfr summary --limit=10 recording.cjfr  # show only the 10 largest event types
cjfr summary --flamegraph storage.html recording.cjfr  # storage flamegraph by event type
```

The `--flamegraph` output shows **byte distribution** across event types, not
CPU time; useful for understanding which event types dominate file size.

---

## `view` output and formatting

```shell
# Show all jdk.GarbageCollection events
cjfr view jdk.GarbageCollection recording.cjfr

# Limit to first 20
cjfr view --limit=20 jdk.GarbageCollection recording.cjfr

# Narrow terminal: truncate long values at the start of cells (keeps the end)
cjfr view --width=120 --truncate=beginning jdk.GarbageCollection recording.cjfr

# JSON output (suitable for piping to jq)
cjfr view --json jdk.GarbageCollection recording.cjfr | jq '.[] | .gcId'

# Combine with time range
cjfr view --start="2024-05-24 12:07:00" --duration=30s --limit=50 \
  jdk.GCHeapSummary recording.cjfr

# Render a JDK named view directly (natively when possible, else via `jfr view`)
cjfr view gc-pauses recording.cjfr
```

### Named views

`cjfr view` is a drop-in replacement for the JDK `jfr view` command and supports
**all** of its named views (`gc-pauses`, `hot-methods`, `allocation-by-site`,
`exception-by-type`, `jvm-information`, …). The list of views is not bundled or
hard-coded: `cjfr` reads the running JVM's own `view.ini` from the `jrt:` runtime
image (`jdk/jfr/internal/query/view.ini`) and evaluates each view's query natively,
so the set of available views always matches the JDK you run `cjfr` on.

```shell
cjfr view hot-methods recording.cjfr
cjfr view allocation-by-site recording.cjfr
cjfr view --width=120 gc-configuration recording.cjfr
```

Any view that can't be evaluated natively — or running on a pre-21 JDK where the
`view.ini` isn't available — falls back automatically to delegating to
`$JAVA_HOME/bin/jfr view`, so every view the installed JDK offers keeps working.
Rendering a named view straight from a `.cjfr` is also faster than opening the
original `.jfr` (measured ~2–3× on a 253 MB `gc_details` recording), since only
the event types the view needs are read.

`--truncate` accepts `beginning` (or `begin`) to keep the end of long cell values,
or `end` (default) to keep the beginning. For fully-qualified class names in stack
traces, `beginning` is usually more useful.

---

## `print` output and formatting

`cjfr print` is a drop-in for the JDK `jfr print` command and renders events
in the same text format:

```shell
# Print all events
cjfr print recording.cjfr

# Filter by event type or glob pattern
cjfr print --events GCPhaseParallel recording.cjfr
cjfr print --events "jdk.GC*" recording.cjfr
cjfr print --events "CPULoad,GCHeapSummary" recording.cjfr

# Filter by JFR category annotation (comma = OR, globs supported)
cjfr print --categories GC recording.cjfr
cjfr print --categories "GC,Profiling" recording.cjfr

# Full-precision output: nanosecond timestamps, raw bytes, exact floats
cjfr print --exact recording.cjfr

# Limit stack trace depth
cjfr print --stack-depth 5 recording.cjfr

# JSON output
cjfr print --json recording.cjfr

# Also works on raw .jfr files
cjfr print recording.jfr
```

---

## `--no-reconstitution`

The `reduced` condenser config combines some event types into buckets
(e.g., many `ObjectAllocationSample` events become a single aggregated entry).
By default, `summary` and `view` expand these back into approximate individual
events. Pass `--no-reconstitution` to skip expansion and read the raw combined
events directly. Useful when you want aggregate metrics rather than reconstructed
individual events.

```shell
cjfr summary --no-reconstitution recording.cjfr
```

This has no effect on files produced with `lossless` or `default`.

---

## Integrity checks

`cjfr inflate` and `cjfr summary` verify a whole-file CRC32 (stored in the file's
footer) before reading. If a `.cjfr` file has been corrupted — silent bit-rot,
a truncated copy, a bad transfer — the command aborts with an integrity error
rather than producing garbage output.

Verification only runs for direct file inputs. It is skipped (and noted) for
inputs that cannot be re-read, such as a `.cjfr` entry inside a ZIP.

If a file is corrupted but you still want to salvage what you can, bypass the
check:

```shell
cjfr summary --ignore-integrity recording.cjfr
cjfr inflate --ignore-integrity recording.cjfr out.jfr
```

Older files written before the CRC feature carry no checksum and are read without
a warning.
