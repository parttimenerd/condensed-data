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

JFR file                                    | runtime (s) | original | compressed | per-hour | %     | per-hour | lossless | size    | %     | per-hour | default | size    | %     | per-hour | reduced | size    | %     | per-hour | archival-max | size    | %     | per-hour
------------------------------------------- | ----------- | -------- | ---------- | -------- | ----- | -------- | -------- | ------- | ----- | -------- | ------- | ------- | ----- | -------- | ------- | ------- | ----- | -------- | ------------ | ------- | ----- | --------
        renaissance-dotty_gc_details_G1.jfr |        70.3 |   12.8MB |      3.0MB |  653.4MB | 23.2% |  151.6MB |    7.3 s |   2.1MB | 16.4% |  106.9MB |   6.0 s |   1.0MB |  8.2% |   53.6MB |   3.4 s | 355.7KB |  2.7% |   17.8MB |        3.4 s | 355.7KB |  2.7% |   17.8MB
          renaissance-all_gc_details_G1.jfr |      1827.1 |  241.5MB |     55.1MB |  475.9MB | 22.8% |  108.5MB |  111.9 s |  49.9MB | 20.7% |   98.4MB |  74.4 s |  19.0MB |  7.9% |   37.4MB |  44.8 s |   7.7MB |  3.2% |   15.3MB |       44.8 s |   7.7MB |  3.2% |   15.3MB
                renaissance-dotty_gc_G1.jfr |        70.6 |  603.4KB |    255.8KB |   30.0MB | 42.4% |   12.7MB |    0.8 s | 215.8KB | 35.8% |   10.7MB |   0.8 s | 174.8KB | 29.0% |    8.7MB |   0.7 s | 147.1KB | 24.4% |    7.3MB |        0.7 s | 147.2KB | 24.4% |    7.3MB
                  renaissance-all_gc_G1.jfr |      1537.4 |   29.3MB |     11.9MB |   68.7MB | 40.6% |   27.9MB |   33.5 s |   8.1MB | 27.7% |   19.0MB |  30.9 s |   6.3MB | 21.6% |   14.9MB |  28.4 s |   4.9MB | 16.6% |   11.4MB |       28.5 s |   4.9MB | 16.6% |   11.4MB
           renaissance-dotty_default_G1.jfr |        67.8 |    6.0MB |      1.5MB |  317.0MB | 25.2% |   80.0MB |    4.0 s |   1.0MB | 16.9% |   53.6MB |   3.0 s | 534.8KB |  8.7% |   27.7MB |   1.9 s | 277.1KB |  4.5% |   14.4MB |        2.0 s | 277.1KB |  4.5% |   14.4MB
  renaissance-dotty_gc_details_SerialGC.jfr |        74.3 |    9.0MB |      2.0MB |  437.9MB | 22.1% |   96.8MB |    5.5 s |   1.3MB | 14.9% |   65.2MB |   4.2 s | 729.2KB |  7.9% |   34.5MB |   1.9 s | 189.9KB |  2.1% |    9.0MB |        1.4 s | 189.7KB |  2.0% |    9.0MB
    renaissance-all_gc_details_SerialGC.jfr |      1587.2 |  242.6MB |     50.4MB |  550.3MB | 20.8% |  114.4MB |  172.2 s |  48.9MB | 20.2% |  111.0MB | 115.3 s |  26.0MB | 10.7% |   58.9MB |  35.3 s |   5.2MB |  2.1% |   11.7MB |       35.5 s |   5.2MB |  2.1% |   11.7MB
renaissance-dotty_gc_details_ParallelGC.jfr |        71.2 |    5.4MB |      1.3MB |  271.8MB | 23.3% |   63.3MB |    4.5 s | 980.2KB | 17.8% |   48.4MB |   3.1 s | 446.9KB |  8.1% |   22.1MB |   1.5 s | 129.2KB |  2.3% |    6.4MB |        1.5 s | 129.1KB |  2.3% |    6.4MB
  renaissance-all_gc_details_ParallelGC.jfr |      1443.1 |  244.9MB |     52.4MB |  611.0MB | 21.4% |  130.8MB |  160.4 s |  39.8MB | 16.2% |   99.2MB | 110.7 s |  20.3MB |  8.3% |   50.5MB |  41.4 s |   3.9MB |  1.6% |    9.8MB |       41.7 s |   3.9MB |  1.6% |    9.8MB
       renaissance-dotty_gc_details_ZGC.jfr |        71.4 |   29.4MB |      5.8MB |    1.4GB | 19.8% |  293.7MB |   11.1 s |   2.7MB |  9.3% |  138.2MB |   8.2 s |   1.6MB |  5.6% |   82.9MB |   1.9 s | 220.1KB |  0.7% |   10.8MB |        2.1 s | 220.2KB |  0.7% |   10.8MB
         renaissance-all_gc_details_ZGC.jfr |      1917.0 |  249.8MB |     59.0MB |  469.2MB | 23.6% |  110.8MB |  223.5 s |  54.5MB | 21.8% |  102.4MB | 163.4 s |  28.5MB | 11.4% |   53.5MB |  49.9 s |   6.3MB |  2.5% |   11.9MB |       50.2 s |   6.3MB |  2.5% |   11.9MB
          renaissance-dotty_gc_SerialGC.jfr |        74.4 |  501.3KB |    194.4KB |   23.7MB | 38.8% |    9.2MB |    1.2 s | 178.9KB | 35.7% |    8.5MB |   1.2 s | 134.1KB | 26.8% |    6.3MB |   1.1 s | 112.7KB | 22.5% |    5.3MB |        1.1 s | 112.7KB | 22.5% |    5.3MB
            renaissance-all_gc_SerialGC.jfr |      1569.4 |   14.2MB |      5.3MB |   32.7MB | 37.0% |   12.1MB |   22.6 s |   5.0MB | 34.8% |   11.4MB |  19.1 s |   3.4MB | 23.7% |    7.7MB |  16.2 s |   2.4MB | 17.1% |    5.6MB |       16.2 s |   2.4MB | 17.1% |    5.6MB
        renaissance-dotty_gc_ParallelGC.jfr |        71.8 | 1020.3KB |    368.3KB |   49.9MB | 36.1% |   18.0MB |    1.2 s | 147.4KB | 14.4% |    7.2MB |   1.1 s | 130.0KB | 12.7% |    6.4MB |   1.0 s | 111.9KB | 11.0% |    5.5MB |        0.9 s | 111.8KB | 11.0% |    5.5MB
          renaissance-all_gc_ParallelGC.jfr |      1395.1 |   57.9MB |     17.0MB |  149.3MB | 29.5% |   44.0MB |   20.7 s |   4.0MB |  7.0% |   10.4MB |  18.6 s |   3.2MB |  5.5% |    8.2MB |  15.6 s |   1.9MB |  3.2% |    4.8MB |       15.8 s |   1.9MB |  3.2% |    4.8MB
               renaissance-dotty_gc_ZGC.jfr |        74.0 |  702.2KB |    301.7KB |   33.4MB | 43.0% |   14.3MB |    1.5 s | 270.1KB | 38.5% |   12.8MB |   1.3 s | 183.4KB | 26.1% |    8.7MB |   1.2 s | 145.6KB | 20.7% |    6.9MB |        1.3 s | 145.6KB | 20.7% |    6.9MB
                 renaissance-all_gc_ZGC.jfr |      1808.5 |   89.9MB |     38.2MB |  179.0MB | 42.5% |   76.0MB |  168.9 s |  35.4MB | 39.4% |   70.5MB | 133.8 s |  19.3MB | 21.5% |   38.5MB | 128.2 s |  17.7MB | 19.6% |   35.1MB |      128.7 s |  17.7MB | 19.6% |   35.1MB
        renaissance-scrabble_default_G1.jfr |         8.8 |   10.1MB |      2.2MB |    4.1GB | 21.5% |  894.2MB |    7.6 s |   2.5MB | 24.3% | 1011.4MB |   2.8 s | 293.7KB |  2.8% |  117.6MB |   2.6 s | 259.4KB |  2.5% |  103.9MB |        2.6 s | 259.4KB |  2.5% |  103.9MB
       renaissance-page-rank_default_G1.jfr |        93.9 |   38.7MB |     10.1MB |    1.5GB | 26.0% |  385.6MB |   35.0 s |  10.1MB | 26.0% |  385.9MB |  20.4 s |   2.7MB |  7.0% |  103.2MB |  19.1 s |   2.4MB |  6.2% |   91.4MB |       18.6 s |   2.4MB |  6.2% |   91.4MB
  renaissance-future-genetic_default_G1.jfr |        62.6 |   11.0MB |      2.6MB |  634.0MB | 24.0% |  152.1MB |   10.3 s |   2.7MB | 24.7% |  156.5MB |   3.9 s | 590.6KB |  5.2% |   33.2MB |   3.2 s | 457.0KB |  4.0% |   25.7MB |        3.1 s | 457.1KB |  4.0% |   25.7MB
      renaissance-movie-lens_default_G1.jfr |       559.0 |   79.1MB |     23.4MB |  509.4MB | 29.6% |  150.9MB |   69.9 s |  21.0MB | 26.5% |  135.1MB |  51.4 s |   9.1MB | 11.5% |   58.8MB |  39.2 s |   5.8MB |  7.3% |   37.1MB |       38.5 s |   5.8MB |  7.3% |   37.1MB
      renaissance-scala-doku_default_G1.jfr |        33.6 |    1.4MB |    390.3KB |  146.1MB | 28.0% |   40.9MB |    1.8 s | 353.5KB | 25.3% |   37.0MB |   1.4 s | 179.1KB | 12.8% |   18.7MB |   1.2 s | 144.1KB | 10.3% |   15.1MB |        1.2 s | 144.1KB | 10.3% |   15.1MB
      renaissance-chi-square_default_G1.jfr |        34.4 |   18.4MB |      4.2MB |    1.9GB | 22.7% |  437.5MB |   16.9 s |   4.4MB | 23.8% |  458.9MB |   8.0 s | 780.7KB |  4.1% |   79.9MB |   7.3 s | 608.7KB |  3.2% |   62.3MB |        7.2 s | 608.7KB |  3.2% |   62.3MB
       renaissance-fj-kmeans_default_G1.jfr |        62.0 |   40.4MB |      8.6MB |    2.3GB | 21.2% |  496.9MB |   33.6 s |   8.9MB | 22.1% |  519.6MB |  15.2 s |   1.2MB |  3.1% |   71.8MB |  14.0 s |   1.1MB |  2.6% |   61.7MB |       14.2 s |   1.1MB |  2.6% |   61.7MB
     renaissance-rx-scrabble_default_G1.jfr |         8.1 |    2.0MB |    502.0KB |  882.0MB | 24.8% |  219.1MB |    2.8 s | 511.1KB | 25.3% |  223.1MB |   1.9 s | 167.6KB |  8.3% |   73.2MB |   1.7 s | 133.9KB |  6.6% |   58.5MB |        1.7 s | 133.9KB |  6.6% |   58.5MB
 renaissance-neo4j-analytics_default_G1.jfr |        42.4 |   11.1MB |      2.5MB |  945.6MB | 22.8% |  215.2MB |   13.1 s |   2.5MB | 22.7% |  215.1MB |   8.6 s | 730.1KB |  6.4% |   60.5MB |   7.5 s | 531.8KB |  4.7% |   44.0MB |        7.5 s | 531.8KB |  4.7% |   44.0MB
        renaissance-reactors_default_G1.jfr |        63.6 |    7.7MB |      1.9MB |  435.3MB | 25.0% |  108.9MB |   10.9 s |   2.0MB | 25.7% |  112.0MB |   7.3 s | 569.5KB |  7.2% |   31.5MB |   6.8 s | 475.0KB |  6.0% |   26.3MB |        6.7 s | 475.0KB |  6.0% |   26.3MB
        renaissance-dec-tree_default_G1.jfr |        31.4 |   10.7MB |      2.8MB |    1.2GB | 26.3% |  323.8MB |   13.9 s |   2.8MB | 25.6% |  315.7MB |  10.2 s | 998.0KB |  9.1% |  111.9MB |   8.7 s | 657.1KB |  6.0% |   73.7MB |        8.5 s | 657.1KB |  6.0% |   73.7MB
renaissance-scala-stm-bench7_default_G1.jfr |        52.7 |    9.7MB |      2.5MB |  662.6MB | 25.3% |  167.6MB |   11.3 s |   2.5MB | 25.4% |  168.6MB |   7.1 s | 660.2KB |  6.6% |   44.0MB |   6.5 s | 562.7KB |  5.7% |   37.5MB |        6.4 s | 562.7KB |  5.7% |   37.5MB
     renaissance-naive-bayes_default_G1.jfr |        60.2 |   46.1MB |      9.6MB |    2.7GB | 20.8% |  573.3MB |   42.3 s |  10.2MB | 22.1% |  608.1MB |  17.0 s |   1.4MB |  3.0% |   83.5MB |  16.4 s |   1.2MB |  2.5% |   69.9MB |       16.4 s |   1.2MB |  2.5% |   69.9MB
             renaissance-als_default_G1.jfr |       128.2 |   21.7MB |      5.5MB |  608.0MB | 25.5% |  154.9MB |   32.8 s |   5.2MB | 23.8% |  144.6MB |  21.2 s |   1.8MB |  8.2% |   49.8MB |  15.8 s |   1.1MB |  4.9% |   29.7MB |       15.7 s |   1.1MB |  4.9% |   29.7MB
   renaissance-par-mnemonics_default_G1.jfr |        30.4 |   14.0MB |      3.0MB |    1.6GB | 21.6% |  356.8MB |   14.7 s |   3.3MB | 23.7% |  392.4MB |   5.6 s | 403.2KB |  2.8% |   46.6MB |   5.3 s | 346.8KB |  2.4% |   40.1MB |        5.2 s | 346.8KB |  2.4% |   40.1MB
    renaissance-scala-kmeans_default_G1.jfr |        10.2 |    1.1MB |    307.8KB |  379.3MB | 28.1% |  106.6MB |    2.4 s | 296.2KB | 27.0% |  102.6MB |   1.9 s | 131.8KB | 12.0% |   45.6MB |   1.7 s | 114.1KB | 10.4% |   39.5MB |        1.6 s | 114.1KB | 10.4% |   39.5MB
    renaissance-philosophers_default_G1.jfr |        28.2 |    7.1MB |      1.8MB |  902.6MB | 25.6% |  230.8MB |   10.8 s |   1.9MB | 26.4% |  238.5MB |   5.8 s | 421.7KB |  5.8% |   52.6MB |   5.0 s | 348.4KB |  4.8% |   43.4MB |        5.0 s | 348.4KB |  4.8% |   43.4MB
  renaissance-log-regression_default_G1.jfr |        34.4 |    9.5MB |      2.4MB |  993.7MB | 25.6% |  254.1MB |   13.2 s |   2.4MB | 25.6% |  254.5MB |   9.7 s | 880.7KB |  9.1% |   90.1MB |   6.6 s | 466.9KB |  4.8% |   47.8MB |        6.7 s | 466.9KB |  4.8% |   47.8MB
       renaissance-gauss-mix_default_G1.jfr |        23.4 |    7.4MB |      2.2MB |    1.1GB | 29.9% |  338.7MB |   15.0 s |   2.2MB | 30.4% |  345.1MB |  11.7 s |   1.0MB | 14.0% |  159.3MB |   9.5 s | 762.4KB | 10.1% |  114.4MB |        9.5 s | 762.4KB | 10.1% |  114.4MB
       renaissance-mnemonics_default_G1.jfr |        36.8 |   14.3MB |      3.2MB |    1.4GB | 22.5% |  314.7MB |   16.4 s |   3.5MB | 24.1% |  337.6MB |   7.4 s | 508.5KB |  3.5% |   48.6MB |   7.0 s | 451.1KB |  3.1% |   43.1MB |        7.1 s | 451.1KB |  3.1% |   43.1MB

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors