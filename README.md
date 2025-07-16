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
  - generate-completion
  - condense
  - inflate, summary
    - without filters
- test all CLI commands
- look at each subcommand and check what needs to be done
  - benchmark (not important)
  - agent
  - view
    - handle empty argument
    - picocli warning without arguments
    - handle first argument is file not event
      - display a list of all possible events + help
    - handle missing files
    - handle non-existing files
    - check if file is a JFR file and handle that somehow
    - handle non-existing events
      - be helpful and offer the closest match
        - `Did you mean 'GC' instead of 'Gc'?`
  - javaagent
    - same commands as agent, e.g. `-javaagent:target/condensed-data.jar="start --help"`
    - terminate on help command
      - use isUsageHelpRequested() to check if help was requested
      - test this
    - allow supplying the args via an env variable
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
```

The generated JFR files are probably larger than real-world files, but smaller than dedicated GC benchmarks.

TODO
----
- [x] check that flags are only recorded once
- [x] test start, stop and status via CLI
- [x] implement simple view
- [ ] test agent
- [ ] support file rotation
- [ ] make all tools support multiple files and selection by query

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors