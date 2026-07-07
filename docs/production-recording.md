---
title: Production Recording
layout: default
nav_order: 5
---

# Production Recording Guide

This guide covers operating the cjfr Java agent in long-running production
services: rotating files, live tuning, storage sizing, and common patterns.

## Starting a Continuous Recording

### At JVM startup

```shell
java -javaagent:/opt/cjfr/cjfr.jar='start,/var/recordings/app_$index.cjfr,--rotating,--max-files=10,--max-size=100m' \
     -jar myapp.jar
```

### Attaching to a running process

```shell
# by main-class substring (case-insensitive)
cjfr agent myapp start '/var/recordings/app_$index.cjfr' --rotating --max-files=10 --max-size=100m

# by PID
cjfr agent 12345 start '/var/recordings/app_$index.cjfr' --rotating --max-files=10 --max-size=100m

# all discovered JVMs at once
cjfr agent all start '/var/recordings/$index.cjfr' --rotating --max-files=5 --max-size=50m
```

> **Single-quote the path** when using `$index` or `$date` in shell to prevent variable expansion:
> `'/var/recordings/app_$index.cjfr'` not `"/var/recordings/app_$index.cjfr"`.

---

## Rotation Knobs

| Flag | Default | Description |
|---|---|---|
| `--rotating` | off | Enable file rotation. Requires `--max-size` or `--max-duration` (or both). |
| `--max-size=<size>` | 0 (unlimited) | Max size per individual file. Rotate when reached. Minimum 1024 bytes. Examples: `50m`, `200m`, `1g`. |
| `--max-duration=<time>` | 0 (unlimited) | Max wall-clock duration per individual file. Rotate when reached. Minimum 1 ms. Examples: `5m`, `1h`. |
| `--max-files=<n>` | 10 | Max number of files kept. Oldest is evicted once limit is reached. Must be ≥ 1 when rotating. |
| `--new-names` | off | If off (default): oldest file is **overwritten** — on-disk names are stable. If on: each rotation creates a new name; oldest file is **deleted** when limit reached. |
| `--duration=<time>` | 0 (unlimited) | Total cap on the whole recording (not per-file). Recording stops after this. Does not require `--rotating`. |

### `--rotating` validation rules
- At least one of `--max-size` or `--max-duration` must be non-zero.
- `--max-duration` without `--rotating` is rejected.
- `--max-size` without `--rotating` is rejected.
- Setting both `--max-size=0` and `--max-duration=0` while rotating is rejected.

### Path placeholders

When `--rotating` is set, the output path should contain a placeholder:

| Placeholder | Replaced with |
|---|---|
| `$index` | Monotonically increasing integer (0, 1, 2, …) |
| `$date` | Timestamp when the file was opened (`YYYY-MM-DD_HH-MM-SS-mmm`, UTC) |

If neither placeholder appears in the path, `.cjfr` is automatically replaced
with `_$index.cjfr` (e.g. `recording.cjfr` → `recording_0.cjfr`, `recording_1.cjfr`, …).

### `--new-names` vs. default (name reuse)

**Default (name reuse):** Files are named `app_0.cjfr`, `app_1.cjfr`, …, `app_9.cjfr`
(for `--max-files=10`). Once all 10 slots are used, file `app_0.cjfr` is **overwritten**
on the next rotation. Disk usage is bounded to exactly `max-files × max-size`.
Log-shippers that watch by filename will see the file change in-place.

**`--new-names`:** Every rotation generates a new name (`app_0.cjfr`, `app_1.cjfr`,
`app_11.cjfr`, …). When `max-files` is reached, the *oldest* file is deleted.
Names are never reused. Log-shippers watching by inode handle this correctly,
but the file-name set grows until `max-files` cap is hit.

---

## Live Tuning a Running Recording

Limits can be changed while recording is active:

```shell
# Increase file count (useful after a disk expansion)
cjfr agent myapp set-max-files 20

# Shrink per-file size cap
cjfr agent myapp set-max-size 50m

# Change per-file duration cap
cjfr agent myapp set-max-duration 15m

# Set or shorten the total recording duration
cjfr agent myapp set-duration 4h
```

**Constraints:** `set-max-files` must be ≥ 1 when rotating. Setting both size and
duration to zero while rotating is rejected. These commands surface any validation
error immediately in the CLI output.

**Not tunable at runtime:** condenser config (`--condenser-config`) and JFR config
(`--config`) are fixed at recording start and cannot be changed.

---

## Common Production Recipes

### 1. Rolling hot buffer — bounded disk usage

Keep the last 500 MB of activity at all times. Names are stable (good for fixed-path shippers):

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-files=5,--max-size=100m' \
     -jar myapp.jar
```

Disk usage: ≤ 500 MB at all times. Oldest file is overwritten in-place on each rotation.

### 2. Time-sliced archive — one file per hour, 24 h retention

Use `--new-names` so each file gets a unique timestamp and can be shipped independently:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$date.cjfr,--rotating,--max-duration=1h,--max-files=24,--new-names' \
     -jar myapp.jar
```

### 3. Fixed-time single-file capture

For a 30-minute snapshot. No rotation needed:

```shell
cjfr agent myapp start /tmp/snapshot.cjfr --duration=30m
```

Or at startup:

```shell
java -javaagent:cjfr.jar='start,/tmp/snapshot.cjfr,--duration=30m' -jar myapp.jar
```

### 4. Combined: total cap with time-sliced rotation

Record for 1 hour total, 10-minute slices, keep at most 6 files:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-duration=10m,--max-files=6,--duration=1h' \
     -jar myapp.jar
```

### 5. Maximum compression for high-volume fleet

Smallest possible files for a fleet of busy services:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-files=10,--max-size=50m,--condenser-config=reduced-default' \
     -jar myapp.jar
```

Use the `platform-inflaterless-minimal` JAR (~450 KB) for the smallest possible
agent footprint. The `.cjfr` files are still readable by any full-size JAR offline.

---

## Checking Recording Status

```shell
cjfr agent myapp status
```

Returns a table showing: config, jfr-config, start time, elapsed time, current file
size (compressed and uncompressed), number of files, whether rotation is active,
and event-error count.

```shell
# Stop a running recording cleanly
cjfr agent myapp stop
```

---

## Storage Sizing

These figures are from benchmarks on renaissance workloads. Real-world recordings
vary — CPU-bound apps (less GC) will be at the low end; GC-heavy apps at the high end.

| Condenser config | Approx. size per hour (busy GC-details workload) |
|---|---|
| `default` | 40–80 MB/hour |
| `reasonable-default` (agent default) | 20–40 MB/hour |
| `reduced-default` | 4–10 MB/hour |

*These are based on a 1800s renaissance benchmark producing ~241 MB of JFR. Actual
sizes depend on event density (GC frequency, thread count, allocation rate). Low-GC
workloads will produce significantly smaller files.*

---

## Tuning JFR Event Coverage

The condenser config controls event *reduction*. The JFR configuration controls
which events are *captured*. Two distinct flags:

| Flag | Controls |
|---|---|
| `--condenser-config` | How aggressively events are reduced/combined |
| `--config` (or `-c`) | Which JFR event set to capture (`default`, `profile`, or a custom .jfc path) |

To use JFR's `profile` config (more events, higher overhead) with cjfr's `reasonable-default` reduction:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app.cjfr,--config=profile,--condenser-config=reasonable-default' \
     -jar myapp.jar
```

To override specific JFR event intervals (e.g. reduce CPU sample frequency):

```shell
java -javaagent:cjfr.jar='start,/var/rec/app.cjfr,--misc-jfr-config=jfr.ExecutionSample#interval=100ms' \
     -jar myapp.jar
```

`--misc-jfr-config` takes `|`-separated `EventName#setting=value` pairs:

```shell
--misc-jfr-config='jfr.ExecutionSample#interval=100ms|jfr.ObjectAllocationSample#throttle=100/s'
```
