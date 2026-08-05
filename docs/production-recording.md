---
title: Production Recording
---

# Production Recording Guide

This guide covers operating the cjfr Java agent in long-running production
services: rotating files, live tuning, storage sizing, and common patterns
for continuous GC profiling.

## Starting a Continuous Recording

### At JVM startup

```shell
java -javaagent:/opt/cjfr/cjfr.jar='start,/var/recordings/app_$index.cjfr,rotating,max-files=10,max-size=100m' \
     -jar myapp.jar
```

!!! tip "Container and environment-variable deployments"
    In Docker containers or anywhere you can't modify the JVM command line directly,
    set `JAVA_TOOL_OPTIONS` instead:
    ```
    JAVA_TOOL_OPTIONS=-javaagent:/opt/cjfr/cjfr.jar=start,/var/rec/app_$index.cjfr,rotating,max-files=10,max-size=100m
    ```
    The JVM parses `JAVA_TOOL_OPTIONS` itself; no shell quoting needed for `$index`.
    See [Container & Sidecar Deployment](cookbook-container.md) for a full Docker example.

### Attaching to a running process

```shell
# by main-class substring (case-insensitive)
cjfr agent myapp start '/var/recordings/app_$index.cjfr' --rotating --max-files=10 --max-size=100m

# by PID
cjfr agent 12345 start '/var/recordings/app_$index.cjfr' --rotating --max-files=10 --max-size=100m

# all discovered JVMs at once
cjfr agent all start '/var/recordings/$index.cjfr' --rotating --max-files=5 --max-size=50m
```

!!! warning "Single-quote the path"
    When the output path contains `$index` or `$date`, always **single-quote** it
    in shell to prevent expansion: `'/var/recordings/app_$index.cjfr'`, not
    `"/var/recordings/app_$index.cjfr"`.

---

## Rotation Knobs

These flags control file rotation. When used in a `-javaagent:` string the dashes are optional
(`rotating`, `max-size=100m`); when passed to the `cjfr agent` CLI they take the standard `--` form
(`--rotating`, `--max-size=100m`).

| Flag | Default | Description |
|---|---|---|
| `rotating` | off | Enable file rotation. Requires `max-size` or `max-duration` (or both). |
| `max-size=<size>` | 0 (unlimited) | Max size per individual file. Rotate when reached. Minimum 1024 bytes. Examples: `50m`, `200m`, `1g`. |
| `max-duration=<time>` | 0 (unlimited) | Max wall-clock duration per individual file. Rotate when reached. Minimum 1 ms. Examples: `5m`, `1h`. |
| `max-files=<n>` | 10 | Max number of files kept. Oldest is evicted once limit is reached. Must be ≥ 1 when rotating. |
| `new-names` | off | If off (default): oldest file is **overwritten**; on-disk names are stable. If on: each rotation creates a new name; oldest file is **deleted** when limit reached. |
| `duration=<time>` | 0 (unlimited) | Total cap on the whole recording (not per-file). Recording stops after this. Does not require `rotating`. |

### `rotating` validation rules
- At least one of `max-size` or `max-duration` must be non-zero.
- `max-duration` without `rotating` is rejected.
- `max-size` without `rotating` is rejected.
- Setting both `max-size=0` and `max-duration=0` while rotating is rejected.

### Path placeholders

When `rotating` is set, the output path should contain a placeholder:

| Placeholder | Replaced with |
|---|---|
| `$index` | Monotonically increasing integer (0, 1, 2, …) |
| `$date` | Timestamp when the file was opened (`YYYY-MM-DD_HH-MM-SS-mmm`, UTC) |

If neither placeholder appears in the path, `.cjfr` is automatically replaced
with `_$index.cjfr` (e.g. `recording.cjfr` → `recording_0.cjfr`, `recording_1.cjfr`, …).

### `new-names` vs. default (name reuse)

**Default (name reuse):** Files are named `app_0.cjfr`, `app_1.cjfr`, …, `app_9.cjfr`
(for `max-files=10`). Once all 10 slots are used, file `app_0.cjfr` is **overwritten**
on the next rotation. Disk usage is bounded to exactly `max-files × max-size`.
Log-shippers that watch by filename will see the file change in-place.

**`new-names`:** Every rotation generates a new name (`app_0.cjfr`, `app_1.cjfr`,
`app_2.cjfr`, …). When `max-files` is reached, the *oldest* file is deleted.
Names are never reused. Log-shippers watching by inode handle this correctly,
but the file-name set grows until `max-files` cap is hit.

**Choosing for log-shippers:** Use `--new-names` with `$date` in the path if you
are shipping files with Filebeat, Fluentd, or similar tools that track files by
inode. These tools reliably ingest completed files before they are deleted, as long
as the shipper is faster than your rotation interval. For fixed-path setups where
the shipper reads by filename, use the default (name reuse) mode and ensure
`max-files × max-size` is large enough to cover upload delays.

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

### 1. Rolling hot buffer; bounded disk usage

Keep the last 500 MB of activity at all times. Names are stable (good for fixed-path shippers):

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,rotating,max-files=5,max-size=100m' \
     -jar myapp.jar
```

Disk usage: ≤ 500 MB at all times. Oldest file is overwritten in-place on each rotation.

### 2. Time-sliced archive; one file per hour, 24 h retention

Use `new-names` so each file gets a unique timestamp and can be shipped independently:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$date.cjfr,rotating,max-duration=1h,max-files=24,new-names' \
     -jar myapp.jar
```

### 3. Fixed-time single-file capture

For a 30-minute snapshot. No rotation needed:

```shell
cjfr agent myapp start /tmp/snapshot.cjfr --duration=30m
```

Or at startup:

```shell
java -javaagent:cjfr.jar='start,/tmp/snapshot.cjfr,duration=30m' -jar myapp.jar
```

### 4. Combined: total cap with time-sliced rotation

Record for 1 hour total, 10-minute slices, keep at most 6 files:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,rotating,max-duration=10m,max-files=6,duration=1h' \
     -jar myapp.jar
```

### 5. Maximum compression for high-volume fleet

Smallest possible files for a fleet of busy services:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,rotating,max-files=10,max-size=50m,condenser-config=reduced' \
     -jar myapp.jar
```

Use the `platform-inflaterless-minimal` JAR (~450 KB) for the smallest possible
agent footprint. The `.cjfr` files are still readable by any full-size JAR offline.

!!! tip "Squeeze further at archival time"
    The agent records with fast compression to keep write overhead low. When you
    later move recordings to cold storage, re-condense them offline for the
    smallest files:

    ```shell
    cjfr condense --condenser-config reduced --compression-level MAX_COMPRESSION app.cjfr archive.cjfr
    ```

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

These figures are the **output `.cjfr` file size**, not the input JFR size.
Measured on renaissance gc_details benchmarks with LZ4FRAMED compression (the default).
Actual sizes depend heavily on workload type; sparse gc-only profiles produce much smaller files.

| Condenser config | `.cjfr` output MB/hour (gc_details-heavy) | `.cjfr` output MB/hour (gc-only sparse) |
|---|---|---|
| `lossless` | ~300 MB/hour | ~25 MB/hour |
| `default` (agent default) | ~130 MB/hour | ~10 MB/hour |
| `reduced` | ~70 MB/hour | ~6 MB/hour |

*Based on a 7m52s renaissance benchmark where the equivalent raw JFR was ~242 MB (~1.8 GB/hour).
For gc-only sparse profiles (renaissance-all_gc_G1.jfr, 29 MB input), output is roughly 10% of the gc_details rate.
Actual results depend on GC frequency, thread count, and allocation rate.*

---

## Tuning JFR Event Coverage

The condenser config controls event *reduction*. The JFR configuration controls
which events are *captured* and at what overhead. These are independent: the
condenser reduces whatever JFR captured; it cannot add events that JFR didn't record.

| Flag | Controls |
|---|---|
| `condenser-config` | How aggressively events are reduced/combined |
| `config` | Which JFR event set to capture (`default`, `profile`, or a custom .jfc path); also controls runtime overhead |

To use JFR's `profile` config (more events, higher overhead — CPU samples, allocation events) with cjfr's `default` reduction:

```shell
java -javaagent:cjfr.jar='start,/var/rec/app.cjfr,config=profile,condenser-config=default' \
     -jar myapp.jar
```

To override specific JFR event intervals (e.g. reduce CPU sample frequency):

```shell
java -javaagent:cjfr.jar='start,/var/rec/app.cjfr,misc-jfr-config=jfr.ExecutionSample#interval=100ms' \
     -jar myapp.jar
```

`misc-jfr-config` takes `|`-separated `EventName#setting=value` pairs:

```shell
misc-jfr-config='jfr.ExecutionSample#interval=100ms|jfr.ObjectAllocationSample#throttle=100/s'
```

---

## GC-Log Replacement Mode

Replace `-Xlog:gc*` unified GC logging with a CJFR recording at near-zero overhead. The `gc-log` preset captures the equivalent of `gc+heap+cpu+metaspace+ref+phases+promotion+ergo+age` at a fraction of the storage cost — and adds structured data, nanosecond timestamps, and ambient system context that the GC log cannot provide.

### Quick start

```shell
java -javaagent:cjfr.jar='start,/var/rec/gc_$index.cjfr,rotating,max-files=24,max-duration=1h,config=gc-log,condenser-config=gc-log' \
     -jar myapp.jar
```

### What it captures

**GC events (all collectors):** GarbageCollection, GCPhasePause, GCPhaseConcurrent and sub-phases, GCHeapSummary, GCCPUTime, MetaspaceSummary, GCReferenceStatistics, PromotionFailed, ConcurrentModeFailure.

**Collector-specific:**
- G1GC: G1GarbageCollection, G1HeapSummary, TenuringDistribution, G1MMU, G1BasicIHOP, G1AdaptiveIHOP, EvacuationInformation, EvacuationFailed, G1EvacuationYoung/OldStatistics
- ZGC: ZYoungGarbageCollection, ZOldGarbageCollection, ZAllocationStall, ZPageAllocation, ZRelocationSet, ZRelocationSetGroup, ZUncommit
- Shenandoah: ShenandoahHeapRegionInformation (sampled, everyChunk)
- Parallel GC: PSHeapSummary
- String deduplication (G1/Parallel, when `-XX:+UseStringDeduplication`): StringDeduplication — maps to `gc+stringdedup=info`

**Ambient context:** CPULoad (1 s), PhysicalMemory, ResidentSetSize, SwapSpace, OSInformation, CPUInformation, VirtualizationInformation, ContainerConfiguration/CPUUsage/CPUThrottling/MemoryUsage/IOUsage (30 s), JVM flags (all 7 primitive flag types + change events), NativeMemoryUsage/Total (1 s), DirectBufferStatistics, FinalizerStatistics, GCLocker (≥1 s), CodeCacheFull, ThreadContextSwitchRate (10 s), ExecuteVMOperation (≥10 ms).

**Not captured** (no JFR events exist for these GC log tags): `gc+refine`, `gc+remset`, `gc+humongous` summary counts, ZGC `gc+mmu`. These are confirmed gaps — see the gc-log research notes for proposed upstream JFR events that would close them.

### Storage estimates

Measured on macOS (GraalVM JDK 25, 256 MB heap, high-allocation-rate workload, 60 s run → extrapolated):

| Config | G1GC MB/hour | ZGC MB/hour | Notes |
|---|---|---|---|
| `gc-log.jfc` + `gc-log` condenser | **~32 MB/hr** | **~15 MB/hr** | Recommended combination |
| `gc-log.jfc` + `lossless` condenser | ~35 MB/hr | ~17 MB/hr | All GC data preserved verbatim |
| `-Xlog:gc*` text | ~115 MB/hr | ~282 MB/hr | No structured access, no compression |
| `default.jfc` + `default` condenser | ~180 MB/hr | ~90 MB/hr | Full profiling events included |

*Workload: constant 32KB allocation at high rate (production GC rates are typically 10–100× lower). For GC-sparse profiles the gc-log preset reaches < 2 MB/hr. ZGC text logs are especially large because each GC emits many structured relocation-set and heap-summary lines that are verbose as text but compress as structured events.*

The `gc-log` CJFR output is **72% smaller than `-Xlog:gc*` text for G1GC** and **95% smaller for ZGC** at the same workload — and supports random-access structured queries; text logs do not.

### Why JFR over `-Xlog:gc*`

| What you need | `-Xlog:gc*` | cjfr `gc-log` preset |
|---|---|---|
| GC pause times (ns precision) | ms only | **ns** |
| Heap before/after each GC | yes | yes |
| GC type, cause, and collector | yes | yes |
| Concurrent phase timings | yes | yes |
| G1 IHOP decisions | yes (info level) | yes + model state |
| G1 MMU | yes | yes |
| CPU time per GC (user/sys/real) | yes | yes |
| Metaspace before/after | yes | yes |
| Tenuring distribution (age buckets) | requires `-Xlog:gc+age=debug` | yes |
| G1 pause sub-phases (level 1+2) | `-Xlog:gc+phases=debug` | yes |
| G1 evacuation statistics | partial (ergo level) | yes (structured fields) |
| ZGC relocation set breakdown | yes (large text tables) | yes (structured, 95% smaller) |
| JIT code cache overflow (affects pauses) | no | **yes** (CodeCacheFull) |
| OS context switch rate (pause spikes) | no | **yes** (ThreadContextSwitchRate) |
| Container CPU/memory limits | no | **yes** |
| JVM flag set at startup | no | **yes** |
| Overall JVM CPU load (1 s) | no | **yes** |
| Resident set size / swap pressure | no | **yes** |
| Bounded rotating files (compressed) | separate logrotate | **built-in** |
| Structured query (field access by name) | grep/regex only | **yes** |
| Crash recovery (chunked writes) | may truncate | **yes** |
| G1 refinement thread activity | `-Xlog:gc+refine=debug` (high-volume text) | **gap** — no JFR event |
| String deduplication stats | `-Xlog:gc+stringdedup=info` (separate subsystem) | **yes** (StringDeduplication event; requires `-XX:+UseStringDeduplication`) |
| Humongous reclaim counts | `-Xlog:gc+humongous=debug` | **gap** — no JFR event |

