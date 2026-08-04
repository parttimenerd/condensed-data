Condensed Data
==============

[![ci](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml)

A library and CLI tool for reading and writing condensed JFR event data to disk.
Focuses on a simple, self-describing, space-saving format.
Stores JFR data via a compressing Java agent that supports file rotation and
live attachment to running JVMs.

Primary use case: long-term storage of GC-related JFR recordings.

**Documentation**

Full docs are published at **<https://parttimenerd.github.io/condensed-data/>**.

A [JMC fork](https://github.com/parttimenerd/jmc) with native `.cjfr` support lets you open `.cjfr` files
directly in JDK Mission Control — no inflation step required.
Download a snapshot build from its [releases page](https://github.com/parttimenerd/jmc/releases/tag/snapshot).

Source Markdown lives in [`docs/`](docs/):
- **[Getting Started](docs/getting-started.md)** — installation, quickstart, agent usage, configuration guide, and troubleshooting
- **[JAR Release Selection](docs/jar-releases.md)** — which JAR variant to download for your environment
- **[Configuration Reference](docs/configurations.md)** — condenser configs and compression algorithm trade-offs
- **[Production Recording Guide](docs/production-recording.md)** — rotating recordings, live tuning, storage sizing

Usage
-----

Download from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest) or build from source (see [Getting Started](docs/getting-started.md)).

The tool can be used via its CLI:
```shell
> java -jar target/condensed-data.jar -h
Usage: cjfr [-hV] [COMMAND]
CLI for the JFR condenser project
  -h, --help       Show this help message and exit.
  -V, --version    Print version information and exit.
Commands:
  condense  Condense one or more JFR files (or a folder/ZIP) into .cjfr format
  inflate   Inflate a condensed JFR file into JFR format
  agent     Use the included Java agent on a specific JVM process
  summary   Print a summary of the condensed JFR file
  view      View a named view or event type from a .cjfr or .jfr file as a table.
  print     Print events from a .cjfr (or .jfr) file in jfr-print format
```
But you can also use its built-in Java agent to directly record condensed JFR files:
```shell
> java -javaagent:target/condensed-data.jar=help
Usage: java -javaagent:condensed-agent.jar='[COMMAND]'
Options:
  h, help         Show this help message and exit.
  V, version      Print version information and exit.
Commands:
  start             Start the recording
  stop              Stop the recording
  status            Get the status of the recording
  set-max-size      Set the max file size
  set-max-duration  Set the max duration of each individual recording when rotating files
  set-max-files     Set the max file count when rotating
  set-duration      Set the duration of the overall recording
```
```shell
> java -javaagent:target/condensed-data.jar=start,help
Usage: agent,start,[hV],[max-duration=<maxDuration>],[max-size=<maxSize>],[max-files=<maxFiles>],[new-names],[duration=<duration>],[condenser-config=<configuration>],[misc-jfr-config=<miscJfrConfig>],[verbose],[config=<jfrConfig>],[rotating],[PATH]
Options:
      [PATH]                          Path to the recording file .cjfr file
      condenser-config=<configuration>
                                      The condenser data-reduction configuration
                                      to use, possible values: default,
                                      lossless, reduced (default default)
      config=<jfrConfig>              The JFR configuration to use: a predefined
                                      name (e.g. 'default', 'profile',
                                      'gc_details'), a name with .jfc suffix, or
                                      a path to a .jfc file. (default default)
      duration=<duration>             The duration of the whole recording, 0 for
                                      unlimited (default 0s)
  h, help                             Show this help message and exit.
      max-duration=<maxDuration>      The maximum duration of each individual
                                      recording, 0 for unlimited, when rotating
                                      files (default 0s)
      max-files=<maxFiles>            The maximum number of files to keep, when
                                      rotating files (default 10)
      max-size=<maxSize>              The maximum size of the recording file (or
                                      the individual files when rotating files)
                                      (default 0B)
      misc-jfr-config=<miscJfrConfig> Additional JFR config, '|' separated, like
                                      'jfr.ExecutionSample#interval=1s' (default
                                      )
      new-names                       When rotating files, use new names instead
                                      of reusing old ones (default false)
      rotating                        Write rotating files. Replaces $date and
                                      $index in the path; if neither placeholder
                                      is present, '_$index' is inserted before
                                      '.cjfr'. Requires --max-files >= 1 and at
                                      least one of --max-size or --max-duration.
                                      (default false)
  V, version                          Print version information and exit.
      verbose                         Be verbose (default false)
```

Arguments are comma-separated `key=value` pairs (no leading dashes), for example:
```shell
java -javaagent:target/condensed-data.jar=start,rotating,max-size=100k,max-files=3,new-names,recording.cjfr
```

The same agent can be attached to an already-running JVM via the `agent` CLI command
(TARGET is a PID, a main-class name filter, or `all`):
```shell
java -jar target/condensed-data.jar agent <PID> start --rotating --max-size=100k --max-files=3 --new-names recording.cjfr
java -jar target/condensed-data.jar agent <PID> status
java -jar target/condensed-data.jar agent <PID> stop
```

### All `jfr` tool views, directly on `.cjfr`

`cjfr view` is a drop-in replacement for the JDK `jfr view` command and supports **all of its
named views** (gc-pauses, hot-methods, allocation-by-site, exception-by-type, jvm-information, …).
It does this by reading the running JVM's own `view.ini` from the `jrt:` runtime image
(`jdk/jfr/internal/query/view.ini`) and evaluating the view's query natively — so the set of views
always matches the JDK you run `cjfr` on, with no view definitions bundled or hard-coded.

```shell
# Named view, rendered natively straight from a .cjfr (no inflation)
cjfr view gc-pauses recording.cjfr
cjfr view hot-methods recording.cjfr
cjfr view allocation-by-site recording.cjfr

# Also works on a raw .jfr, and for a single event type
cjfr view jdk.GarbageCollection recording.jfr
```

Any view that can't be evaluated natively — a `view.ini` older than the parser understands, or a
JDK before 21 (which has no `view.ini`) — transparently falls back to delegating to
`$JAVA_HOME/bin/jfr view`, so `cjfr view` never renders less than `jfr view` would. Because it
queries the compact `.cjfr` directly, event-heavy views run ~2–3× faster than opening the original
`.jfr` (measured on a 253 MB `gc_details` recording). See [Analyzing Recordings](docs/analysis.md).

### `jfr print` drop-in on `.cjfr`

`cjfr print` is a drop-in for the JDK `jfr print` command and supports the same options:

```shell
# Print all events (same format as jfr print)
cjfr print recording.cjfr

# Filter by event type or glob
cjfr print --events GCPhaseParallel,jdk.GC* recording.cjfr

# Filter by JFR category (comma = OR, glob patterns supported)
cjfr print --categories GC recording.cjfr
cjfr print --categories "GC,Profiling" recording.cjfr

# Full-precision output (nanosecond timestamps, raw bytes, exact floats)
cjfr print --exact recording.cjfr

# Limit stack trace depth
cjfr print --stack-depth 5 recording.cjfr

# JSON output
cjfr print --json recording.cjfr

# Also works on raw .jfr files
cjfr print recording.jfr
```

Requirements
------------
JDK 17+

File Format
-----------
`.cjfr` is a self-describing, compressed binary format built on JFR event types.
It uses varints, struct caches, and LZ4 framing by default. Read the source or
open an issue if you need format details — the spec doc is not kept up to date.

Development
-----------
Every commit is formatted via `mvn spotless:apply` in a pre-commit hook to ensure consistent formatting, install it via:
```shell
mvn install
mvn package
bin/update-help.py # updates the help messages in the README
```

This pre-commit hook also runs the tests via `mvn test`.

### JAR Size Optimization

The default `target/condensed-data.jar` includes native libraries for all 18+ supported platforms.
For single-platform deployments, use `reduce-jar.py` to create platform-specific JARs:

```bash
# List available platforms
python3 reduce-jar.py reduce target/condensed-data.jar --list-platforms

# Create platform-specific JAR (~60% smaller)
python3 reduce-jar.py reduce target/condensed-data.jar output.jar --platform darwin/aarch64

# Create minimal inflaterless JAR — strips JMC writer, metadata, annotation stubs,
# then automatically applies femtojar (zopfli + ProGuard): ~2.1 MB → ~360 KB
python3 reduce-jar.py reduce target/condensed-data.jar output.jar --platform darwin/aarch64 --without-jmc

# Skip femtojar compression (get the raw 846 KB stripped JAR)
python3 reduce-jar.py reduce target/condensed-data.jar output.jar --platform darwin/aarch64 --without-jmc --no-femtojar

# Generate all platform variants
python3 reduce-jar.py matrix target/condensed-data.jar output-jars/
```

Benchmarking
------------
To create the JFR files for benchmarking, run the following command:
```shell
python3 bin/create_jfr_files.py
```
This takes a day, as it generates JFR files for
the JFR configurations in the `benchmarks` folder and
multiple GCs using the [renaissance benchmark](https://renaissance.dev/) suite with `--no-forced-gc`.

Now to run the benchmarks, use the following command:
```shell
java -jar target/condensed-data.jar benchmark
./cjfr benchmark
```

### Current Results

**Benchmark run on 2026-08-03**

JFR file | runtime (s) | original | compressed | lossless | default | reduced
-------- | ----------- | -------- | ---------- | -------- | ------- | -------
renaissance-all_gc_details_ZGC.jfr | 1917.0 | 249.8MB | 23.6% | 22.3% | 11.8% | 2.7%
renaissance-all_gc_details_ParallelGC.jfr | 1443.1 | 244.9MB | 21.4% | 17.0% | 8.8% | 1.9%
renaissance-all_gc_details_SerialGC.jfr | 1587.2 | 242.6MB | 20.8% | 20.5% | 11.2% | 2.3%
renaissance-all_gc_details_G1.jfr | 1827.1 | 241.5MB | 22.8% | 20.9% | 8.3% | 3.4%
renaissance-all_gc_ZGC.jfr | 1808.5 | 89.9MB | 42.5% | 39.6% | 21.0% | 19.9%
renaissance-movie-lens_default_G1.jfr | 559.0 | 79.1MB | 29.6% | 26.7% | 12.6% | 8.9%
renaissance-all_gc_ParallelGC.jfr | 1395.1 | 57.9MB | 29.5% | 7.2% | 5.0% | 3.4%
renaissance-naive-bayes_default_G1.jfr | 60.2 | 46.1MB | 20.8% | 22.2% | 3.2% | 2.8%
renaissance-fj-kmeans_default_G1.jfr | 62.0 | 40.4MB | 21.2% | 22.3% | 3.2% | 2.9%
renaissance-page-rank_default_G1.jfr | 93.9 | 38.7MB | 26.0% | 26.1% | 7.1% | 6.4%
renaissance-dotty_gc_details_ZGC.jfr | 71.4 | 29.4MB | 19.8% | 9.5% | 6.3% | 0.8%
renaissance-all_gc_G1.jfr | 1537.4 | 29.3MB | 40.6% | 28.4% | 21.1% | 16.6%
renaissance-als_default_G1.jfr | 128.2 | 21.7MB | 25.5% | 24.0% | 9.0% | 5.9%
renaissance-chi-square_default_G1.jfr | 34.4 | 18.4MB | 22.7% | 24.0% | 4.4% | 3.6%
renaissance-mnemonics_default_G1.jfr | 36.8 | 14.3MB | 22.5% | 24.3% | 3.7% | 3.4%
renaissance-all_gc_SerialGC.jfr | 1569.4 | 14.2MB | 37.0% | 36.0% | 23.2% | 18.9%
renaissance-par-mnemonics_default_G1.jfr | 30.4 | 14.0MB | 21.6% | 24.0% | 3.0% | 2.7%
renaissance-dotty_gc_details_G1.jfr | 70.3 | 12.8MB | 23.2% | 16.7% | 10.5% | 2.9%
renaissance-neo4j-analytics_default_G1.jfr | 42.4 | 11.1MB | 22.8% | 23.0% | 6.9% | 5.2%
renaissance-future-genetic_default_G1.jfr | 62.6 | 11.0MB | 24.0% | 25.0% | 5.5% | 4.6%
renaissance-dec-tree_default_G1.jfr | 31.4 | 10.7MB | 26.3% | 26.0% | 9.9% | 6.9%
renaissance-scrabble_default_G1.jfr | 8.8 | 10.1MB | 21.5% | 24.6% | 3.1% | 2.9%
renaissance-scala-stm-bench7_default_G1.jfr | 52.7 | 9.7MB | 25.3% | 25.8% | 7.0% | 6.2%
renaissance-log-regression_default_G1.jfr | 34.4 | 9.5MB | 25.6% | 26.0% | 10.1% | 6.1%
renaissance-dotty_gc_details_SerialGC.jfr | 74.3 | 9.0MB | 22.1% | 15.3% | 10.8% | 2.4%
renaissance-reactors_default_G1.jfr | 63.6 | 7.7MB | 25.0% | 26.1% | 7.7% | 6.8%
renaissance-gauss-mix_default_G1.jfr | 23.4 | 7.4MB | 29.9% | 30.9% | 15.3% | 11.9%
renaissance-philosophers_default_G1.jfr | 28.2 | 7.1MB | 25.6% | 26.9% | 6.2% | 5.6%
renaissance-dotty_default_G1.jfr | 67.8 | 6.0MB | 25.2% | 17.4% | 10.1% | 5.1%
renaissance-dotty_gc_details_ParallelGC.jfr | 71.2 | 5.4MB | 23.3% | 18.4% | 9.7% | 2.8%
renaissance-rx-scrabble_default_G1.jfr | 8.1 | 2.0MB | 24.8% | 26.7% | 9.9% | 8.3%
renaissance-scala-doku_default_G1.jfr | 33.6 | 1.4MB | 28.0% | 27.3% | 14.6% | 12.4%
renaissance-scala-kmeans_default_G1.jfr | 10.2 | 1.1MB | 28.1% | 29.6% | 14.4% | 13.1%
renaissance-dotty_gc_ParallelGC.jfr | 71.8 | 1020.3KB | 36.1% | 17.3% | 15.4% | 13.7%
renaissance-dotty_gc_ZGC.jfr | 74.0 | 702.2KB | 43.0% | 42.7% | 27.1% | 24.7%
renaissance-dotty_gc_G1.jfr | 70.6 | 603.4KB | 42.4% | 40.8% | 31.6% | 28.1%
renaissance-dotty_gc_SerialGC.jfr | 74.4 | 501.3KB | 38.8% | 41.8% | 31.3% | 28.9%

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

Use `reduced` for archiving (add `--compression-level MAX_COMPRESSION` for maximum compression).

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors