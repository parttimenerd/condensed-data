---
title: Getting Started
layout: default
nav_order: 2
---

# Getting Started with cjfr

`cjfr` records JFR data directly to a compact `.cjfr` format and provides
offline analysis without a full JFR toolchain. The main use case is continuous
GC profiling on production JVMs: low-overhead rotating recordings that fit on
disk long-term, with instant `summary` output and targeted inflation to `.jfr`
when you need JDK Mission Control.

## Installation

Download the latest JAR from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest):

```shell
curl -L -o cjfr.jar https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data.jar
```

Or build from source (requires JDK 17+):

```shell
git clone --recurse-submodules https://github.com/parttimenerd/condensed-data.git
cd condensed-data
mvn package -DskipTests
# JAR is at target/condensed-data.jar
```

For convenience, create an alias:

```shell
alias cjfr='java -jar /path/to/cjfr.jar'
```

---

## 5-Minute Quickstart

### 1. Condense a JFR file

```shell
cjfr condense recording.jfr
# produces recording.cjfr
```

Print compression statistics while condensing:

```shell
cjfr condense --statistics recording.jfr
```

### 2. Inspect the result

```shell
cjfr summary recording.cjfr
```

Generate a storage flamegraph to visualize which event types use the most space:

```shell
cjfr summary --flamegraph flamegraph.html recording.cjfr
open flamegraph.html
```

### 3. View specific events

```shell
cjfr view recording.cjfr jdk.GCHeapSummary
```

### 4. Inflate back to JFR

When you need to open the recording in JDK Mission Control or another JFR consumer:

```shell
cjfr inflate recording.cjfr
# produces recording.inflated.jfr
```

---

## Continuous Recording with the Java Agent

The built-in Java agent records directly to `.cjfr` without an intermediate JFR
file on disk. This is the recommended approach for production: lower I/O overhead
and immediate space savings compared to recording JFR then condensing offline.

### Attach to a running process

```shell
# By main-class substring (case-insensitive)
cjfr agent myapp start recording.cjfr

# Check recording status
cjfr agent myapp status

# Stop recording
cjfr agent myapp stop
```

`myapp` is a case-insensitive substring match on the main class name. You can also use
a PID directly (`cjfr agent 12345 start`) or `all` to target every discovered JVM.

### Record a process from startup

```shell
java -javaagent:cjfr.jar=start,recording.cjfr MyApp
```

The recording runs until the JVM exits.

### Rotating files (for long-running services)

Keep the last 5 files, each up to 100 MB — approximately 500 MB of history on disk:

```shell
java -javaagent:cjfr.jar='start,/tmp/rec_$index.cjfr,--rotating,--max-files=5,--max-size=100m' MyApp
```

Or attach at runtime (use single quotes to prevent shell expansion of `$`):

```shell
cjfr agent myapp start '/tmp/rec_$index.cjfr' --rotating --max-files=5 --max-size=100m
```

**`--duration` vs `--max-duration`:** `--duration=30m` stops the *whole* recording after
30 minutes. `--max-duration=5m` caps each *individual rotated file* at 5 minutes (rotation
trigger). Both can be combined: record for 1 hour total, rotating every 10 minutes.

**`--new-names`:** By default, each rotation reuses the oldest file's name, keeping disk
usage bounded to exactly `--max-files` names. Pass `--new-names` to always generate a
fresh name per rotation — files accumulate until `--max-files` is reached, then the
oldest is deleted.

### Changing limits at runtime

```shell
cjfr agent myapp set-max-files 20     # expand ring buffer after disk expansion
cjfr agent myapp set-max-size 200m    # grow per-file cap
cjfr agent myapp set-max-duration 10m # change rotation interval
cjfr agent myapp set-duration 2h      # cap total recording length
```

### Reading agent output

When the CLI attaches (`cjfr agent PID ...`), the agent writes its output to a temporary
file (`$TMPDIR/jfr-condenser-agent-<pid>-out.log`) which the CLI reads back automatically.

If something goes wrong silently, you can read unread output manually:

```shell
cjfr agent myapp read
```

Exit codes are also written to `$TMPDIR/jfr-condenser-agent-<pid>-exit.code` and read
back by the CLI so non-zero exits are surfaced to the caller.

---

## Choosing a Configuration

The `--condenser-config` option controls how aggressively events are reduced.
All configurations produce valid `.cjfr` files that can be inflated back to JFR.
No data is destroyed that is still needed for accurate GC analysis at that
level of precision.

| Config | Size (with LZ4, gc_details workload) | Use case |
|---|---|---|
| `default` | ~8–42% of original | Full-fidelity GC data, safe default |
| `reasonable-default` | ~4–17% of original | Good compression, slight data reduction |
| `reduced-default` | ~1–11% of original | Maximum compression, more lossy |

*Size varies widely: gc_details-heavy recordings compress best; sparse gc-only profiles
compress least. Ranges from actual renaissance benchmarks.*

> **Default config differs by surface:** `cjfr condense` defaults to `default`. The agent
> `start` command defaults to `reasonable-default`. Specify `--condenser-config` explicitly
> if you need consistent behaviour across both.

Example:

```shell
cjfr condense --condenser-config=reduced-default recording.jfr
```

Or with the agent:

```shell
java -javaagent:cjfr.jar='start,recording.cjfr,--condenser-config=reduced-default' MyApp
```

---

## Choosing a Compression Algorithm

The inner compression algorithm is independent of event reduction above.
The CLI accepts exactly three values for `--compression`:

| Algorithm | CLI value | Speed | Ratio | Best for |
|---|---|---|---|---|
| LZ4 (default) | `LZ4FRAMED` | Very fast | Good | Agent recording, frequent reads |
| gzip | `GZIP` | Slow | Good | Long-term archive, toolchain compat |
| None | `NONE` | Instant | None | Benchmarking, re-compressed transport |

> **Note:** The `.cjfr` format reserves space for additional algorithms, but only
> `NONE`, `GZIP`, and `LZ4FRAMED` are implemented. Passing any other value
> (e.g. `--compression=ZSTD`) is rejected by the CLI.

`LZ4FRAMED` uses block-independent framing, which makes files resilient to partial
corruption and streamable. It is the right default for the agent.

Example (gzip for archival):

```shell
cjfr condense --compression=GZIP recording.jfr
```

---

## Troubleshooting

**`cjfr inflate` fails or produces an empty JFR**

Inflation requires JDK Mission Control libraries on the classpath. Make sure you are
running with the full JAR (not a stripped inflaterless variant) and JDK 17+.

**Agent attaches but nothing is recorded**

Check that JFR is enabled on the target JVM. It is on by default since JDK 11; older
distributions may require `-XX:+FlightRecorder`. Run `cjfr agent <pid> status` to
confirm.

**Output file is unexpectedly large**

Try `--condenser-config=reduced-default` for maximum compression, or switch from
`--compression=LZ4FRAMED` to `--compression=GZIP` for a better ratio at modest cost.

---

## Further Reading

- [JAR Release Selection Guide]({% link jar-releases.md %}) — which JAR to download for your environment
- [Configuration Reference]({% link configurations.md %}) — full condenser config and compression trade-offs
- [Production Recording Guide]({% link production-recording.md %}) — rotating files, live tuning, sizing
- [Analyzing Recordings]({% link analysis.md %}) — time filters, GC percentile, event filters, multi-file
- [Common Workflows]({% link workflows.md %}) — end-to-end recipes
