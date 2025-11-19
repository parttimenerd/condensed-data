Condensed Data
==============

[![ci](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml)

A library and tool for reading and writing condensed JFR event data
to disk. Focusing on a simple, yet space saving, format.
Storing JFR data via a compressing agent that allows file rotations
and more.

The main usage is to compress JFR files related to GC.

Usage
-----

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
  generate-completion  Generate an auto-completion script for bash and zsh for
                         cjfr
  help                 Print help information
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
> java -javaagent:target/condensed-data.jar="start --help"
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

Autocompletion:

```
. <(./cjfr generate-completion)
```

TODO:
- javaagent
  - check what happens if any error is thrown somewhere deep down in the agent
- logging (don't log anything in default warning mode)
  - seems to be missing new lines
    ```
      ➜  condensed-data git:(main) ✗ java  -javaagent:target/condensed-data.jar=start,profiling -jar benchmark/renaissance-gpl-0.15.0.jar all
      Condensed recording to profiling startedBenchmark 'db-shootout' excluded: requires JVM version >=11 and <=18 (found 22).
      ====== scrabble (functional) [default], iteration 0 started ======52046/  renaissance-gpl-0.15.0.jar                                                                                 
      GC bef
    ```
- add examples to the README

Requirements
------------
JDK 17+

File Format
-----------
The file format is described in [FORMAT.md](doc/FORMAT.md) and
is designed to be

- simple
- self-describing (the type information is stored in the file)
- compressed (supports the following compression algorithms natively: NONE, GZIP, XZ, BZIP2, ZSTD; default: XZ)
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
**Benchmark run on 2025-10-22 11:23:21**

JFR file                                    | runtime (s) | original   | compressed | per-hour | %      | per-hour | default | size       | %      | per-hour | reasonable-default | size      | %      | per-hour | reduced-default | size      | %      | per-hour
------------------------------------------- | ----------- | ---------- | ---------- | -------- | ------ | -------- | ------- | ---------- | ------ | -------- | ------------------ | --------- | ------ | -------- | --------------- | --------- | ------ | --------
        renaissance-dotty_gc_details_G1.jfr |       70,26 |   12.752MB |    2.959MB |  653.4MB | 23,21% |  151.6MB |  1,03 s |    1.968MB | 15,44% |  100.9MB |             1,01 s |   1.498MB | 11,75% |   76.8MB |          0,98 s |   1.398MB | 10,96% |   71.6MB
          renaissance-all_gc_details_G1.jfr |     1827,15 |  241.533MB |   55.064MB |  475.9MB | 22,80% |  108.5MB | 10,21 s |   35.586MB | 14,73% |   70.1MB |             9,41 s |  19.720MB |  8,16% |   38.9MB |          8,33 s |  16.622MB |  6,88% |   32.7MB
                renaissance-dotty_gc_G1.jfr |       70,63 |  603.439KB |  255.816KB |   30.0MB | 42,39% |   12.7MB |  0,03 s |  200.118KB | 33,16% |   10.0MB |             0,03 s | 147.826KB | 24,50% |    7.4MB |          0,03 s | 111.369KB | 18,46% |    5.5MB
                  renaissance-all_gc_G1.jfr |     1537,39 |   29.324MB |   11.906MB |   68.7MB | 40,60% |   27.9MB |  1,24 s |    9.779MB | 33,35% |   22.9MB |             1,20 s |   6.475MB | 22,08% |   15.2MB |          0,87 s |   3.010MB | 10,26% |    7.0MB
           renaissance-dotty_default_G1.jfr |       67,83 |    5.973MB |    1.508MB |  317.0MB | 25,25% |   80.0MB |  0,29 s | 1011.438KB | 16,54% |   52.4MB |             0,28 s | 745.199KB | 12,18% |   38.6MB |          0,12 s | 247.383KB |  4,04% |   12.8MB
  renaissance-dotty_gc_details_SerialGC.jfr |       74,30 |    9.038MB |    1.998MB |  437.9MB | 22,11% |   96.8MB |  0,44 s |    1.251MB | 13,84% |   60.6MB |             0,44 s |   1.039MB | 11,50% |   50.3MB |          0,44 s |   1.030MB | 11,40% |   49.9MB
    renaissance-all_gc_details_SerialGC.jfr |     1587,18 |  242.607MB |   50.445MB |  550.3MB | 20,79% |  114.4MB | 11,57 s |   36.043MB | 14,86% |   81.8MB |            11,34 s |  23.438MB |  9,66% |   53.2MB |         11,33 s |  23.043MB |  9,50% |   52.3MB
renaissance-dotty_gc_details_ParallelGC.jfr |       71,18 |    5.374MB |    1.251MB |  271.8MB | 23,28% |   63.3MB |  0,22 s |  732.692KB | 13,32% |   36.2MB |             0,20 s | 538.504KB |  9,79% |   26.6MB |          0,20 s | 478.938KB |  8,70% |   23.7MB
  renaissance-all_gc_details_ParallelGC.jfr |     1443,14 |  244.916MB |   52.440MB |  611.0MB | 21,41% |  130.8MB | 12,46 s |   37.716MB | 15,40% |   94.1MB |            11,63 s |  22.656MB |  9,25% |   56.5MB |         10,14 s |  18.867MB |  7,70% |   47.1MB
       renaissance-dotty_gc_details_ZGC.jfr |       71,39 |   29.351MB |    5.824MB |    1.4GB | 19,84% |  293.7MB |  1,61 s |    3.332MB | 11,35% |  168.0MB |             1,61 s |   2.957MB | 10,07% |  149.1MB |          1,61 s |   2.919MB |  9,95% |  147.2MB
         renaissance-all_gc_details_ZGC.jfr |     1917,04 |  249.841MB |   58.992MB |  469.2MB | 23,61% |  110.8MB | 11,17 s |   38.451MB | 15,39% |   72.2MB |            11,17 s |  23.054MB |  9,23% |   43.3MB |         11,17 s |  22.630MB |  9,06% |   42.5MB
          renaissance-dotty_gc_SerialGC.jfr |       74,36 |  501.309KB |  194.442KB |   23.7MB | 38,79% |    9.2MB |  0,03 s |  144.735KB | 28,87% |    6.8MB |             0,03 s | 103.154KB | 20,58% |    4.9MB |          0,03 s |  96.333KB | 19,22% |    4.6MB
            renaissance-all_gc_SerialGC.jfr |     1569,44 |   14.241MB |    5.268MB |   32.7MB | 36,99% |   12.1MB |  0,48 s |    4.112MB | 28,87% |    9.4MB |             0,45 s |   2.880MB | 20,22% |    6.6MB |          0,36 s |   1.858MB | 13,04% |    4.3MB
        renaissance-dotty_gc_ParallelGC.jfr |       71,82 | 1020.327KB |  368.347KB |   49.9MB | 36,10% |   18.0MB |  0,07 s |  276.290KB | 27,08% |   13.5MB |             0,05 s | 184.945KB | 18,13% |    9.1MB |          0,05 s |  82.249KB |  8,06% |    4.0MB
          renaissance-all_gc_ParallelGC.jfr |     1395,09 |   57.853MB |   17.043MB |  149.3MB | 29,46% |   44.0MB |  3,21 s |   12.618MB | 21,81% |   32.6MB |             2,87 s |   6.088MB | 10,52% |   15.7MB |          1,00 s |   1.345MB |  2,32% |    3.5MB
               renaissance-dotty_gc_ZGC.jfr |       73,97 |  702.165KB |  301.665KB |   33.4MB | 42,96% |   14.3MB |  0,04 s |  201.673KB | 28,72% |    9.6MB |             0,03 s | 121.644KB | 17,32% |    5.8MB |          0,03 s | 103.214KB | 14,70% |    4.9MB
                 renaissance-all_gc_ZGC.jfr |     1808,46 |   89.930MB |   38.182MB |  179.0MB | 42,46% |   76.0MB |  4,57 s |   28.713MB | 31,93% |   57.2MB |             4,42 s |  19.062MB | 21,20% |   37.9MB |          4,38 s |  18.103MB | 20,13% |   36.0MB
        renaissance-scrabble_default_G1.jfr |        8,78 |   10.140MB |    2.181MB |    4.1GB | 21,51% |  894.2MB |  0,57 s |    1.207MB | 11,90% |  494.7MB |             0,45 s | 223.788KB |  2,16% |   89.6MB |          0,13 s | 124.680KB |  1,20% |   49.9MB
       renaissance-page-rank_default_G1.jfr |       93,91 |   38.742MB |   10.058MB |    1.5GB | 25,96% |  385.6MB |  1,69 s |    5.814MB | 15,01% |  222.9MB |             1,39 s |   1.099MB |  2,84% |   42.1MB |          0,59 s | 557.105KB |  1,40% |   20.9MB
  renaissance-future-genetic_default_G1.jfr |       62,62 |   11.028MB |    2.645MB |  634.0MB | 23,99% |  152.1MB |  0,44 s |    1.706MB | 15,47% |   98.1MB |             0,33 s | 541.989KB |  4,80% |   30.4MB |          0,14 s | 215.775KB |  1,91% |   12.1MB
      renaissance-movie-lens_default_G1.jfr |      558,97 |   79.090MB |   23.428MB |  509.4MB | 29,62% |  150.9MB |  3,03 s |   18.304MB | 23,14% |  117.9MB |             2,64 s |   9.447MB | 11,94% |   60.8MB |          1,45 s |   5.752MB |  7,27% |   37.0MB
      renaissance-scala-doku_default_G1.jfr |       33,58 |    1.363MB |  390.270KB |  146.1MB | 27,96% |   40.9MB |  0,06 s |  271.161KB | 19,43% |   28.4MB |             0,05 s | 145.241KB | 10,41% |   15.2MB |          0,03 s | 102.182KB |  7,32% |   10.7MB
      renaissance-chi-square_default_G1.jfr |       34,35 |   18.407MB |    4.174MB |    1.9GB | 22,68% |  437.5MB |  0,68 s |    2.535MB | 13,77% |  265.7MB |             0,53 s | 706.811KB |  3,75% |   72.3MB |          0,24 s | 377.192KB |  2,00% |   38.6MB
       renaissance-fj-kmeans_default_G1.jfr |       61,99 |   40.407MB |    8.556MB |    2.3GB | 21,17% |  496.9MB |  1,79 s |    5.264MB | 13,03% |  305.7MB |             1,34 s |   1.107MB |  2,74% |   64.3MB |          0,58 s | 433.930KB |  1,05% |   24.6MB
     renaissance-rx-scrabble_default_G1.jfr |        8,05 |    1.973MB |  502.029KB |  882.0MB | 24,84% |  219.1MB |  0,09 s |  337.728KB | 16,71% |  147.4MB |             0,08 s | 160.982KB |  7,97% |   70.3MB |          0,04 s | 109.479KB |  5,42% |   47.8MB
 renaissance-neo4j-analytics_default_G1.jfr |       42,44 |   11.148MB |    2.537MB |  945.6MB | 22,76% |  215.2MB |  0,53 s |    1.669MB | 14,97% |  141.6MB |             0,47 s | 729.969KB |  6,39% |   60.5MB |          0,27 s | 373.269KB |  3,27% |   30.9MB
        renaissance-reactors_default_G1.jfr |       63,61 |    7.692MB |    1.925MB |  435.3MB | 25,02% |  108.9MB |  0,29 s |    1.263MB | 16,41% |   71.5MB |             0,24 s | 499.412KB |  6,34% |   27.6MB |          0,12 s | 290.210KB |  3,68% |   16.0MB
        renaissance-dec-tree_default_G1.jfr |       31,36 |   10.728MB |    2.821MB |    1.2GB | 26,29% |  323.8MB |  0,49 s |    2.081MB | 19,40% |  238.9MB |             0,43 s |   1.013MB |  9,45% |  116.3MB |          0,24 s | 551.000KB |  5,02% |   61.8MB
renaissance-scala-stm-bench7_default_G1.jfr |       52,75 |    9.709MB |    2.455MB |  662.6MB | 25,29% |  167.6MB |  0,42 s |    1.534MB | 15,80% |  104.7MB |             0,34 s | 429.800KB |  4,32% |   28.6MB |          0,18 s | 206.889KB |  2,08% |   13.8MB
     renaissance-naive-bayes_default_G1.jfr |       60,24 |   46.098MB |    9.593MB |    2.7GB | 20,81% |  573.3MB |  1,68 s |    5.447MB | 11,82% |  325.5MB |             1,30 s |   1.158MB |  2,51% |   69.2MB |          0,57 s | 659.150KB |  1,40% |   38.5MB
             renaissance-als_default_G1.jfr |      128,23 |   21.656MB |    5.516MB |  608.0MB | 25,47% |  154.9MB |  0,81 s |    3.944MB | 18,21% |  110.7MB |             0,68 s |   1.947MB |  8,99% |   54.7MB |          0,37 s |   1.101MB |  5,08% |   30.9MB
   renaissance-par-mnemonics_default_G1.jfr |       30,44 |   13.985MB |    3.017MB |    1.6GB | 21,57% |  356.8MB |  0,50 s |    1.647MB | 11,78% |  194.8MB |             0,40 s | 359.910KB |  2,51% |   41.6MB |          0,15 s | 173.386KB |  1,21% |   20.0MB
    renaissance-scala-kmeans_default_G1.jfr |       10,15 |    1.070MB |  307.848KB |  379.3MB | 28,11% |  106.6MB |  0,05 s |  214.530KB | 19,59% |   74.3MB |             0,04 s | 122.268KB | 11,16% |   42.3MB |          0,03 s |  91.400KB |  8,35% |   31.7MB
    renaissance-philosophers_default_G1.jfr |       28,20 |    7.071MB |    1.808MB |  902.6MB | 25,57% |  230.8MB |  0,28 s |    1.183MB | 16,73% |  151.0MB |             0,20 s | 393.865KB |  5,44% |   49.1MB |          0,09 s | 184.486KB |  2,55% |   23.0MB
  renaissance-log-regression_default_G1.jfr |       34,37 |    9.486MB |    2.426MB |  993.7MB | 25,57% |  254.1MB |  0,36 s |    1.861MB | 19,62% |  195.0MB |             0,31 s | 969.525KB |  9,98% |   99.2MB |          0,16 s | 584.616KB |  6,02% |   59.8MB
       renaissance-gauss-mix_default_G1.jfr |       23,42 |    7.383MB |    2.204MB |    1.1GB | 29,85% |  338.7MB |  0,30 s |    1.746MB | 23,65% |  268.3MB |             0,26 s | 957.735KB | 12,67% |  143.7MB |          0,16 s | 700.841KB |  9,27% |  105.2MB
       renaissance-mnemonics_default_G1.jfr |       36,81 |   14.320MB |    3.218MB |    1.4GB | 22,47% |  314.7MB |  0,58 s |    1.893MB | 13,22% |  185.2MB |             0,45 s | 437.243KB |  2,98% |   41.8MB |          0,22 s | 201.288KB |  1,37% |   19.2MB
```

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

TODO
----
- [x] check that flags are only recorded once
- [x] test start, stop and status via CLI
- [x] implement simple view
- [ ] test agent
- [ ] make all tools support multiple files
- [ ] add black box tests for the CLI tools
- [ ] check empty files

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors