---
title: Home
layout: default
nav_order: 1
---

# cjfr — Condensed JFR

`cjfr` is a library and CLI for compressing JFR (Java Flight Recorder) data into a
compact, self-describing `.cjfr` format. It is designed for continuous GC profiling
in production: long-term storage of rotating JFR recordings with negligible overhead,
offline analysis without a full JFR toolchain, and selective inflation back to `.jfr`
when you need JDK Mission Control.

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

Condense an existing JFR file:

```shell
cjfr condense recording.jfr
# → recording.cjfr (typically 4–13% of original)
```

Attach the agent to a running process for continuous recording:

```shell
cjfr agent myapp start '/var/rec/app_$index.cjfr' --rotating --max-files=10 --max-size=100m
```

Inspect without inflating:

```shell
cjfr summary recording.cjfr
cjfr summary --gc-percentile=95 recording.cjfr   # show context around worst pauses
```

## Documentation

- [Getting Started]({% link getting-started.md %}) — installation, quickstart, agent usage, troubleshooting
- [JAR Release Selection]({% link jar-releases.md %}) — pick the right JAR variant (universal vs platform vs minimal)
- [Configuration Reference]({% link configurations.md %}) — condenser configs and compression algorithm trade-offs
- [Production Recording Guide]({% link production-recording.md %}) — rotating recordings, live tuning, storage sizing
- [Analyzing Recordings]({% link analysis.md %}) — time filters, GC percentile, event filters, multi-file queries
- [Common Workflows]({% link workflows.md %}) — end-to-end recipes (remote recording, JFR slicing, GC extraction)

## Project links

- Source: [github.com/parttimenerd/condensed-data](https://github.com/parttimenerd/condensed-data)
- Releases: [github.com/parttimenerd/condensed-data/releases](https://github.com/parttimenerd/condensed-data/releases)
- File format spec: [doc/FORMAT.md](https://github.com/parttimenerd/condensed-data/blob/main/doc/FORMAT.md)

License: MIT.
