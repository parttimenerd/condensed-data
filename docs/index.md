---
title: Home
layout: default
nav_order: 1
---

# cjfr — Condensed JFR

`cjfr` is a library and CLI for continuous GC profiling in production JVMs.
It records JFR data directly to a compact, self-describing `.cjfr` format
designed for **long-running rotating recordings**: the agent keeps a bounded
ring-buffer of GC history on each server — the last N files, each capped by
size or time — so you always have recent data without runaway disk growth.

Recordings can be queried offline with `cjfr summary` (no inflation, no JMC),
inflated to standard `.jfr` for JDK Mission Control, or sliced to just the
window around a GC event or incident.

Developed by the [SapMachine](https://sap.github.io/SapMachine/) team at SAP.

## Why use cjfr?

Standard JFR files are large — a gc_details-heavy workload produces ~250 MB/hour.
Keeping weeks of data on each node is expensive. `cjfr` shrinks that by 4–30×
without losing anything GC-relevant:

| Approach | Typical size (gc_details-heavy) | Notes |
|---|---|---|
| Raw JFR | 100% | Full fidelity |
| `cjfr default` + LZ4 | 8–42% | No loss |
| `cjfr reasonable-default` + LZ4 | 4–17% | Millisecond timestamps, 32-frame stacks |
| `cjfr reduced-default` + LZ4 | 1–11% | Aggregate metrics, combined allocation events |

The agent writes directly to `.cjfr` — no intermediate JFR file, no extra disk I/O.
Compression is LZ4 (fast) or GZIP (better ratio, archival). No other algorithms.

## Install

Download the latest JAR from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest):

```shell
curl -L -o cjfr.jar https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data.jar
alias cjfr='java -jar '"$(pwd)"'/cjfr.jar'
```

Requires JDK 17+. The JAR is self-contained — no installation, no classpath setup.

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
cjfr summary app_0.cjfr -i app_1.cjfr -i app_2.cjfr
cjfr summary --gc-percentile=95 app_0.cjfr -i app_1.cjfr   # worst pause context
```

Condense an existing JFR file:

```shell
cjfr condense recording.jfr
# → recording.cjfr (typically 4–13% of original)
```

## Documentation

- [Getting Started]({% link getting-started.md %}) — installation, rotating recording quickstart, basic analysis
- [Production Recording Guide]({% link production-recording.md %}) — rotation knobs, live tuning, storage sizing
- [Analyzing Recordings]({% link analysis.md %}) — time filters, GC percentile, event filters, multi-file queries
- [Common Workflows]({% link workflows.md %}) — command-level recipes (JFR slicing, GC extraction, file merging)
- [Configuration Reference]({% link configurations.md %}) — condenser configs and compression algorithm trade-offs
- [JAR Release Selection]({% link jar-releases.md %}) — pick the right JAR variant (universal vs platform vs minimal)
- **Cookbooks:**
  - [GC Regression Hunt]({% link cookbook-gc-regression.md %}) — before/after comparison, worst-pause extraction
  - [Fleet-Wide GC Monitoring]({% link cookbook-fleet-monitoring.md %}) — batch summary, JSON aggregation, live limit tuning
  - [Container and Sidecar Deployment]({% link cookbook-container.md %}) — inflaterless agent, Kubernetes init-container
  - [Archival Pipeline]({% link cookbook-archival.md %}) — GZIP re-compression, batch script with verification

## Project links

- Source: [github.com/parttimenerd/condensed-data](https://github.com/parttimenerd/condensed-data)
- Releases: [github.com/parttimenerd/condensed-data/releases](https://github.com/parttimenerd/condensed-data/releases)
- File format spec: [doc/FORMAT.md](https://github.com/parttimenerd/condensed-data/blob/main/doc/FORMAT.md)

License: MIT.
