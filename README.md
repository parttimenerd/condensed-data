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
java -jar target/condensed-data.jar agent <PID> start rotating max-size=100k max-files=3 new-names recording.cjfr
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

**Benchmark run on 2026-07-29 18:51**

JFR file | runtime (s) | original | compressed | lossless | default | reduced
-------- | ----------- | -------- | ---------- | -------- | ------- | -------
renaissance-dotty_gc_details_G1.jfr | 70.3 | 12.8MB | 23.2% | 16.4% | 8.2% | 2.7%
renaissance-all_gc_details_G1.jfr | 1827.1 | 241.5MB | 22.8% | 20.7% | 7.9% | 3.2%
renaissance-dotty_gc_G1.jfr | 70.6 | 603.4KB | 42.4% | 35.8% | 29.0% | 24.4%
renaissance-all_gc_G1.jfr | 1537.4 | 29.3MB | 40.6% | 27.7% | 21.6% | 16.6%
renaissance-dotty_default_G1.jfr | 67.8 | 6.0MB | 25.2% | 16.9% | 8.7% | 4.5%
renaissance-dotty_gc_details_SerialGC.jfr | 74.3 | 9.0MB | 22.1% | 14.9% | 7.9% | 2.1%
renaissance-all_gc_details_SerialGC.jfr | 1587.2 | 242.6MB | 20.8% | 20.2% | 10.7% | 2.1%
renaissance-dotty_gc_details_ParallelGC.jfr | 71.2 | 5.4MB | 23.3% | 17.8% | 8.1% | 2.3%
renaissance-all_gc_details_ParallelGC.jfr | 1443.1 | 244.9MB | 21.4% | 16.2% | 8.3% | 1.6%
renaissance-dotty_gc_details_ZGC.jfr | 71.4 | 29.4MB | 19.8% | 9.3% | 5.6% | 0.7%
renaissance-all_gc_details_ZGC.jfr | 1917.0 | 249.8MB | 23.6% | 21.8% | 11.4% | 2.5%
renaissance-dotty_gc_SerialGC.jfr | 74.4 | 501.3KB | 38.8% | 35.7% | 26.8% | 22.5%
renaissance-all_gc_SerialGC.jfr | 1569.4 | 14.2MB | 37.0% | 34.8% | 23.7% | 17.1%
renaissance-dotty_gc_ParallelGC.jfr | 71.8 | 1020.3KB | 36.1% | 14.4% | 12.7% | 11.0%
renaissance-all_gc_ParallelGC.jfr | 1395.1 | 57.9MB | 29.5% | 7.0% | 5.5% | 3.2%
renaissance-dotty_gc_ZGC.jfr | 74.0 | 702.2KB | 43.0% | 38.5% | 26.1% | 20.7%
renaissance-all_gc_ZGC.jfr | 1808.5 | 89.9MB | 42.5% | 39.4% | 21.5% | 19.6%
renaissance-scrabble_default_G1.jfr | 8.8 | 10.1MB | 21.5% | 24.3% | 2.8% | 2.5%
renaissance-page-rank_default_G1.jfr | 93.9 | 38.7MB | 26.0% | 26.0% | 7.0% | 6.2%
renaissance-future-genetic_default_G1.jfr | 62.6 | 11.0MB | 24.0% | 24.7% | 5.2% | 4.0%
renaissance-movie-lens_default_G1.jfr | 559.0 | 79.1MB | 29.6% | 26.5% | 11.5% | 7.3%
renaissance-scala-doku_default_G1.jfr | 33.6 | 1.4MB | 28.0% | 25.3% | 12.8% | 10.3%
renaissance-chi-square_default_G1.jfr | 34.4 | 18.4MB | 22.7% | 23.8% | 4.1% | 3.2%
renaissance-fj-kmeans_default_G1.jfr | 62.0 | 40.4MB | 21.2% | 22.1% | 3.1% | 2.6%
renaissance-rx-scrabble_default_G1.jfr | 8.1 | 2.0MB | 24.8% | 25.3% | 8.3% | 6.6%
renaissance-neo4j-analytics_default_G1.jfr | 42.4 | 11.1MB | 22.8% | 22.7% | 6.4% | 4.7%
renaissance-reactors_default_G1.jfr | 63.6 | 7.7MB | 25.0% | 25.7% | 7.2% | 6.0%
renaissance-dec-tree_default_G1.jfr | 31.4 | 10.7MB | 26.3% | 25.6% | 9.1% | 6.0%
renaissance-scala-stm-bench7_default_G1.jfr | 52.7 | 9.7MB | 25.3% | 25.4% | 6.6% | 5.7%
renaissance-naive-bayes_default_G1.jfr | 60.2 | 46.1MB | 20.8% | 22.1% | 3.0% | 2.5%
renaissance-als_default_G1.jfr | 128.2 | 21.7MB | 25.5% | 23.8% | 8.2% | 4.9%
renaissance-par-mnemonics_default_G1.jfr | 30.4 | 14.0MB | 21.6% | 23.7% | 2.8% | 2.4%
renaissance-scala-kmeans_default_G1.jfr | 10.2 | 1.1MB | 28.1% | 27.0% | 12.0% | 10.4%
renaissance-philosophers_default_G1.jfr | 28.2 | 7.1MB | 25.6% | 26.4% | 5.8% | 4.8%
renaissance-log-regression_default_G1.jfr | 34.4 | 9.5MB | 25.6% | 25.6% | 9.1% | 4.8%
renaissance-gauss-mix_default_G1.jfr | 23.4 | 7.4MB | 29.9% | 30.4% | 14.0% | 10.1%
renaissance-mnemonics_default_G1.jfr | 36.8 | 14.3MB | 22.5% | 24.1% | 3.5% | 3.1%

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

Use `reduced` for archiving (add `--compression-level MAX_COMPRESSION` for maximum compression).

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors