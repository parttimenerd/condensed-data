---
title: Getting Started
---

# Getting Started with cjfr

`cjfr` records JFR data directly to a compact `.cjfr` format designed for
**continuous GC profiling in production**. The core workflow is a rotating
ring-buffer on each server: the agent writes successive `.cjfr` files, evicts
the oldest when the cap is hit, and you always have the last N hours of GC history
on disk at a bounded, predictable storage cost.

Rotating recordings, offline summary without inflation, and targeted JFR slices
for JDK Mission Control are the three operations this tool is built around.

---

## Installation

Download the latest JAR from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest):

```shell
curl -L -o cjfr.jar https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data.jar
alias cjfr='java -jar '"$(pwd)"'/cjfr.jar'
```

Requires JDK 17+. Or build from source:

```shell
git clone --recurse-submodules https://github.com/parttimenerd/condensed-data.git
cd condensed-data && mvn package -DskipTests
# JAR is at target/condensed-data.jar
```

---

## Continuous Rotating Recording (the main use case)

Start a rotating GC recording with the agent — this is the one command most
production deployments run:

```shell
# Keep last 10 × 100 MB ≈ 1 GB of GC history
java -javaagent:cjfr.jar='start,/var/rec/app_$index.cjfr,--rotating,--max-files=10,--max-size=100m' \
     -jar myapp.jar
```

Or attach to an already-running process without restart:

```shell
cjfr agent myapp start '/var/rec/app_$index.cjfr' --rotating --max-files=10 --max-size=100m
```

!!! warning "Single-quote the path"
    When the output path contains `$index` or `$date`, always **single-quote** it
    in shell to prevent expansion: `'/var/rec/app_$index.cjfr'`, not
    `"/var/rec/app_$index.cjfr"`.

Check status or stop at any time:

```shell
cjfr agent myapp status
cjfr agent myapp stop
```

See [Production Recording Guide](production-recording.md) for rotation knobs,
live tuning, storage sizing, `$index` vs `$date` path placeholders, and JFR
config options.

---

## Analysing a Recording

Inspect directly without inflating — fast and JMC-free:

```shell
# Summary: event counts, GC pause stats, allocation rate
cjfr summary recording.cjfr

# Context around the worst 10% of pauses (1-minute window per qualifying GC)
cjfr summary --gc-percentile=90 recording.cjfr

# Summarise across multiple rotation files at once
cjfr summary app_0.cjfr app_1.cjfr app_2.cjfr
```

Inflate to `.jfr` for JDK Mission Control:

```shell
# Full inflation
cjfr inflate recording.cjfr

# Just a 30-minute window — much faster to open in JMC
cjfr inflate --start="2024-05-24 14:25:00" --duration=30m recording.cjfr incident.jfr

# Only events around the worst GC pauses
cjfr inflate --gc-percentile=95 recording.cjfr worst-pauses.jfr
```

See [Analyzing Recordings](analysis.md) for time filters, event filters,
and multi-file queries.

---

## Troubleshooting

**`cjfr inflate` fails or produces an empty JFR**

Make sure you are using the full JAR (not an inflaterless variant) and JDK 17+.
Inflaterless JARs are labelled `*-inflaterless*` in the filename.

**Agent attaches but nothing is recorded**

JFR is enabled by default on JDK 11+; older JVMs may need `-XX:+FlightRecorder`.
Run `cjfr agent <pid> status` to confirm the recording started.

**Output files are larger than expected**

The agent uses `reasonable-default` condensing with `LZ4FRAMED` compression by
default. If that is still too large, switch to `reduced-default` for more aggressive
event reduction (~1–11% of the raw JFR), or add `--compression=GZIP` for a better
byte-level ratio at the cost of slower writes. Both changes are independent.
See [Configuration Reference](configurations.md).

---

## Further Reading

- [Production Recording Guide](production-recording.md) — rotation knobs, live tuning, storage sizing, JFR config
- [Configuration Reference](configurations.md) — condenser configs and compression algorithms
- [Analyzing Recordings](analysis.md) — time filters, GC percentile, event filters, multi-file queries
- [Common Workflows](workflows.md) — end-to-end recipes including condensing existing JFR files
- [Cookbooks](cookbook-gc-regression.md) — GC regression hunt, fleet monitoring, container deployment, archival
- [JAR Release Selection](jar-releases.md) — pick the right JAR variant for your deployment
