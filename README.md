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
CLI for condensed JFR files
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  condense   Condense a JFR file
  inflate    Inflate a condensed JFR file into JFR format
  benchmark  Run the benchmarks on all files in the benchmark folder
  agent      Use the included Java agent on a specific JVM process
  summary    Print a summary of the condensed JFR file
  view       View a specific event of a condensed JFR file as a table
  help       Print help information
```
But you can also use its built-in Java agent to directly record condensed JFR files:
```shell
> java -javaagent:target/condensed-data.jar=help
Usage: cjfr agent [-hV] [COMMAND]
Agent for recording condensed JFR files
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  start   Start the recording
  stop    Stop the recording
  status  Get the status of the recording
  help    Print help information
> java -javaagent:target/condensed-data.jar=start,help

Usage: cjfr agent start [-hV] [verbose] [condenser-config=<configuration>]
                        [max-duration=<maxDuration>] [max-size=<maxSize>]
                        [misc-jfr-config=<miscJfrConfig>] PATH JFR_CONFIG
Start the recording
      PATH                 Path to the recording file .cjfr file
      JFR_CONFIG           The JFR generatorConfiguration to use.
      condenser-config=<configuration>
                           The condenser generatorConfiguration to use,
                             possible values: default, reasonable default,
                             reduced default
  -h, --help               Show this help message and exit.
      max-duration=<maxDuration>
                           The maximum duration of the recording
      max-size=<maxSize>   The maximum size of the recording file
      misc-jfr-config=<miscJfrConfig>
                           Additional JFR config, '|' separated, like 'jfr.
                             ExecutionSample#interval=1s'
  -V, --version            Print version information and exit.
      verbose              Be verbose
```

Autocompletion:

```
. <(./cjfr generate-completion)
```

TODO:
- already tested commands
  - main
  - help
  - condense
  - inflate, summary
    - without filters
- test all CLI commands
- look at each subcommand and check what needs to be done
  - view
    - basic tests already done
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
- compressed (supports GZIP and LZ4 compression natively)
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
**Benchmark run on 2025-07-21 11:37:46**

JFR file                                    | runtime (s) | original   | compressed | per-hour | %      | per-hour | default | size       | %      | per-hour | reasonable-default | size      | %      | per-hour | reduced-default | size      | %      | per-hour
------------------------------------------- | ----------- | ---------- | ---------- | -------- | ------ | -------- | ------- | ---------- | ------ | -------- | ------------------ | --------- | ------ | -------- | --------------- | --------- | ------ | --------
        renaissance-dotty_gc_details_G1.jfr |       70,26 |   12.752MB |    2.959MB |  653.4MB | 23,21% |  151.6MB |  1,05 s |    1.967MB | 15,42% |  100.8MB |             1,03 s |   1.498MB | 11,75% |   76.8MB |          1,01 s |   1.398MB | 10,96% |   71.6MB
          renaissance-all_gc_details_G1.jfr |     1827,15 |  241.533MB |   55.064MB |  475.9MB | 22,80% |  108.5MB |  9,50 s |   35.547MB | 14,72% |   70.0MB |             8,76 s |  19.720MB |  8,16% |   38.9MB |          7,66 s |  16.622MB |  6,88% |   32.8MB
                renaissance-dotty_gc_G1.jfr |       70,63 |  603.439KB |  255.816KB |   30.0MB | 42,39% |   12.7MB |  0,03 s |  199.726KB | 33,10% |    9.9MB |             0,03 s | 147.826KB | 24,50% |    7.4MB |          0,03 s | 111.546KB | 18,49% |    5.6MB
                  renaissance-all_gc_G1.jfr |     1537,39 |   29.324MB |   11.906MB |   68.7MB | 40,60% |   27.9MB |  1,13 s |    9.758MB | 33,28% |   22.8MB |             1,10 s |   6.475MB | 22,08% |   15.2MB |          0,80 s |   3.008MB | 10,26% |    7.0MB
           renaissance-dotty_default_G1.jfr |       67,83 |    5.973MB |    1.508MB |  317.0MB | 25,25% |   80.0MB |  0,28 s | 1011.063KB | 16,53% |   52.4MB |             0,28 s | 745.199KB | 12,18% |   38.6MB |          0,10 s | 247.336KB |  4,04% |   12.8MB
  renaissance-dotty_gc_details_SerialGC.jfr |       74,30 |    9.038MB |    1.998MB |  437.9MB | 22,11% |   96.8MB |  0,35 s |    1.249MB | 13,82% |   60.5MB |             0,34 s |   1.039MB | 11,50% |   50.3MB |          0,35 s |   1.030MB | 11,40% |   49.9MB
    renaissance-all_gc_details_SerialGC.jfr |     1587,18 |  242.607MB |   50.445MB |  550.3MB | 20,79% |  114.4MB | 10,06 s |   36.012MB | 14,84% |   81.7MB |             9,90 s |  23.438MB |  9,66% |   53.2MB |          9,89 s |  23.043MB |  9,50% |   52.3MB
renaissance-dotty_gc_details_ParallelGC.jfr |       71,18 |    5.374MB |    1.251MB |  271.8MB | 23,28% |   63.3MB |  0,22 s |  732.180KB | 13,31% |   36.2MB |             0,21 s | 538.504KB |  9,79% |   26.6MB |          0,20 s | 479.018KB |  8,71% |   23.7MB
  renaissance-all_gc_details_ParallelGC.jfr |     1443,14 |  244.916MB |   52.440MB |  611.0MB | 21,41% |  130.8MB | 11,15 s |   37.667MB | 15,38% |   94.0MB |            10,56 s |  22.656MB |  9,25% |   56.5MB |          9,22 s |  18.868MB |  7,70% |   47.1MB
       renaissance-dotty_gc_details_ZGC.jfr |       71,39 |   29.351MB |    5.824MB |    1.4GB | 19,84% |  293.7MB |  1,26 s |    3.331MB | 11,35% |  168.0MB |             1,26 s |   2.957MB | 10,07% |  149.1MB |          1,26 s |   2.919MB |  9,95% |  147.2MB
         renaissance-all_gc_details_ZGC.jfr |     1917,04 |  249.841MB |   58.992MB |  469.2MB | 23,61% |  110.8MB | 10,48 s |   38.427MB | 15,38% |   72.2MB |            10,62 s |  23.054MB |  9,23% |   43.3MB |         10,49 s |  22.630MB |  9,06% |   42.5MB
          renaissance-dotty_gc_SerialGC.jfr |       74,36 |  501.309KB |  194.442KB |   23.7MB | 38,79% |    9.2MB |  0,03 s |  144.672KB | 28,86% |    6.8MB |             0,03 s | 103.154KB | 20,58% |    4.9MB |          0,02 s |  96.377KB | 19,23% |    4.6MB
            renaissance-all_gc_SerialGC.jfr |     1569,44 |   14.241MB |    5.268MB |   32.7MB | 36,99% |   12.1MB |  0,55 s |    4.102MB | 28,81% |    9.4MB |             0,56 s |   2.880MB | 20,22% |    6.6MB |          0,44 s |   1.858MB | 13,04% |    4.3MB
        renaissance-dotty_gc_ParallelGC.jfr |       71,82 | 1020.327KB |  368.347KB |   49.9MB | 36,10% |   18.0MB |  0,04 s |  276.007KB | 27,05% |   13.5MB |             0,04 s | 184.945KB | 18,13% |    9.1MB |          0,02 s |  82.303KB |  8,07% |    4.0MB
          renaissance-all_gc_ParallelGC.jfr |     1395,09 |   57.853MB |   17.043MB |  149.3MB | 29,46% |   44.0MB |  2,96 s |   12.607MB | 21,79% |   32.5MB |             2,65 s |   6.088MB | 10,52% |   15.7MB |          0,91 s |   1.346MB |  2,33% |    3.5MB
               renaissance-dotty_gc_ZGC.jfr |       73,97 |  702.165KB |  301.665KB |   33.4MB | 42,96% |   14.3MB |  0,03 s |  201.594KB | 28,71% |    9.6MB |             0,03 s | 121.644KB | 17,32% |    5.8MB |          0,02 s | 103.128KB | 14,69% |    4.9MB
                 renaissance-all_gc_ZGC.jfr |     1808,46 |   89.930MB |   38.182MB |  179.0MB | 42,46% |   76.0MB |  4,29 s |   28.710MB | 31,92% |   57.2MB |             4,30 s |  19.062MB | 21,20% |   37.9MB |          4,26 s |  18.103MB | 20,13% |   36.0MB
        renaissance-scrabble_default_G1.jfr |        8,78 |   10.140MB |    2.181MB |    4.1GB | 21,51% |  894.2MB |  0,38 s |    1.206MB | 11,90% |  494.6MB |             0,27 s | 223.788KB |  2,16% |   89.6MB |          0,10 s | 124.664KB |  1,20% |   49.9MB
       renaissance-page-rank_default_G1.jfr |       93,91 |   38.742MB |   10.058MB |    1.5GB | 25,96% |  385.6MB |  1,33 s |    5.813MB | 15,01% |  222.9MB |             1,07 s |   1.099MB |  2,84% |   42.1MB |          0,41 s | 557.073KB |  1,40% |   20.9MB
  renaissance-future-genetic_default_G1.jfr |       62,62 |   11.028MB |    2.645MB |  634.0MB | 23,99% |  152.1MB |  0,42 s |    1.705MB | 15,46% |   98.0MB |             0,32 s | 541.989KB |  4,80% |   30.4MB |          0,14 s | 215.747KB |  1,91% |   12.1MB
      renaissance-movie-lens_default_G1.jfr |      558,97 |   79.090MB |   23.428MB |  509.4MB | 29,62% |  150.9MB |  2,63 s |   18.283MB | 23,12% |  117.7MB |             2,32 s |   9.447MB | 11,94% |   60.8MB |          1,36 s |   5.752MB |  7,27% |   37.0MB
      renaissance-scala-doku_default_G1.jfr |       33,58 |    1.363MB |  390.270KB |  146.1MB | 27,96% |   40.9MB |  0,07 s |  270.999KB | 19,42% |   28.4MB |             0,07 s | 145.241KB | 10,41% |   15.2MB |          0,05 s | 102.159KB |  7,32% |   10.7MB
      renaissance-chi-square_default_G1.jfr |       34,35 |   18.407MB |    4.174MB |    1.9GB | 22,68% |  437.5MB |  0,62 s |    2.534MB | 13,77% |  265.6MB |             0,47 s | 706.811KB |  3,75% |   72.3MB |          0,19 s | 377.212KB |  2,00% |   38.6MB
       renaissance-fj-kmeans_default_G1.jfr |       61,99 |   40.407MB |    8.556MB |    2.3GB | 21,17% |  496.9MB |  1,56 s |    5.263MB | 13,02% |  305.6MB |             1,27 s |   1.107MB |  2,74% |   64.3MB |          0,49 s | 433.935KB |  1,05% |   24.6MB
     renaissance-rx-scrabble_default_G1.jfr |        8,05 |    1.973MB |  502.029KB |  882.0MB | 24,84% |  219.1MB |  0,07 s |  337.610KB | 16,71% |  147.4MB |             0,06 s | 160.982KB |  7,97% |   70.3MB |          0,03 s | 109.482KB |  5,42% |   47.8MB
 renaissance-neo4j-analytics_default_G1.jfr |       42,44 |   11.148MB |    2.537MB |  945.6MB | 22,76% |  215.2MB |  0,43 s |    1.668MB | 14,96% |  141.5MB |             0,36 s | 729.969KB |  6,39% |   60.5MB |          0,19 s | 373.231KB |  3,27% |   30.9MB
        renaissance-reactors_default_G1.jfr |       63,61 |    7.692MB |    1.925MB |  435.3MB | 25,02% |  108.9MB |  0,37 s |    1.262MB | 16,41% |   71.4MB |             0,39 s | 499.412KB |  6,34% |   27.6MB |          0,18 s | 290.223KB |  3,68% |   16.0MB
        renaissance-dec-tree_default_G1.jfr |       31,36 |   10.728MB |    2.821MB |    1.2GB | 26,29% |  323.8MB |  0,39 s |    2.079MB | 19,38% |  238.6MB |             0,33 s |   1.013MB |  9,45% |  116.3MB |          0,17 s | 550.969KB |  5,02% |   61.8MB
renaissance-scala-stm-bench7_default_G1.jfr |       52,75 |    9.709MB |    2.455MB |  662.6MB | 25,29% |  167.6MB |  0,37 s |    1.534MB | 15,80% |  104.7MB |             0,30 s | 429.800KB |  4,32% |   28.6MB |          0,12 s | 206.888KB |  2,08% |   13.8MB
     renaissance-naive-bayes_default_G1.jfr |       60,24 |   46.098MB |    9.593MB |    2.7GB | 20,81% |  573.3MB |  1,65 s |    5.445MB | 11,81% |  325.4MB |             1,27 s |   1.158MB |  2,51% |   69.2MB |          0,50 s | 659.191KB |  1,40% |   38.5MB
             renaissance-als_default_G1.jfr |      128,23 |   21.656MB |    5.516MB |  608.0MB | 25,47% |  154.9MB |  0,77 s |    3.942MB | 18,20% |  110.7MB |             0,65 s |   1.947MB |  8,99% |   54.7MB |          0,33 s |   1.101MB |  5,08% |   30.9MB
   renaissance-par-mnemonics_default_G1.jfr |       30,44 |   13.985MB |    3.017MB |    1.6GB | 21,57% |  356.8MB |  0,49 s |    1.647MB | 11,77% |  194.8MB |             0,37 s | 359.910KB |  2,51% |   41.6MB |          0,14 s | 173.430KB |  1,21% |   20.0MB
    renaissance-scala-kmeans_default_G1.jfr |       10,15 |    1.070MB |  307.848KB |  379.3MB | 28,11% |  106.6MB |  0,04 s |  214.385KB | 19,57% |   74.2MB |             0,03 s | 122.268KB | 11,16% |   42.3MB |          0,02 s |  91.380KB |  8,34% |   31.6MB
    renaissance-philosophers_default_G1.jfr |       28,20 |    7.071MB |    1.808MB |  902.6MB | 25,57% |  230.8MB |  0,29 s |    1.182MB | 16,72% |  150.9MB |             0,22 s | 393.865KB |  5,44% |   49.1MB |          0,11 s | 184.479KB |  2,55% |   23.0MB
  renaissance-log-regression_default_G1.jfr |       34,37 |    9.486MB |    2.426MB |  993.7MB | 25,57% |  254.1MB |  0,36 s |    1.860MB | 19,61% |  194.8MB |             0,31 s | 969.525KB |  9,98% |   99.2MB |          0,17 s | 584.650KB |  6,02% |   59.8MB
       renaissance-gauss-mix_default_G1.jfr |       23,42 |    7.383MB |    2.204MB |    1.1GB | 29,85% |  338.7MB |  0,29 s |    1.742MB | 23,59% |  267.7MB |             0,25 s | 957.735KB | 12,67% |  143.7MB |          0,16 s | 700.778KB |  9,27% |  105.2MB
       renaissance-mnemonics_default_G1.jfr |       36,81 |   14.320MB |    3.218MB |    1.4GB | 22,47% |  314.7MB |  0,56 s |    1.893MB | 13,22% |  185.1MB |             0,44 s | 437.243KB |  2,98% |   41.8MB |          0,20 s | 201.269KB |  1,37% |   19.2MB
```

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

TODO
----
- [x] check that flags are only recorded once
- [x] test start, stop and status via CLI
- [x] implement simple view
- [ ] test agent
- [ ] make all tools support multiple files and selection by query

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors