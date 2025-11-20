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
**Benchmark run on 2025-11-19 17:51:05**

JFR file                                    | runtime (s) | original   | compressed | per-hour | %      | per-hour | default | size      | %      | per-hour | reasonable-default | size      | %      | per-hour | reduced-default | size      | %      | per-hour
------------------------------------------- | ----------- | ---------- | ---------- | -------- | ------ | -------- | ------- | --------- | ------ | -------- | ------------------ | --------- | ------ | -------- | --------------- | --------- | ------ | --------
        renaissance-dotty_gc_details_G1.jfr |       70,26 |   12.752MB |    2.959MB |  653.4MB | 23,21% |  151.6MB |  3,45 s |   1.005MB |  7,88% |   51.5MB |             3,24 s | 659.710KB |  5,05% |   33.0MB |          3,12 s | 590.978KB |  4,53% |   29.6MB
          renaissance-all_gc_details_G1.jfr |     1827,15 |  241.533MB |   55.064MB |  475.9MB | 22,80% |  108.5MB | 60,68 s |  21.466MB |  8,89% |   42.3MB |            45,44 s |   9.454MB |  3,91% |   18.6MB |         37,78 s |   7.967MB |  3,30% |   15.7MB
                renaissance-dotty_gc_G1.jfr |       70,63 |  603.439KB |  255.816KB |   30.0MB | 42,39% |   12.7MB |  0,13 s | 162.676KB | 26,96% |    8.1MB |             0,12 s | 112.295KB | 18,61% |    5.6MB |          0,11 s |  90.248KB | 14,96% |    4.5MB
                  renaissance-all_gc_G1.jfr |     1537,39 |   29.324MB |   11.906MB |   68.7MB | 40,60% |   27.9MB |  8,49 s |   7.321MB | 24,97% |   17.1MB |             6,73 s |   4.320MB | 14,73% |   10.1MB |          3,77 s |   2.319MB |  7,91% |    5.4MB
           renaissance-dotty_default_G1.jfr |       67,83 |    5.973MB |    1.508MB |  317.0MB | 25,25% |   80.0MB |  1,45 s | 576.384KB |  9,42% |   29.9MB |             1,34 s | 378.169KB |  6,18% |   19.6MB |          0,31 s | 181.818KB |  2,97% |    9.4MB
  renaissance-dotty_gc_details_SerialGC.jfr |       74,30 |    9.038MB |    1.998MB |  437.9MB | 22,11% |   96.8MB |  2,19 s | 651.611KB |  7,04% |   30.8MB |             2,08 s | 478.684KB |  5,17% |   22.7MB |          2,08 s | 473.544KB |  5,12% |   22.4MB
    renaissance-all_gc_details_SerialGC.jfr |     1587,18 |  242.607MB |   50.445MB |  550.3MB | 20,79% |  114.4MB | 67,96 s |  23.361MB |  9,63% |   53.0MB |            58,63 s |  13.150MB |  5,42% |   29.8MB |         58,63 s |  12.948MB |  5,34% |   29.4MB
renaissance-dotty_gc_details_ParallelGC.jfr |       71,18 |    5.374MB |    1.251MB |  271.8MB | 23,28% |   63.3MB |  1,19 s | 483.404KB |  8,79% |   23.9MB |             1,07 s | 332.010KB |  6,03% |   16.4MB |          1,00 s | 294.200KB |  5,35% |   14.5MB
  renaissance-all_gc_details_ParallelGC.jfr |     1443,14 |  244.916MB |   52.440MB |  611.0MB | 21,41% |  130.8MB | 73,18 s |  23.959MB |  9,78% |   59.8MB |            61,59 s |  12.933MB |  5,28% |   32.3MB |         51,14 s |  10.677MB |  4,36% |   26.6MB
       renaissance-dotty_gc_details_ZGC.jfr |       71,39 |   29.351MB |    5.824MB |    1.4GB | 19,84% |  293.7MB |  8,04 s |   1.313MB |  4,47% |   66.2MB |             7,73 s |   1.006MB |  3,43% |   50.7MB |          7,84 s | 997.312KB |  3,32% |   49.1MB
         renaissance-all_gc_details_ZGC.jfr |     1917,04 |  249.841MB |   58.992MB |  469.2MB | 23,61% |  110.8MB | 75,58 s |  24.569MB |  9,83% |   46.1MB |            62,55 s |  11.611MB |  4,65% |   21.8MB |         62,53 s |  11.171MB |  4,47% |   21.0MB
          renaissance-dotty_gc_SerialGC.jfr |       74,36 |  501.309KB |  194.442KB |   23.7MB | 38,79% |    9.2MB |  0,11 s | 113.114KB | 22,56% |    5.3MB |             0,10 s |  75.981KB | 15,16% |    3.6MB |          0,10 s |  77.439KB | 15,45% |    3.7MB
            renaissance-all_gc_SerialGC.jfr |     1569,44 |   14.241MB |    5.268MB |   32.7MB | 36,99% |   12.1MB |  3,37 s |   3.073MB | 21,58% |    7.0MB |             2,67 s |   1.783MB | 12,52% |    4.1MB |          2,03 s |   1.391MB |  9,77% |    3.2MB
        renaissance-dotty_gc_ParallelGC.jfr |       71,82 | 1020.327KB |  368.347KB |   49.9MB | 36,10% |   18.0MB |  0,25 s | 207.376KB | 20,32% |   10.2MB |             0,23 s | 130.564KB | 12,80% |    6.4MB |          0,10 s |  65.581KB |  6,43% |    3.2MB
          renaissance-all_gc_ParallelGC.jfr |     1395,09 |   57.853MB |   17.043MB |  149.3MB | 29,46% |   44.0MB | 19,89 s |   8.560MB | 14,80% |   22.1MB |            15,25 s |   3.888MB |  6,72% |   10.0MB |          2,47 s |   1.048MB |  1,81% |    2.7MB
               renaissance-dotty_gc_ZGC.jfr |       73,97 |  702.165KB |  301.665KB |   33.4MB | 42,96% |   14.3MB |  0,16 s | 162.668KB | 23,17% |    7.7MB |             0,15 s |  92.290KB | 13,14% |    4.4MB |          0,14 s |  74.831KB | 10,66% |    3.6MB
                 renaissance-all_gc_ZGC.jfr |     1808,46 |   89.930MB |   38.182MB |  179.0MB | 42,46% |   76.0MB | 32,70 s |  20.790MB | 23,12% |   41.4MB |            26,97 s |  10.269MB | 11,42% |   20.4MB |         26,32 s |   9.291MB | 10,33% |   18.5MB
        renaissance-scrabble_default_G1.jfr |        8,78 |   10.140MB |    2.181MB |    4.1GB | 21,51% |  894.2MB |  1,73 s | 828.559KB |  7,98% |  331.7MB |             0,97 s | 133.530KB |  1,29% |   53.5MB |          0,22 s |  91.982KB |  0,89% |   36.8MB
       renaissance-page-rank_default_G1.jfr |       93,91 |   38.742MB |   10.058MB |    1.5GB | 25,96% |  385.6MB |  7,70 s |   3.982MB | 10,28% |  152.7MB |             3,92 s | 607.439KB |  1,53% |   22.7MB |          0,96 s | 354.842KB |  0,89% |   13.3MB
  renaissance-future-genetic_default_G1.jfr |       62,62 |   11.028MB |    2.645MB |  634.0MB | 23,99% |  152.1MB |  2,04 s |   1.116MB | 10,12% |   64.1MB |             1,24 s | 293.719KB |  2,60% |   16.5MB |          0,37 s | 153.048KB |  1,36% |    8.6MB
      renaissance-movie-lens_default_G1.jfr |      558,97 |   79.090MB |   23.428MB |  509.4MB | 29,62% |  150.9MB | 18,26 s |  12.371MB | 15,64% |   79.7MB |            13,27 s |   5.023MB |  6,35% |   32.4MB |          8,15 s |   3.459MB |  4,37% |   22.3MB
      renaissance-scala-doku_default_G1.jfr |       33,58 |    1.363MB |  390.270KB |  146.1MB | 27,96% |   40.9MB |  0,26 s | 196.855KB | 14,10% |   20.6MB |             0,22 s | 100.842KB |  7,23% |   10.6MB |          0,11 s |  76.180KB |  5,46% |    8.0MB
      renaissance-chi-square_default_G1.jfr |       34,35 |   18.407MB |    4.174MB |    1.9GB | 22,68% |  437.5MB |  3,34 s |   1.667MB |  9,05% |  174.7MB |             1,90 s | 388.107KB |  2,06% |   39.7MB |          0,51 s | 249.945KB |  1,33% |   25.6MB
       renaissance-fj-kmeans_default_G1.jfr |       61,99 |   40.407MB |    8.556MB |    2.3GB | 21,17% |  496.9MB |  8,42 s |   3.386MB |  8,38% |  196.6MB |             4,55 s | 541.316KB |  1,31% |   30.7MB |          1,36 s | 255.247KB |  0,62% |   14.5MB
     renaissance-rx-scrabble_default_G1.jfr |        8,05 |    1.973MB |  502.029KB |  882.0MB | 24,84% |  219.1MB |  0,33 s | 233.628KB | 11,56% |  102.0MB |             0,25 s | 105.180KB |  5,21% |   45.9MB |          0,11 s |  80.260KB |  3,97% |   35.0MB
 renaissance-neo4j-analytics_default_G1.jfr |       42,44 |   11.148MB |    2.537MB |  945.6MB | 22,76% |  215.2MB |  2,14 s |   1.119MB | 10,03% |   94.9MB |             1,50 s | 427.412KB |  3,74% |   35.4MB |          0,56 s | 269.558KB |  2,36% |   22.3MB
        renaissance-reactors_default_G1.jfr |       63,61 |    7.692MB |    1.925MB |  435.3MB | 25,02% |  108.9MB |  1,37 s | 887.717KB | 11,27% |   49.1MB |             0,92 s | 290.492KB |  3,69% |   16.1MB |          0,36 s | 206.098KB |  2,62% |   11.4MB
        renaissance-dec-tree_default_G1.jfr |       31,36 |   10.728MB |    2.821MB |    1.2GB | 26,29% |  323.8MB |  2,11 s |   1.393MB | 12,98% |  159.9MB |             1,57 s | 583.105KB |  5,31% |   65.4MB |          0,66 s | 379.470KB |  3,45% |   42.5MB
renaissance-scala-stm-bench7_default_G1.jfr |       52,75 |    9.709MB |    2.455MB |  662.6MB | 25,29% |  167.6MB |  1,70 s |   1.085MB | 11,17% |   74.0MB |             1,05 s | 262.061KB |  2,64% |   17.5MB |          0,29 s | 155.763KB |  1,57% |   10.4MB
     renaissance-naive-bayes_default_G1.jfr |       60,24 |   46.098MB |    9.593MB |    2.7GB | 20,81% |  573.3MB |  8,54 s |   3.529MB |  7,66% |  210.9MB |             4,28 s | 599.640KB |  1,27% |   35.0MB |          1,02 s | 373.860KB |  0,79% |   21.8MB
             renaissance-als_default_G1.jfr |      128,23 |   21.656MB |    5.516MB |  608.0MB | 25,47% |  154.9MB |  4,61 s |   2.673MB | 12,34% |   75.0MB |             3,31 s |   1.088MB |  5,02% |   30.5MB |          1,52 s | 716.133KB |  3,23% |   19.6MB
   renaissance-par-mnemonics_default_G1.jfr |       30,44 |   13.985MB |    3.017MB |    1.6GB | 21,57% |  356.8MB |  2,39 s |   1.079MB |  7,71% |  127.6MB |             1,32 s | 190.722KB |  1,33% |   22.0MB |          0,28 s | 120.668KB |  0,84% |   13.9MB
    renaissance-scala-kmeans_default_G1.jfr |       10,15 |    1.070MB |  307.848KB |  379.3MB | 28,11% |  106.6MB |  0,18 s | 150.891KB | 13,78% |   52.3MB |             0,15 s |  81.437KB |  7,44% |   28.2MB |          0,09 s |  66.836KB |  6,10% |   23.1MB
    renaissance-philosophers_default_G1.jfr |       28,20 |    7.071MB |    1.808MB |  902.6MB | 25,57% |  230.8MB |  1,25 s | 801.967KB | 11,08% |  100.0MB |             0,75 s | 222.271KB |  3,07% |   27.7MB |          0,22 s | 135.225KB |  1,87% |   16.9MB
  renaissance-log-regression_default_G1.jfr |       34,37 |    9.486MB |    2.426MB |  993.7MB | 25,57% |  254.1MB |  1,95 s |   1.284MB | 13,54% |  134.5MB |             1,46 s | 580.061KB |  5,97% |   59.3MB |          0,70 s | 401.282KB |  4,13% |   41.0MB
       renaissance-gauss-mix_default_G1.jfr |       23,42 |    7.383MB |    2.204MB |    1.1GB | 29,85% |  338.7MB |  1,49 s |   1.217MB | 16,48% |  187.0MB |             1,16 s | 560.804KB |  7,42% |   84.2MB |          0,81 s | 454.964KB |  6,02% |   68.3MB
       renaissance-mnemonics_default_G1.jfr |       36,81 |   14.320MB |    3.218MB |    1.4GB | 22,47% |  314.7MB |  2,55 s |   1.234MB |  8,61% |  120.6MB |             1,40 s | 221.210KB |  1,51% |   21.1MB |          0,32 s | 138.067KB |  0,94% |   13.2MB
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