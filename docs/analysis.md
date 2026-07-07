---
title: Analyzing Recordings
layout: default
nav_order: 6
---

# Analyzing Recordings

`cjfr` queries `.cjfr` files directly — no inflation needed. All analysis
commands accept the same filtering flags, so you can zero in on the
30-second GC storm you care about before deciding whether to inflate the full recording.

## Commands at a glance

| Command | Purpose |
|---|---|
| `cjfr summary` | Aggregate stats: event counts, GC summary, allocation rate |
| `cjfr view <FILE> <EVENT>` | Tabular view of one event type |
| `cjfr inflate` | Convert to JFR for JDK Mission Control, async-profiler, etc. |

All three accept the **same filter flags** described below.

---

## Time-range filtering

Narrow the window you care about with `--start`, `--end`, or `--duration`.
Timestamps are in local time unless you include an explicit offset.

| Flag | Format | Example |
|---|---|---|
| `--start` | `yyyy-MM-dd HH:mm:ss` or ISO-8601 | `--start="2024-05-24 12:07:00"` |
| `--end` | same | `--end="2024-05-24 12:09:00"` |
| `--duration` | `1h30m`, `5m`, `30s`, `500ms` | `--duration=2m` |

Combine `--start` + `--end` **or** `--start` + `--duration`. Don't pass all three.
Use `cjfr summary --short recording.cjfr` to find the recording's start time.

```shell
# Summary of a 2-minute window
cjfr summary --start="2024-05-24 12:07:00" --duration=2m recording.cjfr

# View heap summaries in that window
cjfr view --start="2024-05-24 12:07:00" --end="2024-05-24 12:09:00" \
  recording.cjfr jdk.GCHeapSummary

# Inflate just that window for Mission Control
cjfr inflate --start="2024-05-24 12:07:00" --duration=2m \
  recording.cjfr slice.jfr
```

---

## GC percentile filter

Focus on the worst GC pauses — and the application activity that caused them.
`--gc-percentile=N` retains events from **N seconds before and after** any GC
whose pause duration is at or above the Nth percentile.

This is the fastest way to answer "what was my application doing around the worst
pauses?" without loading the full recording into Mission Control.

```shell
# Keep only events near the slowest 10% of GC pauses (≥ 90th percentile)
cjfr summary --gc-percentile=90 recording.cjfr

# Widen the context window to 2 minutes around each qualifying GC
cjfr summary --gc-percentile=90 --gc-percentile-context=2m recording.cjfr

# Inflate only those high-pause windows to JFR
cjfr inflate --gc-percentile=95 recording.cjfr pauses.jfr
```

`--gc-percentile-context` defaults to `1m`. A smaller value (e.g. `15s`) gives
tighter slices; a larger one (e.g. `5m`) captures longer allocation patterns that
build up before the pause.

---

## Event-type filtering

Pass `--events` to include only specific JFR event types. Accepts a
comma-separated list, and is repeatable.

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

---

## Working with multiple files

Pass `-i` to add more input files. All files are merged in time order,
which is the normal way to work with a rotating recording set.

```shell
# Summary across a whole day of rotating recordings
cjfr summary rec_0.cjfr -i rec_1.cjfr -i rec_2.cjfr

# Shell glob — first file is positional, rest via -i
cjfr summary rec_0.cjfr $(ls rec_*.cjfr | tail -n +2 | sed 's/^/-i /')

# Inflate multiple files into a single JFR
cjfr inflate -i rec_1.cjfr -i rec_2.cjfr rec_0.cjfr merged.jfr

# Combine multi-file with time range: extract 5-minute window across the set
cjfr inflate -i rec_1.cjfr --start="2024-05-24 03:00:00" --duration=5m \
  rec_0.cjfr window.jfr
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
CPU time — useful for understanding which event types dominate file size.

---

## `view` output and formatting

```shell
# Show all jdk.GarbageCollection events
cjfr view recording.cjfr jdk.GarbageCollection

# Limit to first 20
cjfr view --limit=20 recording.cjfr jdk.GarbageCollection

# Narrow terminal: truncate long values at the start of cells (keeps the end)
cjfr view --width=120 --truncate=beginning recording.cjfr jdk.GarbageCollection

# JSON output (suitable for piping to jq)
cjfr view --json recording.cjfr jdk.GarbageCollection | jq '.[] | .gcId'

# Combine with time range
cjfr view --start="2024-05-24 12:07:00" --duration=30s --limit=50 \
  recording.cjfr jdk.GCHeapSummary
```

`--truncate` accepts `beginning` (truncates the start of long values, keeps the
end) or `end` (default). For fully-qualified class names in stack traces,
`beginning` is usually more useful.

---

## `--no-reconstitution`

The `reduced-default` condenser config combines some event types into buckets
(e.g., many `ObjectAllocationSample` events become a single aggregated entry).
By default, `summary` and `view` expand these back into approximate individual
events ("reconstitution"). Pass `--no-reconstitution` to read the raw combined
events instead — faster, and useful when you want aggregate metrics directly.

```shell
cjfr summary --no-reconstitution recording.cjfr
```

This has no effect on files produced with `default` or `reasonable-default`.
