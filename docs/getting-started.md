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
alias cjfr='java -jar /path/to/cjfr.jar'
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
# Keep last 10 × 100 MB ≈ 1 GB of GC history. Names are stable, disk usage bounded.
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

**How rotation works:** When a file reaches `--max-size` (or `--max-duration`), the
agent closes it and opens the next. By default, names cycle — `app_0.cjfr`, `app_1.cjfr`,
…, then back to `app_0.cjfr` — so disk usage is capped at exactly `max-files × max-size`.
Pass `--new-names` to generate unique names instead (oldest deleted when the cap is hit).

!!! tip "`--duration` vs `--max-duration`"
    `--duration=4h` stops the **whole recording** after 4 hours.
    `--max-duration=10m` caps **each individual file** at 10 minutes (rotation trigger).
    Combine both: record for 2 hours total, rotating every 10 minutes.

Check status or stop at any time:

```shell
cjfr agent myapp status
cjfr agent myapp stop
```

Adjust limits while recording is live — no restart needed:

```shell
cjfr agent myapp set-max-files 20      # expand ring buffer
cjfr agent myapp set-max-size 200m     # grow per-file cap
cjfr agent myapp set-max-duration 15m  # change rotation interval
```

See [Production Recording Guide](production-recording.md) for the full
rotation reference, storage sizing, and JFR config options.

---

## Analysing a Recording

Inspect directly without inflating — fast and JMC-free:

```shell
# Summary: event counts, GC pause stats, allocation rate
cjfr summary recording.cjfr

# Context around the worst 10% of pauses (1-minute window per qualifying GC)
cjfr summary --gc-percentile=90 recording.cjfr

# Summarise across multiple rotation files at once
cjfr summary app_0.cjfr -i app_1.cjfr -i app_2.cjfr
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

## Condensing an Existing JFR File

Convert a plain `.jfr` to `.cjfr` for storage:

```shell
cjfr condense recording.jfr          # → recording.cjfr
cjfr condense --statistics recording.jfr  # show compression ratio
```

The agent writes `.cjfr` directly — `condense` is for files you already have.

---

## Troubleshooting

**`cjfr inflate` fails or produces an empty JFR**

Make sure you are using the full JAR (not an inflaterless variant) and JDK 17+.
Inflaterless JARs are labelled `*-inflaterless*` in the filename.

**Agent attaches but nothing is recorded**

JFR is enabled by default on JDK 11+; older JVMs may need `-XX:+FlightRecorder`.
Run `cjfr agent <pid> status` to confirm the recording started.

**Output files are larger than expected**

The agent defaults to `reasonable-default` config. Switch to `reduced-default` for
maximum compression (~1–4% of original), or use `--compression=GZIP` for a better
ratio at slower write speed. See [Configuration Reference](configurations.md).

---

## Further Reading

- [Production Recording Guide](production-recording.md) — rotation knobs, live tuning, storage sizing, JFR config
- [Configuration Reference](configurations.md) — condenser configs and compression algorithms
- [Analyzing Recordings](analysis.md) — time filters, GC percentile, event filters, multi-file queries
- [Common Workflows](workflows.md) — end-to-end recipes
- [Cookbooks](cookbooks.md) — GC regression hunt, fleet monitoring, container deployment, archival
- [JAR Release Selection](jar-releases.md) — pick the right JAR variant for your deployment
