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

Build the tool or download it from the [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest).

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
**Benchmark run on 2025-12-04 16:28:30**

JFR file                                    | runtime (s) | original   | compressed | per-hour | %      | per-hour | default | size      | %      | per-hour | reasonable-default | size      | %      | per-hour | reduced-default | size      | %      | per-hour
------------------------------------------- | ----------- | ---------- | ---------- | -------- | ------ | -------- | ------- | --------- | ------ | -------- | ------------------ | --------- | ------ | -------- | --------------- | --------- | ------ | --------
        renaissance-dotty_gc_details_G1.jfr |       70,26 |   12.752MB |    2.959MB |  653.4MB | 23,21% |  151.6MB |  3,45 s |   1.005MB |  7,88% |   51.5MB |             1,72 s | 479.178KB |  3,67% |   24.0MB |          1,34 s | 405.045KB |  3,10% |   20.3MB
          renaissance-all_gc_details_G1.jfr |     1827,15 |  241.533MB |   55.064MB |  475.9MB | 22,80% |  108.5MB | 64,81 s |  21.465MB |  8,89% |   42.3MB |            31,59 s |   7.522MB |  3,11% |   14.8MB |         28,37 s |   6.908MB |  2,86% |   13.6MB
                renaissance-dotty_gc_G1.jfr |       70,63 |  603.439KB |  255.816KB |   30.0MB | 42,39% |   12.7MB |  0,14 s | 162.719KB | 26,97% |    8.1MB |             0,12 s |  95.505KB | 15,83% |    4.8MB |          0,12 s |  90.013KB | 14,92% |    4.5MB
                  renaissance-all_gc_G1.jfr |     1537,39 |   29.324MB |   11.906MB |   68.7MB | 40,60% |   27.9MB |  8,74 s |   7.321MB | 24,97% |   17.1MB |             4,72 s |   3.057MB | 10,42% |    7.2MB |          3,86 s |   2.315MB |  7,90% |    5.4MB
           renaissance-dotty_default_G1.jfr |       67,83 |    5.973MB |    1.508MB |  317.0MB | 25,25% |   80.0MB |  1,43 s | 576.575KB |  9,43% |   29.9MB |             0,57 s | 272.609KB |  4,46% |   14.1MB |          0,22 s | 145.434KB |  2,38% |    7.5MB
  renaissance-dotty_gc_details_SerialGC.jfr |       74,30 |    9.038MB |    1.998MB |  437.9MB | 22,11% |   96.8MB |  2,26 s | 651.860KB |  7,04% |   30.8MB |             1,11 s | 372.613KB |  4,03% |   17.6MB |          0,83 s | 321.948KB |  3,48% |   15.2MB
    renaissance-all_gc_details_SerialGC.jfr |     1587,18 |  242.607MB |   50.445MB |  550.3MB | 20,79% |  114.4MB | 73,52 s |  23.361MB |  9,63% |   53.0MB |            44,56 s |  11.519MB |  4,75% |   26.1MB |         36,01 s |  10.678MB |  4,40% |   24.2MB
renaissance-dotty_gc_details_ParallelGC.jfr |       71,18 |    5.374MB |    1.251MB |  271.8MB | 23,28% |   63.3MB |  1,24 s | 483.426KB |  8,79% |   23.9MB |             0,72 s | 246.566KB |  4,48% |   12.2MB |          0,59 s | 213.840KB |  3,89% |   10.6MB
  renaissance-all_gc_details_ParallelGC.jfr |     1443,14 |  244.916MB |   52.440MB |  611.0MB | 21,41% |  130.8MB | 76,84 s |  23.959MB |  9,78% |   59.8MB |            38,89 s |   9.827MB |  4,01% |   24.5MB |         31,27 s |   8.622MB |  3,52% |   21.5MB
       renaissance-dotty_gc_details_ZGC.jfr |       71,39 |   29.351MB |    5.824MB |    1.4GB | 19,84% |  293.7MB |  8,13 s |   1.313MB |  4,47% |   66.2MB |             3,43 s | 737.294KB |  2,45% |   36.3MB |          2,11 s | 566.965KB |  1,89% |   27.9MB
         renaissance-all_gc_details_ZGC.jfr |     1917,04 |  249.841MB |   58.992MB |  469.2MB | 23,61% |  110.8MB | 80,19 s |  24.564MB |  9,83% |   46.1MB |            52,38 s |  10.611MB |  4,25% |   19.9MB |         47,23 s |   9.719MB |  3,89% |   18.3MB
          renaissance-dotty_gc_SerialGC.jfr |       74,36 |  501.309KB |  194.442KB |   23.7MB | 38,79% |    9.2MB |  0,11 s | 113.176KB | 22,58% |    5.4MB |             0,09 s |  74.716KB | 14,90% |    3.5MB |          0,10 s |  77.285KB | 15,42% |    3.7MB
            renaissance-all_gc_SerialGC.jfr |     1569,44 |   14.241MB |    5.268MB |   32.7MB | 36,99% |   12.1MB |  3,44 s |   3.073MB | 21,58% |    7.0MB |             2,29 s |   1.776MB | 12,47% |    4.1MB |          2,07 s |   1.391MB |  9,77% |    3.2MB
        renaissance-dotty_gc_ParallelGC.jfr |       71,82 | 1020.327KB |  368.347KB |   49.9MB | 36,10% |   18.0MB |  0,26 s | 207.390KB | 20,33% |   10.2MB |             0,11 s |  75.168KB |  7,37% |    3.7MB |          0,10 s |  65.232KB |  6,39% |    3.2MB
          renaissance-all_gc_ParallelGC.jfr |     1395,09 |   57.853MB |   17.043MB |  149.3MB | 29,46% |   44.0MB | 20,54 s |   8.560MB | 14,80% |   22.1MB |             3,69 s |   1.738MB |  3,00% |    4.5MB |          2,51 s |   1.047MB |  1,81% |    2.7MB
               renaissance-dotty_gc_ZGC.jfr |       73,97 |  702.165KB |  301.665KB |   33.4MB | 42,96% |   14.3MB |  0,16 s | 162.692KB | 23,17% |    7.7MB |             0,15 s |  92.313KB | 13,15% |    4.4MB |          0,14 s |  74.660KB | 10,63% |    3.5MB
                 renaissance-all_gc_ZGC.jfr |     1808,46 |   89.930MB |   38.182MB |  179.0MB | 42,46% |   76.0MB | 33,43 s |  20.788MB | 23,12% |   41.4MB |            27,27 s |  10.265MB | 11,41% |   20.4MB |         26,62 s |   9.291MB | 10,33% |   18.5MB
        renaissance-scrabble_default_G1.jfr |        8,78 |   10.140MB |    2.181MB |    4.1GB | 21,51% |  894.2MB |  1,78 s | 828.686KB |  7,98% |  331.8MB |             0,19 s |  95.626KB |  0,92% |   38.3MB |          0,18 s |  84.384KB |  0,81% |   33.8MB
       renaissance-page-rank_default_G1.jfr |       93,91 |   38.742MB |   10.058MB |    1.5GB | 25,96% |  385.6MB |  8,12 s |   3.982MB | 10,28% |  152.7MB |             0,95 s | 419.719KB |  1,06% |   15.7MB |          0,78 s | 314.678KB |  0,79% |   11.8MB
  renaissance-future-genetic_default_G1.jfr |       62,62 |   11.028MB |    2.645MB |  634.0MB | 23,99% |  152.1MB |  2,12 s |   1.117MB | 10,13% |   64.2MB |             0,35 s | 191.911KB |  1,70% |   10.8MB |          0,29 s | 143.614KB |  1,27% |    8.1MB
      renaissance-movie-lens_default_G1.jfr |      558,97 |   79.090MB |   23.428MB |  509.4MB | 29,62% |  150.9MB | 19,02 s |  12.370MB | 15,64% |   79.7MB |             8,90 s |   3.984MB |  5,04% |   25.7MB |          7,93 s |   3.400MB |  4,30% |   21.9MB
      renaissance-scala-doku_default_G1.jfr |       33,58 |    1.363MB |  390.270KB |  146.1MB | 27,96% |   40.9MB |  0,27 s | 196.888KB | 14,11% |   20.6MB |             0,13 s |  85.984KB |  6,16% |    9.0MB |          0,09 s |  71.012KB |  5,09% |    7.4MB
      renaissance-chi-square_default_G1.jfr |       34,35 |   18.407MB |    4.174MB |    1.9GB | 22,68% |  437.5MB |  3,48 s |   1.666MB |  9,05% |  174.6MB |             0,51 s | 277.301KB |  1,47% |   28.4MB |          0,45 s | 221.942KB |  1,18% |   22.7MB
       renaissance-fj-kmeans_default_G1.jfr |       61,99 |   40.407MB |    8.556MB |    2.3GB | 21,17% |  496.9MB |  8,76 s |   3.385MB |  8,38% |  196.6MB |             0,95 s | 270.638KB |  0,65% |   15.3MB |          0,73 s | 209.533KB |  0,51% |   11.9MB
     renaissance-rx-scrabble_default_G1.jfr |        8,05 |    1.973MB |  502.029KB |  882.0MB | 24,84% |  219.1MB |  0,34 s | 233.669KB | 11,56% |  102.0MB |             0,12 s |  87.549KB |  4,33% |   38.2MB |          0,10 s |  75.323KB |  3,73% |   32.9MB
 renaissance-neo4j-analytics_default_G1.jfr |       42,44 |   11.148MB |    2.537MB |  945.6MB | 22,76% |  215.2MB |  2,16 s |   1.119MB | 10,03% |   94.9MB |             0,56 s | 309.822KB |  2,71% |   25.7MB |          0,34 s | 214.675KB |  1,88% |   17.8MB
        renaissance-reactors_default_G1.jfr |       63,61 |    7.692MB |    1.925MB |  435.3MB | 25,02% |  108.9MB |  1,39 s | 887.656KB | 11,27% |   49.1MB |             0,37 s | 225.111KB |  2,86% |   12.4MB |          0,32 s | 195.146KB |  2,48% |   10.8MB
        renaissance-dec-tree_default_G1.jfr |       31,36 |   10.728MB |    2.821MB |    1.2GB | 26,29% |  323.8MB |  2,18 s |   1.393MB | 12,98% |  159.9MB |             0,72 s | 442.184KB |  4,03% |   49.6MB |          0,52 s | 330.688KB |  3,01% |   37.1MB
renaissance-scala-stm-bench7_default_G1.jfr |       52,75 |    9.709MB |    2.455MB |  662.6MB | 25,29% |  167.6MB |  1,79 s |   1.085MB | 11,17% |   74.0MB |             0,30 s | 184.809KB |  1,86% |   12.3MB |          0,24 s | 146.286KB |  1,47% |    9.8MB
     renaissance-naive-bayes_default_G1.jfr |       60,24 |   46.098MB |    9.593MB |    2.7GB | 20,81% |  573.3MB |  9,26 s |   3.529MB |  7,66% |  210.9MB |             0,96 s | 407.568KB |  0,86% |   23.8MB |          0,88 s | 331.204KB |  0,70% |   19.3MB
             renaissance-als_default_G1.jfr |      128,23 |   21.656MB |    5.516MB |  608.0MB | 25,47% |  154.9MB |  4,62 s |   2.673MB | 12,34% |   75.0MB |             1,61 s | 861.205KB |  3,88% |   23.6MB |          1,23 s | 660.516KB |  2,98% |   18.1MB
   renaissance-par-mnemonics_default_G1.jfr |       30,44 |   13.985MB |    3.017MB |    1.6GB | 21,57% |  356.8MB |  2,48 s |   1.081MB |  7,73% |  127.8MB |             0,27 s | 125.951KB |  0,88% |   14.5MB |          0,26 s | 113.788KB |  0,79% |   13.1MB
    renaissance-scala-kmeans_default_G1.jfr |       10,15 |    1.070MB |  307.848KB |  379.3MB | 28,11% |  106.6MB |  0,18 s | 150.917KB | 13,78% |   52.3MB |             0,09 s |  70.042KB |  6,40% |   24.3MB |          0,08 s |  63.495KB |  5,80% |   22.0MB
    renaissance-philosophers_default_G1.jfr |       28,20 |    7.071MB |    1.808MB |  902.6MB | 25,57% |  230.8MB |  1,28 s | 802.138KB | 11,08% |  100.0MB |             0,22 s | 153.850KB |  2,12% |   19.2MB |          0,21 s | 127.770KB |  1,76% |   15.9MB
  renaissance-log-regression_default_G1.jfr |       34,37 |    9.486MB |    2.426MB |  993.7MB | 25,57% |  254.1MB |  2,00 s |   1.284MB | 13,54% |  134.5MB |             0,75 s | 468.495KB |  4,82% |   47.9MB |          0,57 s | 359.345KB |  3,70% |   36.8MB
       renaissance-gauss-mix_default_G1.jfr |       23,42 |    7.383MB |    2.204MB |    1.1GB | 29,85% |  338.7MB |  1,53 s |   1.217MB | 16,49% |  187.1MB |             0,84 s | 492.606KB |  6,52% |   73.9MB |          0,74 s | 429.833KB |  5,69% |   64.5MB
       renaissance-mnemonics_default_G1.jfr |       36,81 |   14.320MB |    3.218MB |    1.4GB | 22,47% |  314.7MB |  2,66 s |   1.234MB |  8,62% |  120.6MB |             0,29 s | 139.534KB |  0,95% |   13.3MB |          0,29 s | 131.905KB |  0,90% |   12.6MB
```

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

TODO
----
- [x] check that flags are only recorded once
- [x] test start, stop and status via CLI
- [x] implement simple view

- [x] have option to remove line numbers from stack frames
- [x] check benchmark per hour calculation
- [x] check that incomplete files can be read
- [x] increase performance of ensureRecursiveCompleteness

- [ ] make all tools support multiple files
- [ ] add black box tests for the CLI tools
- [ ] check empty files

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors