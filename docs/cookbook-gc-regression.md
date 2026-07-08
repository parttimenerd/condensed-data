---
title: "Cookbook: GC Regression Hunt"
---

# Cookbook: Investigating a GC Regression

**Situation:** A new deployment has worse GC behaviour — longer pauses, more frequent
collections, or higher allocation pressure. You have rotating `.cjfr` recordings from
both before and after the release and need to identify what changed.

---

### Step 1 — Get a high-level diff

```shell
# Before the release — use the most recent single file for the GC Summary section
cjfr summary --short before_0.cjfr

# After the release
cjfr summary --short after_0.cjfr
```

`--short` skips the event-count table and prints only the GC/allocation summary —
fastest way to compare pause stats and heap usage between two recordings.

---

### Step 2 — See which event types dominate around the worst pauses

```shell
# Show event-type distribution only around the top 5% of GC pauses
cjfr summary --gc-percentile=95 --gc-percentile-context=2m \
  after_0.cjfr after_1.cjfr after_2.cjfr
```

`--gc-percentile=95` filters the event table to events that fall within `--gc-percentile-context`
(default 1m, here 2m) of GCs at or above the 95th percentile pause duration.
This tells you which event types cluster around your worst pauses — useful for spotting
whether high `jdk.ObjectAllocationSample` counts or safepoint events are co-located with long GCs.

---

### Step 3 — Extract the worst window for Mission Control

```shell
# Inflate only the high-pause events into a small JFR
cjfr inflate --gc-percentile=95 \
  after_0.cjfr after_1.cjfr after_2.cjfr \
  regression-pauses.jfr

# Open in JMC
jmc regression-pauses.jfr
```

This produces a JFR containing only the GC-heavy windows, which loads in seconds
compared to inflating the full recording.

---

### Step 4 — Compare GC event details directly (no JMC needed)

`cjfr view --json` emits one JSON object per event; pipe to `jq` to project the
fields you care about.

!!! note "Units and collector-specific fields"
    `longestPause` and `sumOfPauses` in `view --json` output are in **nanoseconds**.
    (The `summary --json` GC section uses **microseconds** — the `.gc.p95Micros` field.)

    `longestPause` and `sumOfPauses` are G1GC field names. **ZGC** and **Shenandoah**
    records use `duration` instead. For those collectors, replace the `jq` projection:
    ```shell
    # ZGC / Shenandoah
    cjfr view --json after_0.cjfr jdk.GarbageCollection \
      | jq '.[] | {gcId, cause, duration}'
    ```

```shell
# Before: show GC events with cause, pause, and heap usage
cjfr view --json before_0.cjfr jdk.GarbageCollection \
  | jq '.[] | {gcId, cause, longestPause, sumOfPauses}'

# After: same
cjfr view --json after_0.cjfr jdk.GarbageCollection \
  | jq '.[] | {gcId, cause, longestPause, sumOfPauses}'
```

```shell
# Heap trajectory before vs. after
cjfr view before_0.cjfr jdk.GCHeapSummary
cjfr view after_0.cjfr jdk.GCHeapSummary
```

---

### Step 5 — Check allocation rate change

`cjfr summary` prints an allocation rate section. If the rate jumped between
before and after, look at allocation events:

```shell
cjfr view after_0.cjfr jdk.ObjectAllocationSample | head -30
```

Or inflate only the allocation events to compare in JMC's Memory tab:

```shell
cjfr inflate --events=jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB,\
jdk.ObjectAllocationOutsideTLAB \
  after_0.cjfr alloc-after.jfr
```

---
