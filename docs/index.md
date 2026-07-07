---
title: Home
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

## How it works

```
   ┌──────────────┐     writes            ┌────────────────┐
   │ Java agent   │ ────────────────────▶ │  app_0.cjfr    │
   │ (in-process) │      rotating         │  app_1.cjfr    │
   └──────────────┘      ring-buffer      │  app_2.cjfr …  │
          │                               └────────┬───────┘
          │ set-max-files, set-max-size            │
          │ set-max-duration (live tuning)         │
          ▼                                        ▼
   ┌──────────────┐          ┌──────────────────────────────┐
   │  cjfr agent  │          │  cjfr summary  (no inflate)  │
   │  status/stop │          │  cjfr view     (per-event)   │
   └──────────────┘          │  cjfr inflate → .jfr → JMC   │
                             └──────────────────────────────┘
```

The agent writes `.cjfr` **directly** — no intermediate `.jfr` file and no extra
disk I/O. Rotation caps disk usage at `max-files × max-size`.

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
