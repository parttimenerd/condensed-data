---
title: JAR Release Selection
layout: default
nav_order: 3
---

# JAR Release Selection Guide

`cjfr` releases multiple JAR variants optimised for different deployment contexts.
The key trade-off is size vs. capability: the universal JAR does everything but
weighs 8.7 MB; a platform-inflaterless JAR runs the agent at ~1.5 MB.

## Quick Decision Tree

```
Do you need `cjfr inflate` (convert .cjfr back to .jfr)?
├── Yes → use a JAR *without* "inflaterless" in the name
└── No  → any variant works; prefer inflaterless for smaller agent footprint

Do you know your target OS/arch?
├── Yes → use a platform-specific JAR (e.g. linux-amd64)
└── No  → use the universal JAR

Are you embedding the agent in a container where JAR size matters?
├── Yes → use a *-minimal JAR (LZ4-only, ~450 KB with compression)
└── No  → use a standard platform or universal JAR
```

## Variant Matrix

All variants are published as CI artifacts and in GitHub Releases.

| Variant name | Example file | Approx. size | Strips vs. universal | Loses |
|---|---|---|---|---|
| **Universal** | `condensed-data.jar` | 8.7 MB | nothing | nothing |
| **Universal-inflaterless** | `condensed-data-universal-inflaterless.jar` | 8.1 MB | JMC classes, jetbrains/owasp stubs | `cjfr inflate` |
| **Platform** | `condensed-data-linux-amd64.jar` | 2.1 MB | Native libs for 17 other platforms | nothing |
| **Platform-inflaterless** | `condensed-data-linux-amd64-inflaterless.jar` | 1.5 MB | Native libs for other platforms + JMC | `cjfr inflate` |
| **Platform-minimal** | `condensed-data-linux-amd64-minimal.jar` | 505 KB | As platform + all POMs | `cjfr inflate` |
| **Platform-inflaterless-minimal** | `condensed-data-linux-amd64-inflaterless-minimal.jar` | 441 KB | All of the above + JMC | `cjfr inflate` |

*Sizes shown for linux/amd64. Other platforms are similar; JARs with no LZ4 native lib for that platform (e.g. linux/arm) are ~50 KB smaller.*

## Capability Matrix

| Capability | Universal | Platform | Inflaterless | Minimal |
|---|---|---|---|---|
| `cjfr condense` | ✓ | ✓ | ✓ | ✓ |
| `cjfr summary` | ✓ | ✓ | ✓ | ✓ |
| `cjfr view` | ✓ | ✓ | ✓ | ✓ |
| Java agent (`-javaagent`) | ✓ | ✓ | ✓ | ✓ |
| `cjfr inflate` | ✓ | ✓ | ✗ | ✗ |
| GZIP compression | ✓ | ✓ | ✓ | ✓ |
| LZ4 compression | ✓ | ✓ | ✓ | ✓ |

## Recommended Choices by Persona

**Local developer / ad-hoc analysis**: Download the universal `condensed-data.jar`.
It includes `inflate` for round-trip to JMC and works on any platform out of the box.

**Production JVM agent (known OS, recording only)**: Use `condensed-data-<platform>-inflaterless.jar`
(~1.5 MB). Inflation happens offline on a different host with the full JAR. The agent
needs none of the JMC writer machinery.

**Size-critical sidecar / Java agent in a thin container**: Use
`condensed-data-<platform>-inflaterless-minimal.jar` (~450 KB). Only LZ4 compression is
available in minimal JARs (use `--compression=GZIP` on the full JAR for archival).
The recording file is still fully readable by any full JAR.

**Fleet-wide scraper that only calls `condense` or `summary`**: Use
`condensed-data-<platform>-inflaterless.jar`. GZIP (`--compression=GZIP`) is available
if you want a better compression ratio at the cost of slower writes.

## Inspecting a JAR's Reduction Manifest

Every reduced JAR embeds `jar-reduction-info.json` at its root, listing what was stripped:

```shell
unzip -p condensed-data-linux-amd64.jar jar-reduction-info.json | python3 -m json.tool
```

## Building Variants from Source

```shell
mvn package -DskipTests
# universal JAR: target/condensed-data.jar

# platform-specific JARs (all platforms):
python3 reduce-jar.py matrix target/condensed-data.jar target/platform-jars/

# with minimal variants (femtojar + ProGuard + zopfli):
python3 reduce-jar.py matrix target/condensed-data.jar target/platform-jars/ --with-minimal
```

See [README_JAR_SIZE.md](https://github.com/parttimenerd/condensed-data/blob/main/README_JAR_SIZE.md) for more on the `reduce-jar.py` options.
