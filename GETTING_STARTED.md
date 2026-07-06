# Getting Started with cjfr (condensed-data)

`cjfr` compresses JFR (Java Flight Recorder) files into a compact `.cjfr` format.
It is especially useful for GC-heavy recordings that need to be stored long-term or
transferred over a network, and for continuous recording via the built-in Java agent
with automatic file rotation.

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

If you need to open the recording in a tool like JDK Mission Control:

```shell
cjfr inflate recording.cjfr
# produces recording.inflated.jfr
```

---

## Continuous Recording with the Java Agent

The built-in Java agent records directly to `.cjfr` format without an intermediate JFR
step. This is the recommended approach for production use.

### Record a process from startup

```shell
java -javaagent:cjfr.jar=start,recording.cjfr MyApp
```

The recording runs until the JVM exits. The output is `recording.cjfr`.

### Attach to a running process

```shell
# Find the PID or use the process name
cjfr agent myapp start recording.cjfr

# Check recording status
cjfr agent myapp status

# Stop recording
cjfr agent myapp stop
```

`myapp` is a case-insensitive substring match on the main class name. You can also use
a PID directly (`cjfr agent 12345 start`) or `all` to target every discovered JVM.

### Rotating files (for long-running services)

Keep the last 5 files, each up to 100 MB:

```shell
java -javaagent:cjfr.jar='start,/tmp/rec_$index.cjfr,--rotating,--max-files=5,--max-size=100m' MyApp
```

Or attach at runtime:

```shell
cjfr agent myapp start /tmp/rec_\$index.cjfr --rotating --max-files=5 --max-size=100m
```

---

## Choosing a Configuration

The `--condenser-config` option controls how aggressively events are reduced.
All configurations produce valid `.cjfr` files that can be inflated back to JFR.

| Config | Size | Use case |
|---|---|---|
| `default` | ~8–13% of original | Full-fidelity GC data, safe default |
| `reasonable-default` | ~4–7% of original | Good compression, slight data reduction |
| `reduced-default` | ~1–2% of original | Maximum compression, more lossy |

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

The inner compression algorithm is independent of the event reduction above.
LZ4 (default) is the best choice for most cases.

| Algorithm | Speed | Ratio |
|---|---|---|
| `LZ4` | Very fast | Good (default) |
| `ZSTD` | Fast | Better |
| `GZIP` | Slow | Good |
| `BZIP2` | Very slow | Good |
| `NONE` | Instant | No compression |

Example:

```shell
cjfr condense --compression=ZSTD recording.jfr
```

---

## Common Workflows

### Batch-condense a folder of JFR files

```shell
cjfr condense --force /path/to/jfr-folder/
# produces .cjfr files alongside the originals
```

### Condense a ZIP archive

```shell
cjfr condense recordings.zip output.cjfr
```

### Check what's in an existing .cjfr file

```shell
# Short summary
cjfr summary --short recording.cjfr

# Full breakdown with event counts
cjfr summary --full recording.cjfr

# Machine-readable JSON
cjfr summary --json recording.cjfr
```

### Reduce JAR size for single-platform deployment

The default JAR bundles native libraries for 18+ platforms (~80% of JAR size).
For a production deployment on a known platform:

```shell
# List available platforms
python3 bin/reduce-jar.py reduce cjfr.jar --list-platforms

# Build a darwin/aarch64-only JAR
python3 bin/reduce-jar.py reduce cjfr.jar cjfr-mac.jar --platform darwin/aarch64
```

See [README_JAR_SIZE.md](README_JAR_SIZE.md) for details.

---

## Troubleshooting

**`cjfr inflate` fails or produces an empty JFR**

Inflation requires JDK Mission Control libraries on the classpath. Make sure you are
running with the full JAR (not a stripped version) and JDK 17+.

**Agent attaches but nothing is recorded**

Check that the JVM was started with `-XX:+FlightRecorder` or that JFR is enabled
(it is by default on JDK 11+). Run `cjfr agent <pid> status` to confirm.

**Output file is unexpectedly large**

Try `--condenser-config=reduced-default` for maximum compression, or switch from
`--compression=LZ4` to `--compression=ZSTD` for a better ratio at modest cost.