---
title: Common Workflows
---

# Common Workflows

Practical recipes for the most common `cjfr` use cases.
Each workflow is self-contained; copy the commands, substitute your paths.

---

## Continuous GC recording on a server

The most common production setup: a rolling window of GC data with bounded
disk usage.

```shell
# Keep last 5 × 100 MB ≈ 500 MB on disk at all times
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-files=5,--max-size=100m' \
     -jar myapp.jar
```

Or attach to an already-running process:

```shell
cjfr agent myapp start '/var/rec/app_$index.cjfr' --rotating --max-files=5 --max-size=100m
```

!!! warning "Single-quote the path"
    When the output path contains `$index` or `$date`, always **single-quote** it
    in shell to prevent expansion: `'/var/rec/app_$index.cjfr'`, not
    `"/var/rec/app_$index.cjfr"`.

To verify it is running:

```shell
cjfr agent myapp status
```

---

## Recording on a server, analysing locally

For the full fleet recording workflow; server-side agent setup, transferring files,
and running offline analysis; see [Production Recording](production-recording.md)
and the [Fleet-Wide Monitoring Cookbook](cookbook-fleet-monitoring.md).

---

## Inflating multiple files, keeping only a time range

A common post-incident workflow: you have several rotation files spanning many hours,
and you want a single JFR covering just the 30 minutes around the incident.

```shell
# Step 1: find out when the recording starts
cjfr summary --short app_0.cjfr app_1.cjfr app_2.cjfr

# Step 2: extract the window across all files into a single JFR
cjfr inflate \
  --start="2024-05-24 14:25:00" --duration=30m \
  app_0.cjfr app_1.cjfr app_2.cjfr \
  incident.jfr

# Step 3: open in a JFR viewer; JDK Mission Control, Firefox Profiler, or jfr-query
jmc incident.jfr
```

The time-range filter is applied across the merged file set; you don't need to know
which rotation file contains the incident window.

---

## Producing a JFR slice (time range)

Extract a specific window from a recording without loading the whole file into
Mission Control.

```shell
# 5-minute slice around an incident at 14:32
cjfr inflate --start="2024-05-24 14:30:00" --duration=5m recording.cjfr incident.jfr

# First 10 minutes of a recording
cjfr inflate --start="2024-05-24 12:06:42" --duration=10m recording.cjfr warmup.jfr
```

Timestamps must include a date. Use `cjfr summary --short` to find the recording's
start time, then build your window from there.

---

## Extracting only GC events

Produce a minimal JFR containing just what you need for GC analysis; much
smaller than a full inflation, and faster to open in Mission Control.

```shell
# Inflate to a GC-only JFR
cjfr inflate \
  --events=jdk.GarbageCollection,jdk.GCHeapSummary,jdk.G1HeapSummary,\
jdk.MetaspaceSummary,jdk.GCPhasePause,jdk.TenuringDistribution,\
jdk.GCReferenceStatistics,jdk.GCCPUTime \
  recording.cjfr gc-only.jfr

# Quick GC summary without inflating at all
cjfr summary recording.cjfr

# Zoom into the worst pause spikes and the 1-minute context around them
cjfr summary --gc-percentile=95 recording.cjfr
cjfr inflate --gc-percentile=95 recording.cjfr worst-pauses.jfr
```

---

## Merging multiple rotating files

When a recording spans several rotation files, combine them for analysis:

```shell
# Summarise the whole day
cjfr summary app_0.cjfr app_1.cjfr app_2.cjfr app_3.cjfr

# Or use a glob; the shell expands it
cjfr summary app_*.cjfr

# Merge into a single .jfr for tooling that reads JFR directly
cjfr inflate app_0.cjfr app_1.cjfr app_2.cjfr archive.jfr
```

---

## Keeping only a time range from a long recording

A long continuous recording only has a narrow interesting window. Extract it
and delete the rest:

```shell
# Step 1: find the start time
cjfr summary --short recording.cjfr

# Step 2: extract the 30-minute window around the event
cjfr inflate --start="2024-05-24 14:25:00" --duration=30m recording.cjfr window.jfr

# Step 3 (optional): re-condense back to .cjfr for long-term storage
cjfr condense --condenser-config=reasonable-default window.jfr window.cjfr
```

!!! warning "Re-condensing an inflated recording loses additional precision"
    If the original `.cjfr` was recorded with `reasonable-default` (millisecond
    timestamps, 32-frame stacks), inflating it produces a `.jfr` that already
    reflects those reductions. Re-condensing that `.jfr` applies the same reductions
    again: no extra data is lost (there is nothing left to lose), but you do not
    recover what was already discarded. If you need the sliced window at full
    original precision, go back to the original `.cjfr` and slice it directly with
    `cjfr inflate --start=... --duration=...` without re-condensing.

---

## Condensing a folder of JFR files

`condense` accepts a directory path and merges all `.jfr` files it finds into
a single `.cjfr` alongside the folder:

```shell
cjfr condense recordings/
# produces recordings.cjfr

cjfr condense --condenser-config=reasonable-default recordings/

# Smallest files for cold storage (reduced-default + MAX_COMPRESSION):
cjfr condense --condenser-config=archival-max recordings/
```

---

## Condensing a ZIP archive

A ZIP containing `.jfr` files is treated the same as a folder:

```shell
cjfr condense recordings.zip output.cjfr
```

---

## Inspecting what is in a recording

```shell
# Full event count breakdown
cjfr summary recording.cjfr

# What takes up the most bytes?
cjfr summary --flamegraph storage.html recording.cjfr
open storage.html   # or xdg-open on Linux

# Show the first 20 full GC events
cjfr view --limit=20 recording.cjfr jdk.GarbageCollection

# Same, as JSON (pipe to jq for scripting)
cjfr view --json --limit=20 recording.cjfr jdk.GarbageCollection \
  | jq '.[] | {time: .startTime, cause: .cause, pause: .longestPause}'

# Look at heap size over time (all GC heap summaries)
cjfr view recording.cjfr jdk.GCHeapSummary
```

---

## Changing limits on a running recording

You can tune rotation settings while the agent is recording; no restart needed:

```shell
cjfr agent myapp set-max-files 20      # expand ring buffer after disk expansion
cjfr agent myapp set-max-size 200m     # grow per-file cap
cjfr agent myapp set-max-duration 15m  # change rotation interval
cjfr agent myapp set-duration 4h       # cap total recording length
```

---

## Choosing the right JAR for your platform

To select the smallest JAR that fits your deployment (platform-specific,
inflaterless, or minimal), see [JAR Release Selection](jar-releases.md).
