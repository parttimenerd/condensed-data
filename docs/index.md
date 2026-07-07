---
title: Home
layout: default
nav_order: 1
---

# cjfr — Condensed JFR

A library and CLI tool for reading and writing condensed JFR (Java Flight Recorder)
event data. Focused on a simple, self-describing, space-saving format for long-term
storage of GC-related JFR recordings.

`cjfr` includes a Java agent that records directly to the compact `.cjfr` format
with file rotation, live tuning, and per-platform native compression.

## Install

Download the latest JAR from [GitHub Releases](https://github.com/parttimenerd/condensed-data/releases/latest):

```shell
curl -L -o cjfr.jar https://github.com/parttimenerd/condensed-data/releases/latest/download/condensed-data.jar
alias cjfr='java -jar '"$(pwd)"'/cjfr.jar'
```

Requires JDK 17+.

## Quick example

Condense an existing JFR file:

```shell
cjfr condense recording.jfr
# → recording.cjfr (typically 4–13% of original)
```

Record directly to `.cjfr` with the agent:

```shell
java -javaagent:cjfr.jar=start,recording.cjfr -jar myapp.jar
```

## Next steps

- [Getting Started]({% link getting-started.md %}) — installation, quickstart, agent usage, troubleshooting
- [JAR Release Selection]({% link jar-releases.md %}) — pick the right JAR variant (universal vs platform vs minimal)
- [Configuration Reference]({% link configurations.md %}) — condenser configs and compression algorithm trade-offs
- [Production Recording Guide]({% link production-recording.md %}) — rotating recordings, live tuning, storage sizing

## Project links

- Source: [github.com/parttimenerd/condensed-data](https://github.com/parttimenerd/condensed-data)
- Releases: [github.com/parttimenerd/condensed-data/releases](https://github.com/parttimenerd/condensed-data/releases)
- File format spec: [doc/FORMAT.md](https://github.com/parttimenerd/condensed-data/blob/main/doc/FORMAT.md)

License: MIT.
