Condensed Data
==============

[![ci](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml)

A library and CLI tool for reading and writing condensed JFR event data to disk.
Focuses on a simple, self-describing, space-saving format.
Stores JFR data via a compressing Java agent that supports file rotation and
live attachment to running JVMs.

Primary use case: long-term storage of GC-related JFR recordings.

**[Getting Started Guide](GETTING_STARTED.md)** — installation, quickstart, agent usage, configuration guide, and troubleshooting.

Usage
-----

Download from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest) or build from source (see [Getting Started](GETTING_STARTED.md)).

The tool can be used via its CLI:
```shell
> java -jar target/condensed-data.jar -h
Usage: cjfr [-hV] [COMMAND]
CLI for the JFR condenser project
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  condense             Condense a JFR file
  inflate              Inflate a condensed JFR file into JFR format
  agent                Use the included Java agent on a specific JVM process
  summary              Print a summary of the condensed JFR file
  view                 View a specific event of a condensed JFR file as a table
```
But you can also use its built-in Java agent to directly record condensed JFR files:
```shell
> java -javaagent:target/condensed-data.jar=help
Usage: java -javaagent:condensed-agent.jar='[COMMAND]'
Agent for recording condensed JFR files
Commands:
  start             Start the recording
  stop              Stop the recording
  status            Get the status of the recording
  set-max-size      Set the max file size
  set-max-duration  Set the max duration of each individual recording when
                      rotating files
  set-max-files     Set the max file count when rotating
  set-duration      Set the duration of the overall recording
  help              Print help information
```
```shell
> java -javaagent:target/condensed-data.jar=start,--help
Usage: -javaagent:condensed-agent.jar start [-h] [--new-names] [--rotating]
       [--verbose] [-c=<jfrConfig>] [-d=<configuration>]
       [--duration=<duration>] [-m=<miscJfrConfig>]
       [--max-duration=<maxDuration>] [--max-files=<maxFiles>]
       [--max-size=<maxSize>] [PATH]
Start the recording
      [PATH]                 Path to the recording file .cjfr file
  -c, --config=<jfrConfig>   The JFR generatorConfiguration to use.
  -d, --condenser-config=<configuration>
                             The condenser generatorConfiguration to use,
                               possible values: default, reasonable-default,
                               reduced-default
      --duration=<duration>  The duration of the whole recording, 0 for
                               unlimited
  -h, --help                 Show this help message and exit.
  -m, --misc-jfr-config=<miscJfrConfig>
                             Additional JFR config, '|' separated, like 'jfr.
                               ExecutionSample#interval=1s'
      --max-duration=<maxDuration>
                             The maximum duration of each individual recording,
                               0 for unlimited, when rotating files
      --max-files=<maxFiles> The maximum number of files to keep, when rotating
                               files
      --max-size=<maxSize>   The maximum size of the recording file (or the
                               individual files when rotating files)
      --new-names            When rotating files, use new names instead of
                               reusing old ones
      --rotating             Use rotating files and replace $date and $index in
                               the file names, if no place holder is specified,
                               replaces '.cjfr' with '_$index.cjfr'
      --verbose              Be verbose
```

Third-party Bug Hunting
-----------------------

You can use condensed-data as a differential bug-finding tool against third-party JFR corpora.

Run the automated harness:
```shell
python3 bin/third_party_bug_hunt.py /path/to/third-party-jfrs
```

This runs `condense -> summary -> inflate -> summary` across multiple configurations and
compression algorithms, and reports event-count/event-type mismatches in
`tmp/third_party_bug_hunt/report.json`.

See [THIRD_PARTY_BUG_HUNT.md](THIRD_PARTY_BUG_HUNT.md) for extensive workflows.

Known Limitations / Open Work:
- [x] Agent crash-resilience integration tests (corrupt config, disk full, double-attach)
- [x] `BasicObjectAllocationCombiner` for allocation events (group by thread/class, store summed sizes)
- [x] Consider inlining small structs in `JFRHashConfig.getEmbeddingType()` for better compression ratio (benchmarked: no improvement, tests added)

Requirements
------------
JDK 17+

File Format
-----------
The file format is described in [FORMAT.md](doc/FORMAT.md) and
is designed to be

- simple
- self-describing (the type information is stored in the file)
- compressed (supports the following compression algorithms natively: NONE, GZIP, LZ4FRAMED; default: LZ4FRAMED)
- space efficient (e.g. by using varints and caches)
- streamable
- allows to reduce event data even further by using reducers and reconstitutors

In many cases, we can reduce accuracy without losing the gist of the data.
This drastically reduces the size of the data.

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

```shell
**Benchmark run on 2026-04-27 16:56:11**

JFR file                                    | runtime (s) | original     | compressed  | per-hour      | %      | per-hour      | default | size        | %      | per-hour     | reasonable-default | size         | %      | per-hour     | reduced-default | size        | %      | per-hour    
------------------------------------------- | ----------- | ------------ | ----------- | ------------- | ------ | ------------- | ------- | ----------- | ------ | ------------ | ------------------ | ------------ | ------ | ------------ | --------------- | ----------- | ------ | ------------
        renaissance-dotty_gc_details_G1.jfr |       70,26 |  12.752287MB |  2.959194MB |  653.363342MB | 23,21% |  151.614288MB |  2,68 s |  1.019384MB |  7,99% |  52.228149MB |             2,04 s |    639.008KB |  4,89% |  31.972227MB |          0,77 s |   163.620KB |  1,25% |   8.186597MB
          renaissance-all_gc_details_G1.jfr |     1827,15 | 241.533397MB | 55.063511MB |  475.890076MB | 22,80% |  108.490906MB | 47,64 s | 20.522795MB |  8,50% |  40.435791MB |            38,20 s | 10.9037285MB |  4,51% |  21.483473MB |          4,83 s |  2.177089MB |  0,90% |   4.289489MB
                renaissance-dotty_gc_G1.jfr |       70,63 |    603.439KB |   255.816KB |   30.036728MB | 42,39% |   12.733486MB |  0,12 s |   182.868KB | 30,30% |   9.102424MB |             0,10 s |    111.881KB | 18,54% |   5.568968MB |          0,07 s |    94.592KB | 15,68% |   4.708389MB
                  renaissance-all_gc_G1.jfr |     1537,39 |  29.324034MB | 11.906271MB |   68.665947MB | 40,60% |   27.880043MB |  6,05 s |  8.321565MB | 28,38% |  19.485998MB |             3,02 s |   3.508536MB | 11,96% |   8.215682MB |          2,31 s | 2.5080185MB |  8,55% |   5.872843MB
           renaissance-dotty_default_G1.jfr |       67,83 |   5.972811MB |  1.507894MB |  316.978638MB | 25,25% |     80.0243MB |  0,49 s |   450.754KB |  7,37% |  23.360964MB |             0,34 s |    334.394KB |  5,47% |  17.330423MB |          0,16 s |   157.546KB |  2,58% |   8.165041MB
  renaissance-dotty_gc_details_SerialGC.jfr |       74,30 |   9.037703MB |  1.998353MB |  437.903717MB | 22,11% |  96.8261795MB |  1,87 s |   813.609KB |  8,79% |  38.497856MB |             1,27 s |    490.643KB |  5,30% |  23.215918MB |          0,16 s |   142.389KB |  1,54% |   6.737458MB
    renaissance-all_gc_details_SerialGC.jfr |     1587,18 | 242.606878MB | 50.445438MB |  550.273926MB | 20,79% |  114.418877MB | 67,30 s |  29.61804MB | 12,21% |   67.17878MB |            54,30 s |  16.324868MB |  6,73% |  37.027592MB |          4,36 s |  3.915626MB |  1,61% |   8.881309MB
renaissance-dotty_gc_details_ParallelGC.jfr |       71,18 |   5.373575MB |  1.250996MB |  271.769348MB | 23,28% |   63.269287MB |  1,53 s |   616.900KB | 11,21% |  30.468584MB |             0,99 s |    303.316KB |  5,51% |  14.980734MB |          0,10 s |    84.286KB |  1,53% |   4.162874MB
  renaissance-all_gc_details_ParallelGC.jfr |     1443,14 | 244.915904MB | 52.439739MB |  610.957275MB | 21,41% |  130.814041MB | 73,59 s | 30.594476MB | 12,49% |  76.319733MB |            51,38 s |  13.012482MB |  5,31% |  32.460407MB |          4,31 s |  2.739314MB |  1,12% |   6.833382MB
       renaissance-dotty_gc_details_ZGC.jfr |       71,39 |  29.350696MB |  5.824309MB | 1.445422173GB | 19,84% |  293.711334MB |  4,64 s |   1.60335MB |  5,46% |  80.854561MB |             3,15 s |    1.02967MB |  3,51% |  51.924728MB |          0,56 s |   159.401KB |  0,53% |   7.849977MB
         renaissance-all_gc_details_ZGC.jfr |     1917,04 | 249.840623MB | 58.992313MB |  469.173279MB | 23,61% |  110.781097MB | 83,88 s | 32.487244MB | 13,00% |  61.007481MB |            66,36 s |  16.975392MB |  6,79% |  31.877924MB |          9,99 s |  4.405986MB |  1,76% |   8.273958MB
          renaissance-dotty_gc_SerialGC.jfr |       74,36 |    501.309KB |   194.442KB |   23.700899MB | 38,79% |    9.192859MB |  0,08 s |   125.914KB | 25,12% |   5.952972MB |             0,08 s |     89.785KB | 17,91% |   4.244868MB |          0,07 s |    80.645KB | 16,09% |   3.812717MB
            renaissance-all_gc_SerialGC.jfr |     1569,44 |  14.241157MB |  5.267843MB |   32.666573MB | 36,99% |   12.083456MB |  1,98 s |  3.259761MB | 22,89% |   7.477287MB |             1,58 s |   2.081347MB | 14,62% |   4.774225MB |          1,19 s |  1.514347MB | 10,63% |   3.473631MB
        renaissance-dotty_gc_ParallelGC.jfr |       71,82 |   1020.327KB |   368.347KB |   49.942814MB | 36,10% |   18.029776MB |  0,22 s |  255.8955KB | 25,08% |  12.525534MB |             0,07 s |     80.946KB |  7,93% |   3.962146MB |          0,06 s |    67.560KB |  6,62% |   3.306895MB
          renaissance-all_gc_ParallelGC.jfr |     1395,09 |   57.85318MB | 17.042738MB |  149.288635MB | 29,46% |   43.978348MB | 13,94 s | 11.612105MB | 20,07% |  29.964737MB |             2,60 s |   1.920723MB |  3,32% |   4.956376MB |          1,56 s |  1.046277MB |  1,81% |    2.69989MB
               renaissance-dotty_gc_ZGC.jfr |       73,97 |    702.165KB |   301.665KB |   33.373402MB | 42,96% |   14.337924MB |  0,13 s |   197.385KB | 28,11% |   9.381557MB |             0,12 s |    127.474KB | 18,15% |   6.058731MB |          0,10 s |   104.539KB | 14,89% |   4.968667MB
                 renaissance-all_gc_ZGC.jfr |     1808,46 |  89.929731MB | 38.182118MB |  179.018402MB | 42,46% |   76.007141MB | 19,86 s | 25.898249MB | 28,80% |  51.554283MB |            19,41 s |  13.779075MB | 15,32% |  27.429279MB |         18,83 s | 12.601996MB | 14,01% |  25.086132MB
        renaissance-scrabble_default_G1.jfr |        8,78 |  10.139577MB | 2.1809845MB | 4.059794903GB | 21,51% |  894.204407MB |  0,18 s |   136.727KB |  1,32% |   54.74408MB |             0,17 s |    107.229KB |  1,03% |  42.933327MB |          0,16 s |    91.330KB |  0,88% |  36.567738MB
       renaissance-page-rank_default_G1.jfr |       93,91 |   38.74173MB | 10.057923MB | 1.450346589GB | 25,96% |  385.568085MB |  0,87 s |   688.555KB |  1,74% |  25.776932MB |             0,82 s |    478.910KB |  1,21% |  17.928619MB |          0,66 s |   308.617KB |  0,78% |  11.553483MB
  renaissance-future-genetic_default_G1.jfr |       62,62 |   11.02812MB |  2.645244MB |   634.01593MB | 23,99% |  152.077286MB |  0,37 s |   335.991KB |  2,98% | 18.8636875MB |             0,30 s |    224.164KB |  1,99% |  12.585332MB |          0,21 s |   157.422KB |  1,39% |   8.838199MB
      renaissance-movie-lens_default_G1.jfr |      558,97 |  79.089788MB | 23.428468MB |  509.370758MB | 29,62% |  150.888962MB |  6,25 s |   7.24066MB |  9,15% |  46.632824MB |             5,20 s |   4.188781MB |  5,30% |   26.97747MB |          2,28 s | 2.1497755MB |  2,72% |  13.845438MB
      renaissance-scala-doku_default_G1.jfr |       33,58 |   1.362984MB |   390.270KB |  146.116058MB | 27,96% |   40.857517MB |  0,10 s |   120.752KB |  8,65% |  12.641583MB |             0,09 s |     98.261KB |  7,04% |  10.286967MB |          0,05 s |    75.078KB |  5,38% |   7.859967MB
      renaissance-chi-square_default_G1.jfr |       34,35 |  18.406825MB |  4.174436MB |  1.88377738GB | 22,68% |  437.470154MB |  0,39 s |   433.953KB |  2,30% |  44.411304MB |             0,35 s |    308.681KB |  1,64% |  31.590763MB |          0,27 s |   216.275KB |  1,15% |  22.133892MB
       renaissance-fj-kmeans_default_G1.jfr |       61,99 |  40.407334MB |  8.556082MB | 2.291518211GB | 21,17% |  496.864532MB |  0,83 s |   548.898KB |  1,33% |  31.128279MB |             0,64 s |    326.920KB |  0,79% |  18.539778MB |          0,63 s |   252.650KB |  0,61% |  14.327918MB
     renaissance-rx-scrabble_default_G1.jfr |        8,05 |   1.973365MB |   502.029KB |  882.008606MB | 24,84% |  219.126312MB |  0,08 s |   119.997KB |  5,94% |  52.376457MB |             0,07 s |     94.241KB |  4,66% |   41.13451MB |          0,05 s |    73.820KB |  3,65% |  32.221172MB
 renaissance-neo4j-analytics_default_G1.jfr |       42,44 |   11.14803MB |   2.53684MB |  945.583923MB | 22,76% |  215.176636MB |  0,48 s |   484.761KB |  4,25% |  40.154057MB |             0,36 s |    358.208KB |  3,14% | 29.6713505MB |          0,23 s |   229.067KB |  2,01% |  18.974279MB
        renaissance-reactors_default_G1.jfr |       63,61 |   7.692236MB |  1.924967MB |  435.349152MB | 25,02% |  108.945267MB |  0,32 s |   388.408KB |  4,93% |  21.467108MB |             0,29 s |    261.673KB |  3,32% |  14.462515MB |          0,22 s |   211.649KB |  2,69% |  11.697747MB
        renaissance-dec-tree_default_G1.jfr |       31,36 |  10.728335MB |  2.820837MB | 1.202545881GB | 26,29% |  323.777954MB |  0,59 s |   713.500KB |  6,49% |  79.976669MB |             0,49 s |    479.114KB |  4,36% |   53.70422MB |          0,27 s |   272.359KB |  2,48% |  30.528936MB
renaissance-scala-stm-bench7_default_G1.jfr |       52,75 |   9.708675MB |   2.45492MB | 662.6414795MB | 25,29% |  167.554428MB |  0,31 s |   293.138KB |  2,95% |   19.53846MB |             0,26 s |    206.726KB |  2,08% |  13.778847MB |          0,20 s |   154.865KB |  1,56% |  10.322207MB
     renaissance-naive-bayes_default_G1.jfr |       60,24 | 46.0981865MB |  9.592987MB | 2.690302372GB | 20,81% |  573.285645MB |  0,76 s |  674.6875KB |  1,43% |  39.374943MB |             0,67 s |    458.838KB |  0,97% |  26.777901MB |          0,66 s |   337.878KB |  0,72% |  19.718647MB
             renaissance-als_default_G1.jfr |      128,23 |  21.656352MB |  5.515587MB |  608.004395MB | 25,47% |  154.850693MB |  1,36 s |  1.388979MB |  6,41% |  38.995731MB |             1,12 s |    896.857KB |  4,04% |  24.589226MB |          0,50 s |   430.641KB |  1,94% |  11.806914MB
   renaissance-par-mnemonics_default_G1.jfr |       30,44 |  13.984857MB | 3.0166235MB | 1.615321159GB | 21,57% |  356.797638MB |  0,25 s |   199.840KB |  1,40% |  23.082508MB |             0,22 s |    147.032KB |  1,03% |  16.982962MB |          0,21 s |   122.740KB |  0,86% | 14.1771145MB
    renaissance-scala-kmeans_default_G1.jfr |       10,15 |   1.069575MB |   307.848KB |  379.322998MB | 28,11% |  106.618774MB |  0,06 s |    98.159KB |  8,96% |  33.996075MB |             0,06 s |     78.861KB |  7,20% |  27.312529MB |          0,04 s |    67.282KB |  6,14% |  23.302267MB
    renaissance-philosophers_default_G1.jfr |       28,20 |   7.070744MB |   1.80775MB |   902.56073MB | 25,57% |  230.754227MB |  0,20 s |   254.479KB |  3,51% |  31.722143MB |             0,19 s |    178.619KB |  2,47% |  22.265856MB |          0,18 s |   144.693KB |  2,00% |  18.036821MB
  renaissance-log-regression_default_G1.jfr |       34,37 |    9.48615MB |   2.42593MB |  993.684814MB | 25,57% | 254.1188965MB |  0,62 s |   749.733KB |  7,72% |   76.69474MB |             0,51 s |   486.3955KB |  5,01% |  49.756325MB |          0,20 s |   211.705KB |  2,18% |  21.656588MB
       renaissance-gauss-mix_default_G1.jfr |       23,42 |   7.383413MB |  2.204135MB | 1.108117223GB | 29,85% |  338.740143MB |  0,61 s |   913.976KB | 12,09% | 137.171265MB |             0,49 s |    548.839KB |  7,26% |  82.370819MB |          0,30 s |   374.258KB |  4,95% | 56.1693535MB
       renaissance-mnemonics_default_G1.jfr |       36,81 |  14.319726MB |  3.218011MB | 1.367453456GB | 22,47% |  314.677216MB |  0,32 s |   238.131KB |  1,62% |   22.74016MB |             0,27 s |   166.6455KB |  1,14% |  15.913711MB |          0,27 s |   146.554KB |  1,00% |  13.995057MB
```

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors