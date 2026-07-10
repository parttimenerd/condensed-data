---
title: Configuration Reference
---

# Configuration Reference

!!! success "Default is usually right"
    For 95% of production deployments, use `reasonable-default` (the agent default)
    with `LZ4FRAMED` compression (also the default). You do not need to change this.
    The rest of this page exists for the 5% who need tighter compression or full
    nanosecond fidelity.

## Two independent axes

`cjfr` has **two orthogonal knobs**, and it helps to keep them separate:

1. **Condenser config** (`--condenser-config`) — *what data is reduced.* This is a
   one-way, lossy transform applied while reading JFR events: dropping precision,
   combining events, trimming stacks. Reduced data cannot be recovered on inflation.
2. **Compression** (`--compression`, `--compression-level`) — *how the resulting
   byte stream is packed.* This is fully lossless and reversible; it only affects
   file size and CPU, never the data that comes back out.

You can mix any config with any compression. The condenser config is the bigger
size lever; compression on top is incremental.

## Condenser Configurations

The `--condenser-config` flag controls how aggressively JFR events are reduced.
All configurations produce valid `.cjfr` files. Loss is one-way: data reduced
during condensing cannot be recovered on inflation.

For GC profiling, `reasonable-default` is the right choice for almost all production
deployments; it preserves all GC pause durations, heap sizes, promotion data,
and allocation rates at millisecond precision, which is far more than enough for
GC tuning and capacity planning.

### `default` / `lossless`

Full fidelity. Only structurally redundant data is removed (no-op events like
GC region changes where nothing changed). Everything else is preserved verbatim.
`lossless` is an explicit alias for `default` — identical reductions, clearer
intent when you want to signal "keep everything" (e.g. paired with a stronger
compression level for archival).

**Sizes (with LZ4FRAMED, the default compression):** ~8–42% of the original JFR. Lower for gc_details-heavy recordings (~17% on G1 renaissance); higher for sparse gc-only profiles (~41%).

**Use when:**
- You need exact nanosecond timestamps
- You need source-file line numbers and bytecode index (BCI) in stack frames
- You need per-allocation object sizes in TLAB/PLAB events
- You need stacks deeper than 32 frames
- You are doing forensic analysis or benchmarking

**Defaults to:** `cjfr condense` CLI

**Preserved:** nanosecond timestamps, microsecond durations, full stack traces (unlimited depth), exact memory sizes (lossless integer), all allocation events, all exception events.

**Removed:** provably empty events (e.g. G1HeapRegionTypeChange events with no change).

---

### `reasonable-default`

Conservative lossy compression. Human-readable precision is fully preserved.
Loses sub-millisecond timestamp precision, BCI/line numbers in stacks, and very
short or zero-valued GC data.

**Sizes (with LZ4FRAMED, the default compression):** ~4–17% of the original JFR. Lower for gc_details-heavy recordings (~7% on G1 renaissance); higher for sparse gc-only profiles (~17%).

**Use when:**
- Production long-term storage and capacity planning
- GC tuning (pause durations, heap sizes, promotion data are all accurate)
- You want a ~2× size saving over `default` with minimal analytical impact

**Defaults to:** the Java agent (`-javaagent:cjfr.jar=start,...`)

**Changes from `default`:**

| Field | `default` | `reasonable-default` |
|---|---|---|
| Timestamp resolution | nanosecond | millisecond |
| Duration resolution | nanosecond | microsecond |
| Memory sizes | lossless integer | bfloat16 (~0.4% relative error, e.g. ±4 MB on a 1 GB heap) |
| Stack depth limit | unlimited | 32 frames |
| BCI / source line in stacks | yes | no |
| Zero-count tenured ages | included | dropped |
| Sub-threshold GC pauses | included | dropped |
| PLAB promotion events | per-event | combined |
| Unnecessary addresses | included | dropped |

*bfloat16: 16-bit brain floating-point format used for heap size fields. PLAB: Promotion Local Allocation Buffer; per-thread buffer used during young-to-old promotion in G1GC. BCI: bytecode index (position within a method's bytecode, used for precise source-line mapping).*

---

### `reduced-default`

Aggressive lossy compression. Suitable for bulk archival and fleet-wide
recordings where storage cost outweighs per-event granularity.

**Sizes (with LZ4FRAMED, the default compression):** ~1–11% of the original JFR. Lower for gc_details-heavy recordings (~4% on G1, ~1.4% on ZGC renaissance); higher for sparse gc-only profiles (~11%).

**Use when:**
- Fleet-wide continuous recording where storage is expensive
- You only need aggregate metrics (total GC time, pause percentiles, heap trend)
- You can tolerate losing per-allocation object sizes and per-exception events

**Changes from `reasonable-default`:**

| Field | `reasonable-default` | `reduced-default` |
|---|---|---|
| Stack depth limit | 32 frames | 16 frames |
| Type info in stack frames | yes | no |
| ObjectAllocationSample events | per-event | combined into buckets |
| TLAB/PLAB allocation sizes | per-event | summed |
| Exception events | per-event | combined (same exception class) |
| G1 heap region changes | per-event | combined |
| MonitorEnter / ThreadPark | per-event | combined |

*TLAB: Thread-Local Allocation Buffer; fast-path per-thread allocation region; TLAB/PLAB events record individual object allocation sizes. Combined means only the total bytes allocated per rotation interval is preserved.*

---

### Config Summary Table

`lossless` is an alias for `default` and is omitted from the columns below; it
behaves identically.

| Feature | `default` | `reasonable-default` | `reduced-default` |
|---|---|---|---|
| Size (% of JFR, with LZ4FRAMED) | 8–42% | 4–17% | 1–11% |
| Nanosecond timestamps | ✓ | ✗ (ms) | ✗ (ms) |
| Source line / BCI in stacks | ✓ | ✗ | ✗ |
| Stack depth | unlimited | 32 | 16 |
| Per-allocation object size | ✓ | ✓ | ✗ (summed) |
| Per-exception events | ✓ | ✓ | ✗ (combined) |
| GC pause durations | ✓ | ✓ | ✓ |
| Heap sizes | ✓ | ✓ (bfloat16) | ✓ (bfloat16) |
| Safe for `cjfr inflate` | ✓ | ✓* | ✓* |

*Inflated output reflects the reduced precision (e.g. ms-resolution timestamps,
no BCI). Inflation does not restore lost data; it only converts the format.

---

### Raw flag table

The boolean reduction flags behind each preset, generated from the code
(`Configuration.toFlagTable()`) and verified in `ConfigurationDocTest`:

<!-- BEGIN GENERATED FLAG TABLE -->
| flag | default | lossless | reasonable-default | reduced-default |
| --- | --- | --- | --- | --- |
| memoryAsBFloat16 | no | no | yes | yes |
| ignoreUnnecessaryEvents | yes | yes | yes | yes |
| useSpecificHashesAndRefs | yes | yes | yes | yes |
| combineEventsWithoutDataLoss | yes | yes | yes | yes |
| combinePLABPromotionEvents | no | no | yes | yes |
| combineObjectAllocationSampleEvents | no | no | no | yes |
| sumObjectSizes | no | no | no | yes |
| ignoreZeroSizedTenuredAges | no | no | yes | yes |
| ignoreTooShortGCPauses | no | no | yes | yes |
| removeBCIAndLineNumberFromStackFrames | no | no | yes | yes |
| removeTypeInformationFromStackFrames | no | no | no | yes |
| removeUnnecessaryAddresses | no | no | yes | yes |
| combineExceptionEvents | no | no | no | yes |
| combineG1HeapRegionTypeChangeEvents | no | no | no | yes |
| combineBlockingEvents | no | no | no | yes |
<!-- END GENERATED FLAG TABLE -->

---

## Compression Algorithms

The `--compression` flag selects the inner byte-stream compression. This is
independent of event reduction above.

```
cjfr condense --compression=<value> recording.jfr
```

Accepted values: `NONE`, `GZIP`, `LZ4FRAMED`.

!!! note
    The `.cjfr` format reserves space for additional algorithms, but only
    `NONE`, `GZIP`, and `LZ4FRAMED` are implemented. Passing any other value is
    rejected by the CLI.

| Value | Speed (write) | Speed (read) | Ratio | Use case |
|---|---|---|---|---|
| `LZ4FRAMED` (default) | Very fast | Very fast | Good | Agent recording, streaming, frequent reads |
| `GZIP` | Slow | Moderate | Good | Long-term archive; compatible with standard gzip tooling |
| `NONE` | Instant | Instant | None | Benchmarking the condenser itself; transport with built-in compression |

`LZ4FRAMED` uses block-independent framing: each block can be decompressed
independently. This makes recordings resilient to partial file corruption and
allows streaming reads without buffering the entire file.

### Compression level

`--compression-level` selects how hard the compressor works:
`FAST`, `MEDIUM`, `HIGH_COMPRESSION` (default), `MAX_COMPRESSION`. Higher levels
trade write CPU for a smaller file; they do not change the data or the read path.
The chosen level is recorded in the file's start header, so tools can report it.

### `archival-max` shortcut

`--condenser-config archival-max` is a CLI-only convenience that expands to the
`reduced-default` data reductions **plus** `MAX_COMPRESSION`. It is not a distinct
on-disk configuration — the file records `reduced-default` as its config name and
`MAX_COMPRESSION` as its compression level. Use it for cold, long-term archives
where write time is irrelevant and every byte counts:

```
cjfr condense --condenser-config archival-max recording.jfr archive.cjfr
```

### Combining config and compression

Compression and condenser config are independent knobs. The biggest size reduction
comes from choosing the condenser config; compression on top is incremental.

| Config + Algorithm | Approx. % of original JFR |
|---|---|
| `default` + `LZ4FRAMED` | 8–42% |
| `default` + `GZIP` | 7–35% |
| `reasonable-default` + `LZ4FRAMED` | 4–17% |
| `reasonable-default` + `GZIP` | 3–15% |
| `reduced-default` + `LZ4FRAMED` | 1–11% |
| `reduced-default` + `GZIP` | 1–10% |

*Lower bound = gc_details-heavy workloads (many events); upper bound = sparse gc-only profiles. Measured on renaissance benchmarks.*

For most production deployments: `reasonable-default` + `LZ4FRAMED` (the agent
default) is the right choice; fast writes, fast reads, 4–17% of original size
depending on workload type.
