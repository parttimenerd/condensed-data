---
title: Common Workflows
---

# Common Workflows

Practical recipes for the most common `cjfr` use cases.
Each workflow is self-contained — copy the commands, substitute your paths.

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

> Single-quote the path to prevent shell expansion of `$index`.

To verify it is running:

```shell
cjfr agent myapp status
```

---

## Recording on a server, analysing locally

The typical fleet setup: record with the agent on each server, copy `.cjfr`
files to a workstation, then `summary`/`inflate` without touching the server again.

```
server                          local machine
──────────────────────────      ──────────────────────────────────────
agent → .cjfr               →→  scp/rsync
                             →→  cjfr summary (no inflate needed)
                             →→  cjfr inflate → .jfr → JDK Mission Control
```

The universal JAR, platform JAR, and inflaterless JAR all use LZ4FRAMED by
default — any of them can be used as the agent, and the output is readable by
the full JAR on your workstation. The inflaterless JAR cannot run `inflate`
but is otherwise identical for recording.

**On the server** (use the smallest JAR that fits your deployment):

```shell
# Platform-inflaterless JAR: ~1.5 MB, no inflate capability, minimal deps
# Download the right platform JAR for your server OS/arch from GitHub Releases
curl -L -o cjfr.jar \
  https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data-linux-amd64-inflaterless.jar

# Start rotating recording (~130 MB/hr for gc_details-heavy workloads)
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-files=10,--max-size=100m' \
     -jar myapp.jar
```

**Copy to local machine:**

```shell
scp server:/var/rec/app_*.cjfr ./recordings/
```

**Analyse locally** without inflating (fast, no JMC needed):

```shell
# Summary across all files
cjfr summary recordings/app_0.cjfr -i recordings/app_1.cjfr -i recordings/app_2.cjfr

# Check the worst GC pauses
cjfr summary --gc-percentile=90 recordings/app_0.cjfr \
  -i recordings/app_1.cjfr -i recordings/app_2.cjfr
```

**Inflate to JFR for JDK Mission Control** (needs the full JAR locally):

```shell
# Merge all files into one JFR
cjfr inflate -i recordings/app_1.cjfr -i recordings/app_2.cjfr \
  recordings/app_0.cjfr full-recording.jfr

# Or just a specific time window
cjfr inflate --start="2024-05-24 08:00:00" --duration=1h \
  recordings/app_0.cjfr -i recordings/app_1.cjfr -i recordings/app_2.cjfr \
  last-hour.jfr
```

---

## Inflating multiple files, keeping only a time range

A common post-incident workflow: you have several rotation files spanning many hours,
and you want a single JFR covering just the 30 minutes around the incident.

```shell
# Step 1: find out when the recording starts
cjfr summary --short app_0.cjfr -i app_1.cjfr -i app_2.cjfr

# Step 2: extract the window across all files into a single JFR
cjfr inflate \
  --start="2024-05-24 14:25:00" --duration=30m \
  app_0.cjfr -i app_1.cjfr -i app_2.cjfr \
  incident.jfr

# Step 3: open in JDK Mission Control
jmc incident.jfr
```

The time-range filter is applied across the merged file set — you don't need to know
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

Produce a minimal JFR containing just what you need for GC analysis — much
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
cjfr summary app_0.cjfr -i app_1.cjfr -i app_2.cjfr -i app_3.cjfr

# Shell glob shorthand (bash/zsh)
files=(app_*.cjfr)
extra_args=()
for f in "${files[@]:1}"; do extra_args+=(-i "$f"); done
cjfr summary "${files[0]}" "${extra_args[@]}"

# Merge into a single .cjfr for long-term archival
cjfr condense --force -i app_1.cjfr -i app_2.cjfr app_0.cjfr archive.cjfr
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

# Step 3: re-condense the window if you want to keep it in .cjfr format
cjfr condense --condenser-config=reasonable-default window.jfr window.cjfr
```

---

## Condensing a folder of JFR files

`condense` accepts a directory path and merges all `.jfr` files it finds into
a single `.cjfr` alongside the folder:

```shell
cjfr condense /path/to/jfr-folder/
# produces /path/to/jfr-folder.cjfr

cjfr condense --condenser-config=reasonable-default /path/to/jfr-folder/
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

You can tune rotation settings while the agent is recording — no restart needed:

```shell
cjfr agent myapp set-max-files 20      # expand ring buffer after disk expansion
cjfr agent myapp set-max-size 200m     # grow per-file cap
cjfr agent myapp set-max-duration 15m  # change rotation interval
cjfr agent myapp set-duration 4h       # cap total recording length
```

---

## Reducing JAR size for a specific platform

The universal JAR bundles native LZ4 libraries for all platforms. For a
production deployment on a known OS/architecture, strip it down:

```shell
# See what platforms are available
python3 reduce-jar.py reduce cjfr.jar --list-platforms

# Linux/amd64-only JAR (~2 MB platform)
python3 reduce-jar.py reduce cjfr.jar cjfr-linux.jar --platform linux/amd64

# Same, but also strip the inflate/JMC-writer code (~450 KB with compression)
# Note: inflaterless JAR cannot run cjfr inflate, but is otherwise identical for recording
python3 reduce-jar.py reduce cjfr.jar cjfr-linux-minimal.jar \
  --platform linux/amd64 --without-jmc
```

Pre-built variants are on [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest).
See [JAR Release Selection](jar-releases.md) for the full comparison.
