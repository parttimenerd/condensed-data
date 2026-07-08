---
title: Home
---

# cjfr — Condensed JFR

Developed by the [SapMachine](https://sap.github.io/SapMachine/) team at SAP — the same team behind SAP's production OpenJDK distribution and used in SAP's own production environments.

`cjfr` is a library and CLI for continuous profiling in production JVMs using JFR.
The agent writes JFR data directly to a compact `.cjfr` format with a built-in
rotating ring-buffer: keep the last N files, each capped by size or time, so you
always have recent history without runaway disk growth.

Recordings can be queried offline with `cjfr summary` (no inflation, no JMC),
inflated to standard `.jfr` for JDK Mission Control, or sliced to just the
window around a GC event or incident.

## Why use cjfr instead of raw JFR?

Standard JFR with `gc_details` produces ~250 MB/hour per JVM. Keeping weeks of data
across a fleet is expensive. The naive alternative — gzip at night, rotate weekly —
leaves you with stale data and no way to query it without unpacking.

`cjfr` solves three things together:

1. **Bounded disk usage during recording.** The agent's ring-buffer evicts old files
   automatically. No cron job, no manual cleanup, no "disk full at 3am" incident.
2. **Query without inflation.** `cjfr summary` reads a compact footer in milliseconds.
   Inflating a 200 MB JFR to query one field takes seconds. On a fleet of 100 nodes
   that difference compounds.
3. **Lossy reduction where precision isn't needed.** `reasonable-default` trades
   sub-millisecond timestamp precision for a 2–4× size reduction on top of LZ4
   compression — precision that matters for nanosecond benchmarking but not for
   GC pause analysis. `reduced-default` goes further: aggregate allocation metrics,
   16-frame stacks, combined exception events — suitable for fleet-wide long-term storage.

`cjfr` captures all standard JFR events — GC pauses, heap summaries, CPU samples,
allocation events, lock contention, safepoints. The condenser config controls how
aggressively they are reduced; the JFR config (`--config`) controls which events
are captured in the first place. Works with G1GC, ZGC, Shenandoah, and Serial/Parallel GC.

| Approach | Typical size (gc_details-heavy) | Notes |
|---|---|---|
| Raw JFR | 100% (~250 MB/hour) | Full fidelity |
| `cjfr default` + LZ4FRAMED | 8–42% | Lossless |
| `cjfr reasonable-default` + LZ4FRAMED | 4–17% | Millisecond timestamps, 32-frame stacks |
| `cjfr reduced-default` + LZ4FRAMED | 1–11% | Aggregate metrics, combined allocation events |

*Measured on renaissance gc_details benchmarks. Sparse gc-only profiles produce smaller files.*

## Install

Download the latest JAR from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest):

```shell
curl -L -o cjfr.jar https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data.jar
alias cjfr='java -jar '"$(pwd)"'/cjfr.jar'
```

Requires JDK 17+. The JAR is self-contained; no installation, no classpath setup.

## Quick example

Start a continuous rotating GC recording (the primary use case):

```shell
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-files=10,--max-size=100m' \
     -jar myapp.jar
```

Or attach to a running process:

```shell
cjfr agent myapp start '/var/rec/app_$index.cjfr' --rotating --max-files=10 --max-size=100m
```

Check GC health without inflating:

```shell
cjfr summary app_0.cjfr app_1.cjfr app_2.cjfr
cjfr summary --gc-percentile=95 app_0.cjfr app_1.cjfr   # worst pause context
```

## Documentation

<div class="grid cards" markdown>

-   :material-rocket-launch: **[Getting Started](getting-started.md)**

    Install, start a rotating recording, run your first summary.

-   :material-server: **[Production Recording](production-recording.md)**

    Rotation knobs, live tuning, storage sizing.

-   :material-magnify: **[Analyzing Recordings](analysis.md)**

    Time filters, GC percentile, event filters, multi-file queries.

-   :material-tools: **[Common Workflows](workflows.md)**

    JFR slicing, GC extraction, file merging recipes.

-   :material-tune: **[Configuration Reference](configurations.md)**

    Condenser configs and compression algorithms.

-   :material-package-variant: **[JAR Release Selection](jar-releases.md)**

    Pick the right JAR variant (universal / platform / minimal).

</div>

## Cookbooks

<div class="grid cards" markdown>

-   :material-chart-line-variant: **[GC Regression Hunt](cookbook-gc-regression.md)**

    Before/after comparison, worst-pause extraction, allocation event analysis.

-   :material-server-network: **[Fleet-Wide GC Monitoring](cookbook-fleet-monitoring.md)**

    Batch summary, JSON aggregation, live limit tuning.

-   :material-docker: **[Container & Sidecar Deployment](cookbook-container.md)**

    Inflaterless agent, Kubernetes init-container pattern.

-   :material-archive: **[Archival Pipeline](cookbook-archival.md)**

    GZIP re-compression, batch script with verification.

</div>

## Project links

- Source: [github.com/parttimenerd/condensed-data](https://github.com/parttimenerd/condensed-data)
- Releases: [github.com/parttimenerd/condensed-data/releases](https://github.com/parttimenerd/condensed-data/releases)
- File format spec: [doc/FORMAT.md](https://github.com/parttimenerd/condensed-data/blob/main/doc/FORMAT.md)

License: MIT.
